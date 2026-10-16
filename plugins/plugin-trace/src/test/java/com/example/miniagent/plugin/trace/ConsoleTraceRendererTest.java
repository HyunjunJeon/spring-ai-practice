package com.example.miniagent.plugin.trace;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.change.ChangeOwner;
import com.example.miniagent.core.change.ChangeStatus;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.delegation.ChildStatus;
import com.example.miniagent.core.delegation.DelegationResult;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** REPL 의 트레이스 출력: 수준별 형식, off 와 TUI 사이드바(hasTraceView) 때의 침묵. */
class ConsoleTraceRendererTest {

	private static final ModelRef REF = new ModelRef("fake", "m");

	private static final UUID CHANGE_SET = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

	private static final UUID EVIDENCE = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

	private final RecordingConsole console = new RecordingConsole();

	@Test
	void summaryPrintsOneLinePerTurnAndNothingForTheInnerEvents() {
		ConsoleTraceRenderer renderer = renderer("summary");

		renderer.on(new TraceEvent.TurnStarted(3, "hi"));
		renderer.on(new TraceEvent.RoundStarted(1, 5, 2, 4));
		renderer.on(roundFinished(1, List.of("read_file {}"), 0, null, 250));
		renderer.on(new TraceEvent.ToolExecuted("read_file", 120, 3_000_000L));
		renderer.on(new TraceEvent.ToolFailed("bash", "denied", false, 2_000_000L));
		assertThat(this.console.lines).isEmpty();

		renderer.on(turnFinished(2, 1, "read_file", 250, "model-b", "high"));
		assertThat(this.console.lines).containsExactly("  ⏱ 250ms · 모델 2회 · 도구 read_file · model-b (high)");
	}

	@Test
	void summaryLineWithoutToolsOrModelStaysShort() {
		renderer("summary").on(turnFinished(1, 0, "", 40, null, null));

		assertThat(this.console.lines).containsExactly("  ⏱ 40ms · 모델 1회 · 도구 없음");
	}

	@Test
	void summaryLineShowsTheModelWithoutEffortWhenTheProviderReportsNone() {
		renderer("summary").on(turnFinished(1, 0, "", 40, "model-b", null));

		assertThat(this.console.lines).containsExactly("  ⏱ 40ms · 모델 1회 · 도구 없음 · model-b");
	}

	@Test
	void summaryLineUsesTheModelCapturedForTheTurnOverTheOneInTheEvent(@TempDir Path cwd)
			throws Exception {
		ChatModel chatModel = prompt -> {
			throw new AssertionError("renderer must not call the model");
		};
		ModelBinding binding = new ModelBinding(UUID.randomUUID(), new ModelRef("fake", "captured"), "fp", "fake",
				chatModel, ChatClient.create(chatModel));
		ConsoleTraceRenderer renderer = renderer("summary");

		try (TurnContext.Scope scope = new TurnContext("s1", "t1", cwd, binding).open()) {
			renderer.on(turnFinished(1, 0, "", 40, "model-b", "high"));
		}

		assertThat(this.console.lines).containsExactly("  ⏱ 40ms · 모델 1회 · 도구 없음 · fake/captured (high)");
	}

	@Test
	void detailPrintsTheTurnAsATree() {
		ConsoleTraceRenderer renderer = renderer("detail");

		renderer.on(new TraceEvent.TurnStarted(3, "hi"));
		renderer.on(new TraceEvent.RoundStarted(1, 5, 2, 4));
		renderer.on(roundFinished(1, List.of("read_file {\"path\":\"a\"}"), 0, null, 250));
		renderer.on(new TraceEvent.ToolExecuted("read_file", 120, 3_000_000L));
		renderer.on(new TraceEvent.RoundStarted(2, 7, 2, 4));
		renderer.on(roundFinished(2, List.of(), 42, null, 90));
		renderer.on(turnFinished(2, 1, "read_file", 340, "model-b", "high"));

		assertThat(this.console.lines).containsExactly(
				"┌ 턴 시작 — 입력 메시지 3개",
				"│ [1] → 모델       메시지 5개 (메모리 +2) · 도구 4개",
				"│ [1] ← 도구 요청    read_file {\"path\":\"a\"}   250ms",
				"│      ⚙ read_file 실행  120자 · 3ms",
				"│ [2] → 모델       메시지 7개 (도구 호출·결과 +2) · 도구 4개",
				"│ [2] ← 최종 답변    42자   90ms",
				"└ 턴 완료 — 모델 2회 · 도구 1회 · 340ms");
	}

	@Test
	void detailRoundStartOmitsTheDeltaWhenNothingWasAdded() {
		renderer("detail").on(new TraceEvent.RoundStarted(1, 3, 0, 0));

		assertThat(this.console.lines).containsExactly("│ [1] → 모델       메시지 3개 · 도구 0개");
	}

	@Test
	void detailWarnsWhenTheProviderEndedTheRoundAbnormally() {
		renderer("detail").on(roundFinished(2, List.of(), 42, "LENGTH", 90));

		assertThat(this.console.lines).containsExactly("│ [2] ← 최종 답변    42자   90ms  ⚠ 종료 사유: LENGTH");
	}

	@Test
	void detailCutsLongToolRequestsAndFailureTextsWithAnEllipsis() {
		ConsoleTraceRenderer renderer = renderer("detail");
		String call = "read_file " + "x".repeat(70);
		String error = "e".repeat(100);

		renderer.on(roundFinished(1, List.of(call), 0, null, 10));
		renderer.on(new TraceEvent.ToolFailed("bash", error, false, 2_000_000L));

		assertThat(this.console.lines).containsExactly(
				"│ [1] ← 도구 요청    " + call.substring(0, 60) + "…   10ms",
				"│      ⚙ bash 실패: " + "e".repeat(80) + "… · 2ms");
	}

	@Test
	void detailDistinguishesACancelledToolFromAFailedOne() {
		renderer("detail").on(new TraceEvent.ToolFailed("bash", "Interrupted", true, 2_000_000L));

		assertThat(this.console.lines).containsExactly("│      ⚙ bash 취소됨 · 2ms");
	}

	@Test
	void detailShowsTheModelBindingOfTheTurnAndSummaryDoesNot() {
		UUID binding = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
		TraceEvent.ModelBound plain = new TraceEvent.ModelBound("s1", "t1", REF, binding, "fp-1", null, null);
		TraceEvent.ModelBound managed = new TraceEvent.ModelBound("s1", "t1", REF, binding, "fp-1", "r1", "a1");

		renderer("summary").on(plain);
		assertThat(this.console.lines).isEmpty();

		ConsoleTraceRenderer detail = renderer("detail");
		detail.on(plain);
		detail.on(managed);
		assertThat(this.console.lines).containsExactly(
				"model: fake/m options=fp-1 session=s1 turn=t1 binding=" + binding,
				"model: fake/m options=fp-1 session=s1 turn=t1 binding=" + binding + " run=r1 attempt=a1");
	}

	@Test
	void runChangeAndDelegationStatesPrintAtSummaryAndDetailAlike() {
		for (String level : List.of("summary", "detail")) {
			this.console.lines.clear();
			ConsoleTraceRenderer renderer = renderer(level);

			renderer.on(new TraceEvent.RunStateChanged("run-1", "conv-1", "att-1", RunState.RUNNING, REF));
			renderer.on(new TraceEvent.ChangeStateChanged(CHANGE_SET,
					new ChangeOwner("conv-1", new RunId("run-1"), "att-1", "turn-1"), "digest-1", ChangeStatus.APPLIED,
					null, null));
			renderer.on(new TraceEvent.ChangeStateChanged(CHANGE_SET,
					new ChangeOwner("conv-1", new RunId("run-1"), "att-1", "turn-1"), "digest-1", ChangeStatus.APPLIED,
					VerificationRunner.Code.SUCCEEDED, EVIDENCE));

			assertThat(this.console.lines).as(level).containsExactly(
					"run=run-1 conversation=conv-1 attempt=att-1 state=RUNNING model=fake/m",
					"change=" + CHANGE_SET + " run=run-1 attempt=att-1 state=APPLIED digest=digest-1",
					"change=" + CHANGE_SET + " run=run-1 attempt=att-1 state=APPLIED digest=digest-1"
							+ " verification=SUCCEEDED evidence=" + EVIDENCE);
		}
	}

	@Test
	void delegationLineShowsTheChildStatusOrTheRunStateThatNeedsTheOperator() {
		ConsoleTraceRenderer renderer = renderer("summary");

		renderer.on(new TraceEvent.DelegationStateChanged("conv-1", "plan-1", null, null, null, null, null,
				DelegationResult.Status.COMPLETE, null));
		renderer.on(new TraceEvent.DelegationStateChanged("conv-1", "plan-1", "child-1", "run-9", REF,
				ChildStatus.FAILED, RunState.RUNNING, null, "CHILD_FAILED"));
		renderer.on(new TraceEvent.DelegationStateChanged("conv-1", "plan-1", "child-1", "run-9", null,
				ChildStatus.RUNNING, RunState.WAITING_APPROVAL, null, null));

		assertThat(this.console.lines).containsExactly(
				"delegate=plan-1 result=COMPLETE",
				"delegate=plan-1 child=child-1 run=run-9 state=FAILED model=fake/m failure=CHILD_FAILED",
				"delegate=plan-1 child=child-1 run=run-9 state=WAITING_APPROVAL");
	}

	@Test
	void offPrintsNothingForAnyEvent() {
		allEvents().forEach(renderer("off")::on);

		assertThat(this.console.lines).isEmpty();
	}

	@Test
	void aScreenWithItsOwnTraceViewGetsNoTextTraceAtAnyLevel() {
		this.console.traceView = true;

		for (String level : List.of("summary", "detail")) {
			allEvents().forEach(renderer(level)::on);
		}

		assertThat(this.console.lines).isEmpty();
	}

	@Test
	void switchingTheLevelAtRuntimeChangesWhatTheRendererPrints() {
		TraceSettings settings = new TraceSettings("off");
		ConsoleTraceRenderer renderer = new ConsoleTraceRenderer(this.console, settings);
		TraceEvent.TurnFinished finished = turnFinished(1, 0, "", 40, null, null);

		renderer.on(finished);
		assertThat(this.console.lines).isEmpty();

		settings.setLevel("summary");
		renderer.on(finished);
		assertThat(this.console.lines).containsExactly("  ⏱ 40ms · 모델 1회 · 도구 없음");
	}

	@Test
	void eventsPublishedInTheAssembledPluginReachTheRenderer() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TracePluginAutoConfiguration.class))
			.withBean(AgentConsole.class, () -> this.console)
			.withPropertyValues("agent.plugins.trace.level=summary")
			.run(context -> {
				context.publishEvent(turnFinished(1, 0, "", 40, null, null));

				assertThat(this.console.lines).containsExactly("  ⏱ 40ms · 모델 1회 · 도구 없음");
			});
	}

	private ConsoleTraceRenderer renderer(String level) {
		return new ConsoleTraceRenderer(this.console, new TraceSettings(level));
	}

	private static List<TraceEvent> allEvents() {
		return List.of(new TraceEvent.TurnStarted(3, "hi"), new TraceEvent.RoundStarted(1, 5, 2, 4),
				roundFinished(1, List.of("read_file {}"), 0, "LENGTH", 250),
				new TraceEvent.ToolExecuted("read_file", 120, 3_000_000L),
				new TraceEvent.ToolFailed("bash", "denied", false, 2_000_000L),
				turnFinished(2, 1, "read_file", 250, "model-b", "high"),
				new TraceEvent.RunStateChanged("run-1", "conv-1", "att-1", RunState.RUNNING, REF),
				new TraceEvent.DelegationStateChanged("conv-1", "plan-1", null, null, null, null, null, null, null));
	}

	private static TraceEvent.RoundFinished roundFinished(int round, List<String> toolCalls, int answerChars,
			String abnormalFinish, long millis) {
		return new TraceEvent.RoundFinished(round, toolCalls, answerChars, abnormalFinish, millis * 1_000_000L,
				TraceEvent.Tokens.ZERO, null, null);
	}

	private static TraceEvent.TurnFinished turnFinished(int rounds, int toolCount, String toolSummary, long millis,
			String model, String effort) {
		return new TraceEvent.TurnFinished(rounds, toolCount, toolSummary, millis * 1_000_000L, TraceEvent.Tokens.ZERO,
				model, effort, true);
	}

	/** 화면에 찍힌 줄을 ANSI 꾸밈 없이 모은다. */
	private static final class RecordingConsole extends AgentConsole {

		private final List<String> lines = new ArrayList<>();

		private boolean traceView;

		RecordingConsole() {
			super(new BufferedReader(new StringReader("")), System.out, false);
		}

		@Override
		public void info(String text) {
			this.lines.add(text);
		}

		@Override
		public boolean hasTraceView() {
			return this.traceView;
		}

	}

}
