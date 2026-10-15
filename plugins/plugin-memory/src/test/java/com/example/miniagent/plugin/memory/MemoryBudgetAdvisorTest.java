package com.example.miniagent.plugin.memory;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryBudgetAdvisorTest {

	@Test
	void trustedSteeringIsMandatoryBeforeNextProviderOrInLoopMemoryCall() {
		for (boolean streaming : List.of(false, true)) {
			for (boolean inside : List.of(false, true)) {
				for (boolean countLimit : List.of(false, true)) {
					var queue = new com.example.miniagent.core.InputQueue();
					var target = queue.begin("s", true);
					var scope = new com.example.miniagent.core.SteeringAdvisor.Scope(queue, target, () -> "s", "t", null);
					var calls = new java.util.concurrent.atomic.AtomicInteger();
					var memoryRounds = new java.util.concurrent.atomic.AtomicInteger();
					ChatModel model = new ChatModel() {
						@Override public ChatResponse call(Prompt prompt) {
							calls.incrementAndGet();
							return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
								.toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "f", "{}"))).build())));
						}
						@Override public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
							return reactor.core.publisher.Flux.defer(() -> reactor.core.publisher.Flux.just(call(prompt)));
						}
						@Override public org.springframework.ai.model.tool.ToolCallingChatOptions getOptions() {
							return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
						}
					};
					var tool = org.springframework.ai.tool.function.FunctionToolCallback
						.<java.util.Map<String, Object>, String>builder("f", args -> {
							queue.offer(target, "s", com.example.miniagent.core.InputQueue.Kind.STEER, "x".repeat(100));
							return "r";
						}).description("fixture").inputType(java.util.Map.class).build();
					var budget = new MemoryBudgetAdvisor(countLimit ? 4 : 100, countLimit ? 1000 : 20);
					var memory = org.springframework.ai.chat.memory.MessageWindowChatMemory.builder().maxMessages(100).build();
					var builder = ChatClient.builder(model).defaultSystem("s").defaultToolCallbacks(tool)
						.defaultAdvisors(budget.capture(ToolCallingAdvisor.DEFAULT_ORDER - 101),
								budget.preflight(ToolCallingAdvisor.DEFAULT_ORDER + 1), budget,
								new com.example.miniagent.core.SteeringAdvisor(),
								org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.builder(memory)
									.order(inside ? ToolCallingAdvisor.DEFAULT_ORDER + 50 : ToolCallingAdvisor.DEFAULT_ORDER - 100).build());
					// A compaction-like model call at the in-loop memory position must not run again.
					if (inside) builder.defaultAdvisors(new org.springframework.ai.chat.client.advisor.api.CallAdvisor() {
						@Override public org.springframework.ai.chat.client.ChatClientResponse adviseCall(
								org.springframework.ai.chat.client.ChatClientRequest request,
								org.springframework.ai.chat.client.advisor.api.CallAdvisorChain chain) {
							memoryRounds.incrementAndGet();
							return chain.nextCall(request);
						}
						@Override public int getOrder() { return ToolCallingAdvisor.DEFAULT_ORDER + 49; }
						@Override public String getName() { return "CompactionProbe"; }
					});
					var request = builder.build().prompt().user("q").advisors(a -> a
						.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, "s")
						.param(com.example.miniagent.core.SteeringAdvisor.CONTEXT_KEY, scope));
					assertThatThrownBy(() -> {
						if (streaming) request.stream().content().collectList().block();
						else request.call().content();
					}).hasStackTraceContaining("Mandatory system and active USER/tool group");
					assertThat(calls).hasValue(1);
					if (inside && !streaming) assertThat(memoryRounds).hasValue(1);
					assertThat(scope.consumed()).hasSize(1);
				}
			}
		}
	}

	@TempDir
	Path dir;

	@Test
	void exactMandatoryLimitsDispatchWholeMessagesButOneLessRefuses() {
		List<Prompt> prompts = new ArrayList<>();
		ChatModel model = prompt -> {
			prompts.add(prompt);
			return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
		};
		MemoryBudgetAdvisor exact = new MemoryBudgetAdvisor(2, 5);
		client(model, exact).prompt().system("sys").user("hi").call().content();
		assertThat(prompts).hasSize(1);
		assertThat(prompts.getFirst().getInstructions()).extracting(Message::getText).containsExactly("sys", "hi");
		for (MemoryBudgetAdvisor smaller : List.of(new MemoryBudgetAdvisor(1, 5), new MemoryBudgetAdvisor(2, 4),
				new MemoryBudgetAdvisor(1, 1))) {
			prompts.clear();
			assertThatThrownBy(() -> client(model, smaller).prompt().system("sys").user("hi").call().content())
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("agent.plugins.memory.");
			assertThat(prompts).isEmpty();
		}
	}

	@Test
	void systemCannotBeDroppedToFitAnActiveToolGroup() {
		List<Prompt> prompts = new ArrayList<>();
		ChatModel model = prompt -> {
			prompts.add(prompt);
			return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
		};
		MemoryBudgetAdvisor budget = new MemoryBudgetAdvisor(3, 100);
		assertThatThrownBy(() -> client(model, budget).prompt().messages(new SystemMessage("s"),
				new UserMessage("q"), AssistantMessage.builder().content("").toolCalls(List.of(
						new AssistantMessage.ToolCall("id", "function", "f", "{}"))).build(),
				org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
						new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("id", "f", "r"))).build())
			.call().content()).hasMessageContaining("max-messages");
		assertThat(prompts).isEmpty();
	}

	@Test
	void invalidConfigurationFailsAtStartupWithThePropertyNameEvenWithoutCompaction() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MemoryPluginAutoConfiguration.class))
			.withBean(AgentProperties.class, () -> {
				AgentProperties properties = new AgentProperties();
				properties.setHome(this.dir);
				return properties;
			})
			.withBean(AgentConsole.class, AgentConsole::system)
			.withPropertyValues("agent.plugins.memory.compaction=false");
		for (String property : List.of("max-messages", "max-context-chars", "max-summary-request-chars",
				"max-summary-chars")) {
			runner.withPropertyValues("agent.plugins.memory." + property + "=0").run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
					.hasStackTraceContaining("agent.plugins.memory." + property);
			});
		}
		runner.withPropertyValues("agent.plugins.memory.max-messages=1", "agent.plugins.memory.max-context-chars=1")
			.run(context -> assertThat(context).hasNotFailed());
	}

	private static ChatClient client(ChatModel model, MemoryBudgetAdvisor budget) {
		return ChatClient.builder(model).defaultAdvisors(budget.capture(ToolCallingAdvisor.DEFAULT_ORDER - 1),
				budget.preflight(ToolCallingAdvisor.DEFAULT_ORDER + 1), budget).build();
	}
}
