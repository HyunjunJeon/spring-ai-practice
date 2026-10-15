package com.example.miniagent.plugin.memory;

import java.util.HashSet;
import java.util.Set;

import com.example.miniagent.core.SteeringAdvisor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/** Writes steering before the enclosing memory advisor stores its assistant response. */
final class SteeringHistoryAdvisor implements CallAdvisor, StreamAdvisor {

	private static final String STATE = SteeringHistoryAdvisor.class.getName() + ".state";

	private static final class State {
		private final Set<Long> written = new HashSet<>();
	}

	private final JsonlChatMemoryRepository repository;
	private final int order;
	private final boolean capture;

	SteeringHistoryAdvisor(JsonlChatMemoryRepository repository, int order, boolean capture) {
		this.repository = repository;
		this.order = order;
		this.capture = capture;
	}

	private ChatClientRequest prepare(ChatClientRequest request) {
		if (this.capture && request.context().get(SteeringAdvisor.CONTEXT_KEY) instanceof SteeringAdvisor.Scope) {
			return request.mutate().context(STATE, new State()).build();
		}
		return request;
	}

	private void persist(ChatClientRequest request) {
		if (this.capture || !(request.context().get(STATE) instanceof State state)
				|| !(request.context().get(SteeringAdvisor.CONTEXT_KEY) instanceof SteeringAdvisor.Scope scope)) return;
		synchronized (state) {
			var consumed = scope.consumed().stream()
				.filter(value -> !state.written.contains(value.sequence())).toList();
			if (consumed.isEmpty()) return;
			this.repository.append(scope.sessionId(), consumed.stream()
				.map(value -> (org.springframework.ai.chat.messages.Message) value.message()).toList());
			consumed.forEach(value -> state.written.add(value.sequence()));
		}
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		ChatClientRequest prepared = prepare(request);
		ChatClientResponse response = chain.nextCall(prepared);
		persist(prepared);
		return response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return Flux.defer(() -> {
			ChatClientRequest prepared = prepare(request);
			return chain.nextStream(prepared).doOnComplete(() -> persist(prepared));
		});
	}

	@Override public int getOrder() { return this.order; }
	@Override public String getName() { return this.capture ? "SteeringHistoryCapture" : "SteeringHistoryPersist"; }
}
