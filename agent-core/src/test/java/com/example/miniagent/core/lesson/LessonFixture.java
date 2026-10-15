package com.example.miniagent.core.lesson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.example.miniagent.core.change.ChangeOwner;
import com.example.miniagent.core.change.Fingerprinter;
import com.example.miniagent.core.change.VerificationEvidence;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.change.VerificationTarget;
import com.example.miniagent.core.goal.GoalRecord;
import com.example.miniagent.core.goal.GoalRequest;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.run.*;
import static org.mockito.Mockito.*;
import static com.example.miniagent.core.lesson.LessonRecord.*;

/** Pure local contract data. No model, verification process or auth call is executed. */
final class LessonFixture {
	static final String HASH = "a".repeat(64);
	final Actor actor;
	final LessonStore store;
	final LessonService service;
	final AtomicReference<GoalRecord> goal;
	final RunStore runs = mock(RunStore.class);
	final RunRecord completed;
	LessonFixture(Path root) throws Exception {
		root = root.toRealPath();
		var workspace = Files.createDirectory(root.resolve("workspace")).toRealPath();
		actor = Actor.capture("operator-session", workspace);
		store = new LessonStore(root.resolve("home"));
		var selection = new ModelSelection(new ModelRef("codex", "gpt-6.1-sol"), java.util.Map.of("effort", "high"));
		var runId = RunId.random();
		var request = new GoalRequest("repair parser", List.of(
				new GoalRequest.Gate(List.of("operator-gate"), 1000, 1024)), 2, 10000, 1000L, 0);
		var owner = new ChangeOwner("managed-session", runId, "attempt", "turn");
		var target = new VerificationTarget(UUID.randomUUID(), owner, HASH, HASH, true);
		var evidence = new VerificationEvidence(UUID.randomUUID(), target.id(), owner, List.of("operator-gate"),
				workspace.toString(), workspace.toString(), HASH, HASH, Fingerprinter.sha256(new byte[0]),
				0, false, Instant.EPOCH, Instant.EPOCH);
		var result = new GoalRecord.GateResult(0, HASH, HASH, VerificationRunner.Code.SUCCEEDED, evidence, "");
		goal = new AtomicReference<>(new GoalRecord(1, "verified-goal",
				new GoalRecord.Spec(request, selection, actor.session(), actor.workspace(), runId),
				GoalRecord.State.SUCCEEDED, GoalRecord.Reason.VERIFIED_READONLY, 1, GoalRecord.Phase.READY,
				target, List.of(result), true, ""));
		var spec = new RunSpec(1, runId, owner.sessionId(), actor.session(), null, null,
				selection.ref(), "responses", workspace.toString(), request.budget(), selection.optionsFingerprint(),
				HASH, Instant.EPOCH);
		completed = new RunRecord(spec, 1, RunState.COMPLETED, RunEvent.Reason.NONE, List.of(), null, List.of());
		when(runs.read(runId)).thenReturn(completed);
		service = new LessonService(store, (LessonService.ProofReader) id -> {
			if (!id.equals(goal.get().id())) throw new java.io.IOException("Unknown goal");
			return goal.get();
		}, () -> runs);
	}
	Content content(String value) { return new Content("Parser lesson", value, List.of("parser")); }
	LessonRecord accepted(String text) throws Exception {
		var candidate = service.propose(goal.get().id(), content(text), actor);
		return service.accept(candidate.id(), 1, Scope.SESSION, actor);
	}
	void failGoal() {
		var g = goal.get();
		goal.set(new GoalRecord(1, g.id(), g.spec(), GoalRecord.State.FAILED, GoalRecord.Reason.EVIDENCE_STALE,
				g.steps(), g.phase(), g.target(), g.results(), false, ""));
	}
}
