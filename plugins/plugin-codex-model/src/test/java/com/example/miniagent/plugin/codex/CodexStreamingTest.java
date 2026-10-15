package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.ToolContextKeys;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.TurnContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexStreamingTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final String DELTA = "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}";

	private static final String TOOL = """
			{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"echo","arguments":"{\\"text\\":\\"ok\\"}"}}
			""";

	private static final String TERMINAL = """
			{"type":"response.completed","response":{"id":"r1","status":"completed","usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5}}}
			""";

	private final JsonMapper json = JsonMapper.builder().build();

	private final CodexAuth auth = mock(CodexAuth.class);

	private final CodexCredentials credentials = new CodexCredentials("synthetic", "synthetic-refresh", "account",
			Instant.now().plusSeconds(3600), "plus", CodexCredentials.Source.MINI_AGENT);

	private final ExecutorService handlers = Executors.newCachedThreadPool();

	private final CountDownLatch release = new CountDownLatch(1);

	private HttpServer server;

	@AfterEach
	void stop() {
		this.release.countDown();
		if (this.server != null) {
			this.server.stop(0);
		}
		this.handlers.shutdownNow();
	}

	@Test
	void deltaUnlocksTerminalAndOnlyTerminalExposesToolsUsageAndReasoning() throws Exception {
		CountDownLatch deltaObserved = new CountDownLatch(1);
		CompletableFuture<String> request = new CompletableFuture<>();
		AtomicInteger requests = new AtomicInteger();
		CodexChatModel model = model(exchange -> {
			requests.incrementAndGet();
			request.complete(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			try (OutputStream out = sse(exchange)) {
				event(out, "{\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{\\\"text\\\":\"}");
				event(out, "{\"type\":\"response.function_call_arguments.delta\",\"delta\":\"\\\"ok\\\"}\"}");
				event(out, TOOL);
				event(out, DELTA);
				await(deltaObserved);
				event(out, "{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}}");
				event(out, "{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"opaque\"}}");
				event(out, TERMINAL);
			}
		});
		ToolCallback tool = mock(ToolCallback.class);
		TurnContext turn = new TurnContext("session", "turn", Path.of("."), mock(ModelBinding.class));
		when(tool.getToolDefinition()).thenAnswer(invocation -> {
			assertThat(TurnContext.current()).contains(turn);
			return ToolDefinition.builder().name("echo").description("Echo").inputSchema("{\"type\":\"object\"}").build();
		});
		Prompt prompt = new Prompt("hi", ToolCallingChatOptions.builder().toolCallbacks(tool)
			.toolContext(Map.of(ToolContextKeys.SESSION_ID, "captured")).build());
		Flux<ChatResponse> stream;
		try (var scope = turn.open()) {
			stream = model.stream(prompt);
		}
		verify(this.auth, never()).current();
		assertThat(requests).hasValue(0);

		List<ChatResponse> chunks = stream.doOnNext(chunk -> {
			if ("hello".equals(chunk.getResult().getOutput().getText())) {
				assertThat(chunk.hasToolCalls()).isFalse();
				assertThat(chunk.getMetadata().getUsage().getTotalTokens()).isZero();
				deltaObserved.countDown();
			}
		}).collectList().block(TIMEOUT);

		assertThat(chunks).hasSize(2);
		assertThat(chunks.stream().map(chunk -> chunk.getResult().getOutput().getText()).toList())
			.containsExactly("hello", "");
		ChatResponse terminal = chunks.getLast();
		assertThat(terminal.getResult().getOutput().getToolCalls()).hasSize(1);
		assertThat(terminal.getResult().getOutput().getToolCalls().getFirst().arguments()).isEqualTo("{\"text\":\"ok\"}");
		assertThat(chunks.stream().mapToInt(chunk -> chunk.getMetadata().getUsage().getTotalTokens()).sum()).isEqualTo(5);
		assertThat(terminal.getResult().getOutput().getMetadata().get(CodexResponses.OUTPUT_ITEMS).toString())
			.contains("rs_1", "opaque", "call_1");
		assertThat(request.get(10, TimeUnit.SECONDS)).contains("mini-agent-captured");
		assertThat(CodexResponses.toRequest(new Prompt(List.of(terminal.getResult().getOutput())), "m", "low", null,
				this.json).path("input").toString()).contains("opaque", "call_1");
		// Each subscription owns a fresh exchange.
		assertThat(stream.collectList().block(TIMEOUT)).hasSize(2);
		assertThat(requests).hasValue(2);
		verify(tool, never()).call(any(String.class));
	}

	@Test
	void failedIncompleteAndTruncatedStreamsNeverExposeToolsOrUsageAndNeverRetry() throws Exception {
		AtomicReference<String> ending = new AtomicReference<>("");
		AtomicInteger requests = new AtomicInteger();
		CodexChatModel model = model(exchange -> {
			requests.incrementAndGet();
			try (OutputStream out = sse(exchange)) {
				event(out, DELTA);
				event(out, TOOL);
				if (!ending.get().isEmpty()) {
					event(out, ending.get());
				}
			}
		});
		List<String> endings = List.of("", "{\"type\":\"response.failed\"}", "{\"type\":\"response.incomplete\"}",
				"{\"type\":\"response.done\",\"response\":{\"status\":\"failed\"}}", "{\"type\":\"error\",\"message\":\"bad\"}");
		for (String value : endings) {
			ending.set(value);
			List<ChatResponse> observed = new CopyOnWriteArrayList<>();
			assertThatThrownBy(() -> model.stream(new Prompt("hi")).doOnNext(observed::add).blockLast(TIMEOUT))
				.isInstanceOf(CodexResponses.CodexApiException.class);
			assertThat(observed).hasSize(1).allSatisfy(chunk -> {
				assertThat(chunk.hasToolCalls()).isFalse();
				assertThat(chunk.getMetadata().getUsage().getTotalTokens()).isZero();
			});
		}
		assertThat(requests).hasValue(endings.size());
		verify(this.auth, never()).recoverFromUnauthorized(any());
	}

	@Test
	void invalidToolJsonIsRejectedAtTerminal() throws Exception {
		CodexChatModel model = model(exchange -> {
			try (OutputStream out = sse(exchange)) {
				event(out, TOOL.replace("{\\\"text\\\":\\\"ok\\\"}", "{"));
				event(out, TERMINAL);
			}
		});
		List<ChatResponse> observed = new CopyOnWriteArrayList<>();
		assertThatThrownBy(() -> model.stream(new Prompt("hi")).doOnNext(observed::add).blockLast(TIMEOUT))
			.isInstanceOf(RuntimeException.class);
		assertThat(observed).isEmpty();
	}

	@Test
	void cancellingAfterDeltaClosesHttpWhileServerIsStillStreaming() throws Exception {
		CompletableFuture<Void> disconnected = new CompletableFuture<>();
		CodexChatModel model = model(exchange -> {
			try (OutputStream out = sse(exchange)) {
				event(out, DELTA);
				await(this.release);
				byte[] bytes = new byte[8192];
				for (int i = 0; i < 8192; i++) {
					out.write(bytes);
					out.flush();
				}
				disconnected.completeExceptionally(new AssertionError("Cancelled HTTP remained writable"));
			}
			catch (IOException expected) {
				disconnected.complete(null);
			}
		});
		assertThat(model.stream(new Prompt("hi")).take(1).collectList().block(TIMEOUT)).hasSize(1);
		this.release.countDown();
		disconnected.get(10, TimeUnit.SECONDS);
	}

	@Test
	void cancellationWhileAwaitingHeadersDisposesExchange() throws Exception {
		CountDownLatch requested = new CountDownLatch(1);
		CompletableFuture<Void> disconnected = new CompletableFuture<>();
		CodexChatModel model = model(exchange -> {
			requested.countDown();
			await(this.release);
			try (OutputStream out = sse(exchange)) {
				out.write(new byte[1024 * 1024]);
				out.flush();
				disconnected.completeExceptionally(new AssertionError("Cancelled request remained open"));
			}
			catch (IOException expected) {
				disconnected.complete(null);
			}
		});
		BaseSubscriber<ChatResponse> subscriber = new BaseSubscriber<>() { };
		model.stream(new Prompt("hi")).subscribe(subscriber);
		await(requested);
		subscriber.cancel();
		this.release.countDown();
		disconnected.get(10, TimeUnit.SECONDS);
	}

	@Test
	void boundedByteSubscriberRequestsOneBatchAndCancelsEvenBeforeSubscribe() {
		CodexResponsesClient.SseLines lines = new CodexResponsesClient.SseLines(TIMEOUT);
		Flow.Subscription subscription = mock(Flow.Subscription.class);
		lines.onSubscribe(subscription);
		verify(subscription).request(1);
		lines.onNext(List.of(ByteBuffer.wrap("one\n".getBytes(StandardCharsets.UTF_8))));
		assertThat(lines.next()).isEqualTo("one");
		lines.onNext(List.of(ByteBuffer.allocate(CodexResponsesClient.SseLines.MAX_RESPONSE_BYTES)));
		assertThatThrownBy(lines::hasNext).hasMessageContaining("exceeds");
		verify(subscription).cancel();
		lines.cancel();

		CodexResponsesClient.SseLines cancelled = new CodexResponsesClient.SseLines(TIMEOUT);
		cancelled.cancel();
		Flow.Subscription late = mock(Flow.Subscription.class);
		cancelled.onSubscribe(late);
		verify(late).cancel();
		verify(late, never()).request(anyLong());
	}

	@Test
	void interruptedReadClosesHttpAndPreservesInterruptFlag() throws Exception {
		CountDownLatch deltaSent = new CountDownLatch(1);
		CompletableFuture<Void> disconnected = new CompletableFuture<>();
		CodexChatModel model = model(exchange -> {
			try (OutputStream out = sse(exchange)) {
				event(out, DELTA);
				deltaSent.countDown();
				await(this.release);
				for (int i = 0; i < 8192; i++) {
					out.write(new byte[8192]);
					out.flush();
				}
				disconnected.completeExceptionally(new AssertionError("Interrupted HTTP remained open"));
			}
			catch (IOException expected) {
				disconnected.complete(null);
			}
		});
		CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
		Thread worker = new Thread(() -> {
			try {
				model.call(new Prompt("hi"));
				interrupted.complete(false);
			}
			catch (RuntimeException ex) {
				interrupted.complete(Thread.currentThread().isInterrupted());
			}
		});
		worker.start();
		try {
			await(deltaSent);
			worker.interrupt();
			assertThat(interrupted.get(10, TimeUnit.SECONDS)).isTrue();
			this.release.countDown();
			disconnected.get(10, TimeUnit.SECONDS);
		}
		finally {
			worker.interrupt();
			worker.join(10000);
		}
	}

	@Test
	void unauthorizedRecoveryUsesFailedCredentialsOnlyAndRetriesOnce() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		CodexChatModel model = model(exchange -> {
			if (requests.incrementAndGet() == 1) {
				exchange.sendResponseHeaders(401, -1);
				exchange.close();
			}
			else {
				assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer recovered");
				try (OutputStream out = sse(exchange)) {
					event(out, TERMINAL);
				}
			}
		});
		CodexCredentials recovered = new CodexCredentials("recovered", null, "account",
				Instant.now().plusSeconds(3600), "plus", CodexCredentials.Source.MINI_AGENT);
		when(this.auth.recoverFromUnauthorized(this.credentials)).thenReturn(Optional.of(recovered));
		assertThat(model.stream(new Prompt("hi")).collectList().block(TIMEOUT)).hasSize(1);
		verify(this.auth).recoverFromUnauthorized(this.credentials);
		assertThat(requests).hasValue(2);
	}

	@Test
	void cliUnauthorizedDoesNotReplayStream() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		CodexChatModel model = model(exchange -> {
			requests.incrementAndGet();
			exchange.sendResponseHeaders(401, -1);
			exchange.close();
		});
		CodexCredentials cli = new CodexCredentials("cli", null, "account",
				Instant.now().plusSeconds(3600), "plus", CodexCredentials.Source.CODEX_CLI);
		when(this.auth.current()).thenReturn(cli);
		when(this.auth.recoverFromUnauthorized(cli)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> model.stream(new Prompt("hi")).blockLast(TIMEOUT))
			.isInstanceOf(CodexResponsesClient.CodexHttpException.class);
		assertThat(requests).hasValue(1);
		verify(this.auth).recoverFromUnauthorized(cli);
	}

	private CodexChatModel model(HttpHandler handler) throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/responses", handler);
		this.server.setExecutor(this.handlers);
		this.server.start();
		when(this.auth.current()).thenReturn(this.credentials);
		return new CodexChatModel(this.auth, new CodexResponsesClient(this.json,
				"http://127.0.0.1:" + this.server.getAddress().getPort(), "test", TIMEOUT), this.json, "m", "low");
	}

	private static OutputStream sse(HttpExchange exchange) throws IOException {
		exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
		exchange.sendResponseHeaders(200, 0);
		return exchange.getResponseBody();
	}

	private static void event(OutputStream out, String json) throws IOException {
		out.write(("data: " + json.strip() + "\n\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new AssertionError("Fixture event not observed");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AssertionError(ex);
		}
	}

}
