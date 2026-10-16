package com.example.miniagent.core;

import java.nio.file.Path;

/**
 * Told when a skill's instructions were handed to the model. Recording is best-effort: implementations must not
 * throw, because a usage counter must never fail the load it observes.
 */
@FunctionalInterface
public interface SkillUseListener {
	SkillUseListener NONE = (directory, kind) -> {};

	enum Kind {
		/** The model called {@code load_skill}. */
		CHOSEN,
		/** The operator ran {@code /skill}. */
		FORCED
	}

	/** {@code skillDirectory} is the folder holding the skill's SKILL.md, as the skills plugin discovered it. */
	void loaded(Path skillDirectory, Kind kind);
}
