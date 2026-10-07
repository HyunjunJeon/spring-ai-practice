package com.example.miniagent.plugin.claude;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.miniagent.core.TraceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeMessagesTest {

	private final JsonMapper json = JsonMapper.builder().build();

	record EchoInput(String text) {
	}

	private Prompt prompt(List<org.springframework.ai.chat.messages.Message> messages) {
		var tool = FunctionToolCallback.<EchoInput, String>builder("read", input -> input.text())
			.description("Echo").inputType(EchoInput.class).build();
		return new Prompt(messages, ToolCallingChatOptions.builder().toolCallbacks(tool).build());
	}

	@Test
	void schemasHistoryAndParallelToolResultsUseNativeWireFormat() {
		String foreignId = "id.with.dots/" + "a".repeat(80);
		var assistant = AssistantMessage.builder().content("checking")
			.toolCalls(List.of(new AssistantMessage.ToolCall(foreignId, "function", "read", "{\"text\":\"a\"}"))).build();
		var results = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse(foreignId, "read", "\"result\""),
					new ToolResponseMessage.ToolResponse("toolu_2", "read", "\"second\""))).build();
		var prompt = prompt(List.of(new SystemMessage("application"), new UserMessage("q"), assistant, results));

		JsonNode request = ClaudeMessages.toRequest(prompt, "claude-sonnet", 32, this.json);

		assertThat(request.path("system").path(0).path("text").asString()).isEqualTo(ClaudeMessages.IDENTITY);
		assertThat(request.path("system").path(1).path("text").asString()).isEqualTo("application");
		JsonNode tool = request.path("tools").path(0);
		assertThat(tool.path("name").asString()).isEqualTo("Read");
		assertThat(tool.path("input_schema").path("properties").has("text")).isTrue();
		JsonNode call = request.path("messages").path(1).path("content").path(1);
		assertThat(call.path("type").asString()).isEqualTo("tool_use");
		assertThat(call.path("id").asString()).hasSize(64).matches("[a-zA-Z0-9_-]+");
		JsonNode result = request.path("messages").path(2).path("content");
		assertThat(result.size()).isEqualTo(2);
		assertThat(result.path(0).path("tool_use_id")).isEqualTo(call.path("id"));
		assertThat(result.path(0).path("content").asString()).isEqualTo("\"result\"");
	}

	@Test
	void responseMapsNamesUsageAndSignedNativeReplayWithoutSenpiEnvelope() {
		JsonNode raw = this.json.readTree("""
				{"id":"msg_1","model":"claude-sonnet","stop_reason":"tool_use",
				 "content":[{"type":"thinking","thinking":"opaque","signature":"signed"},
				            {"type":"redacted_thinking","data":"blob"},
				            {"type":"text","text":"checking"},
				            {"type":"tool_use","id":"toolu_1","name":"Read","input":{"text":"a"}}],
				 "usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":64,"cache_creation_input_tokens":20}}
				""");
		Prompt prompt = prompt(List.of(new UserMessage("q")));

		var response = ClaudeMessages.toResponse(raw, prompt, this.json);

		assertThat(response.hasToolCalls()).isTrue();
		assertThat(response.getResult().getOutput().getToolCalls().getFirst().name()).isEqualTo("read");
		assertThat(response.getResult().getOutput().getText()).isEqualTo("checking");
		assertThat(response.getMetadata().getId()).isEqualTo("msg_1");
		assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(99);
		assertThat(response.getMetadata().getUsage().getCacheWriteInputTokens()).isEqualTo(20L);
		assertThat(TraceEvent.Tokens.from(response.getMetadata().getUsage())).isEqualTo(new TraceEvent.Tokens(94, 5, 64));
		var next = ClaudeMessages.toRequest(prompt(List.of(new UserMessage("q"), response.getResult().getOutput())),
				"claude-sonnet", 32, this.json);
		assertThat(next.path("messages").path(1).path("content")).isEqualTo(raw.path("content"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "end_turn", "stop_sequence", "pause_turn", "max_tokens" })
	void terminalReasonsPreserveCompletionStatus(String reason) {
		var raw = this.json.readTree("{\"stop_reason\":\"" + reason + "\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}");
		var response = ClaudeMessages.toResponse(raw, new Prompt("q"), this.json);
		assertThat(response.getResult().getMetadata().getFinishReason())
			.isEqualTo("max_tokens".equals(reason) ? "LENGTH" : "STOP");
	}

	@ParameterizedTest
	@ValueSource(strings = { "refusal", "sensitive", "", "unknown" })
	void abnormalCompletionFailsWithoutExposingServerText(String reason) {
		var raw = this.json.readTree("{\"stop_reason\":\"" + reason
				+ "\",\"content\":[{\"type\":\"text\",\"text\":\"sk-ant-oat-secret\"}]}");
		assertThatThrownBy(() -> ClaudeMessages.toResponse(raw, new Prompt("q"), this.json))
			.isInstanceOf(IllegalStateException.class).hasMessageNotContaining("sk-ant-oat-secret");
	}

	@Test
	void mediaAndMalformedToolInputsAreRejected() {
		var media = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] { 1, 2 }));
		var user = UserMessage.builder().text("image").media(media).build();
		assertThatThrownBy(() -> ClaudeMessages.toRequest(new Prompt(user), "claude-sonnet", 32, this.json))
			.isInstanceOf(IllegalArgumentException.class);
		var assistant = AssistantMessage.builder().content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("toolu_1", "function", "read", "[1]"))).build();
		assertThatThrownBy(() -> ClaudeMessages.toRequest(new Prompt(assistant), "claude-sonnet", 32, this.json))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void malformedRawReplayIsRejected() {
		var assistant = AssistantMessage.builder().content("answer")
			.properties(Map.of(ClaudeMessages.CONTENT_KEY, "{}")).build();
		assertThatThrownBy(() -> ClaudeMessages.toRequest(new Prompt(assistant), "claude-sonnet", 32, this.json))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void sseRequiresMessageStopAndClosedBlocksAndFinishedToolJson() {
		var client = new ClaudeMessagesClient(URI.create("http://127.0.0.1"), Duration.ofSeconds(1), 8192, "2.1.289");
		String good = ClaudeChatModelTest.toolSse();
		assertThat(client.parseSse(good.getBytes(java.nio.charset.StandardCharsets.UTF_8))
			.path("content").path(0).path("input").path("text").asString()).isEqualTo("from-model");
		for (String broken : List.of(good.replace("data: {\"type\":\"message_stop\"}\n\n", ""),
				good.replace("data: {\"type\":\"content_block_stop\",\"index\":0}\n\n", ""),
				good.replace("\\\"from-model\\\"}", "\\\"from-model\\\""))) {
			assertThatThrownBy(() -> client.parseSse(broken.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
				.isInstanceOf(RuntimeException.class);
		}
	}

	@Test
	void sseKeepsThinkingSignaturesAndReplacesCumulativeUsage() {
		var client = new ClaudeMessagesClient(URI.create("http://127.0.0.1"), Duration.ofSeconds(1), 8192, "2.1.289");
		String stream = ClaudeChatModelTest.startSse() + """
				data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

				data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}

				data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}

				data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"nature"}}

				data: {"type":"content_block_stop","index":0}

				data: {"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"blob"}}

				data: {"type":"content_block_stop","index":1}

				data: {"type":"content_block_start","index":2,"content_block":{"type":"text","text":"done"}}

				data: {"type":"content_block_stop","index":2}

				data: {"type":"message_delta","delta":{"stop_reason":null},"usage":{"output_tokens":3}}

				data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5,"cache_read_input_tokens":null}}

				data: {"type":"message_stop"}

				""";

		JsonNode parsed = client.parseSse(stream.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		assertThat(parsed.path("content").path(0).path("signature").asString()).isEqualTo("signature");
		assertThat(parsed.path("content").path(1).path("data").asString()).isEqualTo("blob");
		assertThat(parsed.path("usage").path("output_tokens").asInt()).isEqualTo(5);
		assertThat(parsed.path("usage").path("cache_read_input_tokens").asInt()).isEqualTo(3);
	}

}
