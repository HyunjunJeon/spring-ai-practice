package com.example.miniagent.core.lesson;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable logical revisions. A rollback appends a revision; it never erases history. */
public record LessonRecord(String id, int version, State state, Scope scope, String session,
		String workspace, Content content, Proof proof, Actor creator, Actor operator,
		String operation, Integer rollbackOf, Generation generation, Instant createdAt) {
	public enum State { CANDIDATE, ACCEPTED }
	public enum Scope { SESSION, PROJECT, GLOBAL }
	public record Actor(String session, String workspace) {
		public Actor { text(session, 128); canonical(workspace); }
		public static Actor capture(String session, Path workspace) throws java.io.IOException {
			return new Actor(session, workspace.toRealPath().toString());
		}
	}
	public record Content(String title, String content, List<String> tags) {
		public Content {
			text(title, 160); text(content, 4000);
			tags = List.copyOf(tags);
			if (tags.size() > 12 || tags.stream().distinct().count() != tags.size())
				throw new IllegalArgumentException("Invalid lesson tags");
			tags.forEach(tag -> text(tag, 64));
		}
	}
	public record Proof(String goalId, String runId, String targetId, boolean readOnly,
			String revision, String digest, List<String> evidenceIds) {
		public Proof {
			text(goalId, 128); text(runId, 128); text(targetId, 128);
			if (revision == null || !revision.matches("[0-9a-f]{64}")
					|| digest == null || !digest.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("Invalid lesson source revision");
			evidenceIds = List.copyOf(evidenceIds);
			if (evidenceIds.isEmpty() || evidenceIds.size() > 8
					|| evidenceIds.stream().distinct().count() != evidenceIds.size())
				throw new IllegalArgumentException("Invalid lesson evidence");
			evidenceIds.forEach(id -> java.util.UUID.fromString(id));
		}
	}
	/** Captured binding and actual returned usage, never credentials or executable code. */
	public record Generation(String provider, String model, String bindingId, String optionsFingerprint,
			Long totalTokens) {
		public Generation {
			text(provider, 128); text(model, 256); text(bindingId, 128); text(optionsFingerprint, 128);
			if (totalTokens != null && totalTokens < 0) throw new IllegalArgumentException("Invalid usage");
		}
	}
	public LessonRecord {
		java.util.UUID.fromString(id);
		if (version < 1 || version > LessonStore.MAX_VERSIONS) throw new IllegalArgumentException("Version limit");
		Objects.requireNonNull(state); Objects.requireNonNull(scope);
		text(session, 128); canonical(workspace); Objects.requireNonNull(content);
		Objects.requireNonNull(proof); Objects.requireNonNull(creator); Objects.requireNonNull(operator);
		Objects.requireNonNull(createdAt); text(operation, 32);
		if (rollbackOf != null && (rollbackOf < 1 || rollbackOf >= version))
			throw new IllegalArgumentException("Invalid rollback version");
	}
	public boolean visibleTo(Actor actor) {
		return scope == Scope.GLOBAL || workspace.equals(actor.workspace())
				&& (scope == Scope.PROJECT || session.equals(actor.session()));
	}
	static void text(String value, int max) {
		if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0)
			throw new IllegalArgumentException("Invalid bounded lesson text");
	}
	private static void canonical(String workspace) {
		var path = Path.of(workspace);
		if (!path.isAbsolute() || !path.normalize().toString().equals(workspace))
			throw new IllegalArgumentException("Noncanonical lesson workspace");
	}
}
