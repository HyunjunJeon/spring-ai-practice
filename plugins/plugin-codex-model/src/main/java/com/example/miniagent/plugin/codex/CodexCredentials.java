package com.example.miniagent.plugin.codex;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ChatGPT OAuth 토큰 한 벌.
 *
 * <p>access token 은 JWT 다. 계정 ID({@code chatgpt_account_id})와 플랜, 만료 시각이 클레임에 들어 있어서
 * 별도 API 호출 없이 꺼낼 수 있다. 서명 검증은 하지 않는다(서버가 한다). 값을 화면에 출력하지 않는다.
 */
public record CodexCredentials(String accessToken, @Nullable String refreshToken, String accountId,
		Instant expiresAt, @Nullable String plan, Source source) {

	private static final String AUTH_CLAIM = "https://api.openai.com/auth";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	public enum Source {

		/** {@code /login} 으로 받아 mini-agent 가 직접 관리하는 토큰. 갱신(refresh) 가능. */
		MINI_AGENT,

		/** {@code ~/.codex/auth.json}. 읽기 전용. 갱신하면 Codex CLI 의 refresh token 이 무효가 된다. */
		CODEX_CLI

	}

	public boolean expiresWithin(long seconds) {
		return Instant.now().plusSeconds(seconds).isAfter(this.expiresAt);
	}

	/** access token(JWT) 에서 계정 ID, 플랜, 만료 시각을 읽어 자격 증명을 만든다. */
	public static CodexCredentials fromAccessToken(String accessToken, @Nullable String refreshToken, Source source) {
		JsonNode claims = decodeClaims(accessToken);
		JsonNode auth = claims.path(AUTH_CLAIM);
		String accountId = auth.path("chatgpt_account_id").asString("");
		if (accountId.isEmpty()) {
			throw new IllegalStateException("access token 에 chatgpt_account_id 가 없습니다");
		}
		Instant expiresAt = Instant.ofEpochSecond(claims.path("exp").asLong(0));
		String plan = auth.path("chatgpt_plan_type").asString(null);
		return new CodexCredentials(accessToken, refreshToken, accountId, expiresAt, plan, source);
	}

	private static JsonNode decodeClaims(String jwt) {
		String[] parts = jwt.split("\\.");
		if (parts.length != 3) {
			throw new IllegalStateException("access token 이 JWT 형식이 아닙니다");
		}
		byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
		return JSON.readTree(new String(payload, StandardCharsets.UTF_8));
	}

	@Override
	public String toString() {
		return "CodexCredentials[source=%s, accountId=%s…, plan=%s, expiresAt=%s]".formatted(this.source,
				this.accountId.substring(0, Math.min(8, this.accountId.length())), this.plan, this.expiresAt);
	}

}
