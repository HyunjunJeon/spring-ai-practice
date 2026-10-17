package com.example.miniagent.plugin.codex;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.example.miniagent.core.TraceEvent;
import org.jspecify.annotations.Nullable;
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
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Spring AI 메시지 ↔ OpenAI Responses API 형식 변환. (네트워크 없음, 단위 테스트 대상)
 *
 * <pre>
 * Spring AI                         Responses API input item
 * ───────────────────────────────── ─────────────────────────────────────────────
 * 첫 SystemMessage                  body.instructions
 * 그 밖의 SystemMessage             {role: developer, content}
 * UserMessage                       {role: user, content: [{type: input_text}]}
 * AssistantMessage 텍스트           {type: message, role: assistant, content: [{type: output_text}]}
 * AssistantMessage.ToolCall         {type: function_call, call_id, name, arguments}
 * ToolResponseMessage.ToolResponse  {type: function_call_output, call_id, output}
 * ToolCallback.getToolDefinition()  tools: [{type: function, name, description, parameters}]
 * </pre>
 */
final class CodexResponses {

	/**
	 * 응답 output item 원본(JSON 배열 문자열)을 AssistantMessage 메타데이터에 보관하는 키.
	 *
	 * <p>추론 모델은 reasoning item(암호화된 사고 내용)을 돌려준다. store=false 라 서버가 기억하지 않으므로,
	 * 다음 라운드에 이 원본을 그대로 다시 보내야 모델이 이어서 생각할 수 있다. ToolCallingAdvisor 는
	 * AssistantMessage 객체를 그대로 히스토리에 넣으므로 메타데이터가 다음 라운드까지 살아남는다.
	 * (JSONL 메모리처럼 메타데이터가 사라진 메시지는 텍스트/ToolCall 로부터 다시 만든다)
	 */
	static final String OUTPUT_ITEMS = "codex.outputItems";

	private static final String NOT_COMPLETED = "Codex response did not complete successfully";

	private CodexResponses() {
	}

	record Result(List<JsonNode> items, JsonNode response) {
	}

	// ---- 요청 ----

	static ObjectNode toRequest(Prompt prompt, String model, @Nullable String reasoningEffort,
			@Nullable String promptCacheKey, JsonMapper json) {
		ObjectNode body = json.createObjectNode();
		body.put("model", model);

		List<Message> messages = prompt.getInstructions();
		boolean leadingSystem = !messages.isEmpty() && messages.get(0) instanceof SystemMessage;
		String instructions = leadingSystem ? text(messages.get(0)) : "";
		body.put("instructions", instructions.isBlank() ? "You are a helpful assistant." : instructions);
		ArrayNode input = body.putArray("input");
		for (int i = leadingSystem ? 1 : 0; i < messages.size(); i++) {
			appendInput(input, messages.get(i), json);
		}

		List<ToolDefinition> tools = toolDefinitions(prompt);
		if (!tools.isEmpty()) {
			ArrayNode toolArray = body.putArray("tools");
			for (ToolDefinition tool : tools) {
				ObjectNode node = toolArray.addObject();
				node.put("type", "function");
				node.put("name", tool.name());
				node.put("description", tool.description());
				node.set("parameters", json.readTree(tool.inputSchema()));
				// Spring AI 가 만드는 스키마는 strict 모드 조건(모든 속성 required 등)을 만족하지 않는다.
				node.put("strict", false);
			}
			body.put("tool_choice", "auto");
			body.put("parallel_tool_calls", true);
		}

		if (reasoningEffort != null && !reasoningEffort.isBlank()) {
			ObjectNode reasoning = body.putObject("reasoning");
			reasoning.put("effort", reasoningEffort);
			reasoning.put("summary", "auto");
			body.putArray("include").add("reasoning.encrypted_content");
		}
		if (promptCacheKey != null) {
			body.put("prompt_cache_key", promptCacheKey);
		}
		// ChatGPT 백엔드는 둘 다 필수: 서버에 저장하지 않고(store=false), 항상 SSE 로 받는다(stream=true).
		body.put("store", false);
		body.put("stream", true);
		return body;
	}

	private static void appendInput(ArrayNode input, Message message, JsonMapper json) {
		switch (message) {
			case SystemMessage system -> {
				ObjectNode node = input.addObject();
				node.put("role", "developer");
				node.put("content", text(system));
			}
			case UserMessage user -> {
				ObjectNode node = input.addObject();
				node.put("role", "user");
				ObjectNode part = node.putArray("content").addObject();
				part.put("type", "input_text");
				part.put("text", text(user));
			}
			case AssistantMessage assistant -> appendAssistant(input, assistant, json);
			case ToolResponseMessage tool -> {
				for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
					ObjectNode node = input.addObject();
					node.put("type", "function_call_output");
					// AssistantMessage.ToolCall.id 에 넣어 둔 call_id 가 ToolResponse.id 로 돌아온다.
					node.put("call_id", response.id());
					node.put("output", response.responseData());
				}
			}
			default -> throw new IllegalArgumentException("지원하지 않는 메시지: " + message.getMessageType());
		}
	}

	private static void appendAssistant(ArrayNode input, AssistantMessage assistant, JsonMapper json) {
		Object raw = assistant.getMetadata().get(OUTPUT_ITEMS);
		if (raw instanceof String rawItems && !rawItems.isBlank()) {
			json.readTree(rawItems).forEach(input::add);
			return;
		}
		if (assistant.getText() != null && !assistant.getText().isBlank()) {
			ObjectNode node = input.addObject();
			node.put("type", "message");
			node.put("role", "assistant");
			ObjectNode part = node.putArray("content").addObject();
			part.put("type", "output_text");
			part.put("text", assistant.getText());
		}
		for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
			ObjectNode node = input.addObject();
			node.put("type", "function_call");
			node.put("call_id", call.id());
			node.put("name", call.name());
			node.put("arguments", call.arguments());
		}
	}

	private static List<ToolDefinition> toolDefinitions(Prompt prompt) {
		if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null) {
			return options.getToolCallbacks().stream().map(ToolCallback::getToolDefinition).toList();
		}
		return List.of();
	}

	private static String text(Message message) {
		return (message.getText() != null) ? message.getText() : "";
	}

	// ---- 응답 (SSE) ----

	/**
	 * SSE 줄들을 읽어 output item 목록과 마지막 response 객체를 모은다.
	 *
	 * <p>ChatGPT 백엔드의 {@code response.completed} 에는 output 이 비어 있을 수 있어서,
	 * item 은 {@code response.output_item.done} 이벤트에서 하나씩 모은다. (pi 도 같은 방식)
	 */
	static Result parseSse(Iterator<String> lines, JsonMapper json) {
		List<JsonNode> items = new ArrayList<>();
		while (lines.hasNext()) {
			String line = lines.next();
			if (!line.startsWith("data:")) {
				continue;
			}
			String data = line.substring(5).strip();
			if (data.isEmpty() || data.equals("[DONE]")) {
				continue;
			}
			JsonNode event = json.readTree(data);
			String type = event.path("type").asString("");
			switch (type) {
				case "response.output_item.done" -> items.add(event.path("item"));
				case "response.completed", "response.done" -> {
					// stream() 과 같은 기준: 실패하거나 잘린 응답, response 객체 없는 완료 이벤트, 잘못된 도구 호출은 최종 답이 아니다.
					JsonNode response = event.path("response");
					if (!response.isObject() || !succeeded(response)) {
						throw new CodexApiException(NOT_COMPLETED);
					}
					requireValidToolCalls(items, json);
					return new Result(items, response);
				}
				case "response.incomplete" -> throw unsuccessful(event);
				case "response.failed" -> throw new CodexApiException(
						event.path("response").path("error").path("message").asString("Codex response failed"));
				case "error" -> throw new CodexApiException(firstNonBlank(event.path("message").asString(""),
						event.path("error").path("message").asString(""), data));
				default -> {
					// 텍스트 델타 등 나머지 이벤트는 스트리밍 UI 용이다. 여기서는 완성된 item 만 쓴다.
				}
			}
		}
		throw new CodexApiException("응답 스트림이 완료 이벤트 없이 끝났습니다");
	}

	/** Keeps tool/reasoning items private until a successful terminal event. */
	static final class StreamingParser {

		private final List<JsonNode> items = new ArrayList<>();

		private boolean emittedText;

		private boolean completed;

		ChatResponse next(Iterator<String> lines, JsonMapper json, String model,
				@Nullable String reasoningEffort) {
			while (lines.hasNext()) {
				String line = lines.next();
				if (!line.startsWith("data:")) {
					continue;
				}
				String data = line.substring(5).strip();
				if (data.isEmpty() || data.equals("[DONE]")) {
					continue;
				}
				JsonNode event = json.readTree(data);
				switch (event.path("type").asString("")) {
					case "response.output_text.delta" -> {
						String delta = event.path("delta").asString("");
						if (!delta.isEmpty()) {
							this.emittedText = true;
							return new ChatResponse(List.of(new Generation(new AssistantMessage(delta))));
						}
					}
					case "response.output_item.done" -> this.items.add(event.path("item"));
					case "response.completed", "response.done" -> {
						JsonNode response = event.path("response");
						if (!response.isObject() || !succeeded(response)) {
							throw new CodexApiException(NOT_COMPLETED);
						}
						if (this.items.isEmpty()) {
							response.path("output").forEach(this.items::add);
						}
						requireValidToolCalls(this.items, json);
						ChatResponse result = toChatResponse(new Result(this.items, response), model, reasoningEffort, json);
						this.completed = true;
						if (this.emittedText) {
							Generation generation = result.getResult();
							return new ChatResponse(List.of(new Generation(
									generation.getOutput().mutate().content("").build(), generation.getMetadata())),
									result.getMetadata());
						}
						return result;
					}
					case "response.failed", "response.incomplete", "error" -> throw unsuccessful(event);
					default -> {
						// Argument fragments are never exposed to the tool loop.
					}
				}
			}
			throw new CodexApiException("응답 스트림이 완료 이벤트 없이 끝났습니다");
		}

		boolean completed() {
			return this.completed;
		}

	}

	static ChatResponse toChatResponse(Result result, String model, JsonMapper json) {
		return toChatResponse(result, model, null, json);
	}

	static ChatResponse toChatResponse(Result result, String model, @Nullable String reasoningEffort, JsonMapper json) {
		List<String> finalTexts = new ArrayList<>();
		List<String> allTexts = new ArrayList<>();
		List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
		for (JsonNode item : result.items()) {
			switch (item.path("type").asString("")) {
				case "message" -> {
					StringBuilder text = new StringBuilder();
					item.path("content").forEach(part -> {
						if ("output_text".equals(part.path("type").asString(""))) {
							text.append(part.path("text").asString(""));
						}
					});
					allTexts.add(text.toString());
					// 최신 모델은 중간 설명(commentary)과 최종 답변(final_answer)을 phase 로 나눈다.
					if ("final_answer".equals(item.path("phase").asString(""))) {
						finalTexts.add(text.toString());
					}
				}
				case "function_call" -> toolCalls.add(new AssistantMessage.ToolCall(item.path("call_id").asString(),
						"function", item.path("name").asString(), item.path("arguments").asString("{}")));
				default -> {
					// reasoning item 등은 OUTPUT_ITEMS 메타데이터로만 보관한다.
				}
			}
		}
		String text = String.join("\n\n", finalTexts.isEmpty() ? allTexts : finalTexts);

		ArrayNode rawItems = json.createArrayNode();
		result.items().forEach(rawItems::add);
		AssistantMessage output = AssistantMessage.builder()
			.content(text)
			.toolCalls(toolCalls)
			.properties(Map.of(OUTPUT_ITEMS, json.writeValueAsString(rawItems)))
			.build();

		JsonNode response = result.response();
		String status = response.path("status").asString("completed");
		String finishReason = !toolCalls.isEmpty() ? "TOOL_CALLS"
				: "incomplete".equals(status)
						? "INCOMPLETE:" + response.path("incomplete_details").path("reason").asString("unknown")
						: "STOP";

		JsonNode usage = response.path("usage");
		// 응답에 reasoning.effort 가 있으면 그것, 없으면 요청에 쓴 값. 트레이스 화면이 이 키를 읽는다.
		String effort = response.path("reasoning").path("effort").asString(reasoningEffort);
		var metadataBuilder = ChatResponseMetadata.builder();
		if (effort != null) {
			metadataBuilder.keyValue(TraceEvent.REASONING_EFFORT_METADATA, effort);
		}
		var metadata = metadataBuilder
			.id(response.path("id").asString(""))
			.model(response.path("model").asString(model))
			.usage(new DefaultUsage(usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0),
					usage.path("total_tokens").asInt(0), null,
					usage.path("input_tokens_details").path("cached_tokens").asLong(0), null))
			.build();
		var generation = new Generation(output, ChatGenerationMetadata.builder().finishReason(finishReason).build());
		return new ChatResponse(List.of(generation), metadata);
	}

	/** status 가 없으면 completed 로 본다. */
	private static boolean succeeded(JsonNode response) {
		return "completed".equals(response.path("status").asString("completed"));
	}

	private static void requireValidToolCalls(List<JsonNode> items, JsonMapper json) {
		for (JsonNode item : items) {
			if ("function_call".equals(item.path("type").asString(""))) {
				if (item.path("call_id").asString("").isBlank()
						|| item.path("name").asString("").isBlank()
						|| !json.readTree(item.path("arguments").asString("")).isObject()) {
					throw new CodexApiException("Invalid completed Codex tool call");
				}
			}
		}
	}

	private static CodexApiException unsuccessful(JsonNode event) {
		return new CodexApiException(firstNonBlank(event.path("response").path("error").path("message").asString(""),
				event.path("message").asString(""), NOT_COMPLETED));
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	static class CodexApiException extends RuntimeException {

		CodexApiException(String message) {
			super(message);
		}

	}

}
