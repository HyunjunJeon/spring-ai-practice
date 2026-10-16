package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import com.example.miniagent.core.change.FileChangeStore;
import com.example.miniagent.core.change.Fingerprinter;
import com.example.miniagent.core.change.StagedWorkspace;
import com.example.miniagent.core.change.VerificationEvidence;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.change.VerificationRunner.Code;
import com.example.miniagent.core.change.VerificationTarget;
import com.example.miniagent.core.goal.GoalRecord;
import com.example.miniagent.core.goal.GoalRecord.GateResult;
import com.example.miniagent.core.goal.GoalRequest;
import com.example.miniagent.core.goal.GoalService;
import com.example.miniagent.core.goal.GoalStore;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunRecord;
import com.example.miniagent.core.run.RunSpec;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import static com.example.miniagent.core.lesson.LessonRecord.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * goal-lesson:G2. The production proof reader (public constructor) against a real change store and runner:
 * a proposal-backed goal is trusted only while its source, saved digest, evidence and log are still the ones cited.
 */
class LessonProofReaderTest {
	private static final String HASH = LessonFixture.HASH;
	@TempDir Path root;

	/** A published proposal with a managed run, plus helpers that record gate evidence for it. */
	private final class Scenario implements AutoCloseable {
		final Path workspace;
		final Path home;
		final Actor actor;
		final FileChangeStore changes;
		final GoalStore goals;
		final LessonService service;
		final RunStore runs = mock(RunStore.class);
		final ModelSelection selection = new ModelSelection(new ModelRef("codex", "gpt-6.1-sol"));
		final StagedWorkspace stage;
		final VerificationTarget target;

		Scenario() throws Exception {
			Path real = root.toRealPath();
			workspace = Files.createDirectory(real.resolve("workspace")).toRealPath();
			home = Files.createDirectory(real.resolve("home")).toRealPath();
			Files.writeString(workspace.resolve("source"), "baseline");
			actor = Actor.capture("operator-session", workspace);
			changes = new FileChangeStore(home, workspace);
			goals = new GoalStore(home);
			var verifier = new VerificationRunner(changes, (context, argv) -> {
				throw new AssertionError("No verification process authorized");
			}, Clock.systemUTC());
			ChatModel neverCalled = new ChatModel() {
				@Override public ChatResponse call(Prompt prompt) { throw new AssertionError("No model invocation authorized"); }
				@Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
			};
			var binding = new ModelBinding(UUID.randomUUID(), selection.ref(), selection.optionsFingerprint(),
					"test-binding-only", neverCalled, ChatClient.builder(neverCalled).build());
			stage = StagedWorkspace.open(changes, new TurnContext("managed-session", UUID.randomUUID().toString(),
					workspace, binding, "run", "attempt"), Clock.systemUTC());
			Files.writeString(stage.resolve("source", true, List.of()), "edit");
			stage.record();
			target = verifier.capture(stage);
			assertThat(target.readOnly()).isFalse();
			var goalService = new GoalService(goals, () -> { throw new AssertionError("Agent resolved"); },
					() -> { throw new AssertionError("Executor resolved"); }, () -> runs, () -> changes, () -> verifier);
			service = new LessonService(new LessonStore(home), () -> goalService, () -> runs, () -> verifier, () -> changes);
		}
		@Override public void close() throws IOException { stage.close(); }

		private byte[] log(String argv) { return ("output of " + argv).getBytes(StandardCharsets.UTF_8); }
		/** Gate evidence for the proposal; {@code stored} decides whether the change store holds it. */
		GateResult gate(int index, Code code, Integer exit, boolean stored) throws Exception {
			String argv = "gate-" + index;
			byte[] log = log(argv);
			var evidence = new VerificationEvidence(UUID.randomUUID(), target.id(), target.owner(), List.of(argv),
					workspace.toString(), workspace.toString(), target.revision(), HASH, Fingerprinter.sha256(log), exit,
					exit == null, Instant.EPOCH, Instant.EPOCH);
			if (stored) try (var lease = changes.acquire()) {
				lease.recordEvidence(target.id(), target.owner(), target.digest(), evidence, log);
			}
			return new GateResult(index, target.revision(), HASH, code, evidence, Base64.getEncoder().encodeToString(log));
		}
		GateResult passed(int index) throws Exception { return gate(index, Code.SUCCEEDED, 0, true); }
		GateResult rejected(int index) throws Exception { return gate(index, Code.NON_ZERO_EXIT, 3, true); }
		/** Persists a goal over the scenario's proposal and publishes the run record the lesson reader expects. */
		GoalRecord goal(GoalRecord.State state, GoalRecord.Reason reason, boolean candidate, int gates,
				VerificationTarget cited, GateResult... results) throws Exception {
			var operatorGates = new java.util.ArrayList<GoalRequest.Gate>();
			for (int i = 0; i < gates; i++) operatorGates.add(new GoalRequest.Gate(List.of("gate-" + i), 1000, 1024));
			var request = new GoalRequest("repair parser", operatorGates, 2, 10000, 1000L, 0);
			var spec = new GoalRecord.Spec(request, selection, actor.session(), workspace.toString(), target.owner().runId());
			var goal = new GoalRecord(1, UUID.randomUUID().toString(), spec, state, reason, 1, GoalRecord.Phase.READY,
					cited, List.of(results), candidate, "");
			goals.create(goal);
			var runSpec = new RunSpec(1, spec.runId(), target.owner().sessionId(), actor.session(), null, null,
					selection.ref(), "responses", workspace.toString(), request.budget(), selection.optionsFingerprint(),
					HASH, Instant.EPOCH);
			when(runs.read(spec.runId())).thenReturn(new RunRecord(runSpec, 1,
					candidate ? RunState.COMPLETED : RunState.INTERRUPTED, RunEvent.Reason.NONE, List.of(), null, List.of()));
			return goal;
		}
		/** A verified goal is reconciled by GoalService.inspect unless its claim is busy; hold the claim to read the saved snapshot. */
		LessonRecord propose(GoalRecord goal) throws Exception {
			if (!goal.candidate()) return service.propose(goal.id(), new Content("Parser lesson", "parser pitfall", List.of()), actor);
			try (var claim = goals.claim(goal.id())) {
				return service.propose(goal.id(), new Content("Parser lesson", "parser lesson", List.of()), actor);
			}
		}
	}

	@Test void aProposalBackedVerifiedGoalIsTrustedWhileItsSourceAndEvidenceAreUnchanged() throws Exception {
		try (var s = new Scenario()) {
			var gate = s.passed(0);
			var goal = s.goal(GoalRecord.State.SUCCEEDED, GoalRecord.Reason.VERIFIED_PROPOSAL, true, 1, s.target, gate);
			var lesson = s.propose(goal);
			assertThat(lesson.state()).isEqualTo(State.CANDIDATE);
			assertThat(lesson.proof().readOnly()).isFalse();
			assertThat(lesson.proof().targetId()).isEqualTo(s.target.id().toString());
			assertThat(lesson.proof().digest()).isEqualTo(s.target.digest());
			assertThat(lesson.proof().revision()).isEqualTo(s.target.revision());
			assertThat(lesson.proof().evidenceIds()).containsExactly(gate.evidence().evidenceId().toString());
			assertThat(lesson.content().content()).isEqualTo("parser lesson");
		}
	}

	@Test void aProposalBackedRejectedGoalIsTrustedAndCitesOnlyTheRejectingGate() throws Exception {
		try (var s = new Scenario()) {
			var passed = s.passed(0);
			var rejected = s.rejected(1);
			var goal = s.goal(GoalRecord.State.BLOCKED, GoalRecord.Reason.GATE_FAILED, false, 2, s.target, passed, rejected);
			var lesson = s.propose(goal);
			assertThat(lesson.proof().evidenceIds()).containsExactly(rejected.evidence().evidenceId().toString());
			assertThat(lesson.content().content()).isEqualTo(LessonService.FAILURE_MARKER
					+ " gate 1 `gate-1` 종료 코드 3 (목표 중단 사유: GATE_FAILED)\n\nparser pitfall");
		}
	}

	@Test void evidenceTheChangeStoreDoesNotHoldIsRefusedForVerifiedAndRejectedGoals() throws Exception {
		try (var s = new Scenario()) {
			var verified = s.goal(GoalRecord.State.SUCCEEDED, GoalRecord.Reason.VERIFIED_PROPOSAL, true, 1, s.target,
					s.gate(0, Code.SUCCEEDED, 0, false));
			assertThatThrownBy(() -> s.propose(verified)).isInstanceOf(IOException.class)
					.hasMessage("Lesson persisted evidence mismatch");
			var rejected = s.goal(GoalRecord.State.BLOCKED, GoalRecord.Reason.GATE_FAILED, false, 1, s.target,
					s.gate(0, Code.NON_ZERO_EXIT, 3, false));
			assertThatThrownBy(() -> s.propose(rejected)).isInstanceOf(IOException.class)
					.hasMessage("Lesson persisted evidence mismatch");
			assertThat(s.service.list(s.actor)).isEmpty();
		}
	}

	@Test void everyGateOfAVerifiedRoundIsCheckedAgainstTheChangeStore() throws Exception {
		try (var s = new Scenario()) {
			var held = s.passed(1);
			var foreignFirst = s.goal(GoalRecord.State.SUCCEEDED, GoalRecord.Reason.VERIFIED_PROPOSAL, true, 2, s.target,
					s.gate(0, Code.SUCCEEDED, 0, false), held);
			assertThatThrownBy(() -> s.propose(foreignFirst)).isInstanceOf(IOException.class)
					.hasMessage("Lesson persisted evidence mismatch");
			var both = s.goal(GoalRecord.State.SUCCEEDED, GoalRecord.Reason.VERIFIED_PROPOSAL, true, 2, s.target,
					s.passed(0), held);
			assertThat(s.propose(both).proof().evidenceIds()).hasSize(2).contains(held.evidence().evidenceId().toString());
			assertThat(s.service.list(s.actor)).hasSize(1);
		}
	}

	@Test void aProposalWhoseSavedDigestOrLiveSourceChangedIsRefusedAsStale() throws Exception {
		try (var s = new Scenario()) {
			var gate = s.rejected(0);
			var swapped = new VerificationTarget(s.target.id(), s.target.owner(), "b".repeat(64), s.target.revision(), false);
			var otherDigest = s.goal(GoalRecord.State.BLOCKED, GoalRecord.Reason.GATE_FAILED, false, 1, swapped, gate);
			// VerificationRunner.current compares the saved digest first, so it surfaces as a stale source.
			assertThatThrownBy(() -> s.propose(otherDigest)).isInstanceOf(IOException.class)
					.hasMessage("Lesson source is stale");
			var goal = s.goal(GoalRecord.State.BLOCKED, GoalRecord.Reason.GATE_FAILED, false, 1, s.target, gate);
			assertThat(s.propose(goal).proof().digest()).isEqualTo(s.target.digest());
			Files.writeString(s.workspace.resolve("source"), "external edit");
			assertThatThrownBy(() -> s.propose(goal)).isInstanceOf(IOException.class).hasMessage("Lesson source is stale");
			assertThat(s.service.list(s.actor)).hasSize(1);
		}
	}

	@Test void anAlteredPersistedLogMakesTheProposalUntrustedAndCreatesNoLesson() throws Exception {
		try (var s = new Scenario()) {
			var gate = s.rejected(0);
			var goal = s.goal(GoalRecord.State.BLOCKED, GoalRecord.Reason.GATE_FAILED, false, 1, s.target, gate);
			assertThat(s.propose(goal).proof().evidenceIds()).containsExactly(gate.evidence().evidenceId().toString());
			Path blob = s.changes.directory().resolve("evidence").resolve(gate.evidence().logSha256());
			Files.setPosixFilePermissions(blob, PosixFilePermissions.fromString("rw-------"));
			Files.write(blob, "altered after the gate ran".getBytes(StandardCharsets.UTF_8));
			// The change store reports the corrupt log as recovery required, which current() answers with false.
			assertThatThrownBy(() -> s.propose(goal)).isInstanceOf(IOException.class).hasMessage("Lesson source is stale");
			assertThat(s.service.list(s.actor)).hasSize(1);
		}
	}
}
