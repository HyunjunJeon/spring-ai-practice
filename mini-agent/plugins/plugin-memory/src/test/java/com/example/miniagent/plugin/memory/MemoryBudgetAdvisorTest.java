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
