package com.example.miniagent.core.change;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/** Restore only observed after endpoints; a human edit is never an undo target. */
public final class UndoEngine {
	private final ChangeStore store;
	private final PathPolicy policy;
	private final Clock clock;
	private final ApplyEngine.BoundaryHook hook;

	public UndoEngine(ChangeStore store, PathPolicy policy, Clock clock) {
		this(store, policy, clock, (boundary, checkpoint) -> {});
	}
	public UndoEngine(ChangeStore store, PathPolicy policy, Clock clock, ApplyEngine.BoundaryHook hook) {
		this.store = java.util.Objects.requireNonNull(store);
		this.policy = java.util.Objects.requireNonNull(policy);
		this.clock = java.util.Objects.requireNonNull(clock);
		this.hook = java.util.Objects.requireNonNull(hook);
		if (!store.workspace().equals(policy.root())) throw new IllegalArgumentException("Workspace policy mismatch");
	}

	public ChangeStore.Snapshot undo(UUID id, ChangeOwner owner, String digest, ApplyEngine.OwnershipProof proof)
			throws IOException {
		EngineMutation.interrupted();
		ChangeStore.Lease lease = this.store.acquire();
		ChangeSet set = null;
		boolean restoring = false;
		try {
			var view = lease.read();
			EngineMutation.clean(view);
			var snapshot = EngineMutation.owned(view, id, owner, digest);
			set = snapshot.changeSet();
			if (!List.of(ChangeStatus.APPLIED, ChangeStatus.APPLYING, ChangeStatus.PARTIALLY_APPLIED,
					ChangeStatus.RECOVERY_REQUIRED, ChangeStatus.PARTIALLY_UNDONE).contains(set.status()))
				throw new ChangeStore.Failure(ChangeStore.Code.INVALID_TRANSITION, "Change has no undoable effects");
			RecoveryScanner.classify(this.policy, view, snapshot);
			if (proof == null) throw new ApplyEngine.Failure(ApplyEngine.Code.EXCLUSIVE_OWNERSHIP_REQUIRED, set);
			proof.consume(this.store, set, null);
			RecoveryScanner.classify(this.policy, view, snapshot);
			if (set.status() != ChangeStatus.RECOVERY_REQUIRED)
				lease.transition(id, owner, digest, ChangeStatus.RECOVERY_REQUIRED);
			restoring = true;
			var checkpoints = new HashMap<String, Checkpoint>();
			for (Checkpoint checkpoint : snapshot.checkpoints()) checkpoints.put(checkpoint.before().relativePath(), checkpoint);
			var reader = new Fingerprinter(this.policy);
			for (Diff diff : EngineMutation.ordered(set.diffs(), true)) {
				EngineMutation.interrupted();
				Checkpoint checkpoint = checkpoints.get(diff.relativePath());
				if (checkpoint == null || checkpoint.state() == Checkpoint.State.RESTORED) continue;
				Fingerprint current;
				try {
					this.policy.resolveMutation(diff.relativePath());
					current = reader.fingerprint(diff.relativePath());
				}
				catch (PathPolicy.Violation ex) {
					if (ex.code() == PathPolicy.Code.UNSUPPORTED_FILESYSTEM || ex.code() == PathPolicy.Code.LIMIT) throw ex;
					conflict(lease, set, checkpoint);
					continue;
				}
				if (current.equals(checkpoint.before()) && checkpoint.state() != Checkpoint.State.APPLIED) {
					lease.complete(id, owner, digest, checkpoint.checkpointId(), Checkpoint.State.RESTORED, this.clock.instant());
					continue;
				}
				if (!current.equals(checkpoint.after())) {
					conflict(lease, set, checkpoint);
					continue;
				}
				byte[] before = checkpoint.beforeBlob() == null ? new byte[0] : lease.readBlob(checkpoint.beforeBlob());
				EngineMutation.bytes(checkpoint.before(), before);
				this.hook.reached(ApplyEngine.Boundary.PREPARED, checkpoint);
				EngineMutation.interrupted();
				try {
					EngineMutation.mutate(this.policy, checkpoint.after(), checkpoint.before(), before);
				}
				catch (DirectoryNotEmptyException ex) {
					conflict(lease, set, checkpoint);
					continue;
				}
				catch (PathPolicy.Violation ex) {
					if (ex.code() != PathPolicy.Code.CHANGED && ex.code() != PathPolicy.Code.SYMLINK) throw ex;
					conflict(lease, set, checkpoint);
					continue;
				}
				this.hook.reached(ApplyEngine.Boundary.MUTATED, checkpoint);
				EngineMutation.interrupted();
				if (!reader.fingerprint(diff.relativePath()).equals(checkpoint.before())) {
					conflict(lease, set, checkpoint);
					continue;
				}
				checkpoint = lease.complete(id, owner, digest, checkpoint.checkpointId(), Checkpoint.State.RESTORED, this.clock.instant());
				this.hook.reached(ApplyEngine.Boundary.COMPLETED, checkpoint);
			}
			EngineMutation.interrupted();
			view = lease.read();
			snapshot = view.get(id);
			var report = RecoveryScanner.classify(this.policy, view, snapshot);
			boolean restored = snapshot.checkpoints().stream().allMatch(c -> c.state() == Checkpoint.State.RESTORED)
					&& report.entries().stream().allMatch(e -> e.position() == ConflictReport.Position.BEFORE);
			return lease.transition(id, owner, digest, restored ? ChangeStatus.UNDONE : ChangeStatus.PARTIALLY_UNDONE);
		}
		catch (IOException | RuntimeException ex) {
			if (restoring) EngineMutation.failed(lease, set, ex, true);
			throw ex;
		}
		finally { EngineMutation.close(lease); }
	}

	private void conflict(ChangeStore.Lease lease, ChangeSet set, Checkpoint checkpoint) throws IOException {
		if (checkpoint.state() != Checkpoint.State.CONFLICT)
			lease.complete(set.changeSetId(), set.owner(), set.digest(), checkpoint.checkpointId(),
					Checkpoint.State.CONFLICT, this.clock.instant());
	}
}
