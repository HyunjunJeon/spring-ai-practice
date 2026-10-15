package com.example.miniagent.plugin.claude;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** mini-agent가 소유하는 Claude OAuth 자격 증명. */
public record ClaudeCredentials(String accessToken, String refreshToken, long expiresAtMillis) {

	private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

	public ClaudeCredentials {
		Objects.requireNonNull(accessToken, "accessToken");
		Objects.requireNonNull(refreshToken, "refreshToken");
	}

	public boolean isExpiring(Clock clock) {
		Objects.requireNonNull(clock, "clock");
		return clock.instant().plus(REFRESH_MARGIN).compareTo(Instant.ofEpochMilli(this.expiresAtMillis)) >= 0;
	}

	@Override
	public String toString() {
		return "ClaudeCredentials[accessToken=<redacted>, refreshToken=<redacted>, expiresAtMillis="
				+ this.expiresAtMillis + "]";
	}

}
