package com.example.miniagent.core.run;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

import com.example.miniagent.core.model.ModelRef;

public record RunSpec(int schemaVersion, RunId runId, String conversationId,
		String parentConversationId, RunId parentRunId, String childId, ModelRef model,
		String wireFamily, String cwd, RunBudget budget, String optionsFingerprint,
		String toolsFingerprint, Instant createdAt) {
	public static final int SCHEMA_VERSION = 1;

	public RunSpec {
		if (schemaVersion != SCHEMA_VERSION) {
			throw new IllegalArgumentException("Unsupported spec schema");
		}
		Objects.requireNonNull(runId);
		RunId.requireSafe(conversationId);
		RunId.requireSafe(parentConversationId);
		if (conversationId.equals(parentConversationId) || conversationId.equals(runId.value())) {
			throw new IllegalArgumentException("Run conversation must be independent");
		}
		if (parentRunId != null && childId == null || runId.equals(parentRunId)) {
			throw new IllegalArgumentException("Invalid parent/child identity");
		}
		if (childId != null) {
			RunId.requireSafe(childId);
		}
		Objects.requireNonNull(model);
		if (wireFamily == null || !wireFamily.matches("[A-Za-z0-9_-]{1,64}")) {
			throw new IllegalArgumentException("Invalid wire family");
		}
		Path path = Path.of(cwd);
		if (!path.isAbsolute() || !path.normalize().toString().equals(cwd)) {
			throw new IllegalArgumentException("Cwd must be absolute and normalized");
		}
		Objects.requireNonNull(budget);
		requireDigest(optionsFingerprint);
		requireDigest(toolsFingerprint);
		Objects.requireNonNull(createdAt);
	}

	static void requireDigest(String value) {
		if (value == null || !value.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid SHA-256 digest");
		}
	}
}
