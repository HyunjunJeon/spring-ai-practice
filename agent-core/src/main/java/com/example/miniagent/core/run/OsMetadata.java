package com.example.miniagent.core.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Finder and AppleDouble files the OS drops into any folder. A store never opens or deletes them; every other unknown
 * entry still fails the store closed. The one predicate lives in run because run may depend only on model, while
 * change (which already uses run), the features and the app can reach it without a package cycle. A staged workspace
 * does not skip them: its whole tree is the proposal.
 */
public final class OsMetadata {
	private OsMetadata() {}

	public static boolean matches(String name) {
		return name.equals(".DS_Store") || name.startsWith("._");
	}

	/**
	 * At most {@code limit} entries of {@code directory} that are not OS metadata. Metadata is skipped before the bound,
	 * so it can neither push a real entry past the limit nor hide an unknown one behind it.
	 */
	public static List<Path> entries(Path directory, int limit) throws IOException {
		try (var paths = Files.list(directory)) {
			return paths.filter(path -> !matches(path.getFileName().toString())).limit(limit).toList();
		}
	}
}
