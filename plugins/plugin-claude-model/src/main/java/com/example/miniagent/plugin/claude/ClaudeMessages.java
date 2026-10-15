package com.example.miniagent.plugin.claude;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/** Spring AI 메시지와 Anthropic Messages wire 형식의 경계. 도구는 여기서 실행하지 않는다. */
final class ClaudeMessages {

	static final String CONTENT_KEY = "claude.content";

	static final String IDENTITY = "You are Claude Code, Anthropic's official CLI for Claude.";

	private static final List<String> CLI_TOOLS = List.of("Read", "Write", "Edit", "Bash", "Grep", "Glob",
			"AskUserQuestion", "EnterPlanMode", "ExitPlanMode", "KillShell", "NotebookEdit", "Skill",
			"Task", "TaskOutput", "TodoWrite", "WebFetch", "WebSearch");

	private ClaudeMessages() {
	}

	static ObjectNode toRequest(Prompt prompt, String model, int maxTokens, JsonMapper json) {
		ObjectNode request = json.createObjectNode();
		request.put("model", model);
		request.put("max_tokens", maxTokens);
		request.put("stream", true);
		ArrayNode system = request.putArray("system");
		appendText(system, IDENTITY);
		ArrayNode messages = request.putArray("messages");
		for (Message message : prompt.getInstructions()) {
			if (message instanceof SystemMessage) {
				appendText(system, message.getText());
			}
			else if (message instanceof UserMessage user) {
				if (!user.getMedia().isEmpty()) {
					throw new IllegalArgumentException("Claude 구독 플러그인은 텍스트와 도구 메시지만 지원합니다");
				}
				appendText(messages.addObject().put("role", "user").putArray("content"), user.getText());
			}
			else if (message instanceof AssistantMessage assistant) {
				if (!assistant.getMedia().isEmpty()) {
					throw new IllegalArgumentException("Claude 구독 플러그인은 텍스트와 도구 메시지만 지원합니다");
				}
				ArrayNode content;
				Object raw = assistant.getMetadata().get(CONTENT_KEY);
				if (raw instanceof String blocks) {
					JsonNode parsed = json.readTree(blocks);
					if (!parsed.isArray()) {
						throw new IllegalArgumentException("Claude assistant content must be an array");
					}
					content = (ArrayNode) parsed;
				}
				else {
					content = json.createArrayNode();
					appendText(content, assistant.getText());
					for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
						ObjectNode block = content.addObject();
						block.put("type", "tool_use");
						block.put("id", toolId(call.id()));
						block.put("name", wireName(call.name()));
						JsonNode input = json.readTree(call.arguments());
						if (!input.isObject()) {
							throw new IllegalArgumentException("Claude tool input must be an object");
						}
						block.set("input", input);
					}
				}
				for (JsonNode block : content) {
					if ("tool_use".equals(block.path("type").asString())) {
						((ObjectNode) block).put("name", wireName(block.path("name").asString()));
						((ObjectNode) block).put("id", toolId(block.path("id").asString()));
					}
				}
				messages.addObject().put("role", "assistant").set("content", content);
			}
			else if (message instanceof ToolResponseMessage response) {
				ArrayNode content = messages.addObject().put("role", "user").putArray("content");
				for (ToolResponseMessage.ToolResponse tool : response.getResponses()) {
					ObjectNode result = content.addObject();
					result.put("type", "tool_result");
					result.put("tool_use_id", toolId(tool.id()));
					result.put("content", tool.responseData());
					result.put("is_error", false);
				}
			}
			else {
				throw new IllegalArgumentException("Unsupported Claude message type: " + message.getMessageType());
			}
		}
		ArrayNode tools = request.putArray("tools");
		var names = new HashSet<String>();
		for (ToolCallback callback : callbacks(prompt)) {
			var definition = callback.getToolDefinition();
			String name = wireName(definition.name());
			if (!names.add(name)) {
				throw new IllegalArgumentException("Claude wire tool name collision: " + name);
			}
			ObjectNode tool = tools.addObject();
			tool.put("name", name);
			tool.put("description", definition.description());
			tool.set("input_schema", json.readTree(definition.inputSchema()));
		}
		return request;
	}

	static ChatResponse toResponse(JsonNode message, Prompt prompt, JsonMapper json) {
		return toResponse(message, prompt, json, true);
	}

	static ChatResponse toResponse(JsonNode message, Prompt prompt, JsonMapper json, boolean includeText) {
		String finish = switch (message.path("stop_reason").asString("")) {
			case "end_turn", "stop_sequence", "pause_turn" -> "STOP";
			case "tool_use" -> "TOOL_CALLS";
			case "max_tokens" -> "LENGTH";
			default -> throw new IllegalStateException("Claude 응답이 정상적으로 완료되지 않았습니다");
		};
		StringBuilder text = new StringBuilder();
		List<AssistantMessage.ToolCall> calls = new ArrayList<>();
		for (JsonNode block : message.path("content")) {
			switch (block.path("type").asString("")) {
				case "text" -> text.append(block.path("text").asString(""));
				case "tool_use" -> {
					JsonNode input = block.path("input");
					if (!input.isObject()) {
						throw new IllegalStateException("Claude tool input is incomplete");
					}
					calls.add(new AssistantMessage.ToolCall(block.path("id").asString(), "function",
							applicationName(block.path("name").asString(), prompt), json.writeValueAsString(input)));
				}
				case "thinking", "redacted_thinking" -> {
					// 서명과 opaque 블록은 변형하지 않고 다음 도구 라운드에 재전송한다.
				}
				default -> throw new IllegalStateException("Unsupported Claude response content type");
			}
		}
		if ("TOOL_CALLS".equals(finish) && calls.isEmpty()) {
			throw new IllegalStateException("Claude tool_use response has no tool calls");
		}
		JsonNode usage = message.path("usage");
		int cached = usage.path("cache_read_input_tokens").asInt(0);
		int written = usage.path("cache_creation_input_tokens").asInt(0);
		int input = usage.path("input_tokens").asInt(0) + cached + written;
		int output = usage.path("output_tokens").asInt(0);
		AssistantMessage assistant = AssistantMessage.builder().content(includeText ? text.toString() : "").toolCalls(calls)
			.properties(Map.of(CONTENT_KEY, json.writeValueAsString(message.path("content")))).build();
		var metadata = ChatResponseMetadata.builder().id(message.path("id").asString(""))
			.model(message.path("model").asString(""))
			.usage(new DefaultUsage(input, output, input + output, null, (long) cached, (long) written)).build();
		return new ChatResponse(List.of(new Generation(assistant,
				ChatGenerationMetadata.builder().finishReason(finish).build())), metadata);
	}

	static String wireName(String name) {
		if ("ask_user_question".equals(name)) {
			return "AskUserQuestion";
		}
		return CLI_TOOLS.stream().filter(candidate -> candidate.equalsIgnoreCase(name)).findFirst().orElse(name);
	}

	private static String toolId(String id) {
		String normalized = id.replaceAll("[^a-zA-Z0-9_-]", "_");
		if (normalized.length() <= 64) {
			return normalized;
		}
		try {
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(id.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
			return normalized.substring(0, 47) + "_" + hash;
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String applicationName(String wireName, Prompt prompt) {
		return callbacks(prompt).stream().map(callback -> callback.getToolDefinition().name())
			.filter(name -> wireName(name).equals(wireName)).findFirst().orElse(wireName);
	}

	private static List<ToolCallback> callbacks(Prompt prompt) {
		return prompt.getOptions() instanceof ToolCallingChatOptions options
				&& options.getToolCallbacks() != null ? options.getToolCallbacks() : List.of();
	}

	private static void appendText(ArrayNode content, String text) {
		if (text != null && !text.isEmpty()) {
			content.addObject().put("type", "text").put("text", text);
		}
	}

}
