package com.example.miniagent.plugin.claude;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Claude 공개 OAuth 클라이언트(PKCE). */
public class ClaudeOAuth {

	private static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";

	private static final URI AUTHORIZE_URI = URI.create("https://claude.ai/oauth/authorize");

	private static final URI TOKEN_URI = URI.create("https://platform.claude.com/v1/oauth/token");

	private static final String REDIRECT_URI = "https://platform.claude.com/oauth/code/callback";

	private static final String SCOPE = "org:create_api_key user:profile user:inference user:sessions:claude_code "
			+ "user:mcp_servers user:file_upload";

	private static final int MAX_HEADER_BYTES = 32 * 1024;

	private static final int MAX_BODY_BYTES = 64 * 1024;

	private final HttpClient http;

	private final JsonMapper json = JsonMapper.builder().build();

	private final SecureRandom random = new SecureRandom();

	private final URI authorizeUri;

	private final URI tokenUri;

	private final Duration timeout;

	private final Clock clock;

	public ClaudeOAuth(Duration timeout) {
		this(AUTHORIZE_URI, TOKEN_URI, timeout, Clock.systemUTC());
	}

	ClaudeOAuth(URI authorizeUri, URI tokenUri, Duration timeout, Clock clock) {
		this.authorizeUri = Objects.requireNonNull(authorizeUri, "authorizeUri");
		this.tokenUri = Objects.requireNonNull(tokenUri, "tokenUri");
		this.timeout = Objects.requireNonNull(timeout, "timeout");
		this.clock = Objects.requireNonNull(clock, "clock");
		if (timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException("OAuth timeout must be positive");
		}
		this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	public record Authorization(URI uri, String verifier, String state) {

		public Authorization {
			Objects.requireNonNull(uri, "uri");
			Objects.requireNonNull(verifier, "verifier");
			Objects.requireNonNull(state, "state");
		}

	}

	public Authorization authorization() {
		String verifier = base64Url(randomBytes(32));
		String challenge = base64Url(sha256(verifier));
		String state = base64Url(randomBytes(24));
		Map<String, String> query = orderedMap("code", "true", "response_type", "code", "client_id", CLIENT_ID,
				"redirect_uri", REDIRECT_URI, "scope", SCOPE, "code_challenge", challenge,
				"code_challenge_method", "S256", "state", state);
		return new Authorization(URI.create(this.authorizeUri + "?" + form(query)), verifier, state);
	}

	public ClaudeCredentials exchange(String pasted, Authorization authorization) {
		Objects.requireNonNull(authorization, "authorization");
		PastedCode parsed = parsePasted(pasted, authorization.state());
		if (!constantTimeEquals(authorization.state(), parsed.state())) {
			throw new IllegalStateException("OAuth state does not match");
		}
		return tokenRequest(orderedMap("grant_type", "authorization_code", "client_id", CLIENT_ID, "code",
				parsed.code(), "state", authorization.state(), "redirect_uri", REDIRECT_URI, "code_verifier",
				authorization.verifier()), null, true);
	}

	public ClaudeCredentials refresh(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new IllegalArgumentException("refresh token is required");
		}
		return tokenRequest(orderedMap("grant_type", "refresh_token", "client_id", CLIENT_ID, "refresh_token",
				refreshToken), refreshToken, false);
	}

	private ClaudeCredentials tokenRequest(Map<String, String> payload, String previousRefresh,
			boolean requireRefresh) {
		HttpRequest request = HttpRequest.newBuilder(this.tokenUri)
			.timeout(this.timeout)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(this.json.writeValueAsString(payload), StandardCharsets.UTF_8))
			.build();
		Response response = send(request);
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("Claude OAuth request failed with status " + response.statusCode());
		}
		JsonNode body;
		try {
			body = this.json.readTree(response.body());
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("Claude OAuth returned invalid JSON");
		}
		String access = text(body, "access_token");
		String refresh = text(body, "refresh_token");
		if (access == null || access.isBlank()) {
			throw new IllegalStateException("Claude OAuth response omitted access token");
		}
		if (refresh == null) {
			refresh = previousRefresh;
		}
		if (requireRefresh && (refresh == null || refresh.isBlank())) {
			throw new IllegalStateException("Claude OAuth response omitted refresh token");
		}
		if (refresh == null || refresh.isBlank()) {
			throw new IllegalStateException("Claude OAuth refresh token is unavailable");
		}
		long expiresIn = body.path("expires_in").asLong(0);
		if (expiresIn <= 0) {
			throw new IllegalStateException("Claude OAuth response has invalid expiry");
		}
		long expiresAt;
		try {
			expiresAt = Math.addExact(this.clock.millis(), Math.multiplyExact(expiresIn, 1000));
		}
		catch (ArithmeticException ex) {
			throw new IllegalStateException("Claude OAuth response has invalid expiry");
		}
		return new ClaudeCredentials(access, refresh, expiresAt);
	}

	private Response send(HttpRequest request) {
		long deadline = System.nanoTime() + this.timeout.toNanos();
		CompletableFuture<HttpResponse<InputStream>> responseFuture = this.http.sendAsync(request,
				HttpResponse.BodyHandlers.ofInputStream());
		CompletableFuture<String> bodyFuture = null;
		InputStream body = null;
		try {
			HttpResponse<InputStream> response = responseFuture.get(remaining(deadline), TimeUnit.NANOSECONDS);
			body = response.body();
			validateHeaders(response);
			InputStream responseBody = body;
			bodyFuture = CompletableFuture.supplyAsync(() -> readBody(responseBody));
			String text = bodyFuture.get(remaining(deadline), TimeUnit.NANOSECONDS);
			return new Response(response.statusCode(), text);
		}
		catch (TimeoutException ex) {
			responseFuture.cancel(true);
			if (bodyFuture != null) {
				bodyFuture.cancel(true);
			}
			close(body);
			throw new UncheckedIOException(new HttpTimeoutException("Claude OAuth request timed out"));
		}
		catch (InterruptedException ex) {
			responseFuture.cancel(true);
			if (bodyFuture != null) {
				bodyFuture.cancel(true);
			}
			close(body);
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Claude OAuth request was interrupted", ex);
		}
		catch (ExecutionException ex) {
			close(body);
			Throwable cause = ex.getCause();
			if (cause instanceof UncheckedIOException io) {
				throw io;
			}
			if (cause instanceof IOException io) {
				throw new UncheckedIOException(io);
			}
			throw new IllegalStateException("Claude OAuth request failed");
		}
		catch (RuntimeException ex) {
			close(body);
			throw ex;
		}
	}

	private static long remaining(long deadline) throws TimeoutException {
		long remaining = deadline - System.nanoTime();
		if (remaining <= 0) {
			throw new TimeoutException();
		}
		return remaining;
	}

	private static void validateHeaders(HttpResponse<?> response) {
		long size = 0;
		for (Map.Entry<String, java.util.List<String>> header : response.headers().map().entrySet()) {
			size += header.getKey().length();
			for (String value : header.getValue()) {
				size += value.length();
			}
			if (size > MAX_HEADER_BYTES) {
				throw new IllegalStateException("Claude OAuth response headers are too large");
			}
		}
		response.headers().firstValueAsLong("Content-Length").ifPresent(length -> {
			if (length > MAX_BODY_BYTES) {
				throw new IllegalStateException("Claude OAuth response body is too large");
			}
		});
	}

	private static String readBody(InputStream input) {
		try (input) {
			byte[] bytes = input.readNBytes(MAX_BODY_BYTES + 1);
			if (bytes.length > MAX_BODY_BYTES) {
				throw new IllegalStateException("Claude OAuth response body is too large");
			}
			return new String(bytes, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void close(InputStream input) {
		if (input == null) {
			return;
		}
		try {
			input.close();
		}
		catch (IOException ignored) {
			// The request already failed; closing only cancels the outstanding body read.
		}
	}

	private record Response(int statusCode, String body) {
	}

	private record PastedCode(String code, String state) {
	}

	private static PastedCode parsePasted(String pasted, String expectedState) {
		if (pasted == null || pasted.isBlank()) {
			throw new IllegalArgumentException("authorization code is required");
		}
		String value = pasted.strip();
		if (value.contains("://")) {
			URI uri;
			try {
				uri = URI.create(value);
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("authorization response is invalid");
			}
			Map<String, String> params = parseQuery(uri.getRawQuery());
			if (uri.getRawFragment() != null) {
				params.putAll(parseQuery(uri.getRawFragment()));
			}
			String code = params.get("code");
			String state = params.get("state");
			if (code == null || code.isBlank() || state == null || state.isBlank()) {
				throw new IllegalArgumentException("authorization response is incomplete");
			}
			return new PastedCode(code, state);
		}
		int separator = value.lastIndexOf('#');
		if (separator >= 0) {
			String code = decode(value.substring(0, separator));
			String state = decode(value.substring(separator + 1));
			if (code.isBlank() || state.isBlank()) {
				throw new IllegalArgumentException("authorization response is incomplete");
			}
			return new PastedCode(code, state);
		}
		return new PastedCode(value, expectedState);
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
			throw new IllegalStateException("SHA-256 is unavailable");
		}
	}

	private static boolean constantTimeEquals(String expected, String actual) {
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
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
			.map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
					+ URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
			.collect(Collectors.joining("&"));
	}

	private static Map<String, String> parseQuery(String query) {
		Map<String, String> params = new LinkedHashMap<>();
		if (query == null || query.isBlank()) {
			return params;
		}
		for (String pair : query.split("&")) {
			int separator = pair.indexOf('=');
			if (separator > 0) {
				params.put(decode(pair.substring(0, separator)), decode(pair.substring(separator + 1)));
			}
		}
		return params;
	}

	private static String decode(String value) {
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("authorization response is invalid");
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value == null || value.isNull()) ? null : value.asString();
	}

}
