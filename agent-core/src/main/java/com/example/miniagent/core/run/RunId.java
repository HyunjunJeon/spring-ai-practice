package com.example.miniagent.core.run;

import java.util.UUID;

public record RunId(String value) {
	public RunId {
		requireSafe(value);
	}

	public static RunId random() {
		return new RunId(UUID.randomUUID().toString());
	}

	/** core 내부용이며 core.execution 전용이다. 플러그인 API가 아니다. */
	public static void requireSafe(String id) {
		if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
			throw new IllegalArgumentException("Unsafe execution ID");
		}
	}
}
