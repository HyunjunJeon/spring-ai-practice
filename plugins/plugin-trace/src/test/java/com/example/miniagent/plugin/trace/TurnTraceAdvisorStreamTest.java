package com.example.miniagent.plugin.trace;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 스트림 턴의 TurnFinished 는 구독자가 완료(또는 오류)를 받기 전에 발행돼야 한다.
 * 완료를 받은 쪽(RPC 콘솔 등)은 곧바로 턴을 닫으므로, 그 뒤에 도착한 TurnFinished 는 버려진다.
 * 구독자의 완료 신호 시점에 이미 발행된 이벤트를 적어 두면 스레드 타이밍 없이 순서를 확인할 수 있다.
 */
class TurnTraceAdvisorStreamTest {

	private final List<Object> events = new CopyOnWriteArrayList<>();

	private final TraceSettings settings = new TraceSettings("summary");

	@Test
	void turnFinishedIsPublishedBeforeTheSubscriberSeesTheCompletion() {
		AtomicReference<List<String>> publishedAtCompletion = new AtomicReference<>();
		ChatClient client = traced(Flux.just(answer("ok")));

		client.prompt().user("hello").stream().chatClientResponse()
			.doOnComplete(() -> publishedAtCompletion.set(eventNames()))
			.blockLast();

		assertThat(publishedAtCompletion.get()).containsExactly("TurnStarted", "TurnFinished");
		assertThat(this.settings.currentTurn()).isNull();
	}

	@Test
	void turnFinishedIsPublishedBeforeTheSubscriberSeesTheFailure() {
		AtomicReference<List<String>> publishedAtFailure = new AtomicReference<>();
		ChatClient client = traced(Flux.error(new IllegalStateException("model down")));

		assertThatThrownBy(() -> client.prompt().user("hello").stream().chatClientResponse()
			.doOnError(failure -> publishedAtFailure.set(eventNames()))
			.blockLast()).isInstanceOf(IllegalStateException.class).hasMessage("model down");

		assertThat(publishedAtFailure.get()).containsExactly("TurnStarted", "TurnFinished");
		assertThat(this.settings.currentTurn()).isNull();
	}

	private List<String> eventNames() {
		return this.events.stream().map(event -> event.getClass().getSimpleName()).toList();
	}

	private ChatClient traced(Flux<ChatResponse> modelStream) {
		ChatModel model = new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				throw new AssertionError("streaming only");
			}

			@Override
			public Flux<ChatResponse> stream(Prompt prompt) {
				return modelStream;
			}

			@Override
			public ToolCallingChatOptions getOptions() {
				return ToolCallingChatOptions.builder().build();
			}
		};
		return ChatClient.builder(model)
			.defaultAdvisors(new TurnTraceAdvisor(this.events::add, this.settings))
			.build();
	}

	private static ChatResponse answer(String text) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
	}

}
