package com.example.miniagent.plugin.trace;

import com.example.miniagent.core.TraceEvent;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;

/**
 * 루프 <b>바깥</b> 어드바이저. 사용자 입력 한 건당 정확히 한 번 실행된다.
 *
 * <p>order 가 ToolCallingAdvisor(HIGHEST+300)보다 작으므로 체인에서 먼저 실행되고, 도구 루프 전체를
 * 감싼다. 그래서 여기서 재는 시간은 "모델 여러 번 + 도구 여러 번"을 합친 턴 전체 시간이다.
 * 이 어드바이저는 출력하지 않고 {@link TraceEvent} 만 발행한다. 그리는 일은 렌더러(콘솔/TUI)가 한다.
 */
public class TurnTraceAdvisor implements CallAdvisor, StreamAdvisor {

	/** 안쪽 어드바이저와 공유하는 턴 기록의 Advisor 컨텍스트 키. */
	static final String TURN_RECORD = "trace.turn";

	private final ApplicationEventPublisher events;

	private final TraceSettings settings;

	public TurnTraceAdvisor(ApplicationEventPublisher events, TraceSettings settings) {
		this.events = events;
		this.settings = settings;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		// 컨텍스트 맵은 라운드마다 복사되지만 값(TurnRecord)은 같은 객체라 안쪽에서 채운 내용이 보인다.
		TurnRecord turn = new TurnRecord(request.prompt().getInstructions().size());
		this.settings.setCurrentTurn(turn);
		String userText = request.prompt().getUserMessage().getText();
		this.events.publishEvent(new TraceEvent.TurnStarted(turn.initialMessages, (userText != null) ? userText : ""));
		try {
			return chain.nextCall(request.mutate().context(TURN_RECORD, turn).build());
		}
		finally {
			this.settings.setCurrentTurn(null);
			// 토큰은 안쪽 어드바이저가 라운드마다 더해 둔 값이다. 예외로 끝나도 그때까지의 합이 남는다.
			this.events.publishEvent(turn.finish());
		}
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		TurnRecord turn = new TurnRecord(request.prompt().getInstructions().size());
		this.settings.setCurrentTurn(turn);
		String userText = request.prompt().getUserMessage().getText();
		this.events.publishEvent(new TraceEvent.TurnStarted(turn.initialMessages, (userText != null) ? userText : ""));
		return chain.nextStream(request.mutate().context(TURN_RECORD, turn).build()).doFinally(signal -> {
			this.settings.setCurrentTurn(null);
			this.events.publishEvent(turn.finish());
		});
	}

	@Override
	public String getName() {
		return "TurnTraceAdvisor";
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 100;
	}

}
