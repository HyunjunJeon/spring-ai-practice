package com.example.miniagent.plugin.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.ToolContextKeys;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.ProviderAuth;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.model.registry.ModelRegistry;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunSpec;
import com.example.miniagent.core.run.RunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderPrivateFieldFilterTest {
	@TempDir
	Path home;

	@Test
	void legacyRowDropsSignature() throws Exception {
		assertReplay(Map.of("claude.content", "signed", "reasoningContent", "opaque"), false);
	}

	@Test
	void sameModelRestoresPrivateFieldsWithoutRewritingHistory() throws Exception {
		assertReplay(Map.of("claude.content", "signed", "reasoningContent", "opaque",
				ToolContextKeys.MODEL_PROVIDER, "fake", ToolContextKeys.MODEL_NAME, "a"), true);
	}

	@Test
	void compactionUsesCapturedModelWithoutAnyGlobalModelBean() throws Exception {
		var ref = new ModelRef("fake", "a");
		AtomicReference<Prompt> summary = new AtomicReference<>();
		ModelProvider provider = new ModelProvider() {
			@Override public String providerId() { return "fake"; }
			@Override public List<ModelEntry> models() { return List.of(new ModelEntry(ref, "fake", null)); }
			@Override public ProviderAuth auth() { return () -> true; }
			@Override public ChatModel create(ModelRef selected, Map<String, String> options) {
				return prompt -> {
					summary.set(prompt);
					return new ChatResponse(List.of(new Generation(new AssistantMessage("compressed"))));
				};
			}
		};
		var registry = new ModelRegistry(List.of(provider), ChatClient::create);
		var repository = new JsonlChatMemoryRepository(this.home);
		repository.append("session", List.of(new UserMessage("one"), new AssistantMessage("first"),
				new UserMessage("two"), new AssistantMessage("second"),
				new UserMessage("three"), new AssistantMessage("third")));
		Path file = this.home.resolve("session.jsonl");
		byte[] original = Files.readAllBytes(file);
		var noModels = new StaticListableBeanFactory().getBeanProvider(ChatModel.class);
		var console = new AgentConsole(new java.io.BufferedReader(new java.io.StringReader("")),
				new java.io.PrintStream(java.io.OutputStream.nullOutputStream()), false);
		var memory = new MemoryPluginAutoConfiguration().chatMemory(repository, noModels, console,
				3, 200000, 32000, 8000, true);
		var context = new TurnContext("session", "turn", this.home, registry.bind(new ModelSelection(ref)));
		var runId = RunId.random();
		var turn = new TurnContext("session", "turn", this.home, context.binding(), runId.value(), "attempt");
		var spec = new RunSpec(1, runId, "session", "parent", null, null, ref,
				"fake", this.home.toString(), RunBudget.defaults(),
				new ModelSelection(ref).optionsFingerprint(), "a".repeat(64), Instant.EPOCH);
		try (var writer = new FileRunStore(this.home).create(spec)) {
			writer.append(new RunEvent.StateChanged(Instant.EPOCH, RunState.RUNNING, RunEvent.Reason.NONE));
			writer.append(new RunEvent.AttemptStarted(Instant.EPOCH, "attempt", turn.binding().bindingId()));
			var run = new RunContext(spec, "attempt", writer, turn, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
			try (var runScope = run.open(); var scope = turn.open()) {
				assertThat(memory.get("session")).hasSizeLessThanOrEqualTo(3);
			}
			assertThat(writer.record().usage().tokensUnknown()).isTrue();
			assertThat(writer.record().sequence()).isEqualTo(3);
		}
		assertThat(summary.get()).isNotNull();
		assertThat(summary.get().getContents()).contains("one");
		assertThat(repository.read("session").summary().text()).isEqualTo("compressed");
		assertThat(java.util.Arrays.copyOf(Files.readAllBytes(file), original.length)).isEqualTo(original);
		assertThat(TurnContext.current()).isEmpty();
	}

	private void assertReplay(Map<String, Object> metadata, boolean retained) throws Exception {
		var repository = new JsonlChatMemoryRepository(this.home);
		repository.append("session", List.of(new UserMessage("earlier"),
				AssistantMessage.builder().content("public").properties(metadata).build()));
		Path file = this.home.resolve("session.jsonl");
		byte[] before = Files.readAllBytes(file);
		var ref = new ModelRef("fake", "a");
		var provider = new ModelProvider() {
			@Override public String providerId() { return "fake"; }
			@Override public List<ModelEntry> models() { return List.of(new ModelEntry(ref, "fake", null)); }
			@Override public ProviderAuth auth() { return () -> true; }
			@Override public Set<String> privateMetadataKeys() { return Set.of("claude.content", "reasoningContent"); }
			@Override public ChatModel create(ModelRef selected, Map<String, String> options) {
				return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("summary"))));
			}
		};
		var registry = new ModelRegistry(List.of(provider), ChatClient::create);
		var context = new TurnContext("session", "turn", this.home, registry.bind(new ModelSelection(ref)));
		var memory = new JsonlChatMemory(repository, 100, 200000, null, ignored -> { });
		try (var scope = context.open()) {
			var assistant = memory.get("session").get(1);
			assertThat(assistant.getText()).isEqualTo("public");
			if (retained) {
				assertThat(assistant.getMetadata()).containsEntry("claude.content", "signed");
			}
			else {
				assertThat(assistant.getMetadata()).doesNotContainKeys("claude.content", "reasoningContent");
			}
		}
		assertThat(Files.readAllBytes(file)).isEqualTo(before);
		assertThat(TurnContext.current()).isEmpty();
	}
}
