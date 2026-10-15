package com.example.miniagent.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.miniagent.core.model.*;
import com.example.miniagent.core.model.registry.ModelRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;

class SteeringAdvisorTest {
	@Test void arrivalDuringProviderStreamWaitsForCompleteToolArguments() throws Exception {
		var queue = new InputQueue();
		var target = queue.begin("s", true);
		var scope = new SteeringAdvisor.Scope(queue, target, () -> "s", "t", null);
		var opened = new java.util.concurrent.CountDownLatch(1);
		var complete = new java.util.concurrent.CountDownLatch(1);
		var executions = new AtomicInteger();
		var rounds = new AtomicInteger();
		var tool = FunctionToolCallback.<Map<String, String>, String>builder("f", args -> {
			assertThat(args).containsEntry("text", "complete");
			executions.incrementAndGet();
			return "r";
		}).description("fixture").inputType(Map.class).build();
		ChatModel model = new ChatModel() {
			@Override public ChatResponse call(Prompt prompt) { throw new AssertionError("Streaming only"); }
			@Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
			@Override public Flux<ChatResponse> stream(Prompt prompt) {
				return Flux.create(sink -> {
					if (rounds.incrementAndGet() == 1) {
						// Provider adapters accumulate wire argument fragments; only their final
						// ChatResponse carries an executable ToolCall.
						sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("")))));
						opened.countDown();
						try {
							if (!complete.await(5, java.util.concurrent.TimeUnit.SECONDS))
								throw new AssertionError("Stream not released");
						}
						catch (InterruptedException ex) { Thread.currentThread().interrupt(); sink.error(ex); return; }
						sink.next(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
							.toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "f",
									"{\"text\":\"complete\"}"))).build()))));
					}
					else {
						assertThat(prompt.getInstructions().getLast().getText()).isEqualTo("arrived");
						sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
					}
					sink.complete();
				});
			}
		};
		var client = ChatClient.builder(model).defaultToolCallbacks(tool).defaultAdvisors(new SteeringAdvisor()).build();
		try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
			var result = worker.submit(() -> client.prompt().user("q")
				.advisors(a -> a.param(SteeringAdvisor.CONTEXT_KEY, scope)).stream().content().collectList().block());
			try {
				assertThat(opened.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
				queue.offer(target, "s", InputQueue.Kind.STEER, "arrived");
				assertThat(scope.consumed()).isEmpty();
				assertThat(executions).hasValue(0);
			}
			finally { complete.countDown(); }
			assertThat(result.get(5, java.util.concurrent.TimeUnit.SECONDS)).contains("done");
		}
		assertThat(executions).hasValue(1);
		assertThat(scope.consumed()).hasSize(1);
	}

	@Test void callAndStreamInjectOnlyAfterCompleteToolResultsAndRetainAcrossRounds() {
		for (boolean stream : List.of(false, true)) {
			var queue = new InputQueue();
			var target = queue.begin("s", true);
			var scope = new SteeringAdvisor.Scope(queue, target, () -> "s", "turn", null);
			List<Prompt> prompts = new ArrayList<>();
			var executed = new AtomicInteger();
			var tool = FunctionToolCallback.<Map<String, String>, String>builder("fixture", args -> {
				assertThat(args).containsEntry("text", "complete");
				int round = executed.incrementAndGet();
				queue.offer(target, "s", InputQueue.Kind.STEER, "steer" + round);
				return "result" + round;
			}).description("fixture").inputType(Map.class).build();
			ChatModel model = new ChatModel() {
				@Override public ChatResponse call(Prompt prompt) {
					prompts.add(prompt);
					int round = prompts.size();
					AssistantMessage message = round <= 2 ? AssistantMessage.builder().content("")
						.toolCalls(List.of(new AssistantMessage.ToolCall("id" + round, "function", "fixture",
								"{\"text\":\"complete\"}"))).build() : new AssistantMessage("done");
					return new ChatResponse(List.of(new Generation(message)));
				}
				@Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
				@Override public ToolCallingChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
			};
			var client = ChatClient.builder(model).defaultToolCallbacks(tool).defaultAdvisors(new SteeringAdvisor()).build();
			var request = client.prompt().user("original").advisors(a -> a.param(SteeringAdvisor.CONTEXT_KEY, scope));
			if (stream) assertThat(request.stream().content().collectList().block()).contains("done");
			else assertThat(request.call().content()).isEqualTo("done");
			assertThat(prompts).hasSize(3);
			assertThat(prompts.getFirst().getInstructions()).extracting(Message::getText).containsExactly("original");
			assertThat(prompts.get(1).getInstructions().getLast().getText()).isEqualTo("steer1");
			var finalMessages = prompts.getLast().getInstructions();
			assertThat(finalMessages).filteredOn(UserMessage.class::isInstance).extracting(Message::getText)
				.containsExactly("original", "steer1", "steer2");
			for (int i = 0; i < finalMessages.size(); i++)
				if (finalMessages.get(i) instanceof AssistantMessage assistant && assistant.hasToolCalls())
					assertThat(finalMessages.get(i + 1)).isInstanceOf(ToolResponseMessage.class);
			assertThat(scope.consumed()).extracting(SteeringAdvisor.Consumed::sequence).containsExactly(1L, 2L);
			assertThat(executed).hasValue(2);
		}
	}

	@Test void explicitCapturedBindingIsReusedEvenWhenSelectionChanges() {
		ChatModel model = new ChatModel() {
			@Override public ChatResponse call(Prompt prompt) { return new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))); }
			@Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
		};
		var binding = new ModelBinding(UUID.randomUUID(), new ModelRef("fake", "captured"), "fixture", "fake",
				model, ChatClient.builder(model).build());
		var registry = new ModelRegistry(List.of(), m -> ChatClient.builder(m).build());
		var session = new AgentSession();
		var agent = new Agent(registry, new SessionModelStore() {
			@Override public java.util.Optional<ModelSelection> load(String id) { throw new AssertionError("Must use captured binding"); }
			@Override public void save(String id, ModelSelection selection) { throw new AssertionError(); }
		}, new ModelSelection(binding.ref()), session, Path.of("."), "", List.of(), List.of(), List.of(), List.of(), 0);
		var queue = new InputQueue();
		var target = queue.begin(session.id(), true);
		var context = new TurnContext(session.id(), "previous", Path.of("."), binding);
		var scope = new SteeringAdvisor.Scope(queue, target, session::id, "next", context);
		assertThat(agent.streamQueued("literal", text -> {}, scope).text()).isEqualTo("ok");
		assertThat(scope.captured().binding()).isSameAs(binding);
		assertThat(scope.captured().sessionId()).isEqualTo(session.id());
		assertThat(scope.captured().turnId()).isEqualTo("next");
	}
}
