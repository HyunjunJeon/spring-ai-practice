package com.example.miniagent.core.change;

import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static com.example.miniagent.core.change.ChangeStore.Code.*;

/** A failed publication after the journal append must stop the lease; only a reopen may continue. */
class FileChangeStoreLeasePoisonTest {
	@TempDir Path work;
	@TempDir Path state;
	private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
	private static final byte[] BEFORE = bytes("old\n");
	private static final byte[] AFTER = bytes("new\n");
	private static final String BLOCKED = "Prior publication failed; reopen and inspect";

	private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
	private Path home() { return this.state.resolve("home"); }
	private FileChangeStore store() throws Exception { return new FileChangeStore(home(), this.work); }
	private ChangeOwner owner() { return new ChangeOwner("session", new RunId("run"), "attempt", "turn"); }
	private ChangeSet proposal() throws Exception {
		Fingerprint before = new Fingerprint("source", EntryKind.REGULAR_FILE, Fingerprinter.sha256(BEFORE), BEFORE.length, 0644, null);
		Fingerprint after = new Fingerprint("source", EntryKind.REGULAR_FILE, Fingerprinter.sha256(AFTER), AFTER.length, 0644, null);
		Diff diff = new DiffRenderer(new PathPolicy(this.work, home(), List.of())).render(before, BEFORE, after, AFTER);
		return ChangeSet.propose(UUID.randomUUID(), owner(), this.work.toRealPath().toString(), new ModelRef("fake", "test"),
				List.of(UUID.randomUUID()), Map.of("source", before), Map.of("source", after), List.of(diff), AT);
	}
	private Approval approval(ChangeSet set, ApprovalDecision decision) {
		return new Approval(UUID.randomUUID(), set.changeSetId(), set.digest(), set.owner(), "console", decision, AT);
	}
	private static Path journal(FileChangeStore store) { return store.directory().resolve("journal.log"); }
	private static Path manifest(FileChangeStore store, ChangeSet set) {
		return store.directory().resolve("manifests").resolve(set.changeSetId() + ".json");
	}
	private static void blocked(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
			assertThat(ex.code()).isEqualTo(RECOVERY_REQUIRED);
			assertThat(ex).hasMessage(BLOCKED);
		});
	}

	@Test void manifestPublicationFailureKeepsTheJournalRecordAndBlocksLaterWritesOnThatLease() throws Exception {
		FileChangeStore store = store();
		ChangeSet first = proposal();
		ChangeSet second = proposal();
		Path manifests = store.directory().resolve("manifests");
		try (var lease = store.acquire()) {
			Files.setPosixFilePermissions(manifests, PosixFilePermissions.fromString("r-x------"));
			try {
				assumeFalse(Files.isWritable(manifests), "Privileged user bypasses POSIX write denial");
				assertThatThrownBy(() -> lease.propose(first)).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
					assertThat(ex.code()).isEqualTo(IO);
					assertThat(ex).hasCauseInstanceOf(AccessDeniedException.class);
				});
			}
			finally { Files.setPosixFilePermissions(manifests, PosixFilePermissions.fromString("rwx------")); }
			// The permission is back, so only the poisoned lease state can still refuse writes.
			byte[] durable = Files.readAllBytes(journal(store));
			assertThat(durable).isNotEmpty();
			assertThat(manifest(store, first)).doesNotExist();
			assertThat(lease.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
			assertThat(lease.read().get(first.changeSetId()).changeSet()).isEqualTo(first);
			blocked(() -> lease.propose(second));
			blocked(() -> lease.decide(first.changeSetId(), first.owner(), first.digest(), approval(first, ApprovalDecision.APPROVED)));
			blocked(() -> lease.transition(first.changeSetId(), first.owner(), first.digest(), ChangeStatus.STALE));
			blocked(() -> lease.stagingDirectory(first.owner()));
			assertThat(Files.readAllBytes(journal(store))).isEqualTo(durable);
		}
	}

	@Test void reopeningAfterAFailedManifestPublicationRestoresTheRecordAndWritability() throws Exception {
		FileChangeStore store = store();
		ChangeSet first = proposal();
		Path manifests = store.directory().resolve("manifests");
		try (var lease = store.acquire()) {
			Files.setPosixFilePermissions(manifests, PosixFilePermissions.fromString("r-x------"));
			try {
				assumeFalse(Files.isWritable(manifests), "Privileged user bypasses POSIX write denial");
				assertThatThrownBy(() -> lease.propose(first)).isInstanceOf(ChangeStore.Failure.class);
			}
			finally { Files.setPosixFilePermissions(manifests, PosixFilePermissions.fromString("rwx------")); }
		}
		FileChangeStore reopened = store();
		ChangeStore.View view = reopened.read();
		assertThat(view.journalState()).isEqualTo(ChangeJournal.State.CLEAN);
		assertThat(view.get(first.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.PENDING_APPROVAL);
		assertThat(view.recoveryRequired()).isFalse();
		try (var lease = reopened.acquire()) {
			ChangeStore.Snapshot decided = lease.decide(first.changeSetId(), first.owner(), first.digest(),
					approval(first, ApprovalDecision.REJECTED));
			assertThat(decided.sequence()).isEqualTo(2);
			assertThat(decided.changeSet().status()).isEqualTo(ChangeStatus.REJECTED);
		}
		assertThat(manifest(reopened, first)).exists();
		assertThat(store().read().get(first.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.REJECTED);
	}
}
