package com.example.miniagent.core.run;

import java.time.Instant;
import java.util.UUID;

/** Journal payloads contain identities and digests, not raw prompts, arguments or results. */
public sealed interface RunEvent {
	Instant at();

	enum Reason { NONE, PROCESS_LOST, USER_CANCELLED, BUDGET_EXHAUSTED, ERROR }
	enum UsageKind { MODEL_ROUND, COMPACTION, TOOLS, ACTIVE_TIME }
	enum Effect { READ_ONLY, MUTATING }
	enum Decision { APPROVED, REJECTED, DEFERRED, CANCELLED }
	enum Resolution { READ_RETRY, MUTATING_RETRY, SKIP }

	record StateChanged(Instant at, RunState state, Reason reason) implements RunEvent {}
	record AttemptStarted(Instant at, String attemptId, UUID bindingId) implements RunEvent {}
	record UsageRecorded(Instant at, String attemptId, UsageKind kind, String callId,
			RunUsage usage) implements RunEvent {}
	record AttemptEnded(Instant at, String attemptId) implements RunEvent {}
	record ApprovalRequested(Instant at, String attemptId, String requestId, String toolCallKey,
			String argsDigest, String diffDigest) implements RunEvent {}
	record ApprovalResolved(Instant at, String attemptId, String requestId,
			Decision decision) implements RunEvent {}
	record ToolStarted(Instant at, String attemptId, String toolCallKey, String toolName,
			String argsDigest, Effect effect, String snapshotDigest) implements RunEvent {
		public ToolStarted(Instant at, String attemptId, String toolCallKey, String toolName,
				String argsDigest, Effect effect) {
			this(at, attemptId, toolCallKey, toolName, argsDigest, effect, null);
		}
	}
	record ToolFinished(Instant at, String attemptId, String toolCallKey, String resultDigest) implements RunEvent {}
	record ToolResolution(Instant at, String oldAttemptId, String toolCallKey,
			Resolution decision, String operatorId, String evidenceId) implements RunEvent {}
	record DriftAccepted(Instant at, String expectedDigest, String actualDigest,
			String operatorId, String evidenceId) implements RunEvent {}
}
