package com.example.miniagent.core.goal;

import java.util.List;
import java.util.Objects;
import com.example.miniagent.core.change.VerificationEvidence;
import com.example.miniagent.core.change.VerificationTarget;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.run.RunId;

/** Goal verdict is distinct from its authoritative managed run state. */
public record GoalRecord(int schemaVersion, String id, Spec spec, State state, Reason reason,
		int steps, Phase phase, VerificationTarget target, List<GateResult> results,
		boolean candidate, String feedback) {
	public enum State { PENDING, RUNNING, BLOCKED, SUCCEEDED, FAILED, CANCELLED }
	public enum Reason { NONE, VERIFIED_PROPOSAL, VERIFIED_READONLY, GATE_FAILED, NO_PROGRESS,
		STEP_LIMIT, BUDGET_LIMIT, APPROVAL, INTERRUPTED, CANCEL_REQUESTED, RUN_FAILED,
		EVIDENCE_STALE, CHECKPOINT_UNCERTAIN }
	public enum Phase { READY, MODEL, GATES }
	public record Spec(GoalRequest request, ModelSelection selection, String parentConversationId,
			String workspace, RunId runId) {
		public Spec {
			Objects.requireNonNull(request); Objects.requireNonNull(selection); Objects.requireNonNull(runId);
			safe(parentConversationId);
			var path = java.nio.file.Path.of(workspace);
			if (!path.isAbsolute() || !path.normalize().toString().equals(workspace))
				throw new IllegalArgumentException("Noncanonical goal workspace");
		}
	}
	public record GateResult(int gate, String revision, String configuration, VerificationRunner.Code code,
			VerificationEvidence evidence, String logBase64) {
		public GateResult {
			if (gate < 0 || gate >= 8 || logBase64 == null || logBase64.length() > 87400)
				throw new IllegalArgumentException("Invalid gate result bounds");
			if (revision == null || !revision.matches("[0-9a-f]{64}")
					|| configuration == null || !configuration.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("Invalid gate binding digest");
			Objects.requireNonNull(code);
		}
	}
	public GoalRecord {
		if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported goal schema");
		safe(id); Objects.requireNonNull(spec); Objects.requireNonNull(state); Objects.requireNonNull(reason);
		Objects.requireNonNull(phase);
		results = List.copyOf(results);
		if (steps < 0 || steps > spec.request().maxSteps() || results.size() > 256
				|| feedback == null || feedback.length() > 8192)
			throw new IllegalArgumentException("Invalid goal checkpoint bounds");
		if (candidate && (target == null || results.size() < spec.request().gates().size()))
			throw new IllegalArgumentException("Incomplete goal candidate");
		if (target != null && !target.owner().runId().equals(spec.runId()))
			throw new IllegalArgumentException("Foreign goal proposal");
	}
	static String safe(String id) {
		if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
			throw new IllegalArgumentException("Unsafe goal identity");
		return id;
	}
	GoalRecord checkpoint(State state, Reason reason, int steps, Phase phase, VerificationTarget target,
			List<GateResult> results, boolean candidate, String feedback) {
		return new GoalRecord(1, id, spec, state, reason, steps, phase, target, results, candidate, feedback);
	}
}
