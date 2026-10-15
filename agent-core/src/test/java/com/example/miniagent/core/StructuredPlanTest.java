package com.example.miniagent.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredPlanTest {

	@Test
	void toolCallBecomesThePlan() {
		var call = new AssistantMessage.ToolCall("call_1", "function", "submit_plan",
				"{\"goal\":\"ship it\",\"steps\":[\"write\",\"test\"]}");
		ChatModel model = scripted(AssistantMessage.builder().content("").toolCalls(List.of(call)).build());

		StructuredPlan.Plan plan = StructuredPlan.request(model, "ship it");

		assertThat(plan.goal()).isEqualTo("ship it");
		assertThat(plan.steps()).containsExactly("write", "test");
		assertThat(StructuredPlan.render(plan)).isEqualTo("ship it\n1. write\n2. test");
	}

	@Test
	void markdownAnswerWithoutTheToolIsRejected() {
		ChatModel model = scripted(new AssistantMessage("# 계획\n1. 쓰기"));

		assertThatThrownBy(() -> StructuredPlan.request(model, "ship it"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("submit_plan");
	}

	private static ChatModel scripted(AssistantMessage output) {
		return new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				var metadata = ChatGenerationMetadata.builder()
					.finishReason(output.hasToolCalls() ? "TOOL_CALLS" : "STOP")
					.build();
				return new ChatResponse(List.of(new Generation(output, metadata)));
			}

			@Override
			public ToolCallingChatOptions getOptions() {
				return ToolCallingChatOptions.builder().model("plan-test").build();
			}
		};
	}

}
