package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.model.ToolContextKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * call() 은 일회성 실행, 영속 실행, 목표와 워커가 쓰는 경로다. 로컬 Responses/OAuth 엔드포인트와
 * 임시 디렉터리의 합성 자체 토큰으로 요청 변환, 응답 변환, 401 한 번 재시도를 확인한다.
 */
class CodexChatModelCallTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String COMPLETED = """
			{"type":"response.completed","response":{"id":"resp_ok","status":"completed"}}
			""";

	private final JsonMapper json = JsonMapper.builder().build();

	@TempDir
	Path dir;

	private CodexEndpoints endpoints;

	record EchoInput(String text) {
	}

	@BeforeEach
	void start() throws IOException {
		this.endpoints = new CodexEndpoints();
	}

	@AfterEach
	void stop() {
		this.endpoints.close();
	}

	@Test
	void requestCarriesOverrideModelSessionCacheKeyToolsAndOwnCredentials() {
		CodexCredentials own = saveOwnToken();
		this.endpoints.answerResponses(exchange -> {
			try (OutputStream out = CodexEndpoints.sse(exchange)) {
				CodexEndpoints.event(out, COMPLETED);
			}
		});

		model("auto").call(new Prompt(List.of(new SystemMessage("be brief"), new UserMessage("hi")),
				ToolCallingChatOptions.builder()
					.model("gpt-override")
					.toolCallbacks(echo())
					.toolContext(Map.of(ToolContextKeys.SESSION_ID, "s-1"))
					.build()));

		CodexEndpoints.ResponsesRequest request = this.endpoints.responsesRequests().getFirst();
		assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + own.accessToken());
		assertThat(request.headers().getFirst("chatgpt-account-id")).isEqualTo(CodexEndpoints.ACCOUNT);
		assertThat(request.headers().getFirst("originator")).isEqualTo("test");
		assertThat(request.headers().getFirst("session-id")).isEqualTo("mini-agent-s-1");
		JsonNode body = request.body();
		assertThat(body.path("model").asString()).isEqualTo("gpt-override");
		assertThat(body.path("instructions").asString()).isEqualTo("be brief");
		assertThat(body.path("input").path(0).path("content").path(0).path("text").asString()).isEqualTo("hi");
		assertThat(body.path("tools").path(0).path("name").asString()).isEqualTo("echo");
		assertThat(body.path("reasoning").path("effort").asString()).isEqualTo("low");
		assertThat(body.path("prompt_cache_key").asString()).isEqualTo("mini-agent-s-1");
	}

	@Test
	void requestWithoutOptionsUsesTheConfiguredModelAndNoSession() {
		saveOwnToken();
		this.endpoints.answerResponses(exchange -> {
			try (OutputStream out = CodexEndpoints.sse(exchange)) {
				CodexEndpoints.event(out, COMPLETED);
			}
		});

		model("auto").call(new Prompt("hi"));

		CodexEndpoints.ResponsesRequest request = this.endpoints.responsesRequests().getFirst();
		assertThat(request.headers().getFirst("session-id")).isNull();
		assertThat(request.body().path("model").asString()).isEqualTo("gpt-default");
		assertThat(request.body().path("instructions").asString()).isEqualTo("You are a helpful assistant.");
		assertThat(request.body().has("prompt_cache_key")).isFalse();
		assertThat(request.body().has("tools")).isFalse();
	}

	@Test
	void responseBecomesChatResponseWithTextToolCallUsageAndEffort() {
		saveOwnToken();
		this.endpoints.answerResponses(exchange -> {
			try (OutputStream out = CodexEndpoints.sse(exchange)) {
				CodexEndpoints.event(out, """
						{"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_1","encrypted_content":"opaque","summary":[]}}
						""");
				CodexEndpoints.event(out, """
						{"type":"response.output_item.done","item":{"type":"message","phase":"final_answer","content":[{"type":"output_text","text":"done"}]}}
						""");
				CodexEndpoints.event(out, """
						{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_9","name":"echo","arguments":"{\\"text\\":\\"a\\"}"}}
						""");
				CodexEndpoints.event(out, """
						{"type":"response.completed","response":{"id":"resp_1","model":"gpt-served","status":"completed","usage":{"input_tokens":100,"output_tokens":20,"total_tokens":120,"input_tokens_details":{"cached_tokens":64}}}}
						""");
			}
		});

		ChatResponse response = model("auto").call(new Prompt("hi"));

		AssistantMessage output = response.getResult().getOutput();
		assertThat(output.getText()).isEqualTo("done");
		assertThat(output.getToolCalls())
			.containsExactly(new AssistantMessage.ToolCall("call_9", "function", "echo", "{\"text\":\"a\"}"));
		assertThat(output.getMetadata().get(CodexResponses.OUTPUT_ITEMS)).asString().contains("rs_1", "opaque");
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
		assertThat(response.getMetadata().getId()).isEqualTo("resp_1");
		assertThat(response.getMetadata().getModel()).isEqualTo("gpt-served");
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(100);
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(20);
		assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(120);
		assertThat(response.getMetadata().getUsage().getCacheReadInputTokens()).isEqualTo(64L);
		Object effort = response.getMetadata().get(TraceEvent.REASONING_EFFORT_METADATA);
		assertThat(effort).isEqualTo("low");
	}

	@Test
	void unauthorizedOwnTokenIsRefreshedAndTheRequestRetriedOnceWithTheNewBearer() throws Exception {
		CodexCredentials own = saveOwnToken();
		this.endpoints.honor("refresh-1");
		AtomicInteger requests = new AtomicInteger();
		this.endpoints.answerResponses(exchange -> {
			if (requests.incrementAndGet() == 1) {
				CodexEndpoints.status(exchange, 401);
				return;
			}
			try (OutputStream out = CodexEndpoints.sse(exchange)) {
				CodexEndpoints.event(out, """
						{"type":"response.output_item.done","item":{"type":"message","content":[{"type":"output_text","text":"recovered"}]}}
						""");
				CodexEndpoints.event(out, COMPLETED);
			}
		});

		ChatResponse response = model("auto").call(new Prompt("hi"));

		assertThat(response.getResult().getOutput().getText()).isEqualTo("recovered");
		assertThat(this.endpoints.presentedRefreshTokens()).containsExactly("refresh-1");
		String rotated = auth("auto").readOwn().orElseThrow().accessToken();
		assertThat(rotated).isNotEqualTo(own.accessToken());
		assertThat(this.endpoints.responsesRequests()).extracting(request -> request.headers().getFirst("Authorization"))
			.containsExactly("Bearer " + own.accessToken(), "Bearer " + rotated);
	}

	@Test
	void unauthorizedAfterTheRetryIsNotRetriedAgain() {
		saveOwnToken();
		this.endpoints.honor("refresh-1");
		this.endpoints.answerResponses(exchange -> CodexEndpoints.status(exchange, 401));

		assertThatThrownBy(() -> model("auto").call(new Prompt("hi")))
			.isInstanceOfSatisfying(CodexResponsesClient.CodexHttpException.class,
					ex -> assertThat(ex.status()).isEqualTo(401));

		assertThat(this.endpoints.responsesRequests()).hasSize(2);
		assertThat(this.endpoints.tokenRequests()).hasSize(1);
	}

	@Test
	void otherHttpErrorsAreNeitherRefreshedNorRetried() {
		saveOwnToken();
		this.endpoints.honor("refresh-1");
		this.endpoints.answerResponses(exchange -> CodexEndpoints.status(exchange, 500));

		assertThatThrownBy(() -> model("auto").call(new Prompt("hi")))
			.isInstanceOfSatisfying(CodexResponsesClient.CodexHttpException.class,
					ex -> assertThat(ex.status()).isEqualTo(500));

		assertThat(this.endpoints.responsesRequests()).hasSize(1);
		assertThat(this.endpoints.tokenRequests()).isEmpty();
	}

	@Test
	void unauthorizedCliTokenIsNotRefreshedOrRetried() throws Exception {
		CodexEndpoints.writeCodexCli(cliStore(), Instant.now().plusSeconds(3600));
		byte[] cli = Files.readAllBytes(cliStore());
		this.endpoints.answerResponses(exchange -> CodexEndpoints.status(exchange, 401));

		assertThatThrownBy(() -> model("codex-cli").call(new Prompt("hi")))
			.isInstanceOfSatisfying(CodexResponsesClient.CodexHttpException.class,
					ex -> assertThat(ex.status()).isEqualTo(401));

		assertThat(this.endpoints.responsesRequests()).hasSize(1);
		assertThat(this.endpoints.tokenRequests()).isEmpty();
		assertThat(Files.readAllBytes(cliStore())).isEqualTo(cli);
	}

	private CodexChatModel model(String mode) {
		return new CodexChatModel(auth(mode),
				new CodexResponsesClient(this.json, this.endpoints.baseUrl(), "test", TIMEOUT), this.json,
				"gpt-default", "low");
	}

	private CodexAuth auth(String mode) {
		return new CodexAuth(this.dir.resolve("auth/openai-codex.json"), cliStore(), mode, this.endpoints.oauth());
	}

	private Path cliStore() {
		return this.dir.resolve("codex/auth.json");
	}

	private CodexCredentials saveOwnToken() {
		CodexCredentials own = CodexEndpoints.ownToken(Instant.now().plusSeconds(3600), "refresh-1");
		auth("auto").saveOwn(own);
		return own;
	}

	private static FunctionToolCallback<EchoInput, String> echo() {
		return FunctionToolCallback.<EchoInput, String>builder("echo", (in, ctx) -> in.text())
			.description("Echo text")
			.inputType(EchoInput.class)
			.build();
	}

}
