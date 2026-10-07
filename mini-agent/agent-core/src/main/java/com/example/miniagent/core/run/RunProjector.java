package com.example.miniagent.core.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.example.miniagent.core.run.RunStoreException.Code.INVALID_TRANSITION;
import static com.example.miniagent.core.run.RunStoreException.Code.TERMINAL;

/** Pure projection. Store validates on a replay before committing each event. */
public final class RunProjector {
	private RunProjector() {}

	public static RunRecord project(RunSpec spec, List<RunEvent> events, boolean writerPresent)
			throws RunStoreException {
		RunState state = RunState.CREATED;
		RunEvent.Reason reason = RunEvent.Reason.NONE;
		Instant last = spec.createdAt();
		List<Attempt> attempts = new ArrayList<>();
		Attempt active = null;
		boolean observed = false;
		RunUsage usage = RunUsage.empty();
		Set<String> usageIds = new HashSet<>();
		Set<String> toolIds = new HashSet<>();
		Map<String, RunEvent.ToolStarted> tools = new java.util.LinkedHashMap<>();
		Map<String, RunEvent.ApprovalRequested> approvals = new HashMap<>();
		Set<String> approvalIds = new HashSet<>();
		for (RunEvent event : events) {
			if (state.terminal()) {
				throw new RunStoreException(TERMINAL, "Event follows terminal state");
			}
			require(event != null && event.at() != null && !event.at().isBefore(last), "Nonmonotonic event time");
			last = event.at();
			try {
				switch (event) {
					case RunEvent.StateChanged change -> {
						require(change.state() != null && change.reason() != null, "Missing state/reason");
						boolean allowed = switch (state) {
							case CREATED -> change.state() == RunState.RUNNING;
							case RUNNING -> change.state() == RunState.WAITING_APPROVAL
									|| change.state() == RunState.INTERRUPTED || change.state().terminal();
							case WAITING_APPROVAL -> change.state() == RunState.RUNNING
									|| change.state() == RunState.INTERRUPTED || change.state() == RunState.CANCELLED;
							case INTERRUPTED -> change.state() == RunState.RUNNING || change.state() == RunState.CANCELLED;
							default -> false;
						};
						require(allowed, "Invalid state transition");
						require(!change.state().terminal() || active == null, "End attempt before terminal");
						require(state != RunState.INTERRUPTED || change.state() != RunState.RUNNING || active == null,
								"End interrupted attempt before resuming");
						require(change.state() != RunState.COMPLETED || tools.isEmpty(),
								"Unfinished tools prevent completion");
						require(change.state() != RunState.RUNNING || approvals.isEmpty(), "Resolve approval before running");
						state = change.state();
						reason = change.reason();
					}
					case RunEvent.AttemptStarted start -> {
						RunId.requireSafe(start.attemptId());
						require(state == RunState.RUNNING && active == null, "Attempt requires running state");
						require(attempts.stream().noneMatch(a -> a.attemptId().equals(start.attemptId())
								|| a.bindingId().equals(start.bindingId())), "Attempt/binding must be fresh");
						active = new Attempt(start.attemptId(), start.bindingId(), start.at(), null);
						attempts.add(active);
						observed = false;
					}
					case RunEvent.UsageRecorded recorded -> {
						requireActive(active, recorded.attemptId());
						RunId.requireSafe(recorded.callId());
						require(recorded.kind() != null && recorded.usage() != null, "Missing usage");
						if (recorded.callId().startsWith("child-")) {
							require(recorded.kind() == RunEvent.UsageKind.COMPACTION
									&& recorded.callId().matches("child-[0-9a-f]{64}")
									&& spec.childId() == null && spec.parentRunId() == null,
									"Invalid child charge identity");
						}
						// Child identities survive parent attempts. Ordinary observations remain attempt-local.
						require(usageIds.add(recorded.callId().startsWith("child-")
								? recorded.callId()
								: recorded.attemptId() + ":" + recorded.kind() + ":" + recorded.callId()),
								"Duplicate usage observation");
						usage = usage.plus(recorded.usage());
						observed = true;
					}
					case RunEvent.AttemptEnded end -> {
						requireActive(active, end.attemptId());
						require(observed, "Record usage before ending attempt");
						attempts.set(attempts.size() - 1,
								new Attempt(active.attemptId(), active.bindingId(), active.startedAt(), end.at()));
						active = null;
						approvals.clear();
					}
					case RunEvent.ToolStarted start -> {
						requireActive(active, start.attemptId());
						require(state == RunState.RUNNING, "Tool requires running state");
						RunId.requireSafe(start.toolCallKey());
						RunId.requireSafe(start.toolName());
						RunSpec.requireDigest(start.argsDigest());
						require(start.effect() != null, "Missing effect classification");
						if (start.snapshotDigest() != null) RunSpec.requireDigest(start.snapshotDigest());
						String key = start.attemptId() + ":" + start.toolCallKey();
						require(toolIds.add(key), "Duplicate tool start");
						tools.put(key, start);
					}
					case RunEvent.ToolFinished finish -> {
						requireActive(active, finish.attemptId());
						RunSpec.requireDigest(finish.resultDigest());
						require(tools.remove(finish.attemptId() + ":" + finish.toolCallKey()) != null,
								"Tool finish lacks start");
					}
					case RunEvent.ToolResolution resolution -> {
						require(state == RunState.INTERRUPTED, "Resolution requires interrupted ownership");
						RunId.requireSafe(resolution.operatorId());
						RunId.requireSafe(resolution.evidenceId());
						RunEvent.ToolStarted unknown = tools.get(resolution.oldAttemptId() + ":" + resolution.toolCallKey());
						require(unknown != null && resolution.decision() != null, "Resolution requires UNKNOWN key");
						require(resolution.decision() != RunEvent.Resolution.READ_RETRY
								|| unknown.effect() == RunEvent.Effect.READ_ONLY && unknown.snapshotDigest() != null,
								"READ_RETRY requires authoritative read-only snapshot");
						tools.remove(resolution.oldAttemptId() + ":" + resolution.toolCallKey());
					}
					case RunEvent.DriftAccepted accepted -> {
						require(state == RunState.INTERRUPTED, "Drift acknowledgement requires interrupted ownership");
						RunSpec.requireDigest(accepted.expectedDigest());
						RunSpec.requireDigest(accepted.actualDigest());
						RunId.requireSafe(accepted.operatorId());
						RunId.requireSafe(accepted.evidenceId());
						require(!accepted.expectedDigest().equals(accepted.actualDigest()), "No drift to acknowledge");
					}
					case RunEvent.ApprovalRequested request -> {
						requireActive(active, request.attemptId());
						require(state == RunState.RUNNING, "Approval request requires running state");
						RunId.requireSafe(request.requestId());
						RunId.requireSafe(request.toolCallKey());
						RunSpec.requireDigest(request.argsDigest());
						if (request.diffDigest() != null) {
							RunSpec.requireDigest(request.diffDigest());
						}
						require(approvalIds.add(request.requestId()), "Duplicate approval request");
						approvals.put(request.requestId(), request);
					}
					case RunEvent.ApprovalResolved resolution -> {
						requireActive(active, resolution.attemptId());
						require(resolution.decision() != null && approvals.containsKey(resolution.requestId()),
								"Unknown approval response");
						if (resolution.decision() != RunEvent.Decision.DEFERRED) {
							approvals.remove(resolution.requestId());
						}
					}
				}
			}
			catch (IllegalArgumentException | NullPointerException | ArithmeticException ex) {
				throw new RunStoreException(INVALID_TRANSITION, "Invalid event payload", ex);
			}
		}
		if (!writerPresent && (state == RunState.RUNNING || state == RunState.WAITING_APPROVAL)) {
			state = RunState.INTERRUPTED;
			reason = RunEvent.Reason.PROCESS_LOST;
		}
		return new RunRecord(spec, events.size(), state, reason, attempts, usage, new ArrayList<>(tools.values()));
	}

	private static void requireActive(Attempt active, String id) throws RunStoreException {
		require(active != null && active.attemptId().equals(id), "Event has no matching active attempt");
	}

	private static void require(boolean condition, String message) throws RunStoreException {
		if (!condition) {
			throw new RunStoreException(INVALID_TRANSITION, message);
		}
	}
}
