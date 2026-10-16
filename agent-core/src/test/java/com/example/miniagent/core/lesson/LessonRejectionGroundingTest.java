package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.example.miniagent.core.change.Fingerprinter;
import com.example.miniagent.core.change.VerificationEvidence;
import com.example.miniagent.core.change.VerificationRunner.Code;
import com.example.miniagent.core.goal.GoalRecord;
import com.example.miniagent.core.goal.GoalRecord.GateResult;
import com.example.miniagent.core.goal.GoalRequest;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunRecord;
import com.example.miniagent.core.run.RunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** goal-lesson:G5. Only a gate that ran and refused the target grounds a failure lesson. */
class LessonRejectionGroundingTest {
	private static final String NOT_GROUNDS = "Lesson requires this session's verified goal or a goal its gate rejected";
	private static final String EMPTY_LOG = Fingerprinter.sha256(new byte[0]);
	@TempDir Path root;

	private boolean grounds(LessonFixture f) throws Exception {
		try { f.service.propose("verified-goal", f.content("parser pitfall"), f.actor); return true; }
		catch (IOException ex) {
			assertThat(ex).hasMessage(NOT_GROUNDS);
			return false;
		}
	}
	private static VerificationEvidence evidence(GoalRecord goal, UUID id, List<String> argv, Integer exit, boolean cancelled) {
		var target = goal.target();
		return new VerificationEvidence(id, target.id(), target.owner(), argv, goal.spec().workspace(),
				goal.spec().workspace(), LessonFixture.HASH, LessonFixture.HASH, EMPTY_LOG, exit, cancelled,
				Instant.EPOCH, Instant.EPOCH);
	}
	private static GateResult gate(GoalRecord goal, int index, Code code, UUID id, String argv, Integer exit) {
		boolean cancelled = exit == null;
		return new GateResult(index, LessonFixture.HASH, LessonFixture.HASH, code,
				evidence(goal, id, List.of(argv), exit, cancelled), "");
	}
	private static GoalRecord goalWith(GoalRecord g, GoalRecord.Spec spec, GoalRecord.State state, GoalRecord.Reason reason,
			List<GateResult> results, boolean candidate) {
		return new GoalRecord(1, g.id(), spec, state, reason, 2, GoalRecord.Phase.READY, g.target(), results, candidate, "");
	}
	/** The same goal, run and target as the fixture, but with two operator gates. */
	private static GoalRecord.Spec twoGateSpec(GoalRecord g) {
		var request = new GoalRequest("repair parser", List.of(new GoalRequest.Gate(List.of("first-gate"), 1000, 1024),
				new GoalRequest.Gate(List.of("second-gate"), 1000, 1024)), 2, 10000, 1000L, 0);
		return new GoalRecord.Spec(request, g.spec().selection(), g.spec().parentConversationId(),
				g.spec().workspace(), g.spec().runId());
	}
	private static void interruptedRun(LessonFixture f) throws Exception {
		when(f.runs.read(f.completed.spec().runId())).thenReturn(new RunRecord(f.completed.spec(), f.completed.sequence(),
				RunState.INTERRUPTED, RunEvent.Reason.NONE, f.completed.attempts(), f.completed.usage(), f.completed.uncertainTools()));
	}

	@Test void onlyStoppedGoalsWithAGateStopAndARejectionCodeGroundALesson() throws Exception {
		var f = new LessonFixture(root);
		var grounded = new ArrayList<String>();
		for (var state : GoalRecord.State.values())
			for (var reason : GoalRecord.Reason.values())
				for (var code : Code.values()) {
					f.rejectGoal(state, reason, code, true, false);
					if (grounds(f)) grounded.add(state + "/" + reason + "/" + code);
				}
		var expected = new ArrayList<String>();
		for (var state : List.of("FAILED", "BLOCKED"))
			for (var reason : List.of("GATE_FAILED", "NO_PROGRESS", "STEP_LIMIT"))
				for (var code : List.of("NON_ZERO_EXIT", "TIMED_OUT", "OUTPUT_LIMIT")) expected.add(state + "/" + reason + "/" + code);
		assertThat(grounded).containsExactlyInAnyOrderElementsOf(expected);
		assertThat(f.service.list(f.actor)).hasSize(expected.size());
	}

	@Test void theMarkerNamesTheGateTheOutcomeAndTheStopReason() throws Exception {
		var f = new LessonFixture(root);
		var outcomes = java.util.Map.of(Code.NON_ZERO_EXIT, "종료 코드 1", Code.TIMED_OUT, "시간 초과", Code.OUTPUT_LIMIT, "출력 한도 초과");
		for (var reason : GoalRecord.Reason.values()) {
			if (reason != GoalRecord.Reason.GATE_FAILED && reason != GoalRecord.Reason.NO_PROGRESS
					&& reason != GoalRecord.Reason.STEP_LIMIT) continue;
			for (var outcome : outcomes.entrySet()) {
				f.rejectGoal(GoalRecord.State.BLOCKED, reason, outcome.getKey(), true, false);
				assertThat(f.service.propose("verified-goal", f.content("parser pitfall"), f.actor).content().content())
						.isEqualTo(LessonService.FAILURE_MARKER + " gate 0 `operator-gate` " + outcome.getValue()
								+ " (목표 중단 사유: " + reason + ")\n\nparser pitfall");
			}
		}
	}

	@Test void aCandidateGoalIsNotARejectionEvenWhenItsLastGateFailed() throws Exception {
		var f = new LessonFixture(root);
		f.rejectGoal();
		var g = f.goal.get();
		f.goal.set(goalWith(g, g.spec(), GoalRecord.State.BLOCKED, GoalRecord.Reason.GATE_FAILED, g.results(), true));
		assertThat(grounds(f)).isFalse();
		f.goal.set(goalWith(g, g.spec(), GoalRecord.State.BLOCKED, GoalRecord.Reason.GATE_FAILED, g.results(), false));
		assertThat(grounds(f)).isTrue();
	}

	@Test void aGoalWithoutAnyGateResultIsNotARejection() throws Exception {
		var f = new LessonFixture(root);
		f.rejectGoal();
		var g = f.goal.get();
		f.goal.set(goalWith(g, g.spec(), GoalRecord.State.BLOCKED, GoalRecord.Reason.GATE_FAILED, List.of(), false));
		assertThat(grounds(f)).isFalse();
	}

	@Test void rejectionEvidenceThatContradictsItsCodeIsNotAGroundForALesson() throws Exception {
		var f = new LessonFixture(root);
		f.rejectGoal();
		var g = f.goal.get();
		var id = g.results().getFirst().evidence().evidenceId();
		var bad = new ArrayList<GateResult>();
		// A refusal that exited zero, a code claiming a cancellation the evidence lacks, and the reverse.
		bad.add(gate(g, 0, Code.NON_ZERO_EXIT, id, "operator-gate", 0));
		bad.add(gate(g, 0, Code.NON_ZERO_EXIT, id, "operator-gate", null));
		bad.add(gate(g, 0, Code.TIMED_OUT, id, "operator-gate", 1));
		bad.add(gate(g, 0, Code.OUTPUT_LIMIT, id, "operator-gate", 1));
		bad.add(new GateResult(0, LessonFixture.HASH, LessonFixture.HASH, Code.NON_ZERO_EXIT, null, ""));
		for (var result : bad) {
			f.goal.set(goalWith(g, g.spec(), GoalRecord.State.BLOCKED, GoalRecord.Reason.GATE_FAILED, List.of(result), false));
			assertThat(grounds(f)).as("%s with %s", result.code(), result.evidence()).isFalse();
		}
		assertThat(f.service.list(f.actor)).isEmpty();
		// The consistent shapes ground a lesson, so the refusals above came from the contradiction.
		f.goal.set(goalWith(g, g.spec(), GoalRecord.State.BLOCKED, GoalRecord.Reason.GATE_FAILED,
				List.of(gate(g, 0, Code.NON_ZERO_EXIT, id, "operator-gate", 1)), false));
		assertThat(grounds(f)).isTrue();
		f.goal.set(goalWith(g, g.spec(), GoalRecord.State.BLOCKED, GoalRecord.Reason.GATE_FAILED,
				List.of(gate(g, 0, Code.TIMED_OUT, id, "operator-gate", null)), false));
		assertThat(grounds(f)).isTrue();
	}

	@Test void aRejectedGoalWithTwoGatesCitesAndNamesOnlyTheGateThatStoppedIt() throws Exception {
		var f = new LessonFixture(root);
		var g = f.goal.get();
		var spec = twoGateSpec(g);
		var first = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		// Round one: gate 0 passed, gate 1 failed. Round two repeats that; its gate 1 stopped the goal.
		var results = List.of(gate(g, 0, Code.SUCCEEDED, first.get(0), "first-gate", 0),
				gate(g, 1, Code.NON_ZERO_EXIT, first.get(1), "second-gate", 2),
				gate(g, 0, Code.SUCCEEDED, first.get(2), "first-gate", 0),
				gate(g, 1, Code.NON_ZERO_EXIT, first.get(3), "second-gate", 3));
		f.goal.set(goalWith(g, spec, GoalRecord.State.BLOCKED, GoalRecord.Reason.GATE_FAILED, results, false));
		interruptedRun(f);
		var candidate = f.service.propose("verified-goal", f.content("parser pitfall"), f.actor);
		assertThat(candidate.proof().evidenceIds()).containsExactly(first.get(3).toString());
		assertThat(candidate.content().content()).isEqualTo(LessonService.FAILURE_MARKER
				+ " gate 1 `second-gate` 종료 코드 3 (목표 중단 사유: GATE_FAILED)\n\nparser pitfall");
	}

	@Test void aVerifiedGoalWithTwoGatesCitesEveryGateOfItsLatestPassingRound() throws Exception {
		var f = new LessonFixture(root);
		var g = f.goal.get();
		var spec = twoGateSpec(g);
		var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		var results = List.of(gate(g, 0, Code.SUCCEEDED, ids.get(0), "first-gate", 0),
				gate(g, 1, Code.NON_ZERO_EXIT, ids.get(1), "second-gate", 2),
				gate(g, 0, Code.SUCCEEDED, ids.get(2), "first-gate", 0),
				gate(g, 1, Code.SUCCEEDED, ids.get(3), "second-gate", 0));
		f.goal.set(goalWith(g, spec, GoalRecord.State.SUCCEEDED, GoalRecord.Reason.VERIFIED_READONLY, results, true));
		var candidate = f.service.propose("verified-goal", f.content("parser verified"), f.actor);
		assertThat(candidate.proof().evidenceIds()).containsExactly(ids.get(2).toString(), ids.get(3).toString());
		assertThat(candidate.content()).isEqualTo(f.content("parser verified"));
	}
}
