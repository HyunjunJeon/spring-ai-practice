package com.example.miniagent.core.change;

import java.util.Objects;
import com.example.miniagent.core.run.RunId;

/** Durable identities, never inferred from mutable session or thread-local state. */
public record ChangeOwner(String sessionId, RunId runId, String attemptId, String turnId) {
	public ChangeOwner {
		requireId(sessionId);
		Objects.requireNonNull(runId, "runId");
		requireId(attemptId);
		requireId(turnId);
	}

	static void requireId(String id) {
		if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
			throw new IllegalArgumentException("Invalid change identity");
		}
	}
}
