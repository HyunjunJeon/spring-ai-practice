package com.example.miniagent.plugin.trace;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.example.miniagent.core.TraceEvent;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 루프 <b>안쪽</b> 어드바이저. 모델을 부를 때마다(라운드마다) 실행된다.
 *
 * <p>ToolCallingAdvisor 는 매 라운드 {@code chain.copy(this).nextCall(...)} 로 자기 뒤의 어드바이저만
 * 다시 실행한다. order 가 HIGHEST+300 보다 큰 이 어드바이저는 그 "뒤"에 있으므로 라운드마다 불린다.
 * 응답에 tool call 이 있으면 ToolCallingAdvisor 가 도구를 실행하고 다음 라운드를 시작한다.
 */
public class RoundTraceAdvisor implements CallAdvisor {

	/** provider 마다 이름이 다른 "정상 종료" 사유. 이 밖의 값(LENGTH 등)만 따로 알린다. */
	private static final Set<String> NORMAL_FINISH = Set.of("", "null", "stop", "end_turn", "tool_calls", "tool_use");

	private final ApplicationEventPublisher events;

	public RoundTraceAdvisor(ApplicationEventPublisher events) {
		this.events = events;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		if (!(request.context().get(TurnTraceAdvisor.TURN_RECORD) instanceof TurnRecord turn)) {
			return chain.nextCall(request);
		}
		int round = turn.rounds.incrementAndGet();
		int messages = request.prompt().getInstructions().size();
		int added = Math.max(0, messages - turn.lastMessages);
		turn.lastMessages = messages;
		this.events.publishEvent(new TraceEvent.RoundStarted(round, messages, added, toolCount(request)));

		long start = System.nanoTime();
		ChatClientResponse response = chain.nextCall(request);
		TraceEvent.RoundFinished finished = finished(round, response.chatResponse(), System.nanoTime() - start);
		turn.roundFinished(finished);
		this.events.publishEvent(finished);
		return response;
	}

	@Override
	public String getName() {
		return "RoundTraceAdvisor";
	}

	@Override
	public int getOrder() {
		return ToolCallingAdvisor.DEFAULT_ORDER + 100;
	}

	private static int toolCount(ChatClientRequest request) {
		return (request.prompt().getOptions() instanceof ToolCallingChatOptions options
				&& options.getToolCallbacks() != null) ? options.getToolCallbacks().size() : 0;
	}

	private static TraceEvent.RoundFinished finished(int round, ChatResponse chatResponse, long nanos) {
		if (chatResponse == null || chatResponse.getResult() == null) {
			return new TraceEvent.RoundFinished(round, List.of(), 0, "empty", nanos, TraceEvent.Tokens.ZERO, null, null);
		}
		Generation result = chatResponse.getResult();
		AssistantMessage output = result.getOutput();
		List<String> toolCalls = output.getToolCalls()
			.stream()
			.map(call -> call.name() + " " + call.arguments().replaceAll("\\s+", " "))
			.toList();
		int answerChars = (output.getText() != null) ? output.getText().length() : 0;
		String finish = String.valueOf(result.getMetadata().getFinishReason());
		String abnormal = NORMAL_FINISH.contains(finish.toLowerCase(Locale.ROOT)) ? null : finish;
		// 이 라운드(모델 호출 1번)의 사용량과 실제로 응답한 모델. provider 가 채워 준 메타데이터에서 읽는다.
		ChatResponseMetadata metadata = chatResponse.getMetadata();
		String model = (metadata.getModel() != null && !metadata.getModel().isBlank()) ? metadata.getModel() : null;
		Object effort = metadata.get(TraceEvent.REASONING_EFFORT_METADATA);
		return new TraceEvent.RoundFinished(round, toolCalls, answerChars, abnormal, nanos,
				TraceEvent.Tokens.from(metadata.getUsage()), model, (effort != null) ? effort.toString() : null);
	}

}
