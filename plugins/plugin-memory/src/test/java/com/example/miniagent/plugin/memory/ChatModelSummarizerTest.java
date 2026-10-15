package com.example.miniagent.plugin.memory;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 요약 요청이 어떻게 만들어지는지: 지침, 이전 요약, 메시지 기록(긴 도구 결과는 잘림). */
class ChatModelSummarizerTest {

	@Test
	void exactSummaryRequestAndOutputCapsIncludeInstructionsAndMarkers() {
		List<Prompt> prompts = new ArrayList<>();
		ChatModel model = prompt -> {
			prompts.add(prompt);
			return new ChatResponse(List.of(new Generation(new AssistantMessage("  " + "x".repeat(127) + "😀z  "))));
		};
		String summary = new ChatModelSummarizer(() -> model, 2000, 128).summarize("p".repeat(50000),
				List.of(new UserMessage("q".repeat(50000))));
		assertThat(MemoryBudget.length(prompts.getFirst().getInstructions())).isEqualTo(2000);
		assertThat(summary).isEqualTo("x".repeat(127));
		assertThatThrownBy(() -> new ChatModelSummarizer(() -> model,
				ChatModelSummarizer.MIN_REQUEST_CHARS - 1, 128)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("max-summary-request-chars");
		new ChatModelSummarizer(() -> model, ChatModelSummarizer.MIN_REQUEST_CHARS, 1)
			.summarize("long".repeat(100), List.of(new UserMessage("q".repeat(1000))));
		assertThat(MemoryBudget.length(prompts.getLast().getInstructions()))
			.isLessThanOrEqualTo(ChatModelSummarizer.MIN_REQUEST_CHARS);
		assertThatThrownBy(() -> new ChatModelSummarizer(() -> model, 2000, 0))
			.hasMessageContaining("max-summary-chars");
	}

	@Test
	void scalarAbbreviationDoesNotSplitSurrogatesAndBlankRepliesFail() {
		List<Prompt> prompts = new ArrayList<>();
		ChatModel model = prompt -> {
			prompts.add(prompt);
			return new ChatResponse(List.of(new Generation(new AssistantMessage(" \n "))));
		};
		assertThatThrownBy(() -> new ChatModelSummarizer(() -> model).summarize(null,
				List.of(new UserMessage("x".repeat(1999) + "😀tail")))).isInstanceOf(IllegalStateException.class);
		assertThat(prompts.getFirst().getUserMessage().getText()).contains("x".repeat(1999) + "…")
			.doesNotContain("\uD83D");
	}

	@Test
	void completeRequestAndAcceptedOutputHaveDefaultHardBounds() {
		List<Prompt> prompts = new ArrayList<>();
		ChatModel model = prompt -> {
			prompts.add(prompt);
			return new ChatResponse(List.of(new Generation(new AssistantMessage("x".repeat(10000)))));
		};
		String summary = new ChatModelSummarizer(() -> model).summarize("p".repeat(100000),
				java.util.stream.IntStream.range(0, 1000)
					.mapToObj(i -> (Message) new UserMessage("q".repeat(10000))).toList());
		assertThat(prompts).hasSize(1);
		assertThat(prompts.get(0).getInstructions().stream().mapToInt(m -> m.getText().length()).sum())
			.isLessThanOrEqualTo(32000);
		assertThat(summary.length()).isLessThanOrEqualTo(8000);
	}

	@Test
	void requestCarriesInstructionsPreviousSummaryAndAbbreviatedTranscript() {
		List<Prompt> prompts = new ArrayList<>();
		ChatModel model = new ChatModel() {

			@Override
			public ChatResponse call(Prompt prompt) {
				prompts.add(prompt);
				return new ChatResponse(List.of(new Generation(new AssistantMessage("  - 요약  "))));
			}

		};
		List<Message> messages = List.of(new UserMessage("q1"),
				AssistantMessage.builder()
					.content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "list_dir", "{\"path\":\".\"}")))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "list_dir", "x".repeat(3000))))
					.build(),
				new AssistantMessage("a1"));

		String summary = new ChatModelSummarizer(() -> model).summarize("old", messages);

		assertThat(summary).isEqualTo("- 요약");
		Prompt prompt = prompts.get(0);
		assertThat(prompt.getSystemMessage().getText()).isEqualTo(ChatModelSummarizer.INSTRUCTIONS);
		assertThat(prompt.getUserMessage().getText()).contains("Previous summary:\nold", "USER: q1",
				"ASSISTANT called list_dir {\"path\":\".\"}", "ASSISTANT: a1")
			.contains("TOOL RESULT list_dir: " + "x".repeat(ChatModelSummarizer.MAX_MESSAGE_CHARS) + "…")
			.doesNotContain("x".repeat(ChatModelSummarizer.MAX_MESSAGE_CHARS + 1));
	}

}
