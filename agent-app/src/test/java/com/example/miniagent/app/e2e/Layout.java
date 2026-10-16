package com.example.miniagent.app.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One E2E temp layout: {@code work} is agent.cwd, {@code state} is agent.home and {@code user} is user.home.
 * They are siblings on purpose: FileChangeStore refuses an agent home inside the workspace.
 * Paths are canonical, because the app records real paths (macOS /var is a link to /private/var).
 */
record Layout(Path work, Path state, Path user) {

	static Layout under(Path directory) throws IOException {
		Path root = directory.toRealPath();
		return new Layout(Files.createDirectories(root.resolve("work")),
				Files.createDirectories(root.resolve("state")), Files.createDirectories(root.resolve("user")));
	}
}
