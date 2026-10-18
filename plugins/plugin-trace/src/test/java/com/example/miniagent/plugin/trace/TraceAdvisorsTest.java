package com.example.miniagent.plugin.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.TraceEvent.Tokens;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 턴(루프 바깥)과 라운드(루프 안쪽) 어드바이저가 실제 ChatClient 도구 루프에서 발행하는 이벤트.
 * TUI 그래프, REPL 트레이스, 토큰 표시가 모두 이 이벤트에 달려 있다.
 */
class TraceAdvisorsTest {

	/** 줄바꿈이 섞인 인자: 라운드 이벤트는 공백을 하나로 줄여 보여준다. */
	private static final String ARGUMENTS = "{\"text\":\n \"a\"}";

	private static final ToolCallback ECHO = FunctionToolCallback.<EchoArguments, String>builder("echo",
			arguments -> "r")
		.description("echo")
		.inputType(EchoArguments.class)
		.build();

	/** 스트림은 boundedElastic 스레드에서 이벤트를 발행하므로 동시에 읽어도 안전한 목록을 쓴다. */
	private final List<Object> events = new CopyOnWriteArrayList<>();

	private final CountDownLatch roundStarted = new CountDownLatch(1);

	private final CountDownLatch turnFinished = new CountDownLatch(1);

	private final TraceSettings settings = new TraceSettings("summary");

	@Test
	void callWithTwoToolRoundsPublishesTheTurnAndEveryRoundInOrder() {
		ScriptedModel model = new ScriptedModel(twoToolRoundsThenAnswer());

		String answer = traced(model).prompt().user("hello").call().content();

		assertThat(answer).isEqualTo("done");
		assertTwoToolRoundTurn(model);
		assertThat(events(TraceEvent.RoundFinished.class)).extracting(round -> round.tokens().cached())
			.containsExactly(40L, 60L, 80L);
		assertThat(events(TraceEvent.RoundFinished.class)).extracting(TraceEvent.RoundFinished::reasoningEffort)
			.containsExactly("low", "low", "high");
		assertThat(events(TraceEvent.TurnFinished.class)).singleElement().satisfies(finished -> {
			assertThat(finished.tokens().cached()).isEqualTo(180);
			assertThat(finished.reasoningEffort()).isEqualTo("high");
		});
	}

	@Test
	void streamWithTwoToolRoundsPublishesTheTurnAndEveryRoundInOrder() {
		ScriptedModel model = new ScriptedModel(twoToolRoundsThenAnswer());

		List<String> chunks = traced(model).prompt().user("hello").stream().content().collectList().block();

		assertThat(String.join("", chunks)).isEqualTo("done");
		assertTwoToolRoundTurn(model);
	}

	@Test
	void streamedRoundsKeepTheReasoningEffortTheProviderReported() {
		ScriptedModel model = new ScriptedModel(twoToolRoundsThenAnswer());

		traced(model).prompt().user("hello").stream().content().collectList().block();

		assertThat(events(TraceEvent.RoundFinished.class)).extracting(TraceEvent.RoundFinished::reasoningEffort)
			.containsExactly("low", "low", "high");
		assertThat(events(TraceEvent.TurnFinished.class)).singleElement()
			.satisfies(finished -> assertThat(finished.reasoningEffort()).isEqualTo("high"));
	}

	@Test
	void streamedRoundsKeepTheCachedTokensTheProviderReported() {
		ScriptedModel model = new ScriptedModel(twoToolRoundsThenAnswer());

		traced(model).prompt().user("hello").stream().content().collectList().block();

		assertThat(events(TraceEvent.RoundFinished.class)).extracting(round -> round.tokens().cached())
			.containsExactly(40L, 60L, 80L);
		assertThat(events(TraceEvent.TurnFinished.class)).singleElement()
			.satisfies(finished -> assertThat(finished.tokens().cached()).isEqualTo(180));
	}

	@Test
	void streamedRoundKeepsTheValuesReportedEarlierWhenALaterChunkReportsNone() {
		ChatModel chunked = new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				throw new AssertionError("streaming only");
			}

			@Override
			public Flux<ChatResponse> stream(Prompt prompt) {
				return Flux.just(answerRound("do", "stop", "fake-1", "low", 100, 10, 40),
						answerRound("ne", "stop", "fake-1", null, 0, 0, 0));
			}

			@Override
			public ToolCallingChatOptions getOptions() {
				return ToolCallingChatOptions.builder().build();
			}
		};

		assertThat(String.join("", traced(chunked).prompt().user("hello").stream().content().collectList().block()))
			.isEqualTo("done");

		assertThat(events(TraceEvent.RoundFinished.class)).singleElement().satisfies(round -> {
			assertThat(round.reasoningEffort()).isEqualTo("low");
			assertThat(round.tokens().cached()).isEqualTo(40);
		});
	}

	@Test
	void callFailingInTheSecondRoundStillFinishesTheTurnWithTheUsageSoFar() {
		ScriptedModel model = new ScriptedModel(List.of(
				() -> toolRound(1, "tool_calls", "fake-1", "low", 100, 10, 40),
				() -> {
					throw new IllegalStateException("model down");
				}));

		assertThatThrownBy(() -> traced(model).prompt().user("hello").call().content())
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("model down");

		assertFailedTurn();
		assertThat(this.settings.currentTurn()).isNull();
	}

	@Test
	void streamFailingInTheSecondRoundStillFinishesTheTurnWithTheUsageSoFar() {
		ScriptedModel model = new ScriptedModel(List.of(
				() -> toolRound(1, "tool_calls", "fake-1", "low", 100, 10, 40),
				() -> {
					throw new IllegalStateException("model down");
				}));

		assertThatThrownBy(() -> traced(model).prompt().user("hello").stream().content().collectList().block())
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("model down");

		assertFailedTurn();
		assertThat(this.settings.currentTurn()).isNull();
	}

	@Test
	void cancellingAStreamStillFinishesTheTurnAsNotAnswered() throws InterruptedException {
		ChatModel neverAnswers = new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				throw new AssertionError("streaming only");
			}

			@Override
			public Flux<ChatResponse> stream(Prompt prompt) {
				return Flux.never();
			}

			@Override
			public ToolCallingChatOptions getOptions() {
				return ToolCallingChatOptions.builder().build();
			}
		};

		Disposable subscription = traced(neverAnswers).prompt().user("hello").stream().content().subscribe();
		assertThat(this.roundStarted.await(5, TimeUnit.SECONDS)).as("RoundStarted published").isTrue();
		assertThat(this.settings.currentTurn()).isNotNull();

		subscription.dispose();

		// 취소는 구독을 넘겨받은 boundedElastic 스레드에서 정리될 수 있어, 턴 종료 이벤트를 한도 안에서 기다린다.
		assertThat(this.turnFinished.await(5, TimeUnit.SECONDS)).as("TurnFinished published").isTrue();
		assertThat(this.settings.currentTurn()).isNull();
		assertThat(events(TraceEvent.TurnFinished.class)).singleElement().satisfies(finished -> {
			assertThat(finished.answered()).isFalse();
			assertThat(finished.rounds()).isEqualTo(1);
		});
	}

	@Test
	void roundWithoutProviderMetadataReportsNoUsageModelOrEffort() {
		ScriptedModel model = new ScriptedModel(List.of(() -> new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))))));

		traced(model).prompt().user("hello").call().content();

		assertThat(events(TraceEvent.RoundFinished.class)).singleElement().satisfies(round -> {
			assertThat(round.tokens()).isEqualTo(Tokens.ZERO);
			assertThat(round.model()).isNull();
			assertThat(round.reasoningEffort()).isNull();
			assertThat(round.abnormalFinish()).isNull();
			assertThat(round.answerChars()).isEqualTo(2);
		});
		assertThat(events(TraceEvent.TurnFinished.class)).singleElement().satisfies(finished -> {
			assertThat(finished.answered()).isTrue();
			assertThat(finished.model()).isNull();
		});
	}

	@Test
	void streamedRoundWithoutProviderMetadataReportsNoUsageModelOrEffort() {
		ScriptedModel model = new ScriptedModel(List.of(() -> new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))))));

		traced(model).prompt().user("hello").stream().content().collectList().block();

		assertThat(events(TraceEvent.RoundFinished.class)).singleElement().satisfies(round -> {
			assertThat(round.tokens()).isEqualTo(Tokens.ZERO);
			assertThat(round.model()).isNull();
			assertThat(round.reasoningEffort()).isNull();
		});
	}

	@Test
	void blankModelNameInTheMetadataIsTreatedAsUnreported() {
		ScriptedModel model = new ScriptedModel(List.of(() -> answerRound("ok", "stop", "  ", null, 3, 1, 0)));

		traced(model).prompt().user("hello").call().content();

		assertThat(events(TraceEvent.RoundFinished.class)).singleElement()
			.satisfies(round -> assertThat(round.model()).isNull());
	}

	@Test
	void responseWithoutAnyGenerationIsReportedAsAnEmptyRoundAndNotAnswered() {
		ScriptedModel model = new ScriptedModel(List.of(() -> new ChatResponse(List.of())));

		traced(model).prompt().user("hello").call().chatResponse();

		assertThat(events(TraceEvent.RoundFinished.class)).singleElement().satisfies(round -> {
			assertThat(round.abnormalFinish()).isEqualTo("empty");
			assertThat(round.answerChars()).isZero();
			assertThat(round.toolCalls()).isEmpty();
			assertThat(round.tokens()).isEqualTo(Tokens.ZERO);
		});
		assertThat(events(TraceEvent.TurnFinished.class)).singleElement()
			.satisfies(finished -> assertThat(finished.answered()).isFalse());
	}

	@Test
	void providerCutOffIsKeptAsTheAbnormalFinishAndTheTurnIsNotAnswered() {
		ScriptedModel model = new ScriptedModel(List.of(() -> answerRound("partial", "LENGTH", "fake-1", null, 3, 1, 0)));

		traced(model).prompt().user("hello").call().content();

		assertThat(events(TraceEvent.RoundFinished.class)).singleElement()
			.satisfies(round -> assertThat(round.abnormalFinish()).isEqualTo("LENGTH"));
		assertThat(events(TraceEvent.TurnFinished.class)).singleElement()
			.satisfies(finished -> assertThat(finished.answered()).isFalse());
	}

	@ParameterizedTest
	@ValueSource(strings = { "stop", "STOP", "end_turn", "tool_calls", "tool_use", "" })
	void normalFinishReasonsOfEveryProviderAreNotFlagged(String finishReason) {
		ScriptedModel model = new ScriptedModel(List.of(() -> answerRound("ok", finishReason, "fake-1", null, 3, 1, 0)));

		traced(model).prompt().user("hello").call().content();

		assertThat(events(TraceEvent.RoundFinished.class)).singleElement()
			.satisfies(round -> assertThat(round.abnormalFinish()).isNull());
		assertThat(events(TraceEvent.TurnFinished.class)).singleElement()
			.satisfies(finished -> assertThat(finished.answered()).isTrue());
	}

	@Test
	void roundAdvisorWithoutATurnRecordPassesThroughAndPublishesNothing() {
		ScriptedModel model = new ScriptedModel(List.of(() -> answerRound("ok", "stop", "fake-1", null, 3, 1, 0),
				() -> answerRound("ok", "stop", "fake-1", null, 3, 1, 0)));
		ChatClient client = client(model, new RoundTraceAdvisor(this::publish));

		assertThat(client.prompt().user("hello").call().content()).isEqualTo("ok");
		assertThat(client.prompt().user("hello").stream().content().collectList().block()).containsExactly("ok");

		assertThat(this.events).isEmpty();
	}

	private void assertTwoToolRoundTurn(ScriptedModel model) {
		assertThat(this.events).extracting(event -> event.getClass().getSimpleName())
			.containsExactly("TurnStarted", "RoundStarted", "RoundFinished", "RoundStarted", "RoundFinished",
					"RoundStarted", "RoundFinished", "TurnFinished");
		assertThat(events(TraceEvent.TurnStarted.class)).singleElement().satisfies(started -> {
			assertThat(started.userText()).isEqualTo("hello");
			assertThat(started.inputMessages()).isEqualTo(2);
		});
		// 1라운드는 메모리가 없어 늘어난 메시지가 없고, 이후 라운드는 도구 호출과 결과 두 개씩 늘어난다.
		assertThat(events(TraceEvent.RoundStarted.class)).extracting(TraceEvent.RoundStarted::round,
				TraceEvent.RoundStarted::messages, TraceEvent.RoundStarted::addedMessages,
				TraceEvent.RoundStarted::toolCount)
			.containsExactly(tuple(1, 2, 0, 1), tuple(2, 4, 2, 1),
					tuple(3, 6, 2, 1));
		List<TraceEvent.RoundFinished> rounds = events(TraceEvent.RoundFinished.class);
		assertThat(rounds).extracting(TraceEvent.RoundFinished::round).containsExactly(1, 2, 3);
		assertThat(rounds.get(0).toolCalls()).containsExactly("echo {\"text\": \"a\"}");
		assertThat(rounds.get(1).toolCalls()).containsExactly("echo {\"text\": \"a\"}");
		assertThat(rounds.get(2).toolCalls()).isEmpty();
		assertThat(rounds.get(2).answerChars()).isEqualTo(4);
		assertThat(rounds).extracting(round -> round.tokens().input()).containsExactly(100L, 150L, 200L);
		assertThat(rounds).extracting(round -> round.tokens().output()).containsExactly(10L, 12L, 20L);
		assertThat(rounds).extracting(TraceEvent.RoundFinished::model).containsExactly("fake-1", "fake-1", "fake-2");
		assertThat(rounds).extracting(TraceEvent.RoundFinished::abnormalFinish).containsOnlyNulls();
		assertThat(events(TraceEvent.TurnFinished.class)).singleElement().satisfies(finished -> {
			assertThat(finished.rounds()).isEqualTo(3);
			assertThat(finished.tokens().input()).isEqualTo(450);
			assertThat(finished.tokens().output()).isEqualTo(42);
			assertThat(finished.model()).isEqualTo("fake-2");
			assertThat(finished.answered()).isTrue();
		});
		assertThat(model.turnOpenAtCall).containsExactly(true, true, true);
		assertThat(this.settings.currentTurn()).isNull();
	}

	private void assertFailedTurn() {
		assertThat(this.events).extracting(event -> event.getClass().getSimpleName())
			.containsExactly("TurnStarted", "RoundStarted", "RoundFinished", "RoundStarted", "TurnFinished");
		assertThat(events(TraceEvent.TurnFinished.class)).singleElement().satisfies(finished -> {
			assertThat(finished.answered()).isFalse();
			assertThat(finished.rounds()).isEqualTo(2);
			assertThat(finished.tokens().input()).isEqualTo(100);
			assertThat(finished.tokens().output()).isEqualTo(10);
			assertThat(finished.model()).isEqualTo("fake-1");
		});
	}

	private void publish(Object event) {
		this.events.add(event);
		if (event instanceof TraceEvent.RoundStarted) {
			this.roundStarted.countDown();
		}
		if (event instanceof TraceEvent.TurnFinished) {
			this.turnFinished.countDown();
		}
	}

	private <T extends TraceEvent> List<T> events(Class<T> type) {
		return this.events.stream().filter(type::isInstance).map(type::cast).toList();
	}

	private ChatClient traced(ChatModel model) {
		return client(model, new TurnTraceAdvisor(this::publish, this.settings),
				new RoundTraceAdvisor(this::publish));
	}

	private static ChatClient client(ChatModel model, Advisor... advisors) {
		return ChatClient.builder(model).defaultSystem("sys").defaultToolCallbacks(ECHO).defaultAdvisors(advisors).build();
	}

	private static List<Supplier<ChatResponse>> twoToolRoundsThenAnswer() {
		return List.of(() -> toolRound(1, "tool_calls", "fake-1", "low", 100, 10, 40),
				() -> toolRound(2, "tool_use", "fake-1", "low", 150, 12, 60),
				() -> answerRound("done", "stop", "fake-2", "high", 200, 20, 80));
	}

	private static ChatResponse toolRound(int number, String finishReason, String model, String effort, int input,
			int output, long cached) {
		AssistantMessage message = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-" + number, "function", "echo", ARGUMENTS)))
			.build();
		return response(message, finishReason, model, effort, input, output, cached);
	}

	private static ChatResponse answerRound(String text, String finishReason, String model, @Nullable String effort,
			int input, int output, long cached) {
		return response(new AssistantMessage(text), finishReason, model, effort, input, output, cached);
	}

	private static ChatResponse response(AssistantMessage message, String finishReason, String model,
			@Nullable String effort, int input, int output, long cached) {
		ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
			.model(model)
			.usage(new DefaultUsage(input, output, input + output, null, cached, 0L));
		if (effort != null) {
			metadata.keyValue(TraceEvent.REASONING_EFFORT_METADATA, effort);
		}
		Generation generation = new Generation(message,
				ChatGenerationMetadata.builder().finishReason(finishReason).build());
		return new ChatResponse(List.of(generation), metadata.build());
	}

	record EchoArguments(String text) {
	}

	/** 호출마다 다음 응답(또는 예외)을 내놓는 모델. 호출될 때 턴 기록이 열려 있었는지도 적어 둔다. */
	private final class ScriptedModel implements ChatModel {

		private final List<Supplier<ChatResponse>> script;

		private final List<Boolean> turnOpenAtCall = new ArrayList<>();

		ScriptedModel(List<Supplier<ChatResponse>> script) {
			this.script = script;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			this.turnOpenAtCall.add(TraceAdvisorsTest.this.settings.currentTurn() != null);
			return this.script.get(this.turnOpenAtCall.size() - 1).get();
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.defer(() -> Flux.just(call(prompt)));
		}

		@Override
		public ToolCallingChatOptions getOptions() {
			return ToolCallingChatOptions.builder().build();
		}

	}

}
