package com.example.miniagent.core;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class ProjectInstructionsTest {

	@TempDir
	Path root;

	@BeforeEach
	void canonicalFixturePath() throws Exception {
		this.root = this.root.toRealPath();
	}

	@Test
	void loadsAncestorsInPrecedenceOrderAndStopsAtGitRoot() throws Exception {
		Files.writeString(this.root.resolve("AGENTS.md"), "OUTSIDE");
		Path repo = Files.createDirectories(this.root.resolve("repo"));
		Files.createDirectory(repo.resolve(".git"));
		Path child = Files.createDirectories(repo.resolve("src/nested"));
		Path first = Files.writeString(repo.resolve("CLAUDE.md"), "ROOT_CLAUDE");
		Path second = Files.writeString(repo.resolve("AGENTS.md"), "ROOT_AGENT");
		Path third = Files.writeString(child.resolve("AGENTS.md"), "LEAF_AGENT");
		ProjectInstructions loaded = new ProjectInstructions(child);
		assertThat(loaded.sources()).extracting(ProjectInstructions.Source::path)
			.containsExactly(first, second, third);
		assertThat(loaded.sources()).extracting(ProjectInstructions.Source::content)
			.containsExactly("ROOT_CLAUDE", "ROOT_AGENT", "LEAF_AGENT");
	}

	@Test
	void nonRepositoryLoadsOnlyWorkingDirectoryAndKeepsSnapshot() throws Exception {
		Files.writeString(this.root.resolve("AGENTS.md"), "PARENT");
		Path cwd = Files.createDirectory(this.root.resolve("cwd"));
		Path local = Files.writeString(cwd.resolve("AGENTS.md"), "SNAPSHOT");
		ProjectInstructions loaded = new ProjectInstructions(cwd);
		Files.writeString(local, "CHANGED");
		assertThat(loaded.sources()).containsExactly(new ProjectInstructions.Source(local, "SNAPSHOT"));
	}

	@Test
	void refusesSymlinksAndOversizedFiles() throws Exception {
		Path outside = Files.writeString(this.root.resolve("external"), "OUTSIDE");
		Path cwd = Files.createDirectory(this.root.resolve("cwd"));
		Path instruction = Files.createSymbolicLink(cwd.resolve("AGENTS.md"), outside);
		assertThatIOException().isThrownBy(() -> new ProjectInstructions(cwd));
		Files.delete(instruction);
		Files.write(instruction, new byte[ProjectInstructions.MAX_FILE_BYTES + 1]);
		assertThatIOException().isThrownBy(() -> new ProjectInstructions(cwd));
	}

	@Test
	void enforcesAggregateLimit() throws Exception {
		Files.createDirectory(this.root.resolve(".git"));
		Path directory = this.root;
		for (int i = 0; i < 5; i++) {
			Files.write(directory.resolve("AGENTS.md"), new byte[ProjectInstructions.MAX_FILE_BYTES]);
			directory = Files.createDirectory(directory.resolve("child"));
		}
		Path cwd = directory;
		assertThatIOException().isThrownBy(() -> new ProjectInstructions(cwd));
	}
}
