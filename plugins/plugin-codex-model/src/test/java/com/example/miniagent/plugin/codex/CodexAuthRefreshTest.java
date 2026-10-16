package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 자체 토큰(/login) 수명 주기: 만료 5분 전 갱신, 회전된 토큰 저장, /codex refresh, 모드별 토큰 출처. */
class CodexAuthRefreshTest {

	private final JsonMapper json = JsonMapper.builder().build();

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

	@Test
	void ownTokenFourMinutesFromExpiryIsRefreshedAndTheRotatedTokenIsSaved() throws Exception {
		CodexAuth auth = auth("auto");
		auth.saveOwn(CodexEndpoints.ownToken(Instant.now().plusSeconds(240), "refresh-1"));
		this.endpoints.honor("refresh-1");

		CodexCredentials current = auth.current();

		assertThat(this.endpoints.tokenRequests()).singleElement().satisfies(form -> {
			assertThat(form).containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", "refresh-1");
			assertThat(form.get("client_id")).isNotBlank();
		});
		assertThat(current.source()).isEqualTo(CodexCredentials.Source.MINI_AGENT);
		assertThat(current.refreshToken()).isEqualTo("rotated-1");
		JsonNode saved = this.json.readTree(Files.readString(ownStore()));
		assertThat(saved.path("access_token").asString()).isEqualTo(current.accessToken());
		assertThat(saved.path("refresh_token").asString()).isEqualTo("rotated-1");
		// 새 토큰은 만료까지 한 시간 남았으므로 다음 호출은 갱신하지 않는다.
		assertThat(auth.current().accessToken()).isEqualTo(current.accessToken());
		assertThat(this.endpoints.tokenRequests()).hasSize(1);
	}

	@Test
	void ownTokenSevenMinutesFromExpiryIsUsedWithoutRefresh() {
		CodexAuth auth = auth("auto");
		CodexCredentials own = CodexEndpoints.ownToken(Instant.now().plusSeconds(420), "refresh-1");
		auth.saveOwn(own);
		this.endpoints.honor("refresh-1");

		assertThat(auth.current().accessToken()).isEqualTo(own.accessToken());
		assertThat(this.endpoints.tokenRequests()).isEmpty();
	}

	@Test
	void refreshOwnNowRotatesAStillValidOwnToken() {
		CodexAuth auth = auth("auto");
		auth.saveOwn(CodexEndpoints.ownToken(Instant.now().plusSeconds(3600), "refresh-1"));
		this.endpoints.honor("refresh-1");

		CodexCredentials refreshed = auth.refreshOwnNow();

		assertThat(this.endpoints.presentedRefreshTokens()).containsExactly("refresh-1");
		assertThat(refreshed.refreshToken()).isEqualTo("rotated-1");
		assertThat(auth.readOwn()).get().extracting(CodexCredentials::accessToken).isEqualTo(refreshed.accessToken());
	}

	@Test
	void refreshOwnNowWithoutOwnTokenAsksForLoginAndLeavesTheCliTokenAlone() throws Exception {
		CodexEndpoints.writeCodexCli(cliStore(), Instant.now().plusSeconds(3600));
		byte[] cli = Files.readAllBytes(cliStore());

		assertThatThrownBy(auth("auto")::refreshOwnNow).isInstanceOf(CodexAuth.CodexAuthException.class)
			.hasMessageContaining("/login");

		assertThat(this.endpoints.tokenRequests()).isEmpty();
		assertThat(Files.readAllBytes(cliStore())).isEqualTo(cli);
	}

	@Test
	void miniAgentModeWithoutOwnTokenRefusesTheCliFallback() throws Exception {
		CodexEndpoints.writeCodexCli(cliStore(), Instant.now().plusSeconds(3600));
		byte[] cli = Files.readAllBytes(cliStore());

		assertThatThrownBy(auth("mini-agent")::current).isInstanceOf(CodexAuth.CodexAuthException.class)
			.hasMessageContaining("/login")
			.hasMessageNotContaining("codex login");

		assertThat(ownStore()).doesNotExist();
		assertThat(Files.readAllBytes(cliStore())).isEqualTo(cli);
	}

	@Test
	void codexCliModeUsesTheCliTokenAndNeverRefreshesTheOwnToken() throws Exception {
		CodexAuth auth = auth("codex-cli");
		auth.saveOwn(CodexEndpoints.ownToken(Instant.now().plusSeconds(60), "refresh-1"));
		this.endpoints.honor("refresh-1");
		CodexEndpoints.writeCodexCli(cliStore(), Instant.now().plusSeconds(3600));
		byte[] own = Files.readAllBytes(ownStore());
		byte[] cli = Files.readAllBytes(cliStore());

		CodexCredentials current = auth.current();

		assertThat(current.source()).isEqualTo(CodexCredentials.Source.CODEX_CLI);
		assertThat(current.refreshToken()).isNull();
		assertThat(this.endpoints.tokenRequests()).isEmpty();
		assertThat(Files.readAllBytes(ownStore())).isEqualTo(own);
		assertThat(Files.readAllBytes(cliStore())).isEqualTo(cli);
	}

	@Test
	void noTokenAnywhereExplainsBothWaysToLogIn() {
		assertThatThrownBy(auth("auto")::current).isInstanceOf(CodexAuth.CodexAuthException.class)
			.hasMessageContaining("/login")
			.hasMessageContaining("codex login");
		assertThat(this.endpoints.tokenRequests()).isEmpty();
	}

	private CodexAuth auth(String mode) {
		return new CodexAuth(ownStore(), cliStore(), mode, this.endpoints.oauth());
	}

	private Path ownStore() {
		return this.dir.resolve("auth/openai-codex.json");
	}

	private Path cliStore() {
		return this.dir.resolve("codex/auth.json");
	}

}

/**
 * 127.0.0.1 임의 포트의 가짜 OpenAI 엔드포인트. 실제 자격 증명이나 외부 네트워크를 쓰지 않는다.
 *
 * <p>{@code /oauth/token} 은 실제 서버처럼 refresh token 을 회전시킨다. 지금 유효한 refresh token 으로만
 * 새 토큰을 내주고(n 번째 발급의 refresh token 은 {@code rotated-n}), 이미 쓴 것을 다시 내밀면 400 invalid_grant 로
 * 거절한다. {@code /responses} 는 테스트가 넣은 처리기가 답하고, 요청마다 헤더와 본문을 기록한다.
 */
final class CodexEndpoints implements AutoCloseable {

	static final String ACCOUNT = "acct_synthetic";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ExecutorService handlers = Executors.newCachedThreadPool();

	private final HttpServer server;

	private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();

	private final AtomicInteger tokenArrivals = new AtomicInteger();

	private final CountDownLatch secondTokenArrival = new CountDownLatch(1);

	private final List<ResponsesRequest> responsesRequests = new CopyOnWriteArrayList<>();

	private volatile HttpHandler responses = exchange -> status(exchange, 404);

	private volatile @Nullable Duration holdFirstGrant;

	private @Nullable String validRefresh;

	private int issued;

	record ResponsesRequest(Headers headers, JsonNode body) {
	}

	CodexEndpoints() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/oauth/token", this::token);
		this.server.createContext("/responses", exchange -> {
			this.responsesRequests.add(new ResponsesRequest(new Headers(exchange.getRequestHeaders()),
					JSON.readTree(exchange.getRequestBody().readAllBytes())));
			this.responses.handle(exchange);
		});
		this.server.setExecutor(this.handlers);
		this.server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	CodexOAuth oauth() {
		return new CodexOAuth("test", URI.create(baseUrl()), Duration.ofSeconds(10));
	}

	/** 서버가 지금 받아 주는 refresh token. 발급할 때마다 새것으로 바뀐다. */
	synchronized void honor(String refreshToken) {
		this.validRefresh = refreshToken;
	}

	/** 첫 갱신 요청을 둘째 갱신 요청이 올 때까지(최대 max) 붙잡는다. 두 쪽이 동시에 갱신하는지 드러낸다. */
	void holdFirstGrantUntilSecondArrives(Duration max) {
		this.holdFirstGrant = max;
	}

	void answerResponses(HttpHandler handler) {
		this.responses = handler;
	}

	List<Map<String, String>> tokenRequests() {
		return List.copyOf(this.tokenRequests);
	}

	List<String> presentedRefreshTokens() {
		return this.tokenRequests.stream().map(form -> form.getOrDefault("refresh_token", "")).toList();
	}

	List<ResponsesRequest> responsesRequests() {
		return List.copyOf(this.responsesRequests);
	}

	@Override
	public void close() {
		this.secondTokenArrival.countDown();
		this.server.stop(0);
		this.handlers.shutdownNow();
	}

	private void token(HttpExchange exchange) throws IOException {
		Map<String, String> form = CodexOAuth
			.parseQuery(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		this.tokenRequests.add(form);
		int arrival = this.tokenArrivals.incrementAndGet();
		Duration hold = this.holdFirstGrant;
		if (arrival == 1 && hold != null) {
			awaitQuietly(this.secondTokenArrival, hold);
		}
		else if (arrival == 2) {
			this.secondTokenArrival.countDown();
		}
		String granted = grant(form.get("refresh_token"));
		respond(exchange, (granted != null) ? 200 : 400,
				(granted != null) ? granted : "{\"error\":\"invalid_grant\"}");
	}

	private synchronized @Nullable String grant(@Nullable String refreshToken) {
		if (refreshToken == null || !refreshToken.equals(this.validRefresh)) {
			return null;
		}
		this.issued++;
		this.validRefresh = "rotated-" + this.issued;
		return "{\"access_token\":\"%s\",\"refresh_token\":\"%s\"}"
			.formatted(jwt(Instant.now().plusSeconds(3600), "issued-" + this.issued), this.validRefresh);
	}

	static String jwt(Instant expiresAt, String id) {
		String claims = """
				{"exp": %d, "jti": "%s", "https://api.openai.com/auth": {"chatgpt_account_id": "%s", "chatgpt_plan_type": "plus"}}
				""".formatted(expiresAt.getEpochSecond(), id, ACCOUNT);
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
				+ encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".sig";
	}

	static CodexCredentials ownToken(Instant expiresAt, String refreshToken) {
		return CodexCredentials.fromAccessToken(jwt(expiresAt, "own-" + refreshToken), refreshToken,
				CodexCredentials.Source.MINI_AGENT);
	}

	static void writeCodexCli(Path file, Instant expiresAt) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, """
				{"OPENAI_API_KEY": null, "tokens": {"access_token": "%s", "refresh_token": "cli-refresh", "account_id": "%s"}}
				""".formatted(jwt(expiresAt, "cli"), ACCOUNT));
	}

	static OutputStream sse(HttpExchange exchange) throws IOException {
		exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
		exchange.sendResponseHeaders(200, 0);
		return exchange.getResponseBody();
	}

	static void event(OutputStream out, String json) throws IOException {
		out.write(("data: " + json.strip() + "\n\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	static void status(HttpExchange exchange, int status) throws IOException {
		exchange.sendResponseHeaders(status, -1);
		exchange.close();
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void awaitQuietly(CountDownLatch latch, Duration max) {
		try {
			// 시간이 다 되면 그냥 진행한다. 붙잡는 목적은 둘째 요청이 올 틈을 주는 것뿐이다.
			latch.await(max.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
