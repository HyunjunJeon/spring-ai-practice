package com.example.miniagent.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Resolve configured ancestor aliases once, without following the private home's own leaf. */
public final class StatePaths {
	private StatePaths() {}

	public static Path home(Path configured) throws IOException {
		Path absolute = configured.toAbsolutePath().normalize();
		Path ancestor = absolute.getParent();
		if (ancestor == null) throw new IOException("State home cannot be a filesystem root");
		while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
		return ancestor.toRealPath().resolve(ancestor.relativize(absolute));
	}

	/** Project skill root. The skills plugin reads it and {@code /lesson export} writes PROJECT lessons there. */
	public static Path projectSkills(Path cwd) {
		return cwd.resolve(".mini-agent").resolve("skills");
	}

	/** User skill root under the agent home. {@code /lesson export} writes GLOBAL lessons there. */
	public static Path userSkills(Path home) {
		return home.resolve("skills");
	}

	/**
	 * Sibling of the project skill root that the skills plugin never reads. {@code /lesson unexport} moves PROJECT
	 * skills there and {@code /lesson reinstate} moves them back.
	 */
	public static Path projectRetiredSkills(Path cwd) {
		return cwd.resolve(".mini-agent").resolve("skills-retired");
	}

	/** Sibling of the user skill root that the skills plugin never reads; retired GLOBAL skills wait there. */
	public static Path userRetiredSkills(Path home) {
		return home.resolve("skills-retired");
	}
}
