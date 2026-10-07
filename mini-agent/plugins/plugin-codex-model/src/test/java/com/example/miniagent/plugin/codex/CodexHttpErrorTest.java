package com.example.miniagent.plugin.codex;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 사용자가 실제로 보게 될 오류 문구. 플랜 한도에 걸렸을 때(429)와 토큰 문제(401). */
class CodexHttpErrorTest {

	private final CodexResponsesClient client = new CodexResponsesClient(JsonMapper.builder().build(),
			"https://example.invalid", "test", Duration.ofSeconds(5));

	@Test
	void usageLimitShowsResetTime() {
		var ex = this.client.new CodexHttpException(429,
				"{\"error\":{\"type\":\"usage_limit_reached\",\"message\":\"usage limit\",\"resets_in_seconds\":1800}}");

		assertThat(ex.status()).isEqualTo(429);
		assertThat(ex.getMessage()).contains("사용 한도", "usage limit", "30분");
	}

	@Test
	void unauthorizedPointsToLogin() {
		var ex = this.client.new CodexHttpException(401, "{\"error\":{\"message\":\"token expired\"}}");

		assertThat(ex.getMessage()).contains("token expired", "/login", "codex");
	}

	@Test
	void nonJsonBodyIsShownAsIs() {
		var ex = this.client.new CodexHttpException(502, "Bad Gateway");

		assertThat(ex.getMessage()).isEqualTo("Codex HTTP 502: Bad Gateway");
	}

}
