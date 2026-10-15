package com.example.miniagent.core.change;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PathPolicyTest {
	@TempDir Path work;
	@TempDir Path outside;

	private PathPolicy policy() throws Exception { return new PathPolicy(this.work, this.outside.resolve("state"), List.of()); }

	@Test void canonicalCwdAndNormalNestedCreation() throws Exception {
		Path alias = Files.createSymbolicLink(this.outside.resolve("cwd-alias"), this.work);
		var policy = new PathPolicy(alias, this.outside.resolve("state"), List.of());
		assertThat(policy.root()).isEqualTo(this.work.toRealPath());
		assertThat(policy.resolveMutation("nested/new.txt")).isEqualTo(this.work.toRealPath().resolve("nested/new.txt"));
		Files.createDirectory(this.work.resolve("nested"));
		Files.writeString(this.work.resolve("nested/file"), "ok");
		assertThat(policy.resolve("nested/file")).isEqualTo(this.work.toRealPath().resolve("nested/file"));
	}

	@Test void traversalAbsoluteAndAmbiguousPathsAreTyped() throws Exception {
		var policy = policy();
		for (String path : List.of("../file", "a/../file", "a/./file", "./file", "", "/tmp/file",
				"a//file", "a/", "C:/file", "a\\file", "a\nfile")) {
			assertThatThrownBy(() -> policy.resolve(path))
				.isInstanceOf(PathPolicy.InvalidRelativePath.class)
				.satisfies(ex -> assertThat(((PathPolicy.InvalidRelativePath) ex).code()).isEqualTo(PathPolicy.Code.INVALID_PATH));
		}
	}

	@Test void symlinkParentEscapeRejected() throws Exception {
		Files.createSymbolicLink(this.work.resolve("alias"), this.outside);
		assertCode(() -> policy().resolve("alias/never-opened"), PathPolicy.Code.SYMLINK);
	}

	@Test void internalParentLinksAndLeafMutationAreAlsoRejected() throws Exception {
		Files.createDirectory(this.work.resolve("real"));
		Files.createSymbolicLink(this.work.resolve("alias"), Path.of("real"));
		assertCode(() -> policy().resolveMutation("alias/new"), PathPolicy.Code.SYMLINK);
		Files.createSymbolicLink(this.work.resolve("leaf"), this.outside.resolve("nonexistent"));
		assertThat(policy().observe("leaf").leaf().isSymbolicLink()).isTrue();
		assertCode(() -> policy().resolveMutation("leaf"), PathPolicy.Code.SYMLINK);
	}

	@Test void completedAncestorAndLeafSwapsInvalidateObservations() throws Exception {
		var policy = policy();
		Path parent = Files.createDirectory(this.work.resolve("parent"));
		Files.writeString(parent.resolve("file"), "old");
		var before = policy.observe("parent/file");
		Files.move(parent, this.work.resolve("old-parent"));
		Files.createSymbolicLink(parent, this.outside);
		assertCode(() -> policy.revalidate(before), PathPolicy.Code.SYMLINK);
		Files.delete(parent);
		Files.createDirectory(parent);
		Files.writeString(parent.resolve("file"), "old");
		assertCode(() -> policy.revalidate(before), PathPolicy.Code.CHANGED);
		var leaf = policy.observe("parent/file");
		Files.delete(parent.resolve("file"));
		Files.createSymbolicLink(parent.resolve("file"), this.outside.resolve("unread"));
		assertCode(() -> policy.revalidate(leaf), PathPolicy.Code.CHANGED);
	}

	@Test void excludedPathsAreDeniedBeforeTheirBrokenLinksAreExamined() throws Exception {
		var policy = policy();
		for (String path : List.of(".env", ".env.local", "auth.json", "credential.json", "private.key", "cert.pem",
				".git/config", "target/output", "build/output", "node_modules/package", ".mini-agent/auth/token",
				"keys/private", "certs/public")) {
			assertThat(policy.excluded(path)).isTrue();
			assertCode(() -> policy.resolve(path), PathPolicy.Code.EXCLUDED);
		}
		Files.createSymbolicLink(this.work.resolve(".env"), this.outside.resolve("not-there"));
		assertCode(() -> policy.resolve(".env"), PathPolicy.Code.EXCLUDED);
	}

	@Test void onlySkillsSubtreeIsRetainedReadOnlyAndStateIsExcluded() throws Exception {
		var policy = new PathPolicy(this.work, this.work.resolve("runtime-state"), List.of(this.outside));
		Files.createDirectories(this.work.resolve(".mini-agent/skills/tutorial"));
		Files.writeString(this.work.resolve(".mini-agent/skills/tutorial/SKILL.md"), "source");
		assertThat(policy.excluded(".mini-agent/skills/tutorial/SKILL.md")).isFalse();
		assertThat(policy.resolve(".mini-agent/skills/tutorial/SKILL.md")).exists();
		assertCode(() -> policy.resolveMutation(".mini-agent/skills/tutorial/SKILL.md"), PathPolicy.Code.EXCLUDED);
		assertCode(() -> policy.resolve("runtime-state/blob"), PathPolicy.Code.EXCLUDED);
		assertThat(policy.allowsRead(this.outside.resolve("notes"))).isTrue();
		assertThat(policy.allowsRead(this.work.resolve("runtime-state/blob"))).isFalse();
		assertThatThrownBy(() -> policy.resolveMutation(this.outside.resolve("notes").toString()))
			.isInstanceOf(PathPolicy.InvalidRelativePath.class);
	}

	@Test void nonDirectoryAncestorIsNotPretendedAbsent() throws Exception {
		Files.writeString(this.work.resolve("parent"), "not a directory");
		assertCode(() -> policy().resolveMutation("parent/file"), PathPolicy.Code.UNSUPPORTED_ENTRY);
	}

	private interface Checked { void run() throws Exception; }
	private static void assertCode(Checked action, PathPolicy.Code code) {
		assertThatThrownBy(action::run).isInstanceOf(PathPolicy.Violation.class)
			.satisfies(ex -> assertThat(((PathPolicy.Violation) ex).code()).isEqualTo(code));
	}
}
