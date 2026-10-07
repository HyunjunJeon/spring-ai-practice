package com.example.miniagent.core.change;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.approval.ApprovalDecision;

/** Complete filtered trees bind approvals to unchanged source as well as edited paths. */
public record ChangeSet(UUID changeSetId, ChangeOwner owner, String cwd, ModelRef model,
		List<UUID> bindingIds, Map<String, Fingerprint> baseline, Map<String, Fingerprint> proposed,
		List<Diff> diffs, ChangeStatus status, Approval approval, Instant createdAt) {
	public ChangeSet {
		Objects.requireNonNull(changeSetId);
		Objects.requireNonNull(owner);
		VerificationEvidence.requireCwd(cwd);
		Objects.requireNonNull(model);
		bindingIds = bindingIds.stream().sorted().toList();
		if (bindingIds.isEmpty() || new HashSet<>(bindingIds).size() != bindingIds.size()) {
			throw new IllegalArgumentException("At least one unique binding ID is required");
		}
		baseline = tree(baseline);
		proposed = tree(proposed);
		diffs = diffs.stream().sorted(Comparator.comparing(Diff::relativePath)).toList();
		var changed = new HashSet<String>();
		var paths = new HashSet<>(baseline.keySet());
		paths.addAll(proposed.keySet());
		for (String path : paths) {
			if (!baseline.getOrDefault(path, Fingerprint.missing(path))
					.equals(proposed.getOrDefault(path, Fingerprint.missing(path)))) changed.add(path);
		}
		var seen = new HashSet<String>();
		for (Diff diff : diffs) {
			String path = diff.relativePath();
			if (!seen.add(path) || !diff.before().equals(baseline.getOrDefault(path, Fingerprint.missing(path)))
					|| !diff.after().equals(proposed.getOrDefault(path, Fingerprint.missing(path)))) {
				throw new IllegalArgumentException("Diff does not match source trees");
			}
		}
		if (!seen.equals(changed) || changed.isEmpty()) throw new IllegalArgumentException("Incomplete or empty change set");
		Objects.requireNonNull(status);
		Objects.requireNonNull(createdAt);
		String digest = digestOf(changeSetId, owner, cwd, model, bindingIds, baseline, proposed, diffs);
		if (approval != null && (!approval.changeSetId().equals(changeSetId) || !approval.owner().equals(owner)
				|| !approval.changeSetDigest().equals(digest) || approval.decidedAt().isBefore(createdAt))) {
			throw new IllegalArgumentException("Approval does not match change set");
		}
		if (status == ChangeStatus.APPROVED || status == ChangeStatus.APPLYING || status == ChangeStatus.APPLIED
				|| status == ChangeStatus.PARTIALLY_APPLIED || status == ChangeStatus.UNDONE
				|| status == ChangeStatus.PARTIALLY_UNDONE) {
			if (approval == null || approval.decision() != ApprovalDecision.APPROVED || diffs.stream().anyMatch(d -> !d.reviewable())) {
				throw new IllegalArgumentException("Applying requires approval of complete content");
			}
		}
	}

	private static Map<String, Fingerprint> tree(Map<String, Fingerprint> tree) {
		TreeMap<String, Fingerprint> copy = new TreeMap<>(tree);
		copy.forEach((p, f) -> {
			if (!p.equals(f.relativePath()) || f.kind() == EntryKind.MISSING) {
				throw new IllegalArgumentException("Invalid source tree entry");
			}
		});
		return Collections.unmodifiableMap(copy);
	}

	public static ChangeSet propose(UUID id, ChangeOwner owner, String cwd, ModelRef model, List<UUID> bindings,
			Map<String, Fingerprint> baseline, Map<String, Fingerprint> proposed, List<Diff> diffs, Instant at) {
		return new ChangeSet(id, owner, cwd, model, bindings, baseline, proposed, diffs, ChangeStatus.PENDING_APPROVAL, null, at);
	}

	public String digest() {
		return digestOf(this.changeSetId, this.owner, this.cwd, this.model, this.bindingIds, this.baseline, this.proposed, this.diffs);
	}

	public ChangeSet decide(Approval decision) {
		if (this.status != ChangeStatus.PENDING_APPROVAL || this.approval != null
				&& this.approval.decision() != ApprovalDecision.DEFERRED) {
			throw new IllegalStateException("Change set is not awaiting approval");
		}
		ChangeStatus next = switch (decision.decision()) {
			case APPROVED -> ChangeStatus.APPROVED;
			case REJECTED, CANCELLED -> ChangeStatus.REJECTED;
			case DEFERRED -> ChangeStatus.PENDING_APPROVAL;
		};
		return copy(next, decision);
	}

	public ChangeSet transition(ChangeStatus next) {
		if (!this.status.canTransitionTo(next) || next == ChangeStatus.APPROVED) {
			throw new IllegalStateException("Invalid change transition " + this.status + " -> " + next);
		}
		return copy(next, this.approval);
	}

	private ChangeSet copy(ChangeStatus status, Approval approval) {
		return new ChangeSet(this.changeSetId, this.owner, this.cwd, this.model, this.bindingIds,
				this.baseline, this.proposed, this.diffs, status, approval, this.createdAt);
	}

	private static String digestOf(UUID id, ChangeOwner owner, String cwd, ModelRef ref, List<UUID> bindings,
			Map<String, Fingerprint> baseline, Map<String, Fingerprint> proposed, List<Diff> diffs) {
		try {
			var bytes = new ByteArrayOutputStream();
			var out = new DataOutputStream(bytes);
			DiffRenderer.field(out, "change-set-v1");
			for (String s : List.of(id.toString(), owner.sessionId(), owner.runId().value(), owner.attemptId(),
					owner.turnId(), cwd, ref.provider(), ref.model())) DiffRenderer.field(out, s);
			out.writeInt(bindings.size());
			for (UUID binding : bindings) DiffRenderer.field(out, binding.toString());
			DiffRenderer.field(out, Fingerprinter.treeDigest(baseline));
			DiffRenderer.field(out, Fingerprinter.treeDigest(proposed));
			DiffRenderer.field(out, DiffRenderer.digest(diffs));
			return Fingerprinter.sha256(bytes.toByteArray());
		}
		catch (IOException ex) { throw new AssertionError(ex); }
	}
}
