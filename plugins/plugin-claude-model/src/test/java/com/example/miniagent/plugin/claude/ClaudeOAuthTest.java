package com.example.miniagent.plugin.claude;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeOAuthTest {

	private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");

	private final JsonMapper json = JsonMapper.builder().build();

	private final ExecutorService workers = Executors.newCachedThreadPool();

	private final CountDownLatch stalled = new CountDownLatch(1);

	private final CountDownLatch release = new CountDownLatch(1);

	private HttpServer server;

	@AfterEach
	void stop() {
		this.release.countDown();
		if (this.server != null) {
			this.server.stop(0);
		}
		this.workers.shutdownNow();
	}

	@Test
	void authorizationKeepsPkceVerifierSecretAndUsesIndependentState() throws Exception {
		ClaudeOAuth oauth = oauth(exchange -> respond(exchange, 500, "{}"), Duration.ofSeconds(5));

		ClaudeOAuth.Authorization authorization = oauth.authorization();
		Map<String, String> query = parseQuery(authorization.uri().getRawQuery());

		assertThat(authorization.uri().getScheme()).isEqualTo("https");
		assertThat(authorization.uri().getHost()).isEqualTo("claude.ai");
		assertThat(authorization.uri().getPath()).isEqualTo("/oauth/authorize");
		assertThat(query).containsEntry("code", "true")
			.containsEntry("response_type", "code")
			.containsEntry("client_id", "9d1c250a-e61b-44d9-88ed-5944d1962f5e")
			.containsEntry("redirect_uri", "https://platform.claude.com/oauth/code/callback")
			.containsEntry("scope", "org:create_api_key user:profile user:inference user:sessions:claude_code "
					+ "user:mcp_servers user:file_upload")
			.containsEntry("code_challenge_method", "S256")
			.containsEntry("state", authorization.state());
		String expectedChallenge = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(MessageDigest.getInstance("SHA-256")
				.digest(authorization.verifier().getBytes(StandardCharsets.US_ASCII)));
		assertThat(query.get("code_challenge")).isEqualTo(expectedChallenge);
		assertThat(authorization.state()).isNotEqualTo(authorization.verifier());
		assertThat(authorization.uri().toString()).contains(expectedChallenge).contains(authorization.state())
			.doesNotContain(authorization.verifier());
	}

	@ParameterizedTest
	@ValueSource(strings = { "code-value#state-value",
			"https://platform.claude.com/oauth/code/callback?code=code-value&state=state-value", "code-value" })
	void exchangeAcceptsSupportedPasteFormsAndPostsAllFields(String pasted) throws Exception {
		CompletableFuture<JsonNode> request = new CompletableFuture<>();
		ClaudeOAuth oauth = oauth(exchange -> {
			request.complete(this.json.readTree(exchange.getRequestBody()));
			respond(exchange, 200, """
					{"access_token":"sk-ant-oat-access","refresh_token":"sk-ant-oat-refresh","expires_in":3600}
					""");
		}, Duration.ofSeconds(5));
		ClaudeOAuth.Authorization authorization = new ClaudeOAuth.Authorization(URI.create("https://example.test"),
				"verifier-value", "state-value");

		ClaudeCredentials credentials = oauth.exchange(pasted, authorization);
		JsonNode body = request.get(5, TimeUnit.SECONDS);

		assertThat(body.path("grant_type").asString()).isEqualTo("authorization_code");
		assertThat(body.path("client_id").asString()).isEqualTo("9d1c250a-e61b-44d9-88ed-5944d1962f5e");
		assertThat(body.path("code").asString()).isEqualTo("code-value");
		assertThat(body.path("state").asString()).isEqualTo("state-value");
		assertThat(body.path("redirect_uri").asString())
			.isEqualTo("https://platform.claude.com/oauth/code/callback");
		assertThat(body.path("code_verifier").asString()).isEqualTo("verifier-value");
		assertThat(credentials).isEqualTo(new ClaudeCredentials("sk-ant-oat-access", "sk-ant-oat-refresh",
				NOW.plusSeconds(3600).toEpochMilli()));
	}

	@Test
	void providedStateIsRejectedBeforeNetworkRequest() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		ClaudeOAuth oauth = oauth(exchange -> {
			requests.incrementAndGet();
			respond(exchange, 200, "{}");
		}, Duration.ofSeconds(5));
		ClaudeOAuth.Authorization authorization = new ClaudeOAuth.Authorization(URI.create("https://example.test"),
				"verifier", "expected");

		assertThatThrownBy(() -> oauth.exchange("code#wrong", authorization))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("state")
			.hasMessageNotContaining("code");
		assertThat(requests).hasValue(0);
	}

	@Test
	void refreshRotatesTokenAndPreservesOmittedRefresh() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		ClaudeOAuth oauth = oauth(exchange -> {
			JsonNode body = this.json.readTree(exchange.getRequestBody());
			assertThat(body.path("grant_type").asString()).isEqualTo("refresh_token");
			assertThat(body.path("client_id").asString()).isEqualTo("9d1c250a-e61b-44d9-88ed-5944d1962f5e");
			assertThat(body.path("refresh_token").asString()).startsWith("old-");
			int request = requests.incrementAndGet();
			respond(exchange, 200, request == 1
					? """
							{"access_token":"sk-ant-oat-one","refresh_token":"rotated","expires_in":60}
							"""
					: """
							{"access_token":"sk-ant-oat-two","expires_in":120}
							""");
		}, Duration.ofSeconds(5));

		ClaudeCredentials rotated = oauth.refresh("old-one");
		ClaudeCredentials preserved = oauth.refresh("old-two");

		assertThat(rotated.refreshToken()).isEqualTo("rotated");
		assertThat(preserved.refreshToken()).isEqualTo("old-two");
		assertThat(preserved.expiresAtMillis()).isEqualTo(NOW.plusSeconds(120).toEpochMilli());
	}

	@Test
	void loginRequiresRefreshAndPositiveExpiryWithoutLeakingResponse() throws Exception {
		ClaudeOAuth oauth = oauth(exchange -> respond(exchange, 200,
				"""
						{"access_token":"secret-access","expires_in":0,"server_detail":"secret-body"}
						"""), Duration.ofSeconds(5));
		ClaudeOAuth.Authorization authorization = new ClaudeOAuth.Authorization(URI.create("https://example.test"),
				"verifier", "state");

		assertThatThrownBy(() -> oauth.exchange("secret-code#state", authorization))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageNotContaining("secret-access")
			.hasMessageNotContaining("secret-code")
			.hasMessageNotContaining("secret-body");
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void timeoutBoundsHeaderAndBodyWait(boolean sendHeaders) throws Exception {
		ClaudeOAuth oauth = oauth(exchange -> stall(exchange, sendHeaders), Duration.ofMillis(200));

		Future<ClaudeCredentials> result = this.workers.submit(() -> oauth.refresh("refresh"));
		assertThat(this.stalled.await(5, TimeUnit.SECONDS)).isTrue();
		assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(UncheckedIOException.class)
			.hasRootCauseInstanceOf(HttpTimeoutException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "headers", "body" })
	void oversizedResponsesAreRejectedWithoutEchoingContent(String part) throws Exception {
		String secret = "server-secret";
		ClaudeOAuth oauth = oauth(exchange -> {
			if (part.equals("headers")) {
				exchange.getResponseHeaders().add("X-Oversized", secret.repeat(4_000));
				respond(exchange, 200, "{}");
			}
			else {
				respond(exchange, 200, secret.repeat(8_000));
			}
		}, Duration.ofSeconds(5));

		assertThatThrownBy(() -> oauth.refresh("refresh"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("too large")
			.hasMessageNotContaining(secret);
	}

	@Test
	void interruptionCancelsBodyReadAndPreservesFlag() throws Exception {
		ClaudeOAuth oauth = oauth(exchange -> stall(exchange, true), Duration.ofSeconds(30));
		CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
		Thread caller = Thread.ofVirtual().start(() -> {
			try {
				oauth.refresh("refresh");
				interrupted.complete(false);
			}
			catch (IllegalStateException ex) {
				interrupted.complete(ex.getCause() instanceof InterruptedException
						&& Thread.currentThread().isInterrupted());
			}
			catch (Throwable ex) {
				interrupted.completeExceptionally(ex);
			}
		});
		try {
			assertThat(this.stalled.await(5, TimeUnit.SECONDS)).isTrue();
			caller.interrupt();
			assertThat(interrupted.get(5, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			caller.interrupt();
			caller.join(Duration.ofSeconds(5));
		}
		assertThat(caller.isAlive()).isFalse();
	}

	private ClaudeOAuth oauth(HttpHandler handler, Duration timeout) throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", handler);
		this.server.setExecutor(this.workers);
		this.server.start();
		URI tokenUri = URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/token");
		return new ClaudeOAuth(URI.create("https://claude.ai/oauth/authorize"), tokenUri, timeout,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private void stall(HttpExchange exchange, boolean sendHeaders) throws IOException {
		exchange.getRequestBody().readAllBytes();
		if (sendHeaders) {
			exchange.sendResponseHeaders(200, 100);
			exchange.getResponseBody().write('{');
			exchange.getResponseBody().flush();
		}
		this.stalled.countDown();
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
		exchange.sendResponseHeaders(status, bytes.length);
		try (var output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private static Map<String, String> parseQuery(String query) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String pair : query.split("&")) {
			int separator = pair.indexOf('=');
			result.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
		}
		return result;
	}

}
