package com.example.miniagent.core.change;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.miniagent.core.Agent;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.autoconfigure.AgentAutoConfiguration;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static com.example.miniagent.core.change.ChangeStore.Code.*;

/** agent.changes.* limits must reach the store that the auto-configuration wires, not only the bound properties. */
class ChangeStoreLimitEnforcementTest {
	@TempDir Path temp;
	private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
	private static final byte[] AFTER = bytes("new\n");
	private static final byte[] SMALL = bytes("old\n");
	private Path workspace;

	private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
	private static byte[] filled(int size) {
		byte[] content = new byte[size];
		Arrays.fill(content, (byte) 'x');
		return content;
	}
	@BeforeEach void workspace() throws Exception { this.workspace = Files.createDirectory(this.temp.resolve("workspace")); }
	private Path home(String name) { return this.temp.resolve("homes").resolve(name); }
	/** The Agent mock only satisfies the composition; the change store never calls it. */
	private ApplicationContextRunner runner(String home, String... limits) {
		return new ApplicationContextRunner().withUserConfiguration(AgentAutoConfiguration.class)
				.withBean(Agent.class, () -> mock(Agent.class))
				.withPropertyValues("agent.cwd=" + this.workspace, "agent.home=" + home(home))
				.withPropertyValues(limits);
	}
	private ChangeSet proposal(byte[] before) throws Exception {
		Fingerprint old = new Fingerprint("source", EntryKind.REGULAR_FILE, Fingerprinter.sha256(before), before.length, 0644, null);
		Fingerprint updated = new Fingerprint("source", EntryKind.REGULAR_FILE, Fingerprinter.sha256(AFTER), AFTER.length, 0644, null);
		Diff diff = new DiffRenderer(new PathPolicy(this.workspace, home("diff"), List.of())).render(old, before, updated, AFTER);
		return ChangeSet.propose(UUID.randomUUID(), new ChangeOwner("session", new RunId("run"), "attempt", "turn"),
				this.workspace.toRealPath().toString(), new ModelRef("fake", "test"), List.of(UUID.randomUUID()),
				Map.of("source", old), Map.of("source", updated), List.of(diff), AT);
	}
	private static Approval approval(ChangeSet set) {
		return new Approval(UUID.randomUUID(), set.changeSetId(), set.digest(), set.owner(), "console", ApprovalDecision.APPROVED, AT);
	}
	private static void approvedAndApplying(ChangeStore.Lease lease, ChangeSet set) throws Exception {
		lease.propose(set);
		lease.decide(set.changeSetId(), set.owner(), set.digest(), approval(set));
		lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.APPLYING);
	}
	private static Path journal(ChangeStore store) { return store.directory().resolve("journal.log"); }
	private static List<String> names(Path directory) throws Exception {
		try (var paths = Files.list(directory)) { return paths.map(p -> p.getFileName().toString()).sorted().toList(); }
	}
	private static void code(ChangeStore.Code expected, String message, ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
			assertThat(ex.code()).isEqualTo(expected);
			assertThat(ex).hasMessageContaining(message);
		});
	}
	/** The size a manifest takes on disk, measured on a default-limit store of its own agent home. */
	private long manifestSize(String home, ChangeSet set, Approval decision) throws Exception {
		FileChangeStore measuring = new FileChangeStore(home(home), this.workspace);
		try (var lease = measuring.acquire()) {
			lease.propose(set);
			if (decision != null) lease.decide(set.changeSetId(), set.owner(), set.digest(), decision);
		}
		return Files.size(measuring.directory().resolve("manifests").resolve(set.changeSetId() + ".json"));
	}

	@Test void configuredBlobLimitRefusesOversizedCheckpointBytes() throws Exception {
		byte[] oversized = filled(33);
		ChangeSet set = proposal(oversized);
		runner("limited", "agent.changes.max-blob-bytes=32").run(context -> {
			assertThat(context).hasNotFailed();
			ChangeStore store = context.getBean(ChangeStore.class);
			try (var lease = store.acquire()) {
				approvedAndApplying(lease, set);
				byte[] journal = Files.readAllBytes(journal(store));
				code(LIMIT, "Blob exceeds limit", () -> lease.prepare(set.changeSetId(), set.owner(), set.digest(),
						UUID.randomUUID(), "source", oversized, AFTER, AT));
				assertThat(Files.readAllBytes(journal(store))).isEqualTo(journal);
				assertThat(names(store.directory().resolve("blobs"))).isEmpty();
			}
		});
		// The very same bytes fit under the default limit, so the refusal above comes from the configuration.
		runner("default").run(context -> {
			ChangeStore store = context.getBean(ChangeStore.class);
			try (var lease = store.acquire()) {
				approvedAndApplying(lease, set);
				Checkpoint checkpoint = lease.prepare(set.changeSetId(), set.owner(), set.digest(), UUID.randomUUID(),
						"source", oversized, AFTER, AT);
				assertThat(checkpoint.beforeBlob().size()).isEqualTo(33);
				assertThat(lease.readBlob(checkpoint.beforeBlob())).isEqualTo(oversized);
			}
		});
	}

	@Test void configuredBlobLimitAlsoBoundsEvidenceLogsAtExactlyTheLimit() throws Exception {
		ChangeSet set = proposal(SMALL);
		runner("limited", "agent.changes.max-blob-bytes=32").run(context -> {
			assertThat(context).hasNotFailed();
			ChangeStore store = context.getBean(ChangeStore.class);
			try (var lease = store.acquire()) {
				lease.propose(set);
				byte[] journal = Files.readAllBytes(journal(store));
				byte[] oversized = filled(33);
				code(LIMIT, "Blob exceeds limit", () -> lease.recordEvidence(set.changeSetId(), set.owner(), set.digest(),
						evidence(set, oversized), oversized));
				assertThat(Files.readAllBytes(journal(store))).isEqualTo(journal);
				assertThat(names(store.directory().resolve("evidence"))).isEmpty();
				byte[] exact = filled(32);
				VerificationEvidence accepted = evidence(set, exact);
				lease.recordEvidence(set.changeSetId(), set.owner(), set.digest(), accepted, exact);
				assertThat(lease.readLog(set.changeSetId(), set.owner(), set.digest(), accepted.evidenceId())).isEqualTo(exact);
				assertThat(names(store.directory().resolve("evidence"))).containsExactly(Fingerprinter.sha256(exact));
			}
		});
	}

	@Test void configuredManifestLimitRefusesAManifestOneByteOverIt() throws Exception {
		ChangeSet set = proposal(SMALL);
		long size = manifestSize("measure", set, null);
		runner("tight", "agent.changes.max-manifest-bytes=" + (size - 1)).run(context -> {
			assertThat(context).hasNotFailed();
			ChangeStore store = context.getBean(ChangeStore.class);
			try (var lease = store.acquire()) {
				code(LIMIT, "Manifest exceeds limit", () -> lease.propose(set));
				assertThat(Files.size(journal(store))).isZero();
				assertThat(names(store.directory().resolve("manifests"))).isEmpty();
			}
		});
		runner("exact", "agent.changes.max-manifest-bytes=" + size).run(context -> {
			assertThat(context).hasNotFailed();
			ChangeStore store = context.getBean(ChangeStore.class);
			try (var lease = store.acquire()) {
				lease.propose(set);
				assertThat(lease.read().get(set.changeSetId()).changeSet()).isEqualTo(set);
			}
			assertThat(Files.size(store.directory().resolve("manifests").resolve(set.changeSetId() + ".json"))).isEqualTo(size);
		});
	}

	@Test void configuredJournalLimitRefusesARecordThatWouldOverflowTheJournal() throws Exception {
		ChangeSet set = proposal(SMALL);
		Approval decision = approval(set);
		long size = manifestSize("measure", set, decision);
		runner("tight", "agent.changes.max-manifest-bytes=" + size, "agent.changes.max-journal-bytes=" + size).run(context -> {
			assertThat(context).hasNotFailed();
			ChangeStore store = context.getBean(ChangeStore.class);
			byte[] journal;
			try (var lease = store.acquire()) {
				lease.propose(set);
				journal = Files.readAllBytes(journal(store));
				// Each manifest fits its limit; the journal does not fit the second record behind the first.
				code(LIMIT, "Journal exceeds limit", () -> lease.decide(set.changeSetId(), set.owner(), set.digest(), decision));
			}
			assertThat(Files.readAllBytes(journal(store))).isEqualTo(journal);
			ChangeStore.View view = store.read();
			assertThat(view.journalState()).isEqualTo(ChangeJournal.State.CLEAN);
			assertThat(view.get(set.changeSetId()).changeSet()).isEqualTo(set);
		});
	}

	private static VerificationEvidence evidence(ChangeSet set, byte[] log) {
		return new VerificationEvidence(UUID.randomUUID(), set.changeSetId(), set.owner(), List.of("fixture"), set.cwd(),
				Fingerprinter.treeDigest(set.proposed()), Fingerprinter.sha256(bytes("configuration")),
				Fingerprinter.sha256(log), 0, false, AT, AT);
	}
}
