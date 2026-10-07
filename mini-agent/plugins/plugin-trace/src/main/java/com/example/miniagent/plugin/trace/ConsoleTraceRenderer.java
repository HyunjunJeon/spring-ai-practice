package com.example.miniagent.plugin.trace;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.model.ModelDisplay;
import com.example.miniagent.core.model.TurnContext;

import org.springframework.context.event.EventListener;

/**
 * 줄 단위 REPL 용 렌더러. {@link TraceEvent} 를 받아 수준(summary/detail)에 맞게 텍스트로 찍는다.
 * TUI 는 같은 이벤트를 받아 사이드바 그래프로 그린다.
 */
public class ConsoleTraceRenderer {

	private final AgentConsole console;

	private final TraceSettings settings;

	public ConsoleTraceRenderer(AgentConsole console, TraceSettings settings) {
		this.console = console;
		this.settings = settings;
	}

	@EventListener
	public void on(TraceEvent event) {
		if (this.settings.isOff() || this.console.hasTraceView()) {
			return;
		}
		boolean detail = this.settings.isDetail();
		switch (event) {
			case TraceEvent.DelegationStateChanged e -> this.console.info("delegate=" + e.planId()
					+ (e.childId() == null ? "" : " child=" + e.childId() + " run=" + e.childRunId()
							+ " state=" + (e.runState() == com.example.miniagent.core.run.RunState.WAITING_APPROVAL
									|| e.runState() == com.example.miniagent.core.run.RunState.INTERRUPTED
									? e.runState() : e.childStatus())
							+ (e.childModel() == null ? "" : " model=" + ModelDisplay.ref(e.childModel()))
							+ (e.failureCode() == null ? "" : " failure=" + e.failureCode()))
					+ (e.resultStatus() == null ? "" : " result=" + e.resultStatus()));
			case TraceEvent.ChangeStateChanged e -> this.console.info("change=" + e.changeSetId()
					+ " run=" + e.owner().runId().value() + " attempt=" + e.owner().attemptId()
					+ " state=" + e.status() + " digest=" + e.digest()
					+ (e.verification() == null ? "" : " verification=" + e.verification()
							+ " evidence=" + e.evidenceId()));
			case TraceEvent.RunStateChanged e -> this.console.info("run=" + e.runId()
					+ " conversation=" + e.conversationId() + " attempt=" + e.attemptId()
					+ " state=" + e.state() + " model=" + ModelDisplay.ref(e.ref()));
			case TraceEvent.ModelBound e when detail -> this.console.info(
					"model: " + ModelDisplay.ref(e.ref()) + " options=" + e.optionsFingerprint()
					+ " session=" + e.sessionId() + " turn=" + e.turnId() + " binding=" + e.bindingId()
					+ (e.runId() == null ? "" : " run=" + e.runId() + " attempt=" + e.attemptId()));
			case TraceEvent.TurnStarted e when detail ->
				this.console.info("┌ 턴 시작 — 입력 메시지 %d개".formatted(e.inputMessages()));
			case TraceEvent.RoundStarted e when detail -> {
				// 1라운드에서 늘어난 메시지는 메모리가 붙인 것, 그 뒤 라운드는 도구 호출과 결과다.
				String delta = (e.addedMessages() == 0) ? ""
						: (e.round() == 1) ? " (메모리 +%d)".formatted(e.addedMessages())
								: " (도구 호출·결과 +%d)".formatted(e.addedMessages());
				this.console.info("│ [%d] → 모델       메시지 %d개%s · 도구 %d개".formatted(e.round(), e.messages(), delta, e.toolCount()));
			}
			case TraceEvent.RoundFinished e when detail -> {
				String what = e.toolCalls().isEmpty() ? "최종 답변    %d자".formatted(e.answerChars())
						: "도구 요청    " + String.join(", ", e.toolCalls().stream().map(c -> abbreviate(c, 60)).toList());
				String warn = (e.abnormalFinish() != null) ? "  ⚠ 종료 사유: " + e.abnormalFinish() : "";
				this.console.info("│ [%d] ← %s   %s%s".formatted(e.round(), what, TraceEvent.duration(e.durationNanos()), warn));
			}
			case TraceEvent.ToolExecuted e when detail -> this.console.info(
					"│      ⚙ %s 실행  %d자 · %s".formatted(e.name(), e.resultChars(), TraceEvent.duration(e.durationNanos())));
			case TraceEvent.ToolFailed e when detail -> this.console.info("│      ⚙ %s %s · %s".formatted(e.name(),
					e.cancelled() ? "취소됨" : "실패: " + abbreviate(e.error(), 80), TraceEvent.duration(e.durationNanos())));
			case TraceEvent.TurnFinished e -> {
				if (detail) {
					this.console.info("└ 턴 완료 — 모델 %d회 · 도구 %d회 · %s".formatted(e.rounds(), e.toolCount(),
							TraceEvent.duration(e.durationNanos())));
				}
				else {
					String tools = (e.toolCount() == 0) ? "도구 없음" : "도구 " + e.toolSummary();
					String captured = TurnContext.current().map(context -> ModelDisplay.ref(context.binding().ref()))
						.orElse(e.model());
					String model = (captured == null) ? ""
							: " · " + captured + ((e.reasoningEffort() != null) ? " (" + e.reasoningEffort() + ")" : "");
					this.console.info("  ⏱ %s · 모델 %d회 · %s%s".formatted(TraceEvent.duration(e.durationNanos()), e.rounds(), tools, model));
				}
			}
			default -> {
			}
		}
	}

	private static String abbreviate(String text, int max) {
		return (text.length() <= max) ? text : text.substring(0, max) + "…";
	}

}
