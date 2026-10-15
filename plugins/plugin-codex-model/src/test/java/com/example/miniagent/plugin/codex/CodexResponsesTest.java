package com.example.miniagent.plugin.codex;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 네트워크 없이 요청/응답 변환만 검증한다. */
class CodexResponsesTest {

	private final JsonMapper json = JsonMapper.builder().build();

	record EchoInput(String text) {
	}

	@Test
	void promptIsMappedToResponsesRequest() {
		var echo = FunctionToolCallback.<EchoInput, String>builder("echo", (in, ctx) -> in.text())
			.description("Echo text")
			.inputType(EchoInput.class)
			.build();
		var prompt = new Prompt(List.of(new SystemMessage("be brief"), new UserMessage("hi"),
				AssistantMessage.builder()
					.content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "echo", "{\"text\":\"a\"}")))
					.build(),
				ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "echo", "a"))).build()),
				ToolCallingChatOptions.builder().toolCallbacks(echo).build());

		ObjectNode body = CodexResponses.toRequest(prompt, "gpt-5.5", "low", "session-1", this.json);

		assertThat(body.path("instructions").asString()).isEqualTo("be brief");
		assertThat(body.path("store").asBoolean()).isFalse();
		assertThat(body.path("stream").asBoolean()).isTrue();
		assertThat(body.path("prompt_cache_key").asString()).isEqualTo("session-1");
		assertThat(body.path("reasoning").path("effort").asString()).isEqualTo("low");

		JsonNode input = body.path("input");
		assertThat(input.path(0).path("role").asString()).isEqualTo("user");
		assertThat(input.path(1).path("type").asString()).isEqualTo("function_call");
		assertThat(input.path(2).path("type").asString()).isEqualTo("function_call_output");
		// call_id 가 ToolCall.id → ToolResponse.id 로 왕복해야 모델이 결과를 짝지을 수 있다.
		assertThat(input.path(1).path("call_id").asString()).isEqualTo(input.path(2).path("call_id").asString());

		JsonNode tool = body.path("tools").path(0);
		assertThat(tool.path("name").asString()).isEqualTo("echo");
		assertThat(tool.path("strict").asBoolean()).isFalse();
		assertThat(tool.path("parameters").path("properties").has("text")).isTrue();
	}

	@Test
	void sseWithFunctionCallBecomesToolCallResponse() {
		List<String> sse = List.of("event: response.created", "data: {\"type\":\"response.created\"}", "",
				"data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"xyz\",\"summary\":[]}}",
				"data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_9\",\"name\":\"list_dir\",\"arguments\":\"{\\\"path\\\":\\\".\\\"}\"}}",
				"data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":100,\"output_tokens\":20,\"total_tokens\":120,\"input_tokens_details\":{\"cached_tokens\":64}}}}");

		CodexResponses.Result result = CodexResponses.parseSse(sse.iterator(), this.json);
		ChatResponse response = CodexResponses.toChatResponse(result, "gpt-5.5", this.json);

		assertThat(response.hasToolCalls()).isTrue();
		AssistantMessage output = response.getResult().getOutput();
		assertThat(output.getToolCalls().get(0).id()).isEqualTo("call_9");
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(100);
		assertThat((String) output.getMetadata().get(CodexResponses.OUTPUT_ITEMS)).contains("rs_1", "encrypted_content");
	}

	@Test
	void rawOutputItemsAreReplayedVerbatimInTheNextRound() {
		String raw = "[{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"xyz\",\"summary\":[]},"
				+ "{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_9\",\"name\":\"list_dir\",\"arguments\":\"{}\"}]";
		var assistant = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call_9", "function", "list_dir", "{}")))
			.properties(Map.of(CodexResponses.OUTPUT_ITEMS, raw))
			.build();

		ObjectNode body = CodexResponses.toRequest(new Prompt(List.of(new UserMessage("q"), assistant)), "m", null,
				null, this.json);

		JsonNode input = body.path("input");
		assertThat(input.path(1).path("type").asString()).isEqualTo("reasoning");
		assertThat(input.path(2).path("id").asString()).isEqualTo("fc_1");
		assertThat(body.has("reasoning")).isFalse();
	}

	@Test
	void finalAnswerPhaseWinsOverCommentary() {
		List<String> sse = List.of(
				"data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"phase\":\"commentary\",\"content\":[{\"type\":\"output_text\",\"text\":\"checking...\"}]}}",
				"data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"phase\":\"final_answer\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]}}",
				"data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");

		ChatResponse response = CodexResponses.toChatResponse(CodexResponses.parseSse(sse.iterator(), this.json), "m",
				this.json);

		assertThat(response.getResult().getOutput().getText()).isEqualTo("done");
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
	}

	@Test
	void errorEventIsSurfaced() {
		List<String> sse = List.of("data: {\"type\":\"error\",\"message\":\"model not supported\"}");

		assertThatThrownBy(() -> CodexResponses.parseSse(sse.iterator(), this.json))
			.hasMessageContaining("model not supported");
	}

}
