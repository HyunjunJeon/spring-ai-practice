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
}
