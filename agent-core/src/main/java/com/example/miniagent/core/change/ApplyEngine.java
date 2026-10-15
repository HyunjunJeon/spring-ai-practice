package com.example.miniagent.core.change;

import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.OperatorConsole;

/** Approved source publication under a lease and a separate console capability. */
public final class ApplyEngine {
	public enum Code { IDENTITY_MISMATCH, APPROVAL_REQUIRED, EXCLUSIVE_OWNERSHIP_REQUIRED, STALE, INVALID_PROPOSAL }
	public static final class Failure extends IOException {
		private final Code code;
		private final ChangeSet proposal;
		public Failure(Code code, ChangeSet proposal) {
			super(code + ": " + proposal.changeSetId());
			this.code = code;
			this.proposal = proposal;
		}
		public Code code() { return this.code; }
		public ChangeSet proposal() { return this.proposal; }
	}
	public enum Boundary { PREPARED, MUTATED, COMPLETED }
	@FunctionalInterface public interface BoundaryHook {
		void reached(Boundary boundary, Checkpoint checkpoint) throws IOException;
	}

	/** Deliberately not a record, DTO, tool argument, boolean, or constructible token. */
	public static final class OwnershipProof {
		private final ApplyEngine issuer;
		private final ChangeSet proposal;
		private boolean consumed;
		private OwnershipProof(ApplyEngine issuer, ChangeSet proposal) {
			this.issuer = issuer;
			this.proposal = proposal;
		}
		synchronized void consume(ChangeStore store, ChangeSet set, ApplyEngine applying) throws Failure {
			if (this.consumed || applying != null && applying != this.issuer
					|| !this.issuer.store.workspace().equals(store.workspace())
					|| !this.issuer.store.directory().equals(store.directory())
					|| !this.proposal.owner().equals(set.owner()) || !this.proposal.digest().equals(set.digest())) {
				throw new Failure(Code.EXCLUSIVE_OWNERSHIP_REQUIRED, set);
			}
			this.consumed = true;
		}
	}

	private final ChangeStore store;
	private final PathPolicy policy;
	private final Clock clock;
	private final BoundaryHook hook;

	public ApplyEngine(ChangeStore store, PathPolicy policy, Clock clock) {
		this(store, policy, clock, (boundary, checkpoint) -> {});
	}
	public ApplyEngine(ChangeStore store, PathPolicy policy, Clock clock, BoundaryHook hook) {
		this.store = Objects.requireNonNull(store);
		this.policy = Objects.requireNonNull(policy);
		this.clock = Objects.requireNonNull(clock);
		this.hook = Objects.requireNonNull(hook);
		if (!store.workspace().equals(policy.root())) throw new IllegalArgumentException("Workspace policy mismatch");
	}

	public OwnershipProof confirmExclusiveOwnership(OperatorConsole console, UUID id, ChangeOwner owner,
			String digest) throws IOException {
		EngineMutation.interrupted();
		ChangeStore.Lease lease = this.store.acquire();
		try {
			var view = lease.read();
			EngineMutation.clean(view);
			ChangeSet set = EngineMutation.owned(view, id, owner, digest).changeSet();
			requireApprovedContent(set);
			if (!console.isInteractive()) throw new Failure(Code.EXCLUSIVE_OWNERSHIP_REQUIRED, set);
			if (set.status() == ChangeStatus.APPROVED) baseline(lease, set, false);
			else if (!List.of(ChangeStatus.APPLIED, ChangeStatus.APPLYING, ChangeStatus.PARTIALLY_APPLIED,
					ChangeStatus.RECOVERY_REQUIRED, ChangeStatus.PARTIALLY_UNDONE).contains(set.status()))
				throw new Failure(Code.APPROVAL_REQUIRED, set);
			String answer = console.readLine("Exclude all external workspace writers until this operation ends.\n"
					+ "Type exclusive " + id + " " + digest + ": ");
			EngineMutation.interrupted();
			if (!("exclusive " + id + " " + digest).equals(answer))
				throw new Failure(Code.EXCLUSIVE_OWNERSHIP_REQUIRED, set);
			if (set.status() == ChangeStatus.APPROVED) baseline(lease, set, true);
			return new OwnershipProof(this, set);
		}
		finally { EngineMutation.close(lease); }
	}

	public ChangeStore.Snapshot apply(UUID id, ChangeOwner owner, String digest, OwnershipProof proof) throws IOException {
		EngineMutation.interrupted();
		ChangeStore.Lease lease = this.store.acquire();
		ChangeSet set = null;
		boolean applying = false;
		try {
			var view = lease.read();
			EngineMutation.clean(view);
			set = EngineMutation.owned(view, id, owner, digest).changeSet();
			requireApprovedContent(set);
			if (set.status() == ChangeStatus.STALE) throw new Failure(Code.STALE, set);
			if (set.status() != ChangeStatus.APPROVED) throw new Failure(Code.APPROVAL_REQUIRED, set);
			boolean matchesBeforeOwnership = new Fingerprinter(this.policy).snapshot().equals(set.baseline());
			if (proof == null) throw new Failure(Code.EXCLUSIVE_OWNERSHIP_REQUIRED, set);
			proof.consume(this.store, set, this);
			if (!matchesBeforeOwnership) {
				lease.transition(id, owner, digest, ChangeStatus.STALE);
				throw new Failure(Code.STALE, set);
			}
			baseline(lease, set, true);

			var live = new Fingerprinter(this.policy);
			var stage = new Fingerprinter(PathPolicy.stagedView(lease.stagingDirectory(owner), this.policy));
			Map<String, byte[]> proposedBytes = new HashMap<>();
			long total = 0;
			for (var endpoint : set.proposed().values()) {
				total += endpoint.size();
				if (total > 256L * 1024 * 1024 || set.proposed().size() > 10000
						|| endpoint.size() > 16L * 1024 * 1024)
					throw new PathPolicy.Violation(PathPolicy.Code.LIMIT, endpoint.relativePath());
			}
			for (Diff diff : set.diffs()) {
				String path = diff.relativePath();
				this.policy.resolveMutation(path);
				if (diff.before().kind() == EntryKind.SYMBOLIC_LINK || diff.after().kind() == EntryKind.SYMBOLIC_LINK
						|| diff.before().kind() != EntryKind.MISSING && diff.after().kind() != EntryKind.MISSING
						&& diff.before().kind() != diff.after().kind())
					throw new Failure(Code.INVALID_PROPOSAL, set);
				var actual = stage.read(path);
				if (!actual.fingerprint().equals(diff.after())) throw new Failure(Code.INVALID_PROPOSAL, set);
				EngineMutation.bytes(diff.after(), actual.bytes());
				var original = live.read(path);
				if (!original.fingerprint().equals(diff.before())
						|| !new DiffRenderer(this.policy).render(diff.before(), original.bytes(),
								diff.after(), actual.bytes()).equals(diff))
					throw new Failure(Code.INVALID_PROPOSAL, set);
				proposedBytes.put(path, actual.bytes());
			}
			baseline(lease, set, true);
			EngineMutation.interrupted();
			lease.transition(id, owner, digest, ChangeStatus.APPLYING);
			applying = true;
			for (Diff diff : EngineMutation.ordered(set.diffs(), false)) {
				EngineMutation.interrupted();
				this.policy.resolveMutation(diff.relativePath());
				var current = live.read(diff.relativePath());
				if (!current.fingerprint().equals(diff.before())) throw new Failure(Code.STALE, set);
				Checkpoint checkpoint = lease.prepare(id, owner, digest, UUID.randomUUID(), diff.relativePath(),
						current.bytes(), proposedBytes.get(diff.relativePath()), this.clock.instant());
				this.hook.reached(Boundary.PREPARED, checkpoint);
				EngineMutation.interrupted();
				EngineMutation.mutate(this.policy, diff.before(), diff.after(), proposedBytes.get(diff.relativePath()));
				this.hook.reached(Boundary.MUTATED, checkpoint);
				EngineMutation.interrupted();
				if (!live.fingerprint(diff.relativePath()).equals(diff.after())) throw new Failure(Code.STALE, set);
				checkpoint = lease.complete(id, owner, digest, checkpoint.checkpointId(), Checkpoint.State.APPLIED, this.clock.instant());
				this.hook.reached(Boundary.COMPLETED, checkpoint);
			}
			EngineMutation.interrupted();
			if (!live.snapshot().equals(set.proposed())) throw new Failure(Code.STALE, set);
			return lease.transition(id, owner, digest, ChangeStatus.APPLIED);
		}
		catch (IOException | RuntimeException ex) {
			if (applying) EngineMutation.failed(lease, set, ex, false);
			throw ex;
		}
		finally { EngineMutation.close(lease); }
	}

	private static void requireApprovedContent(ChangeSet set) throws Failure {
		Approval approval = set.approval();
		if (approval == null || approval.decision() != ApprovalDecision.APPROVED
				|| !approval.owner().equals(set.owner()) || !approval.changeSetDigest().equals(set.digest())
				|| !approval.changeSetId().equals(set.changeSetId()) || set.diffs().stream().anyMatch(d -> !d.reviewable()))
			throw new Failure(Code.APPROVAL_REQUIRED, set);
	}

	private void baseline(ChangeStore.Lease lease, ChangeSet set, boolean recordStale) throws IOException {
		if (!new Fingerprinter(this.policy).snapshot().equals(set.baseline())) {
			if (recordStale) lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.STALE);
			throw new Failure(Code.STALE, set);
		}
	}
}
