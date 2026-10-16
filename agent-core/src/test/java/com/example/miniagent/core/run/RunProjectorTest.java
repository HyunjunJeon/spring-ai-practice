package com.example.miniagent.core.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import com.example.miniagent.core.model.ModelRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import static com.example.miniagent.core.run.RunStoreException.Code.INVALID_TRANSITION;
import static com.example.miniagent.core.run.RunStoreException.Code.TERMINAL;
import static org.assertj.core.api.Assertions.*;

/** The projector is the integrity authority replayed on every read and append: one row per rule. */
class RunProjectorTest {
	private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
	private static final String DIGEST = "a".repeat(64);
	private static final String OTHER_DIGEST = "b".repeat(64);
	private static final String CHILD_CHARGE = "child-" + "c".repeat(64);
	private static final UUID BINDING = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OTHER_BINDING = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final RunSpec SPEC = spec(null, null);
	private static final RunSpec CHILD_SPEC = spec(new RunId("parent-run"), "child-1");

	private static RunSpec spec(RunId parent, String childId) {
		return new RunSpec(1, new RunId("run-1"), "conversation-1", "original", parent, childId,
				new ModelRef("fake", "test"), "fake", "/work", RunBudget.defaults(), DIGEST, DIGEST, AT);
	}

	private static RunEvent state(RunState state) {
		return new RunEvent.StateChanged(AT, state, RunEvent.Reason.NONE);
	}
	private static RunEvent attempt(String id, UUID binding) { return new RunEvent.AttemptStarted(AT, id, binding); }
	private static RunEvent usage(String attempt, RunEvent.UsageKind kind, String callId) {
		return new RunEvent.UsageRecorded(AT, attempt, kind, callId, new RunUsage(3, false, 0, 0, 0));
	}
	private static RunEvent round(String attempt, String callId) { return usage(attempt, RunEvent.UsageKind.MODEL_ROUND, callId); }
	private static RunEvent ended(String attempt) { return new RunEvent.AttemptEnded(AT, attempt); }
	private static RunEvent tool(String key, RunEvent.Effect effect, String snapshot) {
		return new RunEvent.ToolStarted(AT, "attempt", key, "fixture_tool", DIGEST, effect, snapshot);
	}
	private static RunEvent finished(String key, String digest) { return new RunEvent.ToolFinished(AT, "attempt", key, digest); }
	private static RunEvent resolution(RunEvent.Resolution decision) {
		return new RunEvent.ToolResolution(AT, "attempt", "key", decision, "operator", "evidence");
	}
	private static RunEvent approval(String requestId) {
		return new RunEvent.ApprovalRequested(AT, "attempt", requestId, "key", DIGEST, null);
	}
	private static RunEvent approved(String requestId, RunEvent.Decision decision) {
		return new RunEvent.ApprovalResolved(AT, "attempt", requestId, decision);
	}

	private static List<RunEvent> then(List<RunEvent> prefix, RunEvent... tail) {
		List<RunEvent> events = new ArrayList<>(prefix);
		events.addAll(List.of(tail));
		return events;
	}

	/** An attempt that is running. */
	private static final List<RunEvent> ACTIVE = List.of(state(RunState.RUNNING), attempt("attempt", BINDING));
	/** The attempt ended and the run is INTERRUPTED with one unfinished mutating tool. */
	private static final List<RunEvent> UNKNOWN_MUTATION = List.of(state(RunState.RUNNING), attempt("attempt", BINDING),
			tool("key", RunEvent.Effect.MUTATING, null), round("attempt", "round"), ended("attempt"), state(RunState.INTERRUPTED));
	/** Same, but the unfinished tool was a read classified against a recorded snapshot. */
	private static final List<RunEvent> UNKNOWN_READ = List.of(state(RunState.RUNNING), attempt("attempt", BINDING),
			tool("key", RunEvent.Effect.READ_ONLY, DIGEST), round("attempt", "round"), ended("attempt"), state(RunState.INTERRUPTED));
	/** The attempt ended and the run is INTERRUPTED with nothing unfinished. */
	private static final List<RunEvent> INTERRUPTED = List.of(state(RunState.RUNNING), attempt("attempt", BINDING),
			round("attempt", "round"), ended("attempt"), state(RunState.INTERRUPTED));

	private record Rejected(String name, RunSpec spec, List<RunEvent> events, RunStoreException.Code code,
			String message, String causeMessage) {
		@Override public String toString() { return this.name; }
	}

	private static Rejected transition(String name, List<RunEvent> events, String message) {
		return new Rejected(name, SPEC, events, INVALID_TRANSITION, message, null);
	}
	private static Rejected payload(String name, List<RunEvent> events, String causeMessage) {
		return new Rejected(name, SPEC, events, INVALID_TRANSITION, "Invalid event payload", causeMessage);
	}

	static Stream<Rejected> rejected() {
		String noActive = "Event has no matching active attempt";
		String badState = "Invalid state transition";
		return Stream.of(
				// Terminal states accept nothing, and that verdict precedes the clock check.
				new Rejected("event after COMPLETED", SPEC, List.of(state(RunState.RUNNING), state(RunState.COMPLETED),
						state(RunState.RUNNING)), TERMINAL, "Event follows terminal state", null),
				new Rejected("event after FAILED", SPEC, List.of(state(RunState.RUNNING), state(RunState.FAILED),
						state(RunState.INTERRUPTED)), TERMINAL, "Event follows terminal state", null),
				new Rejected("event after CANCELLED", SPEC, List.of(state(RunState.RUNNING), state(RunState.CANCELLED),
						state(RunState.RUNNING)), TERMINAL, "Event follows terminal state", null),
				new Rejected("earlier event after a terminal state is TERMINAL, not a clock error", SPEC,
						List.of(state(RunState.RUNNING), state(RunState.COMPLETED),
								new RunEvent.StateChanged(AT.minusSeconds(1), RunState.RUNNING, RunEvent.Reason.NONE)),
						TERMINAL, "Event follows terminal state", null),

				// Clock.
				transition("first event before the spec was created", List.of(
						new RunEvent.StateChanged(AT.minusSeconds(1), RunState.RUNNING, RunEvent.Reason.NONE)),
						"Nonmonotonic event time"),
				transition("event older than its predecessor", List.of(
						new RunEvent.StateChanged(AT.plusSeconds(5), RunState.RUNNING, RunEvent.Reason.NONE),
						new RunEvent.AttemptStarted(AT.plusSeconds(4), "attempt", BINDING)), "Nonmonotonic event time"),
				transition("event without a timestamp", List.of(
						new RunEvent.StateChanged(null, RunState.RUNNING, RunEvent.Reason.NONE)), "Nonmonotonic event time"),

				// State machine.
				transition("CREATED cannot be interrupted", List.of(state(RunState.INTERRUPTED)), badState),
				transition("CREATED cannot complete", List.of(state(RunState.COMPLETED)), badState),
				transition("CREATED cannot wait for approval", List.of(state(RunState.WAITING_APPROVAL)), badState),
				transition("RUNNING cannot return to CREATED", List.of(state(RunState.RUNNING), state(RunState.CREATED)), badState),
				transition("RUNNING cannot start running again", List.of(state(RunState.RUNNING), state(RunState.RUNNING)), badState),
				transition("WAITING_APPROVAL cannot complete", List.of(state(RunState.RUNNING),
						state(RunState.WAITING_APPROVAL), state(RunState.COMPLETED)), badState),
				transition("WAITING_APPROVAL cannot fail", List.of(state(RunState.RUNNING),
						state(RunState.WAITING_APPROVAL), state(RunState.FAILED)), badState),
				transition("INTERRUPTED cannot complete", then(INTERRUPTED, state(RunState.COMPLETED)), badState),
				transition("INTERRUPTED cannot fail", then(INTERRUPTED, state(RunState.FAILED)), badState),
				transition("INTERRUPTED cannot wait for approval", then(INTERRUPTED, state(RunState.WAITING_APPROVAL)), badState),
				transition("state change without a state", List.of(
						new RunEvent.StateChanged(AT, null, RunEvent.Reason.NONE)), "Missing state/reason"),
				transition("state change without a reason", List.of(
						new RunEvent.StateChanged(AT, RunState.RUNNING, null)), "Missing state/reason"),
				transition("terminal state while an attempt is open", then(ACTIVE, state(RunState.FAILED)),
						"End attempt before terminal"),
				transition("cancel while an attempt is open", then(ACTIVE, state(RunState.CANCELLED)),
						"End attempt before terminal"),
				transition("resume while the interrupted attempt is still open", then(ACTIVE,
						state(RunState.INTERRUPTED), state(RunState.RUNNING)), "End interrupted attempt before resuming"),
				transition("open tool at COMPLETED", List.of(state(RunState.RUNNING), attempt("attempt", BINDING),
						tool("key", RunEvent.Effect.READ_ONLY, null), round("attempt", "round"), ended("attempt"),
						state(RunState.COMPLETED)), "Unfinished tools prevent completion"),
				transition("pending approval at RUNNING", then(ACTIVE, approval("request"),
						state(RunState.WAITING_APPROVAL), state(RunState.RUNNING)), "Resolve approval before running"),
				transition("DEFERRED approval is still pending at RUNNING", then(ACTIVE, approval("request"),
						state(RunState.WAITING_APPROVAL), approved("request", RunEvent.Decision.DEFERRED),
						state(RunState.RUNNING)), "Resolve approval before running"),

				// Attempts.
				transition("attempt before running", List.of(attempt("attempt", BINDING)), "Attempt requires running state"),
				transition("second attempt while one is open", then(ACTIVE, attempt("other", OTHER_BINDING)),
						"Attempt requires running state"),
				transition("attempt id reused by a later attempt", then(INTERRUPTED, state(RunState.RUNNING),
						attempt("attempt", OTHER_BINDING)), "Attempt/binding must be fresh"),
				transition("binding id reused by a later attempt", then(INTERRUPTED, state(RunState.RUNNING),
						attempt("other", BINDING)), "Attempt/binding must be fresh"),
				payload("unsafe attempt id", List.of(state(RunState.RUNNING), attempt("../attempt", BINDING)),
						"Unsafe execution ID"),
				transition("attempt end without an attempt", List.of(state(RunState.RUNNING), ended("attempt")), noActive),
				transition("attempt end for another attempt", then(ACTIVE, round("attempt", "round"), ended("other")), noActive),
				transition("attempt end before any usage", then(ACTIVE, ended("attempt")), "Record usage before ending attempt"),

				// Usage.
				transition("usage without an attempt", List.of(state(RunState.RUNNING), round("attempt", "round")), noActive),
				transition("usage for another attempt", then(ACTIVE, round("other", "round")), noActive),
				transition("usage without a kind", then(ACTIVE, new RunEvent.UsageRecorded(AT, "attempt", null, "round",
						RunUsage.empty())), "Missing usage"),
				transition("usage without an amount", then(ACTIVE, new RunEvent.UsageRecorded(AT, "attempt",
						RunEvent.UsageKind.MODEL_ROUND, "round", null)), "Missing usage"),
				payload("unsafe usage call id", then(ACTIVE, round("attempt", "round/1")), "Unsafe execution ID"),
				transition("same usage observation twice", then(ACTIVE, round("attempt", "round"), round("attempt", "round")),
						"Duplicate usage observation"),
				payload("usage overflow", then(ACTIVE, new RunEvent.UsageRecorded(AT, "attempt", RunEvent.UsageKind.MODEL_ROUND,
						"huge", new RunUsage(Long.MAX_VALUE, false, 0, 0, 0)), new RunEvent.UsageRecorded(AT, "attempt",
						RunEvent.UsageKind.TOOLS, "huge", new RunUsage(1, false, 0, 0, 0))), "long overflow"),
				transition("child charge that is not a compaction", then(ACTIVE, usage("attempt", RunEvent.UsageKind.MODEL_ROUND,
						CHILD_CHARGE)), "Invalid child charge identity"),
				transition("child charge id that is not 64 hex digits", then(ACTIVE, usage("attempt",
						RunEvent.UsageKind.COMPACTION, "child-" + "c".repeat(63))), "Invalid child charge identity"),
				transition("child charge id with uppercase hex", then(ACTIVE, usage("attempt",
						RunEvent.UsageKind.COMPACTION, "child-" + "C".repeat(64))), "Invalid child charge identity"),
				new Rejected("child charge recorded on a child run", CHILD_SPEC, then(ACTIVE, usage("attempt",
						RunEvent.UsageKind.COMPACTION, CHILD_CHARGE)), INVALID_TRANSITION, "Invalid child charge identity", null),
				transition("child charge imported twice in one attempt", then(ACTIVE, usage("attempt",
						RunEvent.UsageKind.COMPACTION, CHILD_CHARGE), usage("attempt", RunEvent.UsageKind.COMPACTION,
						CHILD_CHARGE)), "Duplicate usage observation"),
				transition("child charge imported again by a later attempt", chargeTwiceAcrossAttempts(),
						"Duplicate usage observation"),

				// Tool start and finish.
				transition("tool without an attempt", List.of(state(RunState.RUNNING), tool("key", RunEvent.Effect.READ_ONLY, null)),
						noActive),
				transition("tool outside RUNNING", then(ACTIVE, state(RunState.INTERRUPTED),
						tool("key", RunEvent.Effect.READ_ONLY, null)), "Tool requires running state"),
				payload("unsafe tool call key", then(ACTIVE, tool("key/1", RunEvent.Effect.READ_ONLY, null)), "Unsafe execution ID"),
				payload("unsafe tool name", then(ACTIVE, new RunEvent.ToolStarted(AT, "attempt", "key", "bad name", DIGEST,
						RunEvent.Effect.READ_ONLY, null)), "Unsafe execution ID"),
				payload("tool args digest is not sha256", then(ACTIVE, new RunEvent.ToolStarted(AT, "attempt", "key", "fixture_tool",
						"short", RunEvent.Effect.READ_ONLY, null)), "Invalid SHA-256 digest"),
				transition("tool without an effect class", then(ACTIVE, tool("key", null, null)), "Missing effect classification"),
				payload("tool snapshot digest is not sha256", then(ACTIVE, tool("key", RunEvent.Effect.READ_ONLY, "short")),
						"Invalid SHA-256 digest"),
				transition("tool call key started twice", then(ACTIVE, tool("key", RunEvent.Effect.READ_ONLY, null),
						tool("key", RunEvent.Effect.READ_ONLY, null)), "Duplicate tool start"),
				transition("tool call key reused after it finished", then(ACTIVE, tool("key", RunEvent.Effect.READ_ONLY, null),
						finished("key", DIGEST), tool("key", RunEvent.Effect.READ_ONLY, null)), "Duplicate tool start"),
				transition("finish without a start", then(ACTIVE, finished("key", DIGEST)), "Tool finish lacks start"),
				transition("finish twice", then(ACTIVE, tool("key", RunEvent.Effect.READ_ONLY, null), finished("key", DIGEST),
						finished("key", DIGEST)), "Tool finish lacks start"),
				transition("finish without an attempt", List.of(state(RunState.RUNNING), finished("key", DIGEST)), noActive),
				payload("finish digest is not sha256", then(ACTIVE, tool("key", RunEvent.Effect.READ_ONLY, null),
						finished("key", "short")), "Invalid SHA-256 digest"),

				// Operator resolutions.
				transition("resolution while the run is RUNNING", then(ACTIVE, tool("key", RunEvent.Effect.MUTATING, null),
						resolution(RunEvent.Resolution.SKIP)), "Resolution requires interrupted ownership"),
				transition("resolution of a key that never started", then(INTERRUPTED, resolution(RunEvent.Resolution.SKIP)),
						"Resolution requires UNKNOWN key"),
				transition("resolution without a decision", then(UNKNOWN_MUTATION, resolution(null)),
						"Resolution requires UNKNOWN key"),
				transition("the same unknown key resolved twice", then(UNKNOWN_MUTATION, resolution(RunEvent.Resolution.SKIP),
						resolution(RunEvent.Resolution.SKIP)), "Resolution requires UNKNOWN key"),
				transition("read retry of a mutating tool", then(UNKNOWN_MUTATION, resolution(RunEvent.Resolution.READ_RETRY)),
						"READ_RETRY requires authoritative read-only snapshot"),
				transition("read retry of a read without a snapshot", then(List.of(state(RunState.RUNNING), attempt("attempt", BINDING),
						tool("key", RunEvent.Effect.READ_ONLY, null), round("attempt", "round"), ended("attempt"),
						state(RunState.INTERRUPTED)), resolution(RunEvent.Resolution.READ_RETRY)),
						"READ_RETRY requires authoritative read-only snapshot"),
				payload("resolution with an unsafe operator", then(UNKNOWN_MUTATION, new RunEvent.ToolResolution(AT, "attempt",
						"key", RunEvent.Resolution.SKIP, "../operator", "evidence")), "Unsafe execution ID"),
				payload("resolution with an unsafe evidence id", then(UNKNOWN_MUTATION, new RunEvent.ToolResolution(AT, "attempt",
						"key", RunEvent.Resolution.SKIP, "operator", "bad evidence")), "Unsafe execution ID"),

				// Drift acknowledgements.
				transition("drift acknowledgement while RUNNING", then(ACTIVE, new RunEvent.DriftAccepted(AT, DIGEST,
						OTHER_DIGEST, "operator", "evidence")), "Drift acknowledgement requires interrupted ownership"),
				transition("drift acknowledgement without drift", then(INTERRUPTED, new RunEvent.DriftAccepted(AT, DIGEST,
						DIGEST, "operator", "evidence")), "No drift to acknowledge"),
				payload("drift acknowledgement with a short digest", then(INTERRUPTED, new RunEvent.DriftAccepted(AT, "short",
						OTHER_DIGEST, "operator", "evidence")), "Invalid SHA-256 digest"),
				payload("drift acknowledgement with an unsafe operator", then(INTERRUPTED, new RunEvent.DriftAccepted(AT, DIGEST,
						OTHER_DIGEST, "../operator", "evidence")), "Unsafe execution ID"),

				// Approvals.
				transition("approval request without an attempt", List.of(state(RunState.RUNNING), approval("request")), noActive),
				transition("approval request outside RUNNING", then(ACTIVE, state(RunState.INTERRUPTED), approval("request")),
						"Approval request requires running state"),
				payload("approval request with an unsafe id", then(ACTIVE, approval("request/1")), "Unsafe execution ID"),
				payload("approval request with a short args digest", then(ACTIVE, new RunEvent.ApprovalRequested(AT, "attempt",
						"request", "key", "short", null)), "Invalid SHA-256 digest"),
				payload("approval request with a short diff digest", then(ACTIVE, new RunEvent.ApprovalRequested(AT, "attempt",
						"request", "key", DIGEST, "short")), "Invalid SHA-256 digest"),
				transition("approval request id used twice", then(ACTIVE, approval("request"), approval("request")),
						"Duplicate approval request"),
				transition("approval request id reused after it was answered", then(ACTIVE, approval("request"),
						approved("request", RunEvent.Decision.APPROVED), approval("request")), "Duplicate approval request"),
				transition("approval answer for an unknown request", then(ACTIVE, approved("request", RunEvent.Decision.APPROVED)),
						"Unknown approval response"),
				transition("approval answer without a decision", then(ACTIVE, approval("request"), approved("request", null)),
						"Unknown approval response"),
				transition("approval answered twice", then(ACTIVE, approval("request"), approved("request", RunEvent.Decision.REJECTED),
						approved("request", RunEvent.Decision.APPROVED)), "Unknown approval response"),
				transition("approval answer without an attempt", List.of(state(RunState.RUNNING),
						approved("request", RunEvent.Decision.APPROVED)), noActive));
	}

	private static List<RunEvent> chargeTwiceAcrossAttempts() {
		return then(List.of(state(RunState.RUNNING), attempt("attempt", BINDING),
				usage("attempt", RunEvent.UsageKind.COMPACTION, CHILD_CHARGE), ended("attempt"), state(RunState.INTERRUPTED),
				state(RunState.RUNNING), attempt("later", OTHER_BINDING)),
				usage("later", RunEvent.UsageKind.COMPACTION, CHILD_CHARGE));
	}

	@ParameterizedTest @MethodSource("rejected")
	void illegalHistoryIsRefusedWithTheRuleSpecificCodeAndMessage(Rejected rejected) {
		assertThatThrownBy(() -> RunProjector.project(rejected.spec(), rejected.events(), true))
				.isInstanceOfSatisfying(RunStoreException.class, ex -> {
					assertThat(ex.code()).isEqualTo(rejected.code());
					assertThat(ex.getMessage()).isEqualTo(rejected.message());
					assertThat(ex.getCause() == null ? null : ex.getCause().getMessage()).isEqualTo(rejected.causeMessage());
				});
	}

	@Test void emptyJournalProjectsTheFreshRunWithoutAnyEvent() throws Exception {
		var record = RunProjector.project(SPEC, List.of(), true);
		assertThat(record.state()).isEqualTo(RunState.CREATED);
		assertThat(record.reason()).isEqualTo(RunEvent.Reason.NONE);
		assertThat(record.sequence()).isZero();
		assertThat(record.attempts()).isEmpty();
		assertThat(record.usage()).isEqualTo(RunUsage.empty());
		assertThat(record.uncertainTools()).isEmpty();
	}

	@ParameterizedTest @EnumSource(value = RunState.class, names = {"FAILED", "CANCELLED", "INTERRUPTED"})
	void aRunThatDoesNotCompleteKeepsItsUnfinishedToolAsUncertain(RunState end) throws Exception {
		var events = then(List.of(state(RunState.RUNNING), attempt("attempt", BINDING),
				tool("key", RunEvent.Effect.MUTATING, null), round("attempt", "round"), ended("attempt")), state(end));
		var record = RunProjector.project(SPEC, events, true);
		assertThat(record.state()).isEqualTo(end);
		assertThat(record.uncertainTools()).extracting(RunEvent.ToolStarted::toolCallKey).containsExactly("key");
	}

	@Test void finishedToolIsNotUncertainAndAllowsCompletion() throws Exception {
		var events = then(ACTIVE, tool("key", RunEvent.Effect.MUTATING, null), finished("key", DIGEST),
				round("attempt", "round"), ended("attempt"), state(RunState.COMPLETED));
		var record = RunProjector.project(SPEC, events, true);
		assertThat(record.state()).isEqualTo(RunState.COMPLETED);
		assertThat(record.uncertainTools()).isEmpty();
		assertThat(record.sequence()).isEqualTo(events.size());
	}

	@ParameterizedTest @EnumSource(value = RunEvent.Resolution.class, names = {"SKIP", "MUTATING_RETRY"})
	void anyResolutionRemovesTheUnknownToolAndLetsTheRunResume(RunEvent.Resolution decision) throws Exception {
		var resolved = then(UNKNOWN_MUTATION, resolution(decision));
		assertThat(RunProjector.project(SPEC, resolved, true).uncertainTools()).isEmpty();
		var resumed = then(resolved, state(RunState.RUNNING), attempt("later", OTHER_BINDING), round("later", "round"),
				ended("later"), state(RunState.COMPLETED));
		assertThat(RunProjector.project(SPEC, resumed, true).state()).isEqualTo(RunState.COMPLETED);
	}

	@Test void readRetryIsAcceptedOnlyForARecordedReadOnlySnapshot() throws Exception {
		var record = RunProjector.project(SPEC, then(UNKNOWN_READ, resolution(RunEvent.Resolution.READ_RETRY)), true);
		assertThat(record.uncertainTools()).isEmpty();
	}

	@Test void driftAcknowledgementIsAcceptedWhileInterruptedWithDifferingDigests() throws Exception {
		var record = RunProjector.project(SPEC, then(INTERRUPTED,
				new RunEvent.DriftAccepted(AT, DIGEST, OTHER_DIGEST, "operator", "evidence")), true);
		assertThat(record.state()).isEqualTo(RunState.INTERRUPTED);
	}

	@Test void deferredApprovalStaysPendingUntilALaterDecision() throws Exception {
		var waiting = then(ACTIVE, approval("request"), state(RunState.WAITING_APPROVAL),
				approved("request", RunEvent.Decision.DEFERRED));
		assertThat(RunProjector.project(SPEC, waiting, true).state()).isEqualTo(RunState.WAITING_APPROVAL);
		var answered = then(waiting, approved("request", RunEvent.Decision.APPROVED), state(RunState.RUNNING));
		assertThat(RunProjector.project(SPEC, answered, true).state()).isEqualTo(RunState.RUNNING);
	}

	@Test void endingTheAttemptDropsApprovalsThatWereStillPending() throws Exception {
		var events = then(ACTIVE, approval("request"), state(RunState.WAITING_APPROVAL), state(RunState.INTERRUPTED),
				round("attempt", "round"), ended("attempt"), state(RunState.RUNNING));
		assertThat(RunProjector.project(SPEC, events, true).state()).isEqualTo(RunState.RUNNING);
	}

	@Test void ordinaryUsageCallIdMayRepeatInALaterAttempt() throws Exception {
		var later = then(INTERRUPTED, state(RunState.RUNNING), attempt("later", OTHER_BINDING), round("later", "round"),
				ended("later"), state(RunState.COMPLETED));
		var record = RunProjector.project(SPEC, later, true);
		assertThat(record.usage().knownTokens()).isEqualTo(6);
		assertThat(record.attempts()).extracting(Attempt::attemptId).containsExactly("attempt", "later");
	}

	@Test void usageAccumulatesAcrossKindsAndKeepsUnknownSticky() throws Exception {
		var events = then(ACTIVE, round("attempt", "round"),
				new RunEvent.UsageRecorded(AT, "attempt", RunEvent.UsageKind.TOOLS, "tool", new RunUsage(0, true, 2, 0, 1)),
				usage("attempt", RunEvent.UsageKind.COMPACTION, CHILD_CHARGE));
		assertThat(RunProjector.project(SPEC, events, true).usage()).isEqualTo(new RunUsage(6, true, 2, 0, 1));
	}

	@Test void missingWriterTurnsARunningRunIntoProcessLost() throws Exception {
		var running = RunProjector.project(SPEC, ACTIVE, false);
		assertThat(running.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(running.reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
		var waiting = RunProjector.project(SPEC, then(ACTIVE, approval("request"), state(RunState.WAITING_APPROVAL)), false);
		assertThat(waiting.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(waiting.reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
	}

	@Test void presentWriterKeepsTheRunningStateAndReason() throws Exception {
		var running = RunProjector.project(SPEC, ACTIVE, true);
		assertThat(running.state()).isEqualTo(RunState.RUNNING);
		assertThat(running.reason()).isEqualTo(RunEvent.Reason.NONE);
	}

	@Test void missingWriterLeavesEveryOtherStateAndItsReasonAlone() throws Exception {
		var created = RunProjector.project(SPEC, List.of(), false);
		assertThat(created.state()).isEqualTo(RunState.CREATED);
		var cancelled = then(List.of(state(RunState.RUNNING), attempt("attempt", BINDING), round("attempt", "round"),
				ended("attempt")), new RunEvent.StateChanged(AT, RunState.INTERRUPTED, RunEvent.Reason.USER_CANCELLED));
		var interrupted = RunProjector.project(SPEC, cancelled, false);
		assertThat(interrupted.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(interrupted.reason()).isEqualTo(RunEvent.Reason.USER_CANCELLED);
		var completed = RunProjector.project(SPEC, then(List.of(state(RunState.RUNNING), attempt("attempt", BINDING),
				round("attempt", "round"), ended("attempt")), state(RunState.COMPLETED)), false);
		assertThat(completed.state()).isEqualTo(RunState.COMPLETED);
		assertThat(completed.reason()).isEqualTo(RunEvent.Reason.NONE);
	}
}
