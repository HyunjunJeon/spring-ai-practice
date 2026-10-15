package com.example.miniagent.core.run;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Attempt(String attemptId, UUID bindingId, Instant startedAt, Instant endedAt) {
	public Attempt {
		RunId.requireSafe(attemptId);
		Objects.requireNonNull(bindingId);
		Objects.requireNonNull(startedAt);
		if (endedAt != null && endedAt.isBefore(startedAt)) {
			throw new IllegalArgumentException("Attempt end precedes start");
		}
	}
}
