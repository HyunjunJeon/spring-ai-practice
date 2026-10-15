package com.example.miniagent.plugin.claude;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.InterceptingToolCallback;
import com.example.miniagent.core.ToolContextKeys;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 Java HTTP/SSE 경계를 검증한다. 도구는 Spring AI 가 실행하며 외부 런타임은 없다. */
class ClaudeChatModelTest {

	@TempDir
	Path dir;

	private final JsonMapper json = JsonMapper.builder().build();

	private final ExecutorService workers = Executors.newCachedThreadPool();

	private final CountDownLatch entered = new CountDownLatch(1);

	private final CountDownLatch release = new CountDownLatch(1);

	private HttpServer server;

	record EchoInput(String text) {
	}

	@AfterEach
	void stop() {
		this.release.countDown();
		if (this.server != null) {
			this.server.stop(0);
		}
		this.workers.shutdownNow();
	}

	private URI serve(HttpHandler handler) throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/v1/messages", handler);
		this.server.setExecutor(this.workers);
		this.server.start();
		return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1/messages?beta=true");
	}

	private ClaudeChatModel model(URI endpoint, Duration timeout, int maxBytes) {
		ClaudeProperties properties = new ClaudeProperties();
		properties.setAuthStore(this.dir.resolve("claude.json"));
		ClaudeAuth auth = new ClaudeAuth(properties.getAuthStore(), new ClaudeOAuth(Duration.ofSeconds(5)));
		auth.save(new ClaudeCredentials("sk-ant-oat-fixture", "refresh-fixture", 4102444800000L));
		return new ClaudeChatModel(properties, auth,
				new ClaudeMessagesClient(endpoint, timeout, maxBytes, properties.getCliVersion()));
	}

	@Test
	void directJavaRequestUsesSubscriptionHeadersAndIdentityAndMergesUsage() throws Exception {
		CompletableFuture<Map<String, String>> headers = new CompletableFuture<>();
		CompletableFuture<JsonNode> payload = new CompletableFuture<>();
		URI endpoint = serve(exchange -> {
			headers.complete(Map.of("authorization", exchange.getRequestHeaders().getFirst("Authorization"),
					"version", exchange.getRequestHeaders().getFirst("anthropic-version"),
					"beta", exchange.getRequestHeaders().getFirst("anthropic-beta"),
					"userAgent", exchange.getRequestHeaders().getFirst("User-Agent"),
					"app", exchange.getRequestHeaders().getFirst("x-app"),
					"apiKey", String.valueOf(exchange.getRequestHeaders().getFirst("x-api-key"))));
			payload.complete(this.json.readTree(exchange.getRequestBody().readAllBytes()));
			respond(exchange, 200, textSse("subscription-response"));
		});

		var response = model(endpoint, Duration.ofSeconds(5), 8192).call(new Prompt("hello"));

		assertThat(response.getResult().getOutput().getText()).isEqualTo("subscription-response");
		assertThat(headers.get(5, TimeUnit.SECONDS)).containsEntry("authorization", "Bearer sk-ant-oat-fixture")
			.containsEntry("version", "2023-06-01").containsEntry("app", "cli").containsEntry("apiKey", "null");
		assertThat(headers.get().get("beta")).contains("oauth-2025-04-20", "claude-code-20250219");
		assertThat(headers.get().get("userAgent")).startsWith("claude-cli/");
		assertThat(payload.get(5, TimeUnit.SECONDS).path("system").path(0).path("text").asString())
			.isEqualTo(ClaudeMessages.IDENTITY);
		assertThat(payload.get().path("stream").asBoolean()).isTrue();
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(15);
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(5);
		assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(20);
	}

	@Test
	void streamIsColdAndEmitsFragmentedMultilineUtf8BeforeServerCanFinish() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		CountDownLatch observed = new CountDownLatch(1);
		CompletableFuture<Boolean> serverObserved = new CompletableFuture<>();
		URI endpoint = serve(exchange -> {
			requests.incrementAndGet();
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(200, 0);
			try (var out = exchange.getResponseBody()) {
				String prefix = textSse("한글🙂").replace(endSse("end_turn"), "")
					.replace("\"delta\":{", "\ndata: \"delta\":{").replace("\n", "\r\n");
				for (byte next : prefix.getBytes(StandardCharsets.UTF_8)) {
					out.write(next);
					out.flush();
				}
				boolean ready = await(observed);
				serverObserved.complete(ready);
				if (ready) {
					out.write(endSse("end_turn").getBytes(StandardCharsets.UTF_8));
				}
			}
		});
		var stream = model(endpoint, Duration.ofSeconds(10), 8192).stream(new Prompt("hello"));
		assertThat(requests).hasValue(0);
		var responses = stream.doOnNext(response -> {
			if ("한글🙂".equals(response.getResult().getOutput().getText())) {
				observed.countDown();
			}
		}).collectList().toFuture().get(10, TimeUnit.SECONDS);
		assertThat(serverObserved.get(5, TimeUnit.SECONDS)).isTrue();
		assertThat(responses).hasSize(2);
		assertThat(responses.stream().map(r -> r.getResult().getOutput().getText()).toList())
			.containsExactly("한글🙂", "");
		assertThat(responses.stream().mapToInt(r -> r.getMetadata().getUsage().getTotalTokens()).sum()).isEqualTo(20);
		assertThat(responses.getLast().getResult().getOutput().getMetadata())
			.containsKey(ClaudeMessages.CONTENT_KEY);
		assertThat(stream.collectList().toFuture().get(10, TimeUnit.SECONDS)).hasSize(2);
		assertThat(requests).hasValue(2);
	}

	@Test
	void streamHidesPartialToolsAndUsageUntilValidatedHttpCompletion() throws Exception {
		CountDownLatch partial = new CountDownLatch(1);
		CountDownLatch finishTool = new CountDownLatch(1);
		CountDownLatch terminal = new CountDownLatch(1);
		CompletableFuture<Void> served = new CompletableFuture<>();
		URI endpoint = serve(exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(200, 0);
			try (var out = exchange.getResponseBody()) {
				String tool = toolSse();
				int split = tool.indexOf("data: {\"type\":\"content_block_delta\"", tool.indexOf("partial_json") + 1);
				out.write(tool.substring(0, split).getBytes(StandardCharsets.UTF_8));
				out.write("""
						data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":"barrier"}}

						data: {"type":"content_block_stop","index":1}

						""".getBytes(StandardCharsets.UTF_8));
				out.flush();
				if (!await(finishTool)) {
					return;
				}
				out.write(tool.substring(split).getBytes(StandardCharsets.UTF_8));
				out.flush();
				terminal.countDown();
				await(this.release);
			}
			served.complete(null);
		});
		List<ChatResponse> seen = Collections.synchronizedList(new ArrayList<>());
		var result = model(endpoint, Duration.ofSeconds(10), 8192).stream(new Prompt("tool"))
			.doOnNext(response -> {
				seen.add(response);
				if ("barrier".equals(response.getResult().getOutput().getText())) {
					partial.countDown();
				}
			}).collectList().toFuture();
		try {
			assertThat(partial.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(seen).hasSize(1).allSatisfy(response -> {
				assertThat(response.hasToolCalls()).isFalse();
				assertThat(response.getMetadata().getUsage().getTotalTokens()).isZero();
			});
			finishTool.countDown();
			assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(seen).hasSize(1);
			this.release.countDown();
			var responses = result.get(10, TimeUnit.SECONDS);
			assertThat(responses).hasSize(2);
			assertThat(responses.getLast().getResult().getOutput().getToolCalls().getFirst().arguments())
				.isEqualTo("{\"text\":\"from-model\"}");
			assertThat(responses.getLast().getMetadata().getUsage().getTotalTokens()).isEqualTo(20);
			served.get(5, TimeUnit.SECONDS);
		}
		finally {
			finishTool.countDown();
			this.release.countDown();
			result.cancel(true);
		}
	}

	@Test
	void cancellingOnFirstDeltaClosesActiveHttpBody() throws Exception {
		CountDownLatch cancelled = new CountDownLatch(1);
		CompletableFuture<Boolean> disconnected = new CompletableFuture<>();
		URI endpoint = serve(exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(200, 0);
			try (var out = exchange.getResponseBody()) {
				out.write(textSse("early").replace(endSse("end_turn"), "").getBytes(StandardCharsets.UTF_8));
				out.flush();
				if (!await(cancelled)) {
					disconnected.complete(false);
					return;
				}
				byte[] bytes = new byte[16384];
				for (int i = 0; i < 1024; i++) {
					out.write(bytes);
					out.flush();
				}
				disconnected.complete(false);
			}
			catch (IOException ex) {
				disconnected.complete(true);
			}
		});
		var subscriber = new reactor.core.publisher.BaseSubscriber<ChatResponse>() {
			@Override
			protected void hookOnNext(ChatResponse response) {
				cancel();
				cancelled.countDown();
			}
		};
		model(endpoint, Duration.ofSeconds(10), 8192).stream(new Prompt("hello")).subscribe(subscriber);
		assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(disconnected.get(5, TimeUnit.SECONDS)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "truncated", "error", "index", "mismatch", "json", "trailing", "unfinished" })
	void streamingFailuresNeverPublishTerminalUsageOrToolsOrRetry(String failure) throws Exception {
		AtomicInteger requests = new AtomicInteger();
		String prefix = textSse("observed").replace(endSse("end_turn"), "");
		String tail = switch (failure) {
			case "error" -> "data: {\"type\":\"error\",\"error\":{\"message\":\"private-secret\"}}\n\n";
			case "index" -> "data: {\"type\":\"content_block_stop\",\"index\":2}\n\n";
			case "mismatch" -> "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}\n\n";
			case "json" -> "data: {private-secret}\n\n";
			case "trailing" -> endSse("end_turn") + "data: {\"type\":\"message_delta\"}\n\n";
			case "unfinished" -> endSse("end_turn") + "data: {";
			default -> "";
		};
		CountDownLatch observed = new CountDownLatch(1);
		URI endpoint = serve(exchange -> {
			requests.incrementAndGet();
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(200, 0);
			try (var out = exchange.getResponseBody()) {
				out.write(prefix.getBytes(StandardCharsets.UTF_8));
				out.flush();
				if (await(observed)) {
					out.write(tail.getBytes(StandardCharsets.UTF_8));
				}
			}
		});
		List<ChatResponse> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
		var result = model(endpoint, Duration.ofSeconds(10), 8192).stream(new Prompt("hello"))
			.doOnNext(response -> {
				seen.add(response);
				observed.countDown();
			}).collectList().toFuture();
		assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(IllegalStateException.class)
			.satisfies(error -> assertThat(error.getCause().getMessage()).doesNotContain("private-secret"));
		assertThat(seen).hasSize(1).allSatisfy(response -> {
			assertThat(response.hasToolCalls()).isFalse();
			assertThat(response.getMetadata().getUsage().getTotalTokens()).isZero();
		});
		assertThat(requests).hasValue(1);
	}

	@Test
	void streamingSizeLimitFailsWithoutTerminalResponse() throws Exception {
		URI endpoint = serve(exchange -> respond(exchange, 200, textSse("x".repeat(4096))));
		assertThatThrownBy(() -> model(endpoint, Duration.ofSeconds(5), 128).stream(new Prompt("hello"))
			.collectList().toFuture().get(5, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class).hasCauseInstanceOf(UncheckedIOException.class);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void streamingDeadlineBoundsHeadersAndBody(boolean headers) throws Exception {
		URI endpoint = serve(exchange -> stall(exchange, headers));
		var result = model(endpoint, Duration.ofSeconds(1), 8192).stream(new Prompt("hello"))
			.collectList().toFuture();
		assertThat(this.entered.await(5, TimeUnit.SECONDS)).isTrue();
		assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
			.hasRootCauseInstanceOf(HttpTimeoutException.class);
	}

	@Test
	void streamingTerminalMessagePreservesSignedThinkingAndOpaqueReplay() throws Exception {
		String body = startSse() + """
				data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

				data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"private-plan"}}

				data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed"}}

				data: {"type":"content_block_stop","index":0}

				data: {"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"opaque"}}

				data: {"type":"content_block_stop","index":1}

				data: {"type":"content_block_start","index":2,"content_block":{"type":"text","text":"visible"}}

				""" + endSse("end_turn").replace("\"index\":0", "\"index\":2");
		URI endpoint = serve(exchange -> respond(exchange, 200, body));
		var responses = model(endpoint, Duration.ofSeconds(5), 8192).stream(new Prompt("hello"))
			.collectList().toFuture().get(5, TimeUnit.SECONDS);
		assertThat(responses.stream().map(r -> r.getResult().getOutput().getText()).toList())
			.containsExactly("visible", "");
		var nativeMessage = new ClaudeMessagesClient(endpoint, Duration.ofSeconds(5), 8192, "2.1.289")
			.parseSse(body.getBytes(StandardCharsets.UTF_8));
		var replay = ClaudeMessages.toRequest(new Prompt(responses.getLast().getResult().getOutput()),
				"fixture", 32, this.json);
		assertThat(replay.path("messages").path(0).path("content")).isEqualTo(nativeMessage.path("content"));
	}

	private static boolean await(CountDownLatch latch) {
		try {
			return latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	@Test
	void springAdvisorExecutesCanonicalToolAndReplaysItsResult() throws Exception {
		List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
		URI endpoint = serve(exchange -> {
			JsonNode payload = this.json.readTree(exchange.getRequestBody().readAllBytes());
			requests.add(payload);
			if (requests.size() == 1) {
				respond(exchange, 200, toolSse());
			}
			else {
				String data = payload.path("messages").path(2).path("content").path(0).path("content").asString();
				respond(exchange, 200, textSse(data));
			}
		});
		AtomicInteger executions = new AtomicInteger();
		var callback = FunctionToolCallback.<EchoInput, String>builder("read", (input, context) -> {
			executions.incrementAndGet();
			assertThat(context.getContext().get(ToolContextKeys.SESSION_ID)).isEqualTo("session-test");
			return input.text();
		}).description("Echo").inputType(EchoInput.class).build();
		var client = ChatClient.builder(model(endpoint, Duration.ofSeconds(5), 8192))
			.defaultOptions(ToolCallingChatOptions.builder().toolCallbacks(callback)).build();

		var result = client.prompt().user("tool")
			.toolContext(Map.of(ToolContextKeys.SESSION_ID, "session-test")).call().chatResponse();

		assertThat(executions).hasValue(1);
		assertThat(result.getResult().getOutput().getText()).isEqualTo("\"from-model\"");
		assertThat(requests).hasSize(2);
		assertThat(requests.getFirst().path("tools").path(0).path("name").asString()).isEqualTo("Read");
		JsonNode replay = requests.getLast().path("messages").path(1).path("content").path(0);
		assertThat(replay.path("type").asString()).isEqualTo("tool_use");
		assertThat(replay.path("name").asString()).isEqualTo("Read");
		assertThat(replay.path("input").path("text").asString()).isEqualTo("from-model");
	}

	@Test
	void deniedToolDoesNotBypassSpringInterceptor() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		URI endpoint = serve(exchange -> {
			JsonNode payload = this.json.readTree(exchange.getRequestBody().readAllBytes());
			respond(exchange, 200, requests.incrementAndGet() == 1 ? toolSse()
					: textSse(payload.path("messages").path(2).path("content").path(0).path("content").asString()));
		});
		var callback = FunctionToolCallback.<EchoInput, String>builder("read", (input, context) -> {
			throw new AssertionError("Denied tool must not execute");
		}).description("Echo").inputType(EchoInput.class).build();
		var denied = InterceptingToolCallback.wrap(callback, List.of((invocation, chain) -> "denied-sentinel"));
		var client = ChatClient.builder(model(endpoint, Duration.ofSeconds(5), 8192))
			.defaultOptions(ToolCallingChatOptions.builder().toolCallbacks(denied)).build();

		assertThat(client.prompt().user("tool").call().content()).isEqualTo("denied-sentinel");
		assertThat(requests).hasValue(2);
	}

	@Test
	void httpErrorNeverIncludesReflectedCredentials() throws Exception {
		URI endpoint = serve(exchange -> respond(exchange, 401, "sk-ant-oat-fixture refresh-fixture"));

		assertThatThrownBy(() -> model(endpoint, Duration.ofSeconds(5), 8192).call(new Prompt("hello")))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("401")
			.hasMessageNotContaining("sk-ant-oat-fixture").hasMessageNotContaining("refresh-fixture");
	}

	@ParameterizedTest
	@ValueSource(strings = { "sk-ant-api-fixture", "sk-ant-oat-secret\r\nextra-header" })
	void apiKeysAndUnsafeTokenHeadersAreRejectedBeforeNetwork(String token) {
		var client = new ClaudeMessagesClient(URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1), 8192, "2.1.289");

		assertThatThrownBy(() -> client.call(token, this.json.createObjectNode()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(token);
	}

	@Test
	void responseLimitFailsInsteadOfAcceptingPartialSse() throws Exception {
		URI endpoint = serve(exchange -> respond(exchange, 200, textSse("x".repeat(4096))));

		assertThatThrownBy(() -> model(endpoint, Duration.ofSeconds(5), 128).call(new Prompt("hello")))
			.isInstanceOf(UncheckedIOException.class);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void requestDeadlineBoundsHeadersAndBody(boolean headers) throws Exception {
		URI endpoint = serve(exchange -> stall(exchange, headers));
		var model = model(endpoint, Duration.ofSeconds(1), 8192);
		var result = this.workers.submit(() -> model.call(new Prompt("hello")));

		assertThat(this.entered.await(5, TimeUnit.SECONDS)).isTrue();
		assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(UncheckedIOException.class).hasRootCauseInstanceOf(HttpTimeoutException.class);
	}

	@Test
	void interruptCancelsBodyWaitAndPreservesInterruptFlag() throws Exception {
		URI endpoint = serve(exchange -> stall(exchange, true));
		var model = model(endpoint, Duration.ofSeconds(30), 8192);
		CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
		Thread caller = Thread.ofVirtual().start(() -> {
			try {
				model.call(new Prompt("hello"));
				interrupted.complete(false);
			}
			catch (IllegalStateException ex) {
				interrupted.complete(ex.getCause() instanceof InterruptedException && Thread.currentThread().isInterrupted());
			}
			catch (Throwable ex) {
				interrupted.completeExceptionally(ex);
			}
		});
		try {
			assertThat(this.entered.await(5, TimeUnit.SECONDS)).isTrue();
			caller.interrupt();
			assertThat(interrupted.get(5, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			caller.interrupt();
			caller.join(Duration.ofSeconds(5));
		}
		assertThat(caller.isAlive()).isFalse();
	}

	@Test
	void configurationRegistersJavaAuthAndIndependentFactoryAcrossProfiles() {
		var runner = new ApplicationContextRunner().withBean(AgentConsole.class,
				() -> new AgentConsole(new BufferedReader(new StringReader("")), System.out, false))
			.withConfiguration(AutoConfigurations.of(ClaudeModelAutoConfiguration.class));
		runner.withPropertyValues("spring.ai.model.chat=claude", "agent.claude.auth-store=" + this.dir.resolve("auth.json"))
			.run(context -> {
				assertThat(context).hasSingleBean(ModelProvider.class).hasSingleBean(ClaudeAuth.class)
					.hasSingleBean(ClaudeOAuth.class).doesNotHaveBean(ClaudeChatModel.class);
				var provider = context.getBean(ModelProvider.class);
				assertThat(provider.auth().available()).isFalse();
				ModelRef ref = provider.models().getFirst().ref();
				var first = provider.create(ref, Map.of());
				var second = provider.create(ref, Map.of());
				assertThat(first).isInstanceOf(ClaudeChatModel.class).isNotSameAs(second);
				assertThat(first.getOptions()).isInstanceOf(ToolCallingChatOptions.class);
			});
		runner.withPropertyValues("spring.ai.model.chat=anthropic",
				"agent.claude.auth-store=" + this.dir.resolve("auth.json"))
			.run(context -> assertThat(context).hasSingleBean(ModelProvider.class).doesNotHaveBean(ClaudeChatModel.class));
	}

	private void stall(HttpExchange exchange, boolean headers) throws IOException {
		exchange.getRequestBody().readAllBytes();
		if (headers) {
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody().write("data: ".getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
		}
		this.entered.countDown();
		try {
			this.release.await();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		finally {
			exchange.close();
		}
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
		exchange.sendResponseHeaders(status, bytes.length);
		try (var out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	static String startSse() {
		return """
				data: {"type":"message_start","message":{"id":"msg_1","model":"claude-sonnet-5-5","role":"assistant","content":[],"stop_reason":null,"usage":{"input_tokens":10,"output_tokens":0,"cache_read_input_tokens":3,"cache_creation_input_tokens":2}}}

				""";
	}

	static String endSse(String reason) {
		return """
				data: {"type":"content_block_stop","index":0}

				data: {"type":"message_delta","delta":{"stop_reason":"%s"},"usage":{"output_tokens":5,"input_tokens":null}}

				data: {"type":"message_stop"}

				""".formatted(reason);
	}

	static String textSse(String text) {
		String encoded = JsonMapper.builder().build().writeValueAsString(text);
		return startSse() + """
				data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

				data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":%s}}

				""".formatted(encoded) + endSse("end_turn");
	}

	static String toolSse() {
		return startSse() + """
				data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"Read","input":{}}}

				data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"text\\":"}}

				data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\\"from-model\\"}"}}

				""" + endSse("tool_use");
	}

}
