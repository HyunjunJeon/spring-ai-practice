package com.example.miniagent.core.change;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;
import static com.example.miniagent.core.change.ChangeStore.Code.*;

class FileChangeStoreTest {
	@TempDir Path work;
	@TempDir Path state;
	private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
	private static final byte[] BEFORE = bytes("old\n");
	private static final byte[] AFTER = bytes("new\n");

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
	private void approved(ChangeStore.Lease lease, ChangeSet set) throws Exception {
		lease.propose(set);
		lease.decide(set.changeSetId(), set.owner(), set.digest(), approval(set, ApprovalDecision.APPROVED));
		lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.APPLYING);
	}
	private Checkpoint prepare(ChangeStore.Lease lease, ChangeSet set) throws Exception {
		return lease.prepare(set.changeSetId(), set.owner(), set.digest(), UUID.randomUUID(), "source", BEFORE, AFTER, AT);
	}
	private Path journal(FileChangeStore store) { return store.directory().resolve("journal.log"); }
	private static void code(ChangeStore.Code expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> assertThat(ex.code()).isEqualTo(expected));
	}

	@Test void newInstanceRestoresManifestApprovalCheckpointEvidenceAndExactBytes() throws Exception {
		Files.write(this.work.resolve("source"), BEFORE);
		FileChangeStore store = store();
		ChangeSet set = proposal();
		Checkpoint checkpoint;
		Approval approval = approval(set, ApprovalDecision.APPROVED);
		byte[] log = bytes("fixture output\n");
		VerificationEvidence evidence = new VerificationEvidence(UUID.randomUUID(), set.changeSetId(), set.owner(),
				List.of("fixture", "argument with spaces"), set.cwd(), Fingerprinter.treeDigest(set.proposed()),
				Fingerprinter.sha256(bytes("configuration")), Fingerprinter.sha256(log), 0, false, AT, AT);
		ChangeStore.Snapshot persisted;
		try (var lease = store.acquire()) {
			lease.propose(set);
			lease.decide(set.changeSetId(), set.owner(), set.digest(), approval);
			lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.APPLYING);
			checkpoint = prepare(lease, set);
			assertThat(lease.read().recoveryRequired()).isTrue();
			assertThat(lease.readBlob(checkpoint.beforeBlob())).isEqualTo(BEFORE);
			lease.complete(set.changeSetId(), set.owner(), set.digest(), checkpoint.checkpointId(), Checkpoint.State.APPLIED, AT);
			lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.APPLIED);
			persisted = lease.recordEvidence(set.changeSetId(), set.owner(), set.digest(), evidence, log);
		}
		FileChangeStore reopened = store();
		assertThat(reopened.workspaceHash()).isEqualTo(store.workspaceHash());
		assertThat(reopened.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
		assertThat(reopened.read().recoveryRequired()).isFalse();
		assertThat(reopened.read().get(set.changeSetId())).isEqualTo(persisted);
		assertThat(persisted.changeSet().approval()).isEqualTo(approval);
		assertThat(persisted.evidence()).containsExactly(evidence);
		try (var lease = reopened.acquire()) {
			assertThat(lease.readBlob(checkpoint.afterBlob())).isEqualTo(AFTER);
			assertThat(lease.readLog(set.changeSetId(), set.owner(), set.digest(), evidence.evidenceId())).isEqualTo(log);
		}
		// Persistence never mutates the live source or runs the recorded argv.
		assertThat(Files.readAllBytes(this.work.resolve("source"))).isEqualTo(BEFORE);
		assertThat(Files.getPosixFilePermissions(store.directory())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
		try (var files = Files.walk(store.directory())) {
			for (Path path : files.filter(Files::isRegularFile).toList())
				assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
		}
	}

	@Test void verificationExecutionCwdIsSeparateFromBoundSourceWorkspace() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		Path execution = Files.createDirectory(this.state.resolve("execution-clone")).toRealPath();
		byte[] log = bytes("process output");
		var evidence = new VerificationEvidence(UUID.randomUUID(), set.changeSetId(), set.owner(),
				List.of("fixture", "argument with spaces"), execution.toString(), set.cwd(),
				Fingerprinter.treeDigest(set.proposed()), Fingerprinter.sha256(bytes("configuration")),
				Fingerprinter.sha256(log), 0, false, AT, AT);
		try (var lease = store.acquire()) {
			lease.propose(set);
			lease.recordEvidence(set.changeSetId(), set.owner(), set.digest(), evidence, log);
			byte[] before = Files.readAllBytes(journal(store));
			var wrongSource = new VerificationEvidence(UUID.randomUUID(), set.changeSetId(), set.owner(),
					evidence.argv(), evidence.cwd(), execution.toString(), evidence.sourceRevision(),
					evidence.configurationDigest(), evidence.logSha256(), 0, false, AT, AT);
			code(IDENTITY_MISMATCH, () -> lease.recordEvidence(set.changeSetId(), set.owner(), set.digest(), wrongSource, log));
			assertThat(Files.readAllBytes(journal(store))).isEqualTo(before);
		}
		var restored = store().read().get(set.changeSetId()).evidence().getFirst();
		assertThat(restored).isEqualTo(evidence);
		assertThat(restored.cwd()).isEqualTo(execution.toString());
		assertThat(restored.sourceCwd()).isEqualTo(set.cwd());
	}

	@Test void workspaceAliasesShareIdentityAndLeaseExcludesOtherInstances() throws Exception {
		FileChangeStore first = store();
		Path alias = this.state.resolve("alias");
		Files.createSymbolicLink(alias, this.work);
		FileChangeStore second = new FileChangeStore(home(), alias);
		assertThat(second.workspaceHash()).isEqualTo(first.workspaceHash());
		try (var lease = first.acquire()) {
			code(LOCKED, second::acquire);
			code(LOCKED, second::read);
			assertThat(lease.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
			// Rejection must not release the first POSIX lock.
			code(LOCKED, second::acquire);
		}
		try (var lease = second.acquire()) { assertThat(lease.read().changes()).isEmpty(); }
		Path other = Files.createDirectory(this.state.resolve("other"));
		assertThat(new FileChangeStore(home(), other).workspaceHash()).isNotEqualTo(first.workspaceHash());
	}

	@Test void rejectedOwnersDigestsApprovalsAndCheckpointBytesLeaveJournalUnchanged() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		try (var lease = store.acquire()) {
			lease.propose(set);
			byte[] original = Files.readAllBytes(journal(store));
			ChangeOwner wrong = new ChangeOwner("other", set.owner().runId(), "attempt", "turn");
			code(IDENTITY_MISMATCH, () -> lease.transition(set.changeSetId(), wrong, set.digest(), ChangeStatus.STALE));
			code(IDENTITY_MISMATCH, () -> lease.transition(set.changeSetId(), set.owner(), Fingerprinter.sha256(bytes("wrong")), ChangeStatus.STALE));
			Approval foreign = new Approval(UUID.randomUUID(), set.changeSetId(), set.digest(), wrong, "console", ApprovalDecision.APPROVED, AT);
			code(IDENTITY_MISMATCH, () -> lease.decide(set.changeSetId(), set.owner(), set.digest(), foreign));
			code(INVALID_TRANSITION, () -> prepare(lease, set));
			assertThat(Files.readAllBytes(journal(store))).isEqualTo(original);
			lease.decide(set.changeSetId(), set.owner(), set.digest(), approval(set, ApprovalDecision.APPROVED));
			lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.APPLYING);
			original = Files.readAllBytes(journal(store));
			code(IDENTITY_MISMATCH, () -> lease.prepare(set.changeSetId(), set.owner(), set.digest(),
					UUID.randomUUID(), "source", bytes("not baseline"), AFTER, AT));
			assertThat(Files.readAllBytes(journal(store))).isEqualTo(original);
			code(INVALID_TRANSITION, () -> lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.APPLIED));
		}
	}

	@Test void truncatedJournalMarksRecoveryRequired() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		try (var lease = store.acquire()) { approved(lease, set); prepare(lease, set); }
		byte[] full = Files.readAllBytes(journal(store));
		byte[] truncated = Arrays.copyOf(full, full.length - 8);
		Files.write(journal(store), truncated);
		FileChangeStore reopened = store();
		ChangeStore.View view = reopened.read();
		assertThat(view.journalState()).isEqualTo(ChangeJournal.State.TRUNCATED);
		assertThat(view.recoveryRequired()).isTrue();
		assertThat(view.get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.APPLYING);
		assertThat(view.get(set.changeSetId()).checkpoints()).isEmpty();
		try (var lease = reopened.acquire()) {
			code(TRUNCATED_JOURNAL, () -> lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.RECOVERY_REQUIRED));
		}
		assertThat(Files.readAllBytes(journal(store))).isEqualTo(truncated);
	}

	@Test void corruptCompleteRowIsNotMistakenForTruncatedTail() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		try (var lease = store.acquire()) { lease.propose(set); }
		byte[] bytes = Files.readAllBytes(journal(store));
		bytes[4] = bytes[4] == '0' ? (byte) '1' : (byte) '0'; // CRC, not payload or newline.
		Files.write(journal(store), bytes);
		assertThat(store().read().journalState()).isEqualTo(ChangeJournal.State.CORRUPT);
		assertThat(store().read().recoveryRequired()).isTrue();
		try (var lease = store().acquire()) { code(CORRUPT, () -> lease.propose(set)); }
		assertThat(Files.readAllBytes(journal(store))).isEqualTo(bytes);
	}

	@Test void futureCompleteAndPartialJournalRecordsRemainOpaque() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		try (var lease = store.acquire()) { lease.propose(set); }
		byte[] prefix = Files.readAllBytes(journal(store));
		for (String future : List.of("2\t2\t00000000\t{}\n", "2\t2\tpartial")) {
			Files.write(journal(store), prefix);
			Files.write(journal(store), bytes(future), StandardOpenOption.APPEND);
			byte[] saved = Files.readAllBytes(journal(store));
			ChangeStore.View view = store().read();
			assertThat(view.journalState()).isEqualTo(ChangeJournal.State.FUTURE_SCHEMA);
			assertThat(view.get(set.changeSetId()).changeSet()).isEqualTo(set);
			try (var lease = store().acquire()) {
				code(FUTURE_SCHEMA, () -> lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.STALE));
			}
			assertThat(Files.readAllBytes(journal(store))).isEqualTo(saved);
		}
	}

	@Test void preparedCheckpointSurvivesNewInstanceWithoutAutomaticApply() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		Checkpoint checkpoint;
		try (var lease = store.acquire()) { approved(lease, set); checkpoint = prepare(lease, set); }
		byte[] saved = Files.readAllBytes(journal(store));
		ChangeStore.View view = store().read();
		assertThat(view.journalState()).isEqualTo(ChangeJournal.State.CLEAN);
		assertThat(view.recoveryRequired()).isTrue();
		assertThat(view.get(set.changeSetId()).checkpoints()).containsExactly(checkpoint);
		assertThat(Files.readAllBytes(journal(store))).isEqualTo(saved);
		assertThat(Files.exists(this.work.resolve("source"))).isFalse();
	}

	@Test void contentAddressedBackupIntegrityFailurePreservesCorruptBytes() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		Checkpoint checkpoint;
		try (var lease = store.acquire()) { approved(lease, set); checkpoint = prepare(lease, set); }
		Path backup = store.directory().resolve("blobs").resolve(checkpoint.beforeBlob().sha256());
		byte[] altered = bytes("bad\n");
		Files.write(backup, altered);
		assertThat(store().read().journalState()).isEqualTo(ChangeJournal.State.CORRUPT);
		try (var lease = store().acquire()) {
			code(CORRUPT, () -> lease.readBlob(checkpoint.beforeBlob()));
			code(CORRUPT, () -> lease.complete(set.changeSetId(), set.owner(), set.digest(), checkpoint.checkpointId(), Checkpoint.State.APPLIED, AT));
		}
		assertThat(Files.readAllBytes(backup)).isEqualTo(altered);
	}

	@Test void restoredAndConflictStatesPersistAndRequireExplicitCompletion() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		Checkpoint checkpoint;
		try (var lease = store.acquire()) {
			approved(lease, set);
			checkpoint = prepare(lease, set);
			lease.complete(set.changeSetId(), set.owner(), set.digest(), checkpoint.checkpointId(), Checkpoint.State.CONFLICT, AT);
		}
		assertThat(store().read().get(set.changeSetId()).checkpoints().getFirst().state()).isEqualTo(Checkpoint.State.CONFLICT);
		try (var lease = store().acquire()) {
			lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.RECOVERY_REQUIRED);
			lease.complete(set.changeSetId(), set.owner(), set.digest(), checkpoint.checkpointId(), Checkpoint.State.RESTORED, AT);
			lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.UNDONE);
			code(INVALID_TRANSITION, () -> lease.complete(set.changeSetId(), set.owner(), set.digest(), checkpoint.checkpointId(), Checkpoint.State.APPLIED, AT));
		}
		assertThat(store().read().get(set.changeSetId()).checkpoints().getFirst().state()).isEqualTo(Checkpoint.State.RESTORED);
		assertThat(store().read().recoveryRequired()).isFalse();
	}

	@Test void laggingAtomicManifestDoesNotHideNewerJournalState() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		Path manifest = store.directory().resolve("manifests").resolve(set.changeSetId() + ".json");
		byte[] initial;
		try (var lease = store.acquire()) {
			lease.propose(set);
			initial = Files.readAllBytes(manifest);
			lease.decide(set.changeSetId(), set.owner(), set.digest(), approval(set, ApprovalDecision.REJECTED));
		}
		Files.write(manifest, initial);
		assertThat(store().read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.REJECTED);
		assertThat(Files.readAllBytes(manifest)).isEqualTo(initial); // Read does not repair.
	}

	@Test void duplicateUnknownAndFutureManifestFieldsAreRejectedWithoutRewrite() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		try (var lease = store.acquire()) { lease.propose(set); }
		Path manifest = store.directory().resolve("manifests").resolve(set.changeSetId() + ".json");
		String original = Files.readString(manifest);
		for (String invalid : List.of("{\"unknown\":true," + original.substring(1),
				"{\"schemaVersion\":1," + original.substring(1), original.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))) {
			Files.writeString(manifest, invalid);
			assertThat(store().read().journalState()).isEqualTo(invalid.contains("\"schemaVersion\":2")
					? ChangeJournal.State.FUTURE_SCHEMA : ChangeJournal.State.CORRUPT);
			assertThat(Files.readString(manifest)).isEqualTo(invalid);
		}
	}

	@Test void boundedReadsAndUnsupportedForceFailTyped() throws Exception {
		FileChangeStore store = store();
		try (var lease = store.acquire()) { assertThat(lease.read().changes()).isEmpty(); }
		Files.write(journal(store), new byte[129]);
		FileChangeStore bounded = new FileChangeStore(home(), this.work, new ChangeProperties(64, 128, 128));
		// Workspace identity is itself bounded; use the journal scanner to isolate its bound.
		code(LIMIT, () -> new ChangeJournal(journal(store), 128, 128).scan());
		Path zip = this.state.resolve("unsupported.zip");
		try (var fs = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true"))) {
			code(UNSUPPORTED_FILESYSTEM, () -> AtomicFiles.forceDirectory(fs.getPath("/")));
		}
		assertThat(bounded.workspaceHash()).isEqualTo(store.workspaceHash());
	}

	@Test void unsafeStateLinksAndWorkspaceIdentityMismatchAreRejected() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		try (var lease = store.acquire()) { lease.propose(set); }
		Path identity = store.directory().resolve("workspace.json");
		String original = Files.readString(identity);
		String wrong = original.replace(store.workspaceHash(), Fingerprinter.sha256(bytes("wrong")));
		Files.writeString(identity, wrong);
		code(IDENTITY_MISMATCH, store()::read);
		assertThat(Files.readString(identity)).isEqualTo(wrong);
		Files.writeString(identity, original);
		Path log = journal(store);
		Path outside = this.state.resolve("outside");
		byte[] bytes = Files.readAllBytes(log);
		Files.write(outside, bytes);
		Files.delete(log);
		Files.createSymbolicLink(log, outside);
		code(CORRUPT, store()::read);
		assertThat(Files.readAllBytes(outside)).isEqualTo(bytes);
		assertThat(Files.isSymbolicLink(log)).isTrue();
	}

	@Test void stablePrivateStagingAndClosedLeaseCannotWrite() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		ChangeStore.Lease lease = store.acquire();
		Path staging = lease.stagingDirectory(set.owner());
		assertThat(staging.startsWith(store.directory().resolve("staging"))).isTrue();
		lease.close();
		code(CLOSED, () -> lease.propose(set));
		try (var next = store().acquire()) {
			assertThat(next.stagingDirectory(set.owner())).isEqualTo(staging);
		}
	}

	@Test void appliedUndoRequiresExplicitRecoveryStateBeforeRestoring() throws Exception {
		ChangeSet set = proposal();
		try (var lease = store().acquire()) {
			approved(lease, set);
			Checkpoint checkpoint = prepare(lease, set);
			lease.complete(set.changeSetId(), set.owner(), set.digest(), checkpoint.checkpointId(), Checkpoint.State.APPLIED, AT);
			lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.APPLIED);
			code(INVALID_TRANSITION, () -> lease.complete(set.changeSetId(), set.owner(), set.digest(),
					checkpoint.checkpointId(), Checkpoint.State.RESTORED, AT));
			lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.RECOVERY_REQUIRED);
			lease.complete(set.changeSetId(), set.owner(), set.digest(), checkpoint.checkpointId(), Checkpoint.State.RESTORED, AT);
			lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.UNDONE);
		}
		assertThat(store().read().recoveryRequired()).isFalse();
	}

	@Test void unresolvedEffectsBlockAnotherProposalFromEnteringApplying() throws Exception {
		ChangeSet first = proposal(), second = proposal();
		try (var lease = store().acquire()) {
			approved(lease, first);
			prepare(lease, first);
			lease.propose(second);
			lease.decide(second.changeSetId(), second.owner(), second.digest(), approval(second, ApprovalDecision.APPROVED));
			byte[] original = Files.readAllBytes(journal(store()));
			code(RECOVERY_REQUIRED, () -> lease.transition(second.changeSetId(), second.owner(), second.digest(), ChangeStatus.APPLYING));
			assertThat(Files.readAllBytes(journal(store()))).isEqualTo(original);
		}
	}
}
