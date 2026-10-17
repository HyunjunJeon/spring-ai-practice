package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Named.named;

/**
 * call() 과 stream() 은 같은 응답 끝을 같게 다뤄야 한다. 영속 실행, 목표, 위임과 워커는 call() 을 쓰므로
 * 실패하거나 잘린 응답을 최종 답으로 받거나 잘못된 도구 호출을 도구 루프에 넘기면 안 된다.
 */
class CodexCallStreamParityTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String DELTA = "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}";

	private static final String COMPLETED = """
			{"type":"response.completed","response":{"id":"r1","status":"completed","usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5}}}
			""";

	@TempDir
	Path dir;

	private CodexEndpoints endpoints;

	@BeforeEach
	void start() throws IOException {
		this.endpoints = new CodexEndpoints();
	}

	@AfterEach
	void stop() {
		this.endpoints.close();
	}

	static Stream<Named<List<String>>> rejectedEndings() {
		String tool = tool("call_1", "echo", "{\"text\":\"ok\"}");
		return Stream.of(
				named("response.failed", List.of(tool,
						"{\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"overloaded\"}}}")),
				named("response.incomplete", List.of(tool,
						"{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}")),
				named("response.incomplete without response", List.of(tool, "{\"type\":\"response.incomplete\"}")),
				named("response.done with failed status", List.of(tool,
						"{\"type\":\"response.done\",\"response\":{\"status\":\"failed\"}}")),
				named("response.completed with incomplete status", List.of(tool,
						"{\"type\":\"response.completed\",\"response\":{\"status\":\"incomplete\"}}")),
				named("response.completed without response", List.of(tool, "{\"type\":\"response.completed\"}")),
				named("response.done without response", List.of(tool, "{\"type\":\"response.done\"}")),
				named("error event", List.of(tool, "{\"type\":\"error\",\"message\":\"bad\"}")),
				named("no terminal event", List.of(tool)),
				named("tool call with blank call_id", List.of(tool("", "echo", "{}"), COMPLETED)),
				named("tool call with blank name", List.of(tool("call_1", "", "{}"), COMPLETED)),
				named("tool call with array arguments", List.of(tool("call_1", "echo", "[1]"), COMPLETED)),
				named("tool call with malformed arguments", List.of(tool("call_1", "echo", "{"), COMPLETED)));
	}

	@ParameterizedTest
	@MethodSource("rejectedEndings")
	void callRejectsEveryEndingThatStreamRejectsAndNeitherExposesAToolCall(List<String> events) {
		answerWith(events);
		CodexChatModel model = model();

		List<ChatResponse> streamed = new CopyOnWriteArrayList<>();
		Throwable fromStream = catchThrowable(
				() -> model.stream(new Prompt("hi")).doOnNext(streamed::add).blockLast(TIMEOUT));
		AtomicReference<ChatResponse> accepted = new AtomicReference<>();
		Throwable fromCall = catchThrowable(() -> accepted.set(model.call(new Prompt("hi"))));

		assertThat(fromStream).as("stream()").isNotNull();
		assertThat(fromCall).as(() -> "call() " + ((accepted.get() != null)
				? "accepted it with finish reason " + accepted.get().getResult().getMetadata().getFinishReason()
				: "threw differently"))
			.isExactlyInstanceOf(fromStream.getClass())
			.hasMessage(fromStream.getMessage());
		assertThat(streamed).noneMatch(ChatResponse::hasToolCalls);
		assertThat(this.endpoints.responsesRequests()).hasSize(2);
	}

	@Test
	void completedEndingGivesTheSameToolCallOnBothPaths() {
		answerWith(List.of(tool("call_1", "echo", "{\"text\":\"ok\"}"), COMPLETED));
		CodexChatModel model = model();

		ChatResponse called = model.call(new Prompt("hi"));
		ChatResponse terminal = model.stream(new Prompt("hi")).blockLast(TIMEOUT);

		AssistantMessage.ToolCall expected = new AssistantMessage.ToolCall("call_1", "function", "echo",
				"{\"text\":\"ok\"}");
		assertThat(called.getResult().getOutput().getToolCalls()).containsExactly(expected);
		assertThat(terminal.getResult().getOutput().getToolCalls()).containsExactly(expected);
		assertThat(called.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
		assertThat(terminal.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
	}

	private void answerWith(List<String> events) {
		this.endpoints.answerResponses(exchange -> {
			try (OutputStream out = CodexEndpoints.sse(exchange)) {
				CodexEndpoints.event(out, DELTA);
				for (String event : events) {
					CodexEndpoints.event(out, event);
				}
			}
		});
	}

	private CodexChatModel model() {
		CodexAuth auth = new CodexAuth(this.dir.resolve("auth/openai-codex.json"), this.dir.resolve("codex/auth.json"),
				"auto", this.endpoints.oauth());
		auth.saveOwn(CodexEndpoints.ownToken(Instant.now().plusSeconds(3600), "refresh-1"));
		return new CodexChatModel(auth, new CodexResponsesClient(JSON, this.endpoints.baseUrl(), "test", TIMEOUT), JSON,
				"m", "low");
	}

	private static String tool(String callId, String name, String arguments) {
		ObjectNode event = JSON.createObjectNode();
		event.put("type", "response.output_item.done");
		ObjectNode item = event.putObject("item");
		item.put("type", "function_call");
		item.put("id", "fc_1");
		item.put("call_id", callId);
		item.put("name", name);
		item.put("arguments", arguments);
		return JSON.writeValueAsString(event);
	}

}
