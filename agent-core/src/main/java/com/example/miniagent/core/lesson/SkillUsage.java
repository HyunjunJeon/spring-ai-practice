package com.example.miniagent.core.lesson;

import java.time.Instant;
import java.util.Objects;
import com.example.miniagent.core.SkillUseListener;

/**
 * Export link and load counts of one skill promoted from a lesson. Identity is (scope, workspace, name);
 * workspace is the real workspace path for PROJECT and null for GLOBAL. SkillUsageStore checks scope and workspace.
 */
public record SkillUsage(LessonRecord.Scope scope, String workspace, String name, String lessonId, int lessonVersion,
		Instant exportedAt, String exportDigest, long chosenLoads, long forcedLoads, Instant lastLoadedAt,
		Instant retiredAt, String retiredReason, Instant restoredAt) {
	public record Key(LessonRecord.Scope scope, String workspace, String name) {}
	public SkillUsage {
		Objects.requireNonNull(scope); Objects.requireNonNull(name); Objects.requireNonNull(lessonId);
		Objects.requireNonNull(exportedAt); Objects.requireNonNull(exportDigest);
	}
	public Key key() { return new Key(scope, workspace, name); }
	SkillUsage loaded(SkillUseListener.Kind kind, Instant at) {
		boolean chosen = switch (kind) {
			case CHOSEN -> true;
			case FORCED -> false;
		};
		return new SkillUsage(scope, workspace, name, lessonId, lessonVersion, exportedAt, exportDigest,
				chosenLoads + (chosen ? 1 : 0), forcedLoads + (chosen ? 0 : 1), at, retiredAt, retiredReason, restoredAt);
	}
}
