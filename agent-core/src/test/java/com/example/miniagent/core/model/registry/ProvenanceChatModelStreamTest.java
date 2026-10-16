package com.example.miniagent.core.model.registry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ToolContextKeys;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The streaming path the TUI uses every turn: history filtering on the way out, provenance stamping on the way back. */
class ProvenanceChatModelStreamTest {
	private static final ModelRef TARGET = new ModelRef("zai", "glm-test");
	private static final Set<String> KEYS = Set.of("reasoningContent", "claude.content");

	@Test
	void streamedHistoryIsFilteredForForeignMessagesOnly() {
		StreamingStub raw = new StreamingStub(List.of(chunk("done", Map.of())));
		var model = new ProvenanceChatModel(raw, TARGET, KEYS);
		var user = new UserMessage("question");
		var foreign = assistant("foreign answer", new ModelRef("claude", "sonnet"));
		var own = assistant("own answer", TARGET);
		var tool = ToolResponseMessage.builder().responses(List.of(
				new ToolResponseMessage.ToolResponse("call1", "read_file", "result"))).build();
		ChatOptions options = ToolCallingChatOptions.builder().build();

		drain(model.stream(new Prompt(List.of(user, foreign, own, tool), options)));

		List<Message> sent = raw.received.get().getInstructions();
		assertThat(sent).hasSize(4);
		assertThat(sent.get(0)).isSameAs(user);
		assertThat(sent.get(1).getMetadata()).doesNotContainKeys(KEYS.toArray(String[]::new))
				.containsEntry("public", "value");
		assertThat(sent.get(1).getText()).isEqualTo("foreign answer");
		assertThat(sent.get(2)).isSameAs(own);
		assertThat(sent.get(2).getMetadata()).containsKeys(KEYS.toArray(String[]::new));
		assertThat(sent.get(3)).isSameAs(tool);
		assertThat(raw.received.get().getOptions()).isSameAs(options);
		assertThat(foreign.getMetadata()).containsKeys(KEYS.toArray(String[]::new));
	}

	@Test
	void everyEmittedChunkCarriesTheBoundProviderAndModelAndKeepsItsOwnContent() {
		ChatResponseMetadata usage = ChatResponseMetadata.builder().usage(new DefaultUsage(3, 2)).build();
		StreamingStub raw = new StreamingStub(List.of(
				chunk("one ", Map.of("reasoningContent", "thinking")),
				chunk("two ", Map.of(ToolContextKeys.MODEL_PROVIDER, "claude", ToolContextKeys.MODEL_NAME, "other")),
				new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("three").build())), usage)));
		var model = new ProvenanceChatModel(raw, TARGET, KEYS);

		List<ChatResponse> chunks = drain(model.stream(new Prompt(new UserMessage("go"))));

		assertThat(chunks).extracting(chunk -> chunk.getResult().getOutput().getText())
				.containsExactly("one ", "two ", "three");
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getResult().getOutput().getMetadata())
				.containsEntry(ToolContextKeys.MODEL_PROVIDER, "zai")
				.containsEntry(ToolContextKeys.MODEL_NAME, "glm-test"));
		assertThat(chunks.get(0).getResult().getOutput().getMetadata()).containsEntry("reasoningContent", "thinking");
		assertThat(chunks.get(2).getMetadata().getUsage().getTotalTokens()).isEqualTo(5);
	}

	@Test
	void usageOnlyChunkWithoutGenerationsPassesThroughWithItsUsage() {
		ChatResponseMetadata usage = ChatResponseMetadata.builder().usage(new DefaultUsage(7, 4)).build();
		StreamingStub raw = new StreamingStub(List.of(chunk("text", Map.of()), new ChatResponse(List.of(), usage)));

		List<ChatResponse> chunks = drain(new ProvenanceChatModel(raw, TARGET, KEYS).stream(
				new Prompt(new UserMessage("go"))));

		assertThat(chunks).hasSize(2);
		assertThat(chunks.get(1).getResults()).isEmpty();
		assertThat(chunks.get(1).getMetadata().getUsage().getTotalTokens()).isEqualTo(11);
	}

	@Test
	void replyStreamedByThisModelKeepsItsPrivateFieldsWhenReplayedToItNextTurn() {
		StreamingStub first = new StreamingStub(List.of(
				chunk("signed reply", Map.of("reasoningContent", "thinking", "claude.content", "signed"))));
		var model = new ProvenanceChatModel(first, TARGET, KEYS);
		AssistantMessage reply = drain(model.stream(new Prompt(new UserMessage("turn one")))).getFirst()
				.getResult().getOutput();

		StreamingStub second = new StreamingStub(List.of(chunk("next", Map.of())));
		drain(new ProvenanceChatModel(second, TARGET, KEYS).stream(
				new Prompt(List.of(new UserMessage("turn one"), reply, new UserMessage("turn two")))));
		assertThat(second.received.get().getInstructions().get(1)).isSameAs(reply);
		assertThat(second.received.get().getInstructions().get(1).getMetadata())
				.containsEntry("reasoningContent", "thinking").containsEntry("claude.content", "signed");

		// The same reply, replayed to a different model, loses the provider-private fields.
		StreamingStub switched = new StreamingStub(List.of(chunk("next", Map.of())));
		drain(new ProvenanceChatModel(switched, new ModelRef("zai", "other"), KEYS).stream(
				new Prompt(List.of(new UserMessage("turn one"), reply, new UserMessage("turn two")))));
		assertThat(switched.received.get().getInstructions().get(1).getMetadata())
				.doesNotContainKeys("reasoningContent", "claude.content");
	}

	@Test
	void providerStreamFailureReachesTheSubscriberUnchanged() {
		IllegalStateException failure = new IllegalStateException("provider down");
		StreamingStub raw = new StreamingStub(List.of());
		raw.failure = failure;
		var model = new ProvenanceChatModel(raw, TARGET, KEYS);

		assertThatThrownBy(() -> drain(model.stream(new Prompt(new UserMessage("go"))))).isSameAs(failure);
	}

	@Test
	void optionsComeFromTheWrappedModel() {
		StreamingStub raw = new StreamingStub(List.of());
		assertThat(new ProvenanceChatModel(raw, TARGET, KEYS).getOptions()).isSameAs(raw.options);
	}

	private static List<ChatResponse> drain(Flux<ChatResponse> stream) {
		return stream.collectList().block(Duration.ofSeconds(5));
	}

	private static ChatResponse chunk(String text, Map<String, Object> metadata) {
		return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text)
				.properties(metadata).build())));
	}

	private static AssistantMessage assistant(String text, ModelRef stampedBy) {
		return AssistantMessage.builder().content(text)
				.properties(Map.of(ToolContextKeys.MODEL_PROVIDER, stampedBy.provider(),
						ToolContextKeys.MODEL_NAME, stampedBy.model(), "public", "value",
						"reasoningContent", "private", "claude.content", "signed")).build();
	}

	private static final class StreamingStub implements ChatModel {
		private final List<ChatResponse> chunks;

		private final AtomicReference<Prompt> received = new AtomicReference<>();

		private final ChatOptions options = ToolCallingChatOptions.builder().build();

		private RuntimeException failure;

		private StreamingStub(List<ChatResponse> chunks) {
			this.chunks = chunks;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			throw new AssertionError("the streaming path must not call the blocking API");
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			this.received.set(prompt);
			return this.failure == null ? Flux.fromIterable(this.chunks) : Flux.error(this.failure);
		}

		@Override
		public ChatOptions getOptions() {
			return this.options;
		}
	}
}
