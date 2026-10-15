package com.example.miniagent.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ProviderAuth;
import com.example.miniagent.plugin.memory.JsonlChatMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Actual application ChatClient dispatches, with no provider/network calls. */
class MemoryBudgetTest {

	@TempDir
	static Path dir;

	@DynamicPropertySource
	static void paths(DynamicPropertyRegistry registry) {
		registry.add("agent.cwd", () -> dir.toString());
		registry.add("agent.home", () -> dir.resolve(".home").toString());
	}

	@TestConfiguration
	static class Models {
		@Bean
		@Primary
		RecordingModel recordingModel() {
			return new RecordingModel();
		}

		@Bean
		ModelProvider fakeModelProvider(RecordingModel model) {
			return new ModelProvider() {
				@Override
				public String providerId() {
					return "fake";
				}

				@Override
				public List<ModelEntry> models() {
					return List.of(new ModelEntry(new ModelRef("fake", "fake-scripted"), "fake", null));
				}

				@Override
				public ProviderAuth auth() {
					return () -> true;
				}

				@Override
				public ChatModel create(ModelRef ref, java.util.Map<String, String> options) {
					return model;
				}
			};
		}

		@Bean
		ToolCallback budgetTool(RecordingModel model) {
			return FunctionToolCallback.builder("budget_tool", (Input input) -> "r".repeat(model.toolChars))
				.description("Budget fixture").inputType(Input.class).build();
		}
	}

	record Input(String value) {
	}

	static class RecordingModel implements ChatModel {
		final List<Prompt> prompts = new ArrayList<>();

		int toolChars;

		int toolRounds;

		int rounds;

		boolean duplicateIds;

		@Override
		public ToolCallingChatOptions getOptions() {
			return ToolCallingChatOptions.builder().build();
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			this.prompts.add(prompt);
			AssistantMessage output;
			if (prompt.getOptions() instanceof ToolCallingChatOptions && ++this.rounds <= this.toolRounds) {
				output = AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
						"call_" + (this.duplicateIds ? 1 : this.rounds), "function", "budget_tool", "{\"value\":\"v\"}"))).build();
			}
			else {
				output = new AssistantMessage("done");
			}
			return new ChatResponse(List.of(new Generation(output)));
		}
	}

	abstract class Fixture {
		@Autowired
		Agent agent;

		@Autowired
		AgentSession session;

		@Autowired
		ChatMemory memory;

		@Autowired
		RecordingModel model;

		@Autowired
		JsonlChatMemoryRepository repository;

		@BeforeEach
		void reset() {
			this.session.startNew();
			this.model.prompts.clear();
			this.model.toolChars = 3;
			this.model.toolRounds = 0;
			this.model.rounds = 0;
			this.model.duplicateIds = false;
		}
	}

	abstract class Scenarios extends Fixture {

		@Test
		void ordinaryConversationRetainsNewestTurnWithinTheFinalCount() {
			this.agent.run("first");
			this.model.prompts.clear();
			this.agent.run("second");
			assertThat(this.model.prompts).hasSize(1);
			assertThat(this.model.prompts.getFirst().getInstructions()).extracting(Message::getText)
				.contains("first", "done", "second");
			assertBounded();
		}

		@Test
		void legacySummaryAndHistoryRemainOptionalInEveryRound() throws Exception {
			this.memory.add(this.session.id(), List.of(new UserMessage("covered"), new AssistantMessage("answer"),
					new UserMessage("raw"), new AssistantMessage("answer")));
			this.repository.appendSummary(this.session.id(), new JsonlChatMemoryRepository.Summary("x".repeat(10000), 2));
			this.model.toolRounds = 1;
			this.agent.run("active");
			assertThat(this.model.prompts.stream().filter(p -> p.getOptions() instanceof ToolCallingChatOptions))
				.hasSize(2);
			List<Message> first = this.model.prompts.getFirst().getInstructions();
			assertThat(first).hasSize(3);
			assertThat(first.stream().filter(m -> Boolean.TRUE.equals(m.getMetadata().get("memory.summary"))))
				.hasSize(1);
			assertThat(this.model.prompts.getLast().getInstructions()).hasSize(4)
				.noneMatch(m -> Boolean.TRUE.equals(m.getMetadata().get("memory.summary")));
			assertThat(java.nio.file.Files.readString(dir.resolve(".home/sessions/" + this.session.id() + ".jsonl")))
				.contains("x".repeat(10000));
			assertBounded();
		}

		@Test
		void actualSummaryModelPromptAndFinalRequestAreBothBounded() {
			for (int i = 0; i < 8; i++) {
				this.memory.add(this.session.id(), List.of(new UserMessage("q".repeat(3000)),
						new AssistantMessage("a".repeat(3000))));
			}
			this.agent.run("active");
			assertThat(this.model.prompts).hasSize(2);
			assertThat(this.repository.read(this.session.id()).summary().text().length()).isLessThanOrEqualTo(128);
			assertBounded();
		}

		@Test
		void historyIsRemovedAsWholeGroupsOnSubsequentToolRounds() {
			this.memory.add(this.session.id(), List.of(new UserMessage("old"), new AssistantMessage("answer")));
			this.model.toolRounds = 1;
			this.agent.run("active");
			assertThat(this.model.prompts.stream().filter(p -> p.getOptions() instanceof ToolCallingChatOptions))
				.hasSize(2);
			List<Message> second = this.model.prompts.getLast().getInstructions();
			assertThat(second).hasSize(4);
			assertThat(second).extracting(Message::getText).contains("active").doesNotContain("old", "answer");
			assertThat(second.get(2)).isInstanceOf(AssistantMessage.class);
			assertThat(second.get(3)).isInstanceOf(ToolResponseMessage.class);
			assertBounded();
		}

		@Test
		void intrinsicMandatoryOverflowMakesZeroModelCalls() {
			// Enough history to trigger compaction under the old policy.
			for (int i = 0; i < 5; i++) {
				this.memory.add(this.session.id(), List.of(new UserMessage("old" + i), new AssistantMessage("answer")));
			}
			assertThatThrownBy(() -> this.agent.run("x".repeat(2001))).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-context-chars");
			assertThat(this.model.prompts).isEmpty();
		}

		@Test
		void streamingRefusesTheSameMandatoryOverflowBeforeAModelCall() {
			for (int i = 0; i < 5; i++) {
				this.memory.add(this.session.id(), List.of(new UserMessage("old" + i), new AssistantMessage("answer")));
			}
			assertThatThrownBy(() -> this.agent.stream("x".repeat(2001), text -> { }))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-context-chars");
			assertThat(this.model.prompts).isEmpty();
		}

		@Test
		void oversizedLiveToolDataIsRefusedBeforeTheNextModelDispatch() {
			this.model.toolRounds = 1;
			this.model.toolChars = 2001;
			assertThatThrownBy(() -> this.agent.run("active")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-context-chars");
			assertThat(this.model.prompts).hasSize(1);
			assertBounded();
		}

		@Test
		void activeToolGroupCountOverflowIsRefusedWithoutSplittingIt() {
			this.model.toolRounds = 2;
			assertThatThrownBy(() -> this.agent.run("active")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-messages");
			assertThat(this.model.prompts).hasSize(2);
			assertBounded();
		}

		@Test
		void invalidActiveToolIdsMakeZeroModelCalls() {
			// Exercise the plugin capture boundary using the application's actual advisors.
			var client = org.springframework.ai.chat.client.ChatClient.builder(this.model)
				.defaultAdvisors(this.agent.advisors()).build();
			assertThatThrownBy(() -> client.prompt().system("s").messages(new UserMessage("active"),
					ToolResponseMessage.builder().responses(List.of(
							new ToolResponseMessage.ToolResponse("orphan", "budget_tool", "data"))).build())
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, this.session.id())).call().content())
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IDs");
			assertThat(this.model.prompts).isEmpty();
		}

		@Test
		void duplicateLiveToolIdsAreRefusedBeforeAnotherModelRound() {
			this.model.toolRounds = 2;
			this.model.duplicateIds = true;
			assertThatThrownBy(() -> this.agent.run("active")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("IDs");
			assertThat(this.model.prompts).hasSize(2);
			assertBounded();
		}

		private void assertBounded() {
			MemoryBudgetTest.assertBounded(this.model.prompts, 4, 2000);
		}
	}

	abstract class CompletionScenarios extends Fixture {
		@Autowired
		ToolCallback budgetTool;

		private String run() {
			return ChatClient.builder(this.model).defaultAdvisors(this.agent.advisors())
				.defaultTools(this.budgetTool).build().prompt().system("s").user("active")
				.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, this.session.id())).call().content();
		}

		@Test
		void twoCompleteToolPairsReachSuccessfulThirdDispatch() {
			this.model.toolRounds = 2;
			assertThat(run()).isEqualTo("done");
			assertThat(this.model.prompts).hasSize(3);
			assertThat(this.model.prompts).extracting(p -> p.getInstructions().size()).containsExactly(2, 4, 6);
			assertCompletePairs(this.model.prompts.get(1), 1);
			assertCompletePairs(this.model.prompts.get(2), 2);
			assertBounded(this.model.prompts, 100, 2000);
		}

		@Test
		void charactersAloneEvictOldestCompleteHistoricalGroupDuringSuccessfulToolRounds() {
			AssistantMessage oldCall = AssistantMessage.builder().content("").toolCalls(List.of(
					new AssistantMessage.ToolCall("old_call", "function", "budget_tool", "{}"))).build();
			ToolResponseMessage oldResult = ToolResponseMessage.builder().responses(List.of(
					new ToolResponseMessage.ToolResponse("old_call", "budget_tool", "h".repeat(1700)))).build();
			this.memory.add(this.session.id(), List.of(new UserMessage("old"), oldCall, oldResult,
					new AssistantMessage("old answer"), new UserMessage("recent"), new AssistantMessage("a".repeat(150))));
			this.model.toolRounds = 2;
			this.model.toolChars = 150;
			assertThat(run()).isEqualTo("done");
			assertThat(this.model.prompts).hasSize(3);
			Prompt first = this.model.prompts.getFirst();
			assertThat(first.getInstructions()).contains(oldCall, oldResult);
			// Adding the live pair still fits the count; only characters require eviction.
			assertThat(first.getInstructions().size() + 2).isLessThan(100);
			assertThat(chars(first) + "budget_tool".length() + "{\"value\":\"v\"}".length()
					+ this.model.toolChars + 2).isGreaterThan(2000);
			for (int round = 1; round <= 2; round++) {
				Prompt prompt = this.model.prompts.get(round);
				assertThat(prompt.getInstructions()).extracting(Message::getText)
					.contains("recent", "a".repeat(150)).doesNotContain("old", "old answer");
				assertThat(prompt.getInstructions()).doesNotContain(oldCall, oldResult);
				assertCompletePairs(prompt, round);
			}
			assertThat(this.repository.findByConversationId(this.session.id())).extracting(Message::getText)
				.contains("old", "old answer", "recent");
			assertBounded(this.model.prompts, 100, 2000);
		}

		@Test
		void exactLiveToolCharacterBoundaryCompletesButOneOverStopsBeforeNextDispatch() {
			this.model.toolRounds = 1;
			// system + active + call name/args + JSON string quotes + callback payload.
			this.model.toolChars = 2000 - "s".length() - "active".length()
					- "budget_tool".length() - "{\"value\":\"v\"}".length() - 2;
			assertThat(run()).isEqualTo("done");
			assertThat(this.model.prompts).hasSize(2);
			assertThat(chars(this.model.prompts.getLast())).isEqualTo(2000);
			assertCompletePairs(this.model.prompts.getLast(), 1);
			assertBounded(this.model.prompts, 100, 2000);

			this.session.startNew();
			this.model.prompts.clear();
			this.model.rounds = 0;
			this.model.toolChars++;
			assertThatThrownBy(this::run).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-context-chars");
			assertThat(this.model.prompts).hasSize(1);
			assertBounded(this.model.prompts, 100, 2000);
		}

		private void assertCompletePairs(Prompt prompt, int pairs) {
			List<Message> messages = prompt.getInstructions();
			int active = -1;
			for (int i = 0; i < messages.size(); i++) {
				if (messages.get(i) instanceof UserMessage && "active".equals(messages.get(i).getText())) {
					active = i;
				}
			}
			assertThat(active).isGreaterThanOrEqualTo(1);
			assertThat(messages.getFirst().getText()).isEqualTo("s");
			assertThat(messages.subList(active, messages.size())).hasSize(1 + pairs * 2);
			for (int round = 1; round <= pairs; round++) {
				String id = "call_" + round;
				assertThat(messages.get(active + round * 2 - 1)).isInstanceOfSatisfying(AssistantMessage.class,
						assistant -> assertThat(assistant.getToolCalls()).containsExactly(
								new AssistantMessage.ToolCall(id, "function", "budget_tool", "{\"value\":\"v\"}")));
				assertThat(messages.get(active + round * 2)).isInstanceOfSatisfying(ToolResponseMessage.class,
						tool -> assertThat(tool.getResponses()).containsExactly(
								new ToolResponseMessage.ToolResponse(id, "budget_tool",
										"\"" + "r".repeat(this.model.toolChars) + "\"")));
			}
		}
	}

	private static long chars(Prompt prompt) {
		long chars = 0;
		for (Message message : prompt.getInstructions()) {
			chars += message.getText() == null ? 0 : message.getText().length();
			if (message instanceof AssistantMessage assistant) {
				for (var call : assistant.getToolCalls()) {
					chars += call.name().length() + call.arguments().length();
				}
			}
			if (message instanceof ToolResponseMessage tool) {
				for (var response : tool.getResponses()) {
					chars += response.responseData().length();
				}
			}
		}
		return chars;
	}

	private static void assertBounded(List<Prompt> prompts, int maxMessages, long maxChars) {
		for (Prompt prompt : prompts) {
			assertThat(prompt.getInstructions().size()).isLessThanOrEqualTo(maxMessages);
			assertThat(chars(prompt)).isLessThanOrEqualTo(maxChars);
		}
	}

	@Nested
	@SpringBootTest(classes = MiniAgentApplication.class, properties = { "agent.repl.enabled=false", "agent.plugins.trace.level=off",
			"agent.plugins.fs.enabled=false", "agent.plugins.bash.enabled=false", "agent.plugins.skills.enabled=false",
			"agent.plugins.permission.enabled=false", "agent.plugins.memory.compaction=false",
			"agent.plugins.memory.max-messages=100", "agent.plugins.memory.max-context-chars=2000",
			"agent.plugins.memory.inside-loop=false" })
	@Import({ AgentTestSupport.class, Models.class })
	class CompletionOutside extends CompletionScenarios {
	}

	@Nested
	@SpringBootTest(classes = MiniAgentApplication.class, properties = { "agent.repl.enabled=false", "agent.plugins.trace.level=off",
			"agent.plugins.fs.enabled=false", "agent.plugins.bash.enabled=false", "agent.plugins.skills.enabled=false",
			"agent.plugins.permission.enabled=false", "agent.plugins.memory.compaction=false",
			"agent.plugins.memory.max-messages=100", "agent.plugins.memory.max-context-chars=2000",
			"agent.plugins.memory.inside-loop=true" })
	@Import({ AgentTestSupport.class, Models.class })
	class CompletionInside extends CompletionScenarios {
	}

	@Nested
	@SpringBootTest(classes = MiniAgentApplication.class, properties = { "agent.repl.enabled=false", "agent.plugins.trace.level=off",
			"agent.plugins.fs.enabled=false", "agent.plugins.bash.enabled=false", "agent.plugins.skills.enabled=false",
			"agent.plugins.permission.enabled=false", "agent.plugins.memory.compaction=true",
			"agent.plugins.memory.max-messages=4", "agent.plugins.memory.max-context-chars=2000",
			"agent.plugins.memory.max-summary-request-chars=2000", "agent.plugins.memory.max-summary-chars=128",
			"agent.plugins.memory.inside-loop=false" })
	@Import({ AgentTestSupport.class, Models.class })
	class Outside extends Scenarios {
	}

	@Nested
	@SpringBootTest(classes = MiniAgentApplication.class, properties = { "agent.repl.enabled=false", "agent.plugins.trace.level=off",
			"agent.plugins.fs.enabled=false", "agent.plugins.bash.enabled=false", "agent.plugins.skills.enabled=false",
			"agent.plugins.permission.enabled=false", "agent.plugins.memory.compaction=true",
			"agent.plugins.memory.max-messages=4", "agent.plugins.memory.max-context-chars=2000",
			"agent.plugins.memory.max-summary-request-chars=2000", "agent.plugins.memory.max-summary-chars=128",
			"agent.plugins.memory.inside-loop=true" })
	@Import({ AgentTestSupport.class, Models.class })
	class Inside extends Scenarios {
	}
}
