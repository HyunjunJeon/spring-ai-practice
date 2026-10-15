package com.example.miniagent.core.model.registry;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ToolContextKeys;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderPrivateFieldFilterTest {
	private static final ModelRef TARGET = new ModelRef("zai", "glm-test");
	private static final Set<String> KEYS = Set.of("reasoningContent", "claude.content", "codex.outputItems");

	@Test
	void sameRefRetainsOpaqueFieldsAndOriginalMessage() {
		var message = assistant(TARGET);
		assertThat(ProviderPrivateFieldFilter.filter(List.of(message), TARGET, KEYS))
			.containsExactly(message);
		assertThat(ProviderPrivateFieldFilter.filter(List.of(message), TARGET, KEYS).getFirst())
			.isSameAs(message);
	}

	@Test
	void foreignModelRemovesPrivateFieldsButPreservesPublicTextAndToolPair() {
		var message = assistant(new ModelRef("zai", "other"));
		var tool = ToolResponseMessage.builder().responses(List.of(
				new ToolResponseMessage.ToolResponse("call1", "read_file", "actual result"))).build();
		var result = ProviderPrivateFieldFilter.filter(List.of(message, tool), TARGET, KEYS);
		assertThat(result.getFirst().getMetadata()).doesNotContainKeys(KEYS.toArray(String[]::new))
			.containsEntry("public", "value");
		assertThat(result.getFirst().getText()).isEqualTo("public answer");
		assertThat(((AssistantMessage) result.getFirst()).getToolCalls()).isEqualTo(message.getToolCalls());
		assertThat(result.get(1)).isSameAs(tool);
		assertThat(message.getMetadata()).containsEntry("reasoningContent", "private");
	}

	@Test
	void legacyRowDropsSignature() {
		var legacy = AssistantMessage.builder().content("legacy")
			.properties(Map.of("claude.content", "signed", "reasoningContent", "private")).build();
		assertThat(ProviderPrivateFieldFilter.filter(List.of(legacy), TARGET, KEYS).getFirst().getMetadata())
			.doesNotContainKeys("claude.content", "reasoningContent");
		assertThat(legacy.getMetadata()).containsEntry("claude.content", "signed");
	}

	@Test
	void wrapperFiltersRealDispatchAndStampsEveryOutput() {
		AtomicReference<List<Message>> received = new AtomicReference<>();
		ChatModel raw = prompt -> {
			received.set(prompt.getInstructions());
			return new ChatResponse(List.of(new Generation(assistant(TARGET))));
		};
		var model = new ProvenanceChatModel(raw, TARGET, KEYS);
		ChatResponse response = model.call(new Prompt(List.of(assistant(new ModelRef("claude", "other")))));
		assertThat(received.get().getFirst().getMetadata()).doesNotContainKeys(KEYS.toArray(String[]::new));
		assertThat(response.getResult().getOutput().getMetadata())
			.containsEntry(ToolContextKeys.MODEL_PROVIDER, TARGET.provider())
			.containsEntry(ToolContextKeys.MODEL_NAME, TARGET.model())
			.containsEntry("reasoningContent", "private");
	}

	private static AssistantMessage assistant(ModelRef ref) {
		return AssistantMessage.builder().content("public answer")
			.properties(Map.of(ToolContextKeys.MODEL_PROVIDER, ref.provider(), ToolContextKeys.MODEL_NAME, ref.model(),
					"public", "value", "reasoningContent", "private", "claude.content", "signed",
					"codex.outputItems", "encrypted"))
			.toolCalls(List.of(new AssistantMessage.ToolCall("call1", "function", "read_file", "{}"))).build();
	}
}
