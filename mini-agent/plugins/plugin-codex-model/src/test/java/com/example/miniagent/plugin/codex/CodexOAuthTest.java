package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 로컬 전송으로 헤더 대기, 본문 정지와 취소를 확인한다. */
class CodexOAuthTest {

	private final CountDownLatch stalled = new CountDownLatch(1);

	private final CountDownLatch release = new CountDownLatch(1);

	private final ExecutorService workers = Executors.newCachedThreadPool();

	private HttpServer server;

	@AfterEach
	void stop() {
		this.release.countDown();
		if (this.server != null) {
			this.server.stop(0);
		}
		this.workers.shutdownNow();
	}

	@ParameterizedTest
	@CsvSource({ "exchange,false", "exchange,true", "refresh,false", "refresh,true",
			"usercode,false", "usercode,true", "poll,false", "poll,true" })
	void allOAuthRequestsBoundHeadersAndBody(String operation, boolean sendHeaders) throws Exception {
		CodexOAuth oauth = oauth(exchange -> {
			if (operation.equals("poll") && exchange.getRequestURI().getPath().endsWith("/usercode")) {
				respond(exchange, """
						{"device_auth_id":"device","user_code":"code","interval":1}
						""");
				return;
			}
			stall(exchange, sendHeaders);
		}, Duration.ofSeconds(1));

		Future<CodexCredentials> result = this.workers.submit(() -> invoke(oauth, operation));
		assertThat(this.stalled.await(5, TimeUnit.SECONDS)).isTrue();
		assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(UncheckedIOException.class)
			.hasRootCauseInstanceOf(HttpTimeoutException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "exchange", "refresh" })
	void completedTokenResponsesStillParse(String operation) throws Exception {
		String claims = """
				{"exp":4102444800,"https://api.openai.com/auth":{"chatgpt_account_id":"account"}}
				""";
		String token = "header." + Base64.getUrlEncoder().withoutPadding()
			.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".sig";
		CodexOAuth oauth = oauth(exchange -> {
			String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(form).contains("grant_type=" + (operation.equals("refresh") ? "refresh_token" : "authorization_code"));
			respond(exchange, "{\"access_token\":\"" + token + "\",\"refresh_token\":\"rotated\"}");
		}, Duration.ofSeconds(5));

		CodexCredentials credentials = invoke(oauth, operation);

		assertThat(credentials.accessToken()).isEqualTo(token);
		assertThat(credentials.refreshToken()).isEqualTo("rotated");
		assertThat(credentials.accountId()).isEqualTo("account");
	}

	@Test
	void interruptionStopsBodyWaitAndPreservesInterrupt() throws Exception {
		CodexOAuth oauth = oauth(exchange -> stall(exchange, true), Duration.ofSeconds(30));
		CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
		Thread caller = Thread.ofVirtual().start(() -> {
			try {
				oauth.refresh("refresh");
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

	private CodexOAuth oauth(HttpHandler handler, Duration timeout) throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", handler);
		this.server.setExecutor(this.workers);
		this.server.start();
		return new CodexOAuth("test", URI.create("http://127.0.0.1:" + this.server.getAddress().getPort()), timeout);
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

	private static void respond(HttpExchange exchange, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(200, bytes.length);
		try (var out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static CodexCredentials invoke(CodexOAuth oauth, String operation) {
		return switch (operation) {
			case "exchange" -> oauth.exchange("code", "verifier", "http://localhost/callback");
			case "refresh" -> oauth.refresh("refresh");
			case "usercode", "poll" -> oauth.deviceLogin(new CodexOAuth.LoginUi() {
				@Override
				public void show(String message) {
				}

				@Override
				public String ask(String prompt) {
					throw new AssertionError("device login must not prompt");
				}

				@Override
				public void openBrowser(String url) {
					throw new AssertionError("device login must not open a browser");
				}
			});
			default -> throw new IllegalArgumentException(operation);
		};
	}

}
