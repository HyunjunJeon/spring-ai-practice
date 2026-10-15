package com.example.miniagent.plugin.memory;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.ConsoleApprovalBroker;
import com.example.miniagent.core.change.StagingToolInterceptor;
import com.example.miniagent.core.delegation.*;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.model.registry.ModelRegistry;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class ChildMemoryIsolationTest {
	@TempDir Path directory;

	@Test void memoryInsideOutsideAndOffUseOnlyActualChildIds() throws Exception {
		for (String mode : List.of("inside", "outside", "off")) {
			Path home = Files.createTempDirectory(directory, "home-");
			Path workspace = Files.createTempDirectory(directory, "work-");
			var properties = new AgentProperties();
			properties.setHome(home);
			var console = new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(java.io.OutputStream.nullOutputStream()), false);
			var repository = new JsonlChatMemoryRepository(home.resolve("sessions"));
			repository.append("parent", List.of(new UserMessage("parent private history")));
			Path parentFile = home.resolve("sessions/parent.jsonl");
			byte[] parentBytes = Files.readAllBytes(parentFile);
			new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MemoryPluginAutoConfiguration.class))
				.withBean(AgentProperties.class, () -> properties).withBean(AgentConsole.class, () -> console)
				.withPropertyValues("agent.plugins.memory.enabled=" + !mode.equals("off"),
					"agent.plugins.memory.inside-loop=" + mode.equals("inside"),
					"agent.plugins.memory.compaction=false")
				.run(spring -> {
					assertThat(spring).hasNotFailed();
					try {
						var prompts = new ArrayList<Prompt>();
						var ref = new ModelRef("fake", "child");
						var count = new AtomicInteger();
						ChatModel model = new ChatModel() {
							@Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
								return ToolCallingChatOptions.builder().build();
							}
							@Override public ChatResponse call(Prompt prompt) {
								prompts.add(prompt);
								if (count.getAndIncrement() == 0) {
									return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
										.toolCalls(List.of(new AssistantMessage.ToolCall("read", "function", "read_file",
											"{\"path\":\"source.txt\"}"))).build())));
								}
								return new ChatResponse(List.of(new Generation(new AssistantMessage("child answer"))));
							}
						};
						var registry = new ModelRegistry(List.of(provider(ref, model)), ChatClient::create);
						var selections = new FileSessionModelStore(home);
						var session = new AgentSession();
						session.switchTo("parent");
						var agent = new Agent(registry, selections, new ModelSelection(ref), session, workspace, "system",
							List.of(), List.of(), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
						var parent = new TurnContext("parent", UUID.randomUUID().toString(), workspace,
							registry.bind(new ModelSelection(ref)));
						Files.writeString(workspace.resolve("source.txt"), "local source");
						ToolCallback callback = new ToolCallback() {
							final ToolDefinition definition = ToolDefinition.builder().name("read_file").description("read local")
								.inputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}").build();
							@Override public ToolDefinition getToolDefinition() { return definition; }
							@Override public String call(String input) { throw new AssertionError("Context required"); }
							@Override public String call(String input, ToolContext tools) {
								assertThat(tools.getContext().get(ToolContextKeys.SESSION_ID)).isNotEqualTo("parent");
								try {
									return Files.readString(com.example.miniagent.core.change.StagedWorkspace.from(tools)
										.orElseThrow().resolve("source.txt", false, List.of()));
								}
								catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
							}
						};
						var definition = callback.getToolDefinition();
						StagingToolInterceptor.Policy policy = () -> Map.of("read_file",
							new StagingToolInterceptor.Declaration(SideEffectClass.definitionDigest(definition),
								StagingToolInterceptor.Kind.READ, List.of()));
						var contributions = mode.equals("off") ? List.<ChildScopeFactory.AdvisorContribution>of()
							: List.of(spring.getBean(ChildScopeFactory.AdvisorContribution.class));
						var factory = new ChildScopeFactory(agent, home, new FileRunStore(home), new RunRequestStore(home),
							selections, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new ConsoleApprovalBroker(console),
							List.of(callback), List.of(), List.of(new RunBudgetInterceptor(), new ToolJournalInterceptor()),
							List.of(policy), List.of(() -> Map.of("read_file", SideEffectClass.definitionDigest(definition))),
							contributions);
						var authority = new ToolScope(Set.of("read_file"), Set.of(workspace), Set.of(), Set.of(),
							false, false, false, false);
						var child = new ChildSpec("child", "child task", ChildRole.RESEARCH, null, Set.of(),
							authority, RunBudget.defaults(), 1, true);
						try (var scope = factory.create(parent, child, authority)) {
							assertThat(scope.call().record().state()).isEqualTo(RunState.COMPLETED);
							assertThat(prompts).hasSize(2);
							assertThat(prompts.stream().flatMap(prompt -> prompt.getInstructions().stream()))
								.noneMatch(message -> String.valueOf(message.getText()).contains("parent private"));
							List<Message> saved = repository.findByConversationId(scope.context().sessionId());
							if (mode.equals("off")) assertThat(saved).isEmpty();
							else {
								assertThat(saved).anyMatch(message -> "child task".equals(message.getText()))
									.anyMatch(message -> "child answer".equals(message.getText()));
								assertThat(saved.stream().filter(AssistantMessage.class::isInstance)
									.map(AssistantMessage.class::cast).anyMatch(AssistantMessage::hasToolCalls))
									.isEqualTo(mode.equals("inside"));
							}
							assertThat(selections.load(scope.context().sessionId())).contains(new ModelSelection(ref));
						}
						assertThat(Files.readAllBytes(parentFile)).isEqualTo(parentBytes);
						assertThat(session.id()).isEqualTo("parent");
						assertThat(selections.load("parent")).isEmpty();
					}
					catch (Exception ex) { throw new AssertionError(ex); }
				});
		}
	}

	@Test void scopedCompactionUsesCapturedChildModelAndCannotWriteParent() {
		var ref = new ModelRef("fake", "child");
		var summaries = new AtomicInteger();
		ChatModel model = prompt -> {
			summaries.incrementAndGet();
			return new ChatResponse(List.of(new Generation(new AssistantMessage("child summary"))));
		};
		var registry = new ModelRegistry(List.of(provider(ref, model)), ChatClient::create);
		var turn = new TurnContext(UUID.randomUUID().toString(), UUID.randomUUID().toString(), directory,
			registry.bind(new ModelSelection(ref)));
		var repository = new JsonlChatMemoryRepository(directory.resolve("sessions"));
		repository.append("parent", List.of(new UserMessage("parent private")));
		repository.append(turn.sessionId(), List.of(new UserMessage("old"), new AssistantMessage("old answer"),
			new UserMessage("recent"), new AssistantMessage("recent answer"), new UserMessage("active")));
		var memory = new ScopeMemory(new JsonlChatMemory(repository, 3, 20000,
			new ChatModelSummarizer(() -> turn.binding().chatModel()), ignored -> {}), () -> turn);
		try (var ignored = turn.open()) {
			assertThatThrownBy(() -> memory.get("parent")).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> memory.add("parent", List.of(new UserMessage("leak"))))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> memory.clear("parent")).isInstanceOf(IllegalArgumentException.class);
			memory.get(turn.sessionId());
			assertThat(summaries).hasValue(1);
			assertThat(repository.read(turn.sessionId()).summary()).isNotNull();
			assertThat(repository.read("parent").summary()).isNull();
			assertThat(repository.findByConversationId("parent")).extracting(Message::getText).containsExactly("parent private");
		}
		assertThatThrownBy(() -> memory.get(turn.sessionId())).isInstanceOf(IllegalArgumentException.class);
	}

	private static ModelProvider provider(ModelRef ref, ChatModel model) {
		return new ModelProvider() {
			@Override public String providerId() { return ref.provider(); }
			@Override public List<ModelEntry> models() { return List.of(new ModelEntry(ref, "fake", null)); }
			@Override public ProviderAuth auth() { return () -> true; }
			@Override public ChatModel create(ModelRef selected, Map<String, String> options) {
				return new ChatModel() {
					@Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() { return model.getOptions(); }
					@Override public ChatResponse call(Prompt prompt) { return model.call(prompt); }
				};
			}
		};
	}
}
