package com.example.miniagent.plugin.skills;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** SKILL.md 하나가 읽히지 않아도 나머지 스킬은 로드되어야 한다(레지스트리 생성이 시작을 막지 않는다). */
class SkillRegistryFailureTest {

	@TempDir
	Path root;

	private void writeGoodSkill(String name) throws Exception {
		Path dir = Files.createDirectories(this.root.resolve(name));
		Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: ok\n---\nbody of " + name + "\n");
	}

	private List<String> loadedNames() {
		return new SkillRegistry(List.of(this.root)).all().stream().map(SkillRegistry.Skill::name).toList();
	}

	@Test
	void nonUtf8SkillFileIsSkippedAndNeighboursStillLoad() throws Exception {
		writeGoodSkill("a-good");
		Path bad = Files.createDirectories(this.root.resolve("m-bad"));
		byte[] head = "---\nname: m-bad\ndescription: broken ".getBytes(StandardCharsets.UTF_8);
		byte[] tail = "\n---\nbody\n".getBytes(StandardCharsets.UTF_8);
		byte[] content = new byte[head.length + 1 + tail.length];
		System.arraycopy(head, 0, content, 0, head.length);
		content[head.length] = (byte) 0xFF;
		System.arraycopy(tail, 0, content, head.length + 1, tail.length);
		Files.write(bad.resolve("SKILL.md"), content);
		writeGoodSkill("z-good");

		assertThat(loadedNames()).containsExactly("a-good", "z-good");
	}

	@Test
	void directoryNamedSkillMdIsSkippedAndNeighboursStillLoad() throws Exception {
		writeGoodSkill("a-good");
		Files.createDirectories(this.root.resolve("m-odd").resolve("SKILL.md"));
		writeGoodSkill("z-good");

		assertThat(loadedNames()).containsExactly("a-good", "z-good");
	}

	@Test
	void unreadableSkillFileIsSkippedAndNeighboursStillLoad() throws Exception {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		writeGoodSkill("a-good");
		writeGoodSkill("m-locked");
		writeGoodSkill("z-good");
		Path locked = this.root.resolve("m-locked").resolve("SKILL.md");
		Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
		// root 로 실행되면 권한이 무시되어 이 상황을 만들 수 없다.
		assumeFalse(Files.isReadable(locked));

		assertThat(loadedNames()).containsExactly("a-good", "z-good");
	}

	@Test
	void unreadableSkillFolderIsSkippedAndNeighboursStillLoad() throws Exception {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		writeGoodSkill("a-good");
		writeGoodSkill("m-locked");
		writeGoodSkill("z-good");
		Path locked = this.root.resolve("m-locked");
		Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
		try {
			// root 로 실행되면 권한이 무시되어 이 상황을 만들 수 없다.
			assumeFalse(Files.isReadable(locked));

			assertThat(loadedNames()).containsExactly("a-good", "z-good");
		}
		finally {
			Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
		}
	}

}
