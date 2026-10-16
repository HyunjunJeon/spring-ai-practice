package com.example.miniagent.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.change.Fingerprinter;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.goal.GoalRecord;
import com.example.miniagent.core.goal.GoalRequest;
import com.example.miniagent.core.goal.GoalService;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.ProviderAuth;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GoalService verdicts from real gate processes, with the offline fake model in the goal steps. */
@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.plugins.bash.enabled=false", "agent.plugins.memory.enabled=false",
		"agent.plugins.trace.level=off" })
@Import(GoalLoopFakeModelTest.GoalFixture.class)
class GoalLoopFakeModelTest {
	static final String GOAL = "Report readiness without tools; the host runs the gate.";
	static final long WALL_MILLIS = 180_000;
	static final long GATE_TIMEOUT_MILLIS = 30_000;
	static final int MAX_OUTPUT_BYTES = 4096;
	static final int TOOL_CALL_CAP = 20;
	@TempDir static Path root;
	static Path work;

	@DynamicPropertySource static void paths(DynamicPropertyRegistry properties) throws IOException {
		work = Files.createDirectory(root.resolve("workspace")).toRealPath();
		Path home = Files.createDirectory(root.resolve("state")).toRealPath();
		properties.add("agent.cwd", () -> work.toString());
		properties.add("agent.home", () -> home.toString());
	}

	@Autowired GoalService goals;
	@Autowired RunStore runs;
	@Autowired Agent agent;
	@Autowired AgentSession session;
	@Autowired SwitchablePolicy policy;

	@BeforeEach void freshSession() { this.session.startNew(); }

	@Test void passingGateOnAnUnchangedWorkspaceSucceedsReadOnlyWithACompletedRun() throws Exception {
		var goal = execute(gate("/usr/bin/true"), 2, null);

		assertThat(goal.state()).isEqualTo(GoalRecord.State.SUCCEEDED);
		assertThat(goal.reason()).isEqualTo(GoalRecord.Reason.VERIFIED_READONLY);
		assertThat(goal.candidate()).isTrue();
		assertThat(goal.steps()).isEqualTo(1);
		assertThat(goal.target().readOnly()).isTrue();
		assertThat(goal.results()).singleElement().satisfies(result -> {
			assertThat(result.code()).isEqualTo(VerificationRunner.Code.SUCCEEDED);
			assertThat(result.evidence().exitCode()).isZero();
			assertThat(result.evidence().argv()).containsExactly("/usr/bin/true");
		});
		assertThat(this.goals.inspect(goal.id())).isEqualTo(goal);
		assertThat(this.runs.read(goal.spec().runId()).state()).isEqualTo(RunState.COMPLETED);
	}

	@Test void unchangedFailingGateRunsOnceAndStopsAtTheStepLimit() throws Exception {
		var goal = execute(gate("/usr/bin/false"), 2, null);

		assertThat(goal.state()).isEqualTo(GoalRecord.State.FAILED);
		assertThat(goal.reason()).isEqualTo(GoalRecord.Reason.STEP_LIMIT);
		assertThat(goal.steps()).isEqualTo(2);
		assertThat(goal.candidate()).isFalse();
		// Step 2 sees the same revision and configuration, so the gate is not launched again.
		assertThat(goal.results()).singleElement().satisfies(result -> {
			assertThat(result.code()).isEqualTo(VerificationRunner.Code.NON_ZERO_EXIT);
			assertThat(result.evidence().exitCode()).isEqualTo(1);
		});
		assertThat(goal.feedback()).startsWith("Gate 0 NON_ZERO_EXIT");
		assertThat(this.goals.inspect(goal.id())).isEqualTo(goal);
		assertThat(this.runs.read(goal.spec().runId()).state()).isEqualTo(RunState.INTERRUPTED);
	}

	@Test void secondUnchangedRetryBlocksTheGoalForNoProgress() throws Exception {
		var goal = execute(gate("/usr/bin/false"), 3, null);

		assertThat(goal.state()).isEqualTo(GoalRecord.State.BLOCKED);
		assertThat(goal.reason()).isEqualTo(GoalRecord.Reason.NO_PROGRESS);
		assertThat(goal.steps()).isEqualTo(3);
		assertThat(goal.results()).singleElement()
				.extracting(GoalRecord.GateResult::code).isEqualTo(VerificationRunner.Code.NON_ZERO_EXIT);
		assertThat(goal.feedback()).isEqualTo("Unchanged failed revision/configuration");
		assertThat(this.runs.read(goal.spec().runId()).state()).isEqualTo(RunState.INTERRUPTED);
	}

	@Test void gateThatOutlivesItsTimeoutBlocksTheGoalAsGateFailed() throws Exception {
		var goal = execute(new GoalRequest.Gate(List.of("/bin/sleep", "10"), 300, MAX_OUTPUT_BYTES), 3, null);

		assertThat(goal.state()).isEqualTo(GoalRecord.State.BLOCKED);
		assertThat(goal.reason()).isEqualTo(GoalRecord.Reason.GATE_FAILED);
		assertThat(goal.steps()).isEqualTo(1);
		assertThat(goal.results()).singleElement().satisfies(result -> {
			assertThat(result.code()).isEqualTo(VerificationRunner.Code.TIMED_OUT);
			assertThat(result.evidence().cancelled()).isTrue();
		});
		assertThat(goal.feedback()).startsWith("Gate 0 TIMED_OUT");
		assertThat(this.runs.read(goal.spec().runId()).state()).isEqualTo(RunState.INTERRUPTED);
	}

	@Test void gateThatExceedsItsOutputBoundBlocksTheGoalAsGateFailed() throws Exception {
		var goal = execute(new GoalRequest.Gate(List.of("/usr/bin/yes"), GATE_TIMEOUT_MILLIS, 64), 3, null);

		assertThat(goal.state()).isEqualTo(GoalRecord.State.BLOCKED);
		assertThat(goal.reason()).isEqualTo(GoalRecord.Reason.GATE_FAILED);
		assertThat(goal.steps()).isEqualTo(1);
		assertThat(goal.results()).singleElement().satisfies(result -> {
			assertThat(result.code()).isEqualTo(VerificationRunner.Code.OUTPUT_LIMIT);
			assertThat(result.evidence().cancelled()).isTrue();
		});
		assertThat(goal.feedback()).startsWith("Gate 0 OUTPUT_LIMIT");
		assertThat(this.runs.read(goal.spec().runId()).state()).isEqualTo(RunState.INTERRUPTED);
	}

	@Test void gateRefusedBeforeLaunchBlocksTheGoalAsEvidenceStale() throws Exception {
		this.policy.delegate.set((context, argv) -> {
			throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, null);
		});
		GoalRecord goal;
		try { goal = execute(gate("/usr/bin/true"), 2, null); }
		finally { this.policy.delegate.set(SwitchablePolicy.PASS_THROUGH); }

		assertThat(goal.state()).isEqualTo(GoalRecord.State.BLOCKED);
		assertThat(goal.reason()).isEqualTo(GoalRecord.Reason.EVIDENCE_STALE);
		assertThat(goal.results()).isEmpty();
		assertThat(goal.feedback()).isEqualTo("Gate refused: UNSUPPORTED_SANDBOX");
		assertThat(this.runs.read(goal.spec().runId()).state()).isEqualTo(RunState.INTERRUPTED);
	}

	@Test void sourceChangedAfterSuccessRevokesTheVerdictWithoutRerunning() throws Exception {
		var goal = execute(gate("/usr/bin/true"), 2, null);
		assertThat(goal.state()).isEqualTo(GoalRecord.State.SUCCEEDED);
		Files.writeString(work.resolve("operator-edit-" + goal.id() + ".txt"), "changed after verification\n");

		var revoked = this.goals.inspect(goal.id());

		assertThat(revoked.state()).isEqualTo(GoalRecord.State.FAILED);
		assertThat(revoked.reason()).isEqualTo(GoalRecord.Reason.EVIDENCE_STALE);
		assertThat(revoked.candidate()).isFalse();
		assertThat(revoked.results()).isEqualTo(goal.results());
		assertThat(this.goals.inspect(goal.id())).isEqualTo(revoked);
		assertThat(this.runs.read(goal.spec().runId()).state()).isEqualTo(RunState.COMPLETED);
	}

	@Test void tokenCapSpentByTheFirstStepFailsTheGoalBeforeAnyGateRuns() throws Exception {
		var goal = execute(gate("/usr/bin/true"), 2, 1L);

		assertThat(goal.state()).isEqualTo(GoalRecord.State.FAILED);
		assertThat(goal.reason()).isEqualTo(GoalRecord.Reason.BUDGET_LIMIT);
		assertThat(goal.steps()).isEqualTo(1);
		assertThat(goal.results()).isEmpty();
		var run = this.runs.read(goal.spec().runId());
		assertThat(run.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(run.reason()).isEqualTo(RunEvent.Reason.BUDGET_EXHAUSTED);
	}

	@Test void interruptedProviderCallBlocksTheGoalAndKeepsTheWorkerInterrupt() throws Exception {
		var pending = submit(gate("/usr/bin/true"), 2, null, GoalFixture.model("interrupted"));
		record Outcome(GoalRecord goal, boolean interrupted) {}
		var worker = new FutureTask<>(() -> new Outcome(this.goals.execute(pending.id()),
				Thread.currentThread().isInterrupted()));
		Thread.ofPlatform().name("b17-goal-worker").start(worker);

		var outcome = worker.get(60, TimeUnit.SECONDS);

		assertThat(outcome.interrupted()).isTrue();
		var goal = outcome.goal();
		assertThat(goal.state()).isEqualTo(GoalRecord.State.BLOCKED);
		assertThat(goal.reason()).isEqualTo(GoalRecord.Reason.INTERRUPTED);
		assertThat(goal.steps()).isEqualTo(1);
		assertThat(goal.phase()).isEqualTo(GoalRecord.Phase.READY);
		assertThat(goal.target().readOnly()).isTrue();
		assertThat(goal.results()).isEmpty();
		assertThat(this.goals.inspect(goal.id())).isEqualTo(goal);
		assertThat(this.runs.read(goal.spec().runId()).state()).isEqualTo(RunState.INTERRUPTED);
	}

	@Test void providerFailureFailsTheGoalWithItsRunAndReachesTheCaller() throws Exception {
		var pending = submit(gate("/usr/bin/true"), 2, null, GoalFixture.model("failing"));

		assertThatThrownBy(() -> this.goals.execute(pending.id()))
				.isInstanceOf(IllegalStateException.class).hasMessage("synthetic provider outage");

		var goal = this.goals.inspect(pending.id());
		assertThat(goal.state()).isEqualTo(GoalRecord.State.FAILED);
		assertThat(goal.reason()).isEqualTo(GoalRecord.Reason.RUN_FAILED);
		assertThat(goal.steps()).isEqualTo(1);
		assertThat(goal.target().readOnly()).isTrue();
		assertThat(goal.results()).isEmpty();
		assertThat(this.runs.read(pending.spec().runId()).state()).isEqualTo(RunState.FAILED);
	}

	private GoalRecord execute(GoalRequest.Gate gate, int maxSteps, Long tokenCap) throws IOException {
		return this.goals.execute(submit(gate, maxSteps, tokenCap, this.agent.selectedModel()).id());
	}

	private GoalRecord submit(GoalRequest.Gate gate, int maxSteps, Long tokenCap, ModelSelection model)
			throws IOException {
		return this.goals.submit(request(gate, maxSteps, WALL_MILLIS, tokenCap), model, this.session.id(), work);
	}

	static GoalRequest request(GoalRequest.Gate gate, int maxSteps, long wallMillis, Long tokenCap) {
		return new GoalRequest(GOAL, List.of(gate), maxSteps, wallMillis, tokenCap, TOOL_CALL_CAP);
	}

	static GoalRequest.Gate gate(String... argv) {
		return new GoalRequest.Gate(List.of(argv), GATE_TIMEOUT_MILLIS, MAX_OUTPUT_BYTES);
	}

	/** Launch policy and models shared by the B17 goal tests. */
	@TestConfiguration static class GoalFixture {
		static final String PROVIDER = "fixture";

		@Bean SwitchablePolicy goalFixtureLaunchPolicy() { return new SwitchablePolicy(); }

		@Bean PromptLog goalFixturePrompts() { return new PromptLog(); }

		@Bean ModelProvider goalFixtureModels(PromptLog prompts) {
			return new ModelProvider() {
				@Override public String providerId() { return PROVIDER; }
				@Override public ProviderAuth auth() { return () -> true; }
				@Override public List<ModelEntry> models() {
					return Stream.of("failing", "interrupted", "witness")
							.map(name -> new ModelEntry(new ModelRef(PROVIDER, name), PROVIDER, null)).toList();
				}
				@Override public ChatModel create(ModelRef ref, Map<String, String> options) {
					return switch (ref.model()) {
						case "failing" -> new FixtureModel(prompt -> {
							throw new IllegalStateException("synthetic provider outage");
						});
						// What a provider client reports when its blocking call is interrupted.
						case "interrupted" -> new FixtureModel(prompt -> {
							throw new IllegalStateException("synthetic provider call interrupted", new InterruptedException());
						});
						case "witness" -> new FixtureModel(prompt -> {
							prompts.prompts.add(prompt);
							return new ChatResponse(List.of(new Generation(new AssistantMessage("noted"))));
						});
						default -> throw new IllegalArgumentException("Unknown fixture model " + ref);
					};
				}
			};
		}

		static ModelSelection model(String name) { return new ModelSelection(new ModelRef(PROVIDER, name)); }
	}

	/** The only LaunchPolicy bean in these contexts; a test may swap in a refusal and must restore it. */
	static final class SwitchablePolicy implements VerificationRunner.LaunchPolicy {
		// Fixture-only policy: these tests do not claim OS sandbox enforcement.
		static final VerificationRunner.LaunchPolicy PASS_THROUGH = (context, argv) -> new VerificationRunner.Launch(
				argv, Map.of(), Fingerprinter.sha256("b17-goal-fixture".getBytes(StandardCharsets.UTF_8)));
		final AtomicReference<VerificationRunner.LaunchPolicy> delegate = new AtomicReference<>(PASS_THROUGH);

		@Override public VerificationRunner.Launch prepare(VerificationRunner.Context context, List<String> argv)
				throws IOException {
			return this.delegate.get().prepare(context, argv);
		}
	}

	static final class PromptLog {
		final List<Prompt> prompts = new CopyOnWriteArrayList<>();
	}

	static final class FixtureModel implements ChatModel {
		private final Function<Prompt, ChatResponse> answer;

		FixtureModel(Function<Prompt, ChatResponse> answer) { this.answer = answer; }

		@Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }

		@Override public ChatResponse call(Prompt prompt) { return this.answer.apply(prompt); }
	}
}
