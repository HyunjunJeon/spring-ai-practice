package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** 로컬 HTTP 서버로 전송 계층을 확인한다: 정상 SSE, 오류 상태 코드, 서버가 멈췄을 때의 대기 제한. */
class CodexResponsesClientTest {

	private static final String COMPLETED_STREAM = """
			data: {"type":"response.output_item.done","item":{"type":"message","phase":"final_answer","content":[{"type":"output_text","text":"done"}]}}

			data: {"type":"response.completed","response":{"status":"completed"}}

			""";

	private final JsonMapper json = JsonMapper.builder().build();

	private final CodexCredentials credentials = new CodexCredentials("token", null, "account",
			Instant.now().plusSeconds(3600), "plus", CodexCredentials.Source.MINI_AGENT);

	private final CountDownLatch release = new CountDownLatch(1);

	private final ExecutorService handlers = Executors.newCachedThreadPool();

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
	void completedStreamIsParsed() throws IOException {
		CodexResponsesClient client = client(exchange -> respond(exchange, 200, COMPLETED_STREAM), Duration.ofSeconds(10));

		CodexResponses.Result result = client.send(this.json.createObjectNode(), this.credentials, "session-1");

		assertThat(result.items()).hasSize(1);
	}

	@Test
	void errorStatusBecomesHttpException() throws IOException {
		CodexResponsesClient client = client(
				exchange -> respond(exchange, 429, "{\"error\":{\"message\":\"usage limit\",\"resets_in_seconds\":600}}"),
				Duration.ofSeconds(10));

		assertThatThrownBy(() -> client.send(this.json.createObjectNode(), this.credentials, null))
			.isInstanceOfSatisfying(CodexResponsesClient.CodexHttpException.class,
					ex -> assertThat(ex.status()).isEqualTo(429))
			.hasMessageContaining("사용 한도");
	}

	@Test
	void stalledStreamIsCutAfterIdleTimeout() throws IOException {
		CodexResponsesClient client = client(exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			OutputStream body = exchange.getResponseBody();
			body.write("data: {\"type\":\"response.created\"}\n\n".getBytes(StandardCharsets.UTF_8));
			body.flush();
			// 첫 이벤트만 보내고 서버가 멈춘다.
			await();
		}, Duration.ofMillis(300));

		assertTimeoutPreemptively(Duration.ofSeconds(10),
				() -> assertThatThrownBy(() -> client.send(this.json.createObjectNode(), this.credentials, null))
					.hasMessageContaining("idle-timeout"));
	}

	@Test
	void missingResponseHeadersAreCutAfterIdleTimeout() throws IOException {
		CodexResponsesClient client = client(exchange -> await(), Duration.ofMillis(300));

		assertTimeoutPreemptively(Duration.ofSeconds(10),
				() -> assertThatThrownBy(() -> client.send(this.json.createObjectNode(), this.credentials, null))
					.hasMessageContaining("idle-timeout"));
	}

	private CodexResponsesClient client(HttpHandler handler, Duration idleTimeout) throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		this.server.createContext("/responses", handler);
		this.server.setExecutor(this.handlers);
		this.server.start();
		return new CodexResponsesClient(this.json, "http://127.0.0.1:" + this.server.getAddress().getPort(), "test",
				idleTimeout);
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private void await() {
		try {
			this.release.await();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
