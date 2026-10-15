package com.example.miniagent.core.delegation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunUsage;
import org.jspecify.annotations.Nullable;

public record ChildResult(String childId, UUID conversationId, RunId runId, UUID attemptId,
		ModelRef model, ChildStatus status, @Nullable ChildFailure failure,
		List<ChildEvidence> evidence, RunUsage usage) {
	public ChildResult {
		if (childId == null || !childId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
			throw new IllegalArgumentException("Invalid child ID");
		}
		Objects.requireNonNull(conversationId);
		Objects.requireNonNull(runId);
		Objects.requireNonNull(attemptId);
		Objects.requireNonNull(model);
		Objects.requireNonNull(status);
		Objects.requireNonNull(usage);
		evidence = List.copyOf(evidence);
		if (conversationId.toString().equals(runId.value()) || conversationId.equals(attemptId)
				|| attemptId.toString().equals(runId.value())) {
			throw new IllegalArgumentException("Child identities must be distinct");
		}
		boolean unsuccessful = status.terminal() && status != ChildStatus.SUCCEEDED;
		if (unsuccessful != (failure != null)) throw new IllegalArgumentException("Status/failure mismatch");
		if (failure != null) {
			if ((status == ChildStatus.CANCELLED) != (failure.code() == ChildFailure.Code.CANCELLED)
					|| (status == ChildStatus.TIMED_OUT) != (failure.code() == ChildFailure.Code.TIMED_OUT)
					|| (status == ChildStatus.SKIPPED) != (failure.code() == ChildFailure.Code.DEPENDENCY_FAILED)) {
				throw new IllegalArgumentException("Status/failure code mismatch");
			}
		}
		for (ChildEvidence item : evidence) {
			if (item instanceof ChildEvidence.Collected c
					&& (!c.owner().sessionId().equals(conversationId.toString())
					|| !c.owner().runId().equals(runId)
					|| !c.owner().attemptId().equals(attemptId.toString()))) {
				throw new IllegalArgumentException("Foreign collected owner");
			}
			if (item instanceof ChildEvidence.Verification v
					&& (!v.evidence().owner().sessionId().equals(conversationId.toString())
					|| !v.evidence().owner().runId().equals(runId)
					|| !v.evidence().owner().attemptId().equals(attemptId.toString()))) {
				throw new IllegalArgumentException("Foreign verification owner");
			}
		}
	}

	public boolean verifiedSuccess(ChildRole role, String revision, String configurationDigest) {
		Objects.requireNonNull(role);
		if (this.status != ChildStatus.SUCCEEDED
				|| this.evidence.stream().anyMatch(ChildEvidence.Rejected.class::isInstance)
				|| this.evidence.stream().anyMatch(e -> e instanceof ChildEvidence.ToolResult t
					&& t.outcome() != ChildEvidence.ToolResult.Outcome.FINISHED)
				|| this.evidence.stream().anyMatch(e -> e instanceof ChildEvidence.Verification v
					&& (v.outcome() != com.example.miniagent.core.change.VerificationRunner.Code.SUCCEEDED
						|| !v.evidence().successful() || !v.evidence().validFor(revision, configurationDigest)))) {
			return false;
		}
		// Preserve the existing pure contract's direct owned process proof.
		return verifiedSuccess(revision, configurationDigest)
				|| this.evidence.stream().anyMatch(e -> e instanceof ChildEvidence.Collected c
					&& c.role() == role && c.revision().equals(revision)
					&& c.configurationDigest().equals(configurationDigest));
	}

	/** Parent independently checks persisted digest/revision before trusting this predicate. */
	public boolean verifiedSuccess(String revision, String configurationDigest) {
		return this.status == ChildStatus.SUCCEEDED
				&& this.evidence.stream().noneMatch(ChildEvidence.Rejected.class::isInstance)
				&& this.evidence.stream().noneMatch(e -> e instanceof ChildEvidence.ToolResult t
					&& t.outcome() != ChildEvidence.ToolResult.Outcome.FINISHED)
				&& this.evidence.stream().anyMatch(e -> e instanceof ChildEvidence.Verification v
					&& v.outcome() == com.example.miniagent.core.change.VerificationRunner.Code.SUCCEEDED
					&& v.evidence().successful() && v.evidence().validFor(revision, configurationDigest))
				&& this.evidence.stream().noneMatch(e -> e instanceof ChildEvidence.Verification v
					&& v.evidence().validFor(revision, configurationDigest)
					&& (v.outcome() != com.example.miniagent.core.change.VerificationRunner.Code.SUCCEEDED
							|| !v.evidence().successful()));
	}
}
