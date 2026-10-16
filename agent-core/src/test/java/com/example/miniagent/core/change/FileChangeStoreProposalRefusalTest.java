package com.example.miniagent.core.change;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static com.example.miniagent.core.change.ChangeStore.Code.*;

/** The store is the last guard before the journal: every refusal must leave the journal bytes untouched. */
class FileChangeStoreProposalRefusalTest {
	@TempDir Path work;
	@TempDir Path state;
	private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
	private static final byte[] BEFORE = bytes("old\n");
	private static final byte[] AFTER = bytes("new\n");
	private static final String PATCH = "@@ -1 +1 @@\n-old\n+new\n";

	private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
	private Path home() { return this.state.resolve("home"); }
	private FileChangeStore store() throws Exception { return new FileChangeStore(home(), this.work); }
	private ChangeOwner owner() { return new ChangeOwner("session", new RunId("run"), "attempt", "turn"); }
	private static Fingerprint file(String path, byte[] content) {
		return new Fingerprint(path, EntryKind.REGULAR_FILE, Fingerprinter.sha256(content), content.length, 0644, null);
	}
	private ChangeSet proposal() throws Exception { return proposal(UUID.randomUUID(), this.work.toRealPath().toString(), AFTER); }
	private ChangeSet proposal(UUID id, String cwd, byte[] after) throws Exception {
		Fingerprint before = file("source", BEFORE);
		Fingerprint changed = file("source", after);
		Diff diff = new DiffRenderer(new PathPolicy(this.work, home(), List.of())).render(before, BEFORE, changed, after);
		return ChangeSet.propose(id, owner(), cwd, new ModelRef("fake", "test"),
				List.of(UUID.randomUUID()), Map.of("source", before), Map.of("source", changed), List.of(diff), AT);
	}
	/** DiffRenderer refuses excluded paths, so the diff is built by hand to reach the store's own guard. */
	private ChangeSet touching(String path, boolean inBaseline) throws Exception {
		Fingerprint present = file(path, AFTER);
		Fingerprint absent = Fingerprint.missing(path);
		Diff diff = inBaseline ? new Diff(present, absent, Diff.Content.TEXT, PATCH, "")
				: new Diff(absent, present, Diff.Content.TEXT, PATCH, "");
		return ChangeSet.propose(UUID.randomUUID(), owner(), this.work.toRealPath().toString(), new ModelRef("fake", "test"),
				List.of(UUID.randomUUID()), inBaseline ? Map.of(path, present) : Map.of(),
				inBaseline ? Map.of() : Map.of(path, present), List.of(diff), AT);
	}
	private Approval approval(ChangeSet set, ApprovalDecision decision) {
		return new Approval(UUID.randomUUID(), set.changeSetId(), set.digest(), set.owner(), "console", decision, AT);
	}
	private static Path journal(FileChangeStore store) { return store.directory().resolve("journal.log"); }

	/** Runs a refused call and proves the journal was not touched and the lease can still publish. */
	private void refused(FileChangeStore store, ChangeStore.Lease lease, ChangeStore.Code code, String message,
			org.assertj.core.api.ThrowableAssert.ThrowingCallable action) throws Exception {
		byte[] before = Files.readAllBytes(journal(store));
		assertThatThrownBy(action).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
			assertThat(ex.code()).isEqualTo(code);
			assertThat(ex).hasMessage(message);
		});
		assertThat(Files.readAllBytes(journal(store))).isEqualTo(before);
		assertThat(lease.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
	}

	@Test void duplicateChangeIdIsRefusedEvenWhenTheContentDiffers() throws Exception {
		FileChangeStore store = store();
		ChangeSet first = proposal();
		ChangeSet sameId = proposal(first.changeSetId(), first.cwd(), bytes("other\n"));
		try (var lease = store.acquire()) {
			lease.propose(first);
			refused(store, lease, ALREADY_EXISTS, "Change set already exists", () -> lease.propose(first));
			refused(store, lease, ALREADY_EXISTS, "Change set already exists", () -> lease.propose(sameId));
			assertThat(lease.read().changes()).extracting(s -> s.changeSet()).containsExactly(first);
		}
	}

	@Test void proposalBoundToAnotherWorkspaceIsRefused() throws Exception {
		FileChangeStore store = store();
		String elsewhere = Files.createDirectory(this.state.resolve("elsewhere")).toRealPath().toString();
		ChangeSet foreign = proposal(UUID.randomUUID(), elsewhere, AFTER);
		ChangeSet own = proposal();
		try (var lease = store.acquire()) {
			lease.propose(own);
			refused(store, lease, IDENTITY_MISMATCH, "Change cwd mismatch", () -> lease.propose(foreign));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {".env", ".git/config", "config/secret.txt", "tools/id_rsa", "deploy/server.pem"})
	void excludedPathInTheProposedTreeIsRefused(String path) throws Exception {
		FileChangeStore store = store();
		try (var lease = store.acquire()) {
			lease.propose(proposal());
			ChangeSet created = touching(path, false);
			refused(store, lease, IDENTITY_MISMATCH, "Excluded source path: " + path, () -> lease.propose(created));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {".env", ".git/config", "config/secret.txt", "tools/id_rsa", "deploy/server.pem"})
	void excludedPathInTheBaselineTreeIsRefused(String path) throws Exception {
		FileChangeStore store = store();
		try (var lease = store.acquire()) {
			lease.propose(proposal());
			ChangeSet deleted = touching(path, true);
			refused(store, lease, IDENTITY_MISMATCH, "Excluded source path: " + path, () -> lease.propose(deleted));
		}
	}

	@ParameterizedTest
	@EnumSource(ApprovalDecision.class)
	void initialRecordAlreadyDecidedIsRefused(ApprovalDecision decision) throws Exception {
		FileChangeStore store = store();
		ChangeSet pending = proposal();
		ChangeSet preDecided = pending.decide(approval(pending, decision));
		try (var lease = store.acquire()) {
			lease.propose(proposal());
			refused(store, lease, INVALID_TRANSITION, "Initial record must be an unapproved proposal",
					() -> lease.propose(preDecided));
		}
	}

	@Test void refusedProposalsLeaveNoManifestAndTheLeaseKeepsPublishing() throws Exception {
		FileChangeStore store = store();
		ChangeSet own = proposal();
		ChangeSet foreign = proposal(UUID.randomUUID(), Files.createDirectory(this.state.resolve("elsewhere")).toRealPath().toString(), AFTER);
		ChangeSet excluded = touching(".env", false);
		ChangeSet later = proposal();
		try (var lease = store.acquire()) {
			lease.propose(own);
			assertThatThrownBy(() -> lease.propose(foreign)).isInstanceOf(ChangeStore.Failure.class);
			assertThatThrownBy(() -> lease.propose(excluded)).isInstanceOf(ChangeStore.Failure.class);
			assertThat(lease.propose(later).changeSet()).isEqualTo(later);
		}
		try (var manifests = Files.list(store.directory().resolve("manifests"))) {
			assertThat(manifests.map(p -> p.getFileName().toString()))
					.containsExactlyInAnyOrder(own.changeSetId() + ".json", later.changeSetId() + ".json");
		}
		assertThat(store.read().changes()).extracting(s -> s.changeSet().changeSetId())
				.containsExactly(own.changeSetId(), later.changeSetId());
	}

	@Test void decidingTwiceIsRefusedAsInvalidTransition() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		try (var lease = store.acquire()) {
			lease.propose(set);
			lease.decide(set.changeSetId(), set.owner(), set.digest(), approval(set, ApprovalDecision.APPROVED));
			refused(store, lease, INVALID_TRANSITION, "Proposal is not awaiting approval",
					() -> lease.decide(set.changeSetId(), set.owner(), set.digest(), approval(set, ApprovalDecision.REJECTED)));
			assertThat(lease.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.APPROVED);
		}
	}

	@Test void decidingARejectedProposalAgainIsRefused() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		try (var lease = store.acquire()) {
			lease.propose(set);
			lease.decide(set.changeSetId(), set.owner(), set.digest(), approval(set, ApprovalDecision.REJECTED));
			refused(store, lease, INVALID_TRANSITION, "Proposal is not awaiting approval",
					() -> lease.decide(set.changeSetId(), set.owner(), set.digest(), approval(set, ApprovalDecision.APPROVED)));
			assertThat(lease.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.REJECTED);
		}
	}

	@Test void unknownChangeIdIsNotFoundForDecideAndTransition() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		UUID unknown = UUID.randomUUID();
		try (var lease = store.acquire()) {
			lease.propose(set);
			refused(store, lease, NOT_FOUND, "Change set not found: " + unknown,
					() -> lease.decide(unknown, set.owner(), set.digest(), approval(set, ApprovalDecision.APPROVED)));
			refused(store, lease, NOT_FOUND, "Change set not found: " + unknown,
					() -> lease.transition(unknown, set.owner(), set.digest(), ChangeStatus.STALE));
		}
	}

	@Test void transitionAlongAnEdgeTheStatusTableForbidsIsRefused() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		try (var lease = store.acquire()) {
			lease.propose(set);
			// APPROVED is reachable only through decide, and a status never moves to itself.
			refused(store, lease, INVALID_TRANSITION, "Invalid change transition",
					() -> lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.APPROVED));
			refused(store, lease, INVALID_TRANSITION, "Invalid change transition",
					() -> lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.PENDING_APPROVAL));
			lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.STALE);
			refused(store, lease, INVALID_TRANSITION, "Invalid change transition",
					() -> lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.APPLYING));
			assertThat(lease.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.STALE);
		}
	}
}
