package com.example.miniagent.core.change;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persistence only: a lease excludes cooperating writers, not external editors. */
public interface ChangeStore {
	Path workspace();
	Path directory();
	String workspaceHash();
	View read() throws Failure;
	Lease acquire() throws Failure;
	/** Stage metadata may queue behind this JVM's short journal lease, without applying live files. */
	default Lease acquireStaging() throws Failure { return acquire(); }

	enum Code {
		IO, UNSUPPORTED_FILESYSTEM, LOCKED, CLOSED, NOT_FOUND, ALREADY_EXISTS,
		IDENTITY_MISMATCH, INVALID_TRANSITION, LIMIT, CORRUPT, TRUNCATED_JOURNAL,
		FUTURE_SCHEMA, RECOVERY_REQUIRED
	}

	final class Failure extends IOException {
		private final Code code;
		public Failure(Code code, String message) { super(message); this.code = code; }
		public Failure(Code code, String message, Throwable cause) { super(message, cause); this.code = code; }
		public Code code() { return this.code; }
	}

	record Snapshot(long sequence, ChangeSet changeSet, String digest,
			List<Checkpoint> checkpoints, List<VerificationEvidence> evidence) {
		public Snapshot {
			if (sequence < 1) throw new IllegalArgumentException("Invalid sequence");
			Objects.requireNonNull(changeSet);
			if (!changeSet.digest().equals(digest)) throw new IllegalArgumentException("Change digest mismatch");
			checkpoints = List.copyOf(checkpoints);
			evidence = List.copyOf(evidence);
		}
	}

	record View(ChangeJournal.State journalState, List<Snapshot> changes) {
		public View { Objects.requireNonNull(journalState); changes = List.copyOf(changes); }
		public boolean recoveryRequired() {
			return this.journalState != ChangeJournal.State.CLEAN || this.changes.stream().anyMatch(s ->
					s.changeSet().status() == ChangeStatus.APPLYING
					|| s.changeSet().status() == ChangeStatus.RECOVERY_REQUIRED
					|| s.changeSet().status() == ChangeStatus.PARTIALLY_APPLIED
					|| s.changeSet().status() == ChangeStatus.PARTIALLY_UNDONE
					|| s.checkpoints().stream().anyMatch(c -> c.state() == Checkpoint.State.PREPARED
							|| c.state() == Checkpoint.State.CONFLICT));
		}
		public Snapshot get(UUID id) throws Failure {
			return this.changes.stream().filter(s -> s.changeSet().changeSetId().equals(id)).findFirst()
					.orElseThrow(() -> new Failure(Code.NOT_FOUND, "Change set not found: " + id));
		}
	}

	interface Lease extends AutoCloseable {
		View read() throws Failure;
		Path stagingDirectory(ChangeOwner owner) throws Failure;
		Snapshot propose(ChangeSet proposal) throws Failure;
		Snapshot decide(UUID id, ChangeOwner owner, String digest, Approval approval) throws Failure;
		Snapshot transition(UUID id, ChangeOwner owner, String digest, ChangeStatus next) throws Failure;
		Checkpoint prepare(UUID id, ChangeOwner owner, String digest, UUID checkpointId,
				String relativePath, byte[] beforeBytes, byte[] afterBytes, Instant at) throws Failure;
		Checkpoint complete(UUID id, ChangeOwner owner, String digest, UUID checkpointId,
				Checkpoint.State state, Instant at) throws Failure;
		Snapshot recordEvidence(UUID id, ChangeOwner owner, String digest,
				VerificationEvidence evidence, byte[] log) throws Failure;
		byte[] readBlob(Checkpoint.BlobRef ref) throws Failure;
		byte[] readLog(UUID id, ChangeOwner owner, String digest, UUID evidenceId) throws Failure;
		@Override void close() throws Failure;
	}
}
