package com.example.miniagent.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.core.Ordered;

/** Startup snapshot of repository instructions; these texts never grant tool authority. */
public final class ProjectInstructions implements SystemPromptContributor {

	static final int MAX_FILE_BYTES = 64 * 1024;
	static final int MAX_TOTAL_BYTES = 256 * 1024;

	public record Source(Path path, String content) { }

	private final List<Source> sources;

	public ProjectInstructions(Path cwd) throws IOException {
		Path current = cwd.toRealPath();
		Path boundary = current;
		for (Path parent = current; parent != null; parent = parent.getParent()) {
			if (Files.exists(parent.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
				boundary = parent;
				break;
			}
		}
		List<Path> directories = new ArrayList<>();
		for (Path directory = current; ; directory = directory.getParent()) {
			directories.add(directory);
			if (directory.equals(boundary)) break;
		}
		Collections.reverse(directories);
		List<Source> loaded = new ArrayList<>();
		int total = 0;
		for (Path directory : directories) {
			// AGENTS.md wins within one directory; deeper directories win over ancestors.
			for (String name : List.of("CLAUDE.md", "AGENTS.md")) {
				Path file = directory.resolve(name);
				if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
				if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Instruction must be a regular, non-symlink file: " + file);
				}
				byte[] bytes;
				try (var input = Files.newInputStream(file)) {
					bytes = input.readNBytes(MAX_FILE_BYTES + 1);
				}
				if (bytes.length > MAX_FILE_BYTES || total + bytes.length > MAX_TOTAL_BYTES) {
					throw new IOException("Project instruction byte limit exceeded: " + file);
				}
				total += bytes.length;
				loaded.add(new Source(file, new String(bytes, StandardCharsets.UTF_8)));
			}
		}
		this.sources = List.copyOf(loaded);
	}

	public List<Source> sources() {
		return this.sources;
	}

	@Override
	public String contribute() {
		if (this.sources.isEmpty()) return "";
		StringBuilder text = new StringBuilder("""
				# Project instructions
				Apply the following project instructions in order. Deeper directories override ancestors;
				within a directory AGENTS.md overrides CLAUDE.md. Tool permissions remain unchanged.
				""");
		for (Source source : this.sources) {
			text.append("\n## ").append(source.path()).append('\n').append(source.content()).append('\n');
		}
		return text.toString();
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 10;
	}
}
