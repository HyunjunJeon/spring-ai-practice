package com.example.miniagent.plugin.memory;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.SlashCommand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 메모리 플러그인의 켜고 끄기와 기본값: 한도, 요약, 루프 안팎 배치. */
class MemoryPluginToggleTest {

	private static final String COMPACTION_OFF = "agent.plugins.memory.compaction=false";

	@TempDir
	Path home;

	private final List<Prompt> prompts = new ArrayList<>();

	private final AtomicInteger modelCalls = new AtomicInteger();

	private String modelAnswer = "summary";

	@Test
	void disabledPluginRegistersNoMemoryBeansAndCreatesNoSessionDirectory() {
		runner("agent.plugins.memory.enabled=false").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeansOfType(JsonlChatMemoryRepository.class)).isEmpty();
			assertThat(context.getBeansOfType(ChatMemory.class)).isEmpty();
			assertThat(context.getBeansOfType(Advisor.class)).isEmpty();
			assertThat(context.getBeansOfType(SlashCommand.class)).isEmpty();
			assertThat(context.getBeansOfType(AgentPlugin.class)).isEmpty();
		});
		assertThat(this.home.resolve("sessions")).doesNotExist();
	}

	@Test
	void enabledPluginRegistersTheSessionCommandsAndDescribesItsDefaults() {
		runner().run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeansOfType(SlashCommand.class).values().stream().map(SlashCommand::name)
				.collect(Collectors.toSet())).isEqualTo(Set.of("sessions", "fork", "tree", "new", "resume", "history",
						"forget"));
			assertThat(context.getBean(AgentPlugin.class)).isEqualTo(
					AgentPlugin.of("memory", "JSONL 세션 메모리 (루프 바깥, 요약 켜짐)"));
		});
	}

	@Test
	void memoryAdvisorSitsOutsideTheToolLoopByDefaultAndInsideItWhenAsked() {
		runner().run(context -> {
			int order = context.getBean("chatMemoryAdvisor", Advisor.class).getOrder();
			assertThat(order).isEqualTo(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER)
				.isLessThan(ToolCallingAdvisor.DEFAULT_ORDER);
			assertThat(context.getBean(AgentPlugin.class).description()).contains("루프 바깥");
		});
		runner("agent.plugins.memory.inside-loop=true").run(context -> {
			int order = context.getBean("chatMemoryAdvisor", Advisor.class).getOrder();
			assertThat(order).isEqualTo(ToolCallingAdvisor.DEFAULT_ORDER + 50)
				.isGreaterThan(ToolCallingAdvisor.DEFAULT_ORDER);
			assertThat(context.getBean(AgentPlugin.class).description()).contains("루프 안쪽");
		});
	}

	@Test
	void defaultMessageLimitIsOneHundredAndTheSummaryFoldsDownToHalfOfIt() {
		runner().run(context -> {
			var memory = context.getBean(ChatMemory.class);
			var repository = context.getBean(JsonlChatMemoryRepository.class);
			repository.append("fits", turns(50, turn -> "q" + turn, "a"));
			List<Message> overflowing = turns(50, turn -> "q" + turn, "a");
			overflowing.add(new UserMessage("q51"));
			repository.append("overflows", overflowing);

			assertThat(memory.get("fits")).hasSize(100);
			assertThat(this.modelCalls).hasValue(0);

			List<Message> folded = memory.get("overflows");

			assertThat(this.modelCalls).hasValue(1);
			assertThat(repository.read("overflows").summary().covers()).isEqualTo(52);
			assertThat(folded).hasSize(50);
			assertThat(folded.getFirst().getText()).startsWith(JsonlChatMemory.SUMMARY_HEADER);
			assertThat(folded.get(1).getText()).isEqualTo("q27");
		});
	}

	@Test
	void defaultContextLimitIsTwoHundredThousandCharacters() {
		runner().run(context -> {
			var memory = context.getBean(ChatMemory.class);
			var repository = context.getBean(JsonlChatMemoryRepository.class);
			repository.append("exact", List.of(new UserMessage("x".repeat(99_999)), new AssistantMessage("a"),
					new UserMessage("y".repeat(99_999)), new AssistantMessage("b")));
			repository.append("over", List.of(new UserMessage("x".repeat(100_000)), new AssistantMessage("a"),
					new UserMessage("y".repeat(99_999)), new AssistantMessage("b")));

			assertThat(memory.get("exact")).hasSize(4);
			assertThat(this.modelCalls).hasValue(0);

			List<Message> folded = memory.get("over");

			assertThat(this.modelCalls).hasValue(1);
			assertThat(repository.read("over").summary().covers()).isEqualTo(2);
			assertThat(folded).hasSize(3);
			assertThat(folded.get(1).getText()).startsWith("y");
		});
	}

	@Test
	void defaultSummaryRequestIsBoundedTo32000CharactersAndTheSummaryTo8000() {
		this.modelAnswer = "s".repeat(20_000);
		runner().run(context -> {
			var repository = context.getBean(JsonlChatMemoryRepository.class);
			repository.append("long", turns(60, turn -> "u".repeat(2000), "a".repeat(2000)));

			context.getBean(ChatMemory.class).get("long");

			assertThat(this.prompts).hasSize(1);
			var messages = this.prompts.getFirst().getInstructions();
			assertThat(messages.get(0).getText().length() + messages.get(1).getText().length()).isEqualTo(32_000);
			assertThat(messages.get(1).getText()).endsWith(ChatModelSummarizer.OMITTED);
			assertThat(repository.read("long").summary().text()).hasSize(8000);
		});
	}

	@Test
	void disabledCompactionNeverCallsTheModelAndStillKeepsTheFullHistoryOnDisk() {
		runner(COMPACTION_OFF).run(context -> {
			var memory = context.getBean(ChatMemory.class);
			var repository = context.getBean(JsonlChatMemoryRepository.class);
			repository.append("overflows", turns(51, turn -> "q" + turn, "a"));

			List<Message> window = memory.get("overflows");

			assertThat(this.modelCalls).hasValue(0);
			assertThat(repository.read("overflows").summary()).isNull();
			assertThat(window).hasSize(100);
			assertThat(window.getFirst().getText()).isEqualTo("q2");
			assertThat(repository.findByConversationId("overflows")).hasSize(102);
			assertThat(context.getBean(AgentPlugin.class).description()).contains("요약 꺼짐");
		});
	}

	private static List<Message> turns(int count, IntFunction<String> question, String answer) {
		List<Message> messages = new ArrayList<>();
		for (int turn = 1; turn <= count; turn++) {
			messages.add(new UserMessage(question.apply(turn)));
			messages.add(new AssistantMessage(answer));
		}
		return messages;
	}

	private ApplicationContextRunner runner(String... properties) {
		var agentProperties = new AgentProperties();
		agentProperties.setHome(this.home);
		var console = new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(java.io.OutputStream.nullOutputStream()), false);
		ChatModel model = new ChatModel() {
			@Override
			public ChatOptions getOptions() {
				return ToolCallingChatOptions.builder().build();
			}

			@Override
			public ChatResponse call(Prompt prompt) {
				MemoryPluginToggleTest.this.prompts.add(prompt);
				MemoryPluginToggleTest.this.modelCalls.incrementAndGet();
				return new ChatResponse(List.of(new Generation(
						new AssistantMessage(MemoryPluginToggleTest.this.modelAnswer))));
			}
		};
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MemoryPluginAutoConfiguration.class))
			.withBean(AgentProperties.class, () -> agentProperties)
			.withBean(AgentConsole.class, () -> console)
			.withBean(ChatModel.class, () -> model)
			.withPropertyValues(properties);
	}

}
