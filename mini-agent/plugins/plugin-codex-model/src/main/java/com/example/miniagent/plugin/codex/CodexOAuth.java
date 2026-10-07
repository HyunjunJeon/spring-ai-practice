package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ChatGPT 계정 OAuth (Codex CLI 와 같은 공개 클라이언트, PKCE).
 *
 * <ul>
 *   <li>브라우저 로그인: 인증 URL 을 열고, {@code http://localhost:1455/auth/callback} 으로 돌아오는 code 를
 *       로컬 서버로 받는다. 포트가 사용 중이면 리다이렉트된 URL 을 붙여넣게 한다.</li>
 *   <li>디바이스 코드 로그인: 브라우저를 열 수 없는 환경용. 다른 기기에서 코드를 입력한다.</li>
 * </ul>
 * 흐름과 엔드포인트는 pi 의 {@code packages/ai/src/auth/oauth/openai-codex.ts} 와 같다.
 */
public class CodexOAuth {

	private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";

	private static final String AUTH_BASE = "https://auth.openai.com";

	private static final String AUTHORIZE_URL = AUTH_BASE + "/oauth/authorize";

	private static final int CALLBACK_PORT = 1455;

	private static final String REDIRECT_URI = "http://localhost:" + CALLBACK_PORT + "/auth/callback";

	private static final String DEVICE_VERIFICATION_URL = AUTH_BASE + "/codex/device";

	private static final String DEVICE_REDIRECT_URI = AUTH_BASE + "/deviceauth/callback";

	private static final String SCOPE = "openid profile email offline_access";

	private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);

	private static final Duration DEVICE_TIMEOUT = Duration.ofMinutes(15);

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

	private final JsonMapper json = JsonMapper.builder().build();

	private final SecureRandom random = new SecureRandom();

	private final String originator;

	private final URI authBase;

	private final Duration requestTimeout;

	public CodexOAuth(String originator) {
		this(originator, URI.create(AUTH_BASE), REQUEST_TIMEOUT);
	}

	CodexOAuth(String originator, URI authBase, Duration requestTimeout) {
		if (requestTimeout.isZero() || requestTimeout.isNegative()) {
			throw new IllegalArgumentException("OAuth request timeout must be positive");
		}
		this.originator = originator;
		this.authBase = authBase;
		this.requestTimeout = requestTimeout;
	}

	/** 로그인 중 사용자와 주고받는 창구. REPL 명령이 AgentConsole 로 구현한다. */
	public interface LoginUi {

		void show(String message);

		@Nullable String ask(String prompt);

		void openBrowser(String url);

	}

	// ---- 브라우저 로그인 (PKCE) ----

	public CodexCredentials browserLogin(LoginUi ui) {
		String verifier = base64Url(randomBytes(32));
		String challenge = base64Url(sha256(verifier));
		String state = HexFormat.of().formatHex(randomBytes(16));
		String url = AUTHORIZE_URL + "?" + form(orderedMap("response_type", "code", "client_id", CLIENT_ID,
				"redirect_uri", REDIRECT_URI, "scope", SCOPE, "code_challenge", challenge, "code_challenge_method",
				"S256", "state", state, "id_token_add_organizations", "true", "codex_cli_simplified_flow", "true",
				"originator", this.originator));

		CompletableFuture<String> callback = new CompletableFuture<>();
		HttpServer server = startCallbackServer(state, callback);
		ui.show("브라우저에서 ChatGPT 계정으로 로그인하세요. 창이 안 열리면 아래 URL 을 직접 여세요.\n" + url);
		ui.openBrowser(url);

		String code;
		if (server != null) {
			try {
				code = callback.get(LOGIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			}
			catch (TimeoutException ex) {
				throw new IllegalStateException("로그인 시간이 초과되었습니다(5분).");
			}
			catch (ExecutionException ex) {
				throw new IllegalStateException("로그인 실패: " + ex.getCause().getMessage());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("로그인이 중단되었습니다.");
			}
			finally {
				server.stop(1);
			}
		}
		else {
			// Codex CLI 가 로그인 중이면 1455 포트를 쓰고 있다. 이때는 수동으로 붙여넣는다.
			String pasted = ui.ask("포트 1455 가 사용 중입니다. 로그인 후 브라우저 주소창의 URL 을 붙여넣으세요: ");
			Map<String, String> params = parseQuery((pasted != null && pasted.contains("?"))
					? pasted.substring(pasted.indexOf('?') + 1) : "code=" + ((pasted != null) ? pasted.strip() : ""));
			if (params.containsKey("state") && !state.equals(params.get("state"))) {
				throw new IllegalStateException("state 가 일치하지 않습니다.");
			}
			code = params.get("code");
		}
		if (code == null || code.isBlank()) {
			throw new IllegalStateException("authorization code 를 받지 못했습니다.");
		}
		return exchange(code, verifier, REDIRECT_URI);
	}

	private @Nullable HttpServer startCallbackServer(String state, CompletableFuture<String> callback) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", CALLBACK_PORT), 0);
			server.createContext("/auth/callback", exchange -> handleCallback(exchange, state, callback));
			server.start();
			return server;
		}
		catch (BindException ex) {
			return null;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void handleCallback(HttpExchange exchange, String state, CompletableFuture<String> callback)
			throws IOException {
		Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
		boolean ok = !params.containsKey("error") && state.equals(params.get("state"));
		String body = params.containsKey("error") ? "로그인 실패: " + params.get("error")
				: ok ? "mini-agent 로그인 완료. 이 창을 닫고 터미널로 돌아가세요." : "state 가 일치하지 않습니다.";
		byte[] bytes = ("<html><meta charset='utf-8'><body style='font-family:sans-serif'><h3>" + body
				+ "</h3></body></html>").getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(ok ? 200 : 400, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
		// 응답을 다 보낸 뒤에 기다리는 쪽을 깨운다. 먼저 깨우면 서버가 닫혀 브라우저에 오류 페이지가 뜬다.
		if (params.containsKey("error")) {
			callback.completeExceptionally(new IllegalStateException(params.get("error") + ": " + params.getOrDefault("error_description", "")));
		}
		else if (ok) {
			callback.complete(params.get("code"));
		}
	}

	// ---- 디바이스 코드 로그인 ----

	public CodexCredentials deviceLogin(LoginUi ui) {
		JsonNode device = postJson("/api/accounts/deviceauth/usercode", Map.of("client_id", CLIENT_ID));
		String deviceAuthId = device.path("device_auth_id").asString();
		String userCode = device.path("user_code").asString();
		long interval = Math.max(1, device.path("interval").asLong(5));
		ui.show("다른 기기/브라우저에서 %s 를 열고 코드를 입력하세요:  %s".formatted(DEVICE_VERIFICATION_URL, userCode));

		Instant deadline = Instant.now().plus(DEVICE_TIMEOUT);
		while (Instant.now().isBefore(deadline)) {
			sleep(Duration.ofSeconds(interval));
			HttpResponse<String> response = send(request("/api/accounts/deviceauth/token")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(this.json.writeValueAsString(
						Map.of("device_auth_id", deviceAuthId, "user_code", userCode))))
				.build());
			if (response.statusCode() == 200) {
				JsonNode body = this.json.readTree(response.body());
				return exchange(body.path("authorization_code").asString(), body.path("code_verifier").asString(),
						DEVICE_REDIRECT_URI);
			}
			if (response.statusCode() != 403 && response.statusCode() != 404 && !response.body().contains("pending")) {
				throw new IllegalStateException("디바이스 로그인 실패 (" + response.statusCode() + "): " + response.body());
			}
		}
		throw new IllegalStateException("디바이스 로그인 시간이 초과되었습니다(15분).");
	}

	// ---- 토큰 교환 / 갱신 ----

	public CodexCredentials exchange(String code, String verifier, String redirectUri) {
		return tokenRequest(orderedMap("grant_type", "authorization_code", "client_id", CLIENT_ID, "code", code,
				"code_verifier", verifier, "redirect_uri", redirectUri), "exchange", null);
	}

	/** 자체 토큰 전용. 응답에 새 refresh token 이 오면 그것으로 바꾼다(rotation). */
	public CodexCredentials refresh(String refreshToken) {
		return tokenRequest(orderedMap("grant_type", "refresh_token", "refresh_token", refreshToken, "client_id",
				CLIENT_ID), "refresh", refreshToken);
	}

	private CodexCredentials tokenRequest(Map<String, String> params, String operation, @Nullable String previousRefresh) {
		HttpResponse<String> response = send(request("/oauth/token")
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form(params)))
			.build());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("토큰 %s 실패 (%d): %s".formatted(operation, response.statusCode(), response.body()));
		}
		JsonNode body = this.json.readTree(response.body());
		String access = body.path("access_token").asString(null);
		String refresh = body.path("refresh_token").asString(previousRefresh);
		if (access == null) {
			throw new IllegalStateException("토큰 응답에 access_token 이 없습니다");
		}
		return CodexCredentials.fromAccessToken(access, refresh, CodexCredentials.Source.MINI_AGENT);
	}

	// ---- helpers ----

	private JsonNode postJson(String url, Map<String, String> payload) {
		HttpResponse<String> response = send(request(url)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(this.json.writeValueAsString(payload)))
			.build());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("요청 실패 %s (%d): %s".formatted(url, response.statusCode(), response.body()));
		}
		return this.json.readTree(response.body());
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(this.authBase.resolve(path)).timeout(this.requestTimeout);
	}

	private HttpResponse<String> send(HttpRequest request) {
		// HttpRequest 기한만으로 본문 수신 정지를 막을 수 없어 응답 전체의 완료에도 기한을 둔다.
		CompletableFuture<HttpResponse<String>> response = this.http.sendAsync(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		try {
			return response.get(this.requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException ex) {
			response.cancel(true);
			throw new UncheckedIOException(new HttpTimeoutException("OAuth request timed out after " + this.requestTimeout));
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof IOException io) {
				throw new UncheckedIOException(io);
			}
			throw new IllegalStateException("OAuth request failed", ex.getCause());
		}
		catch (InterruptedException ex) {
			response.cancel(true);
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		this.random.nextBytes(bytes);
		return bytes;
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static Map<String, String> orderedMap(String... keyValues) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put(keyValues[i], keyValues[i + 1]);
		}
		return map;
	}

	private static String form(Map<String, String> params) {
		return params.entrySet()
			.stream()
			.map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
					+ URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
	}

	static Map<String, String> parseQuery(@Nullable String query) {
		Map<String, String> params = new LinkedHashMap<>();
		if (query == null || query.isBlank()) {
			return params;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		return params;
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("로그인이 중단되었습니다.");
		}
	}

}
