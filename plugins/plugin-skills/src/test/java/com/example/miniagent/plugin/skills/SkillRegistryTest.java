package com.example.miniagent.plugin.skills;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** 스킬 탐색: 우선순위, 깊이 제한, 없는 루트, 정렬 순서, 심볼릭 링크. */
class SkillRegistryTest {

	@TempDir
	Path project;

	@TempDir
	Path user;

	@TempDir
	Path outside;

	private static Path skill(Path base, String relativeDir, String name, String description) throws Exception {
		Path dir = Files.createDirectories(base.resolve(relativeDir));
		Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: " + description
				+ "\n---\nInstructions of " + description + "\n");
		return dir;
	}

	private static List<String> names(SkillRegistry registry) {
		return registry.all().stream().map(SkillRegistry.Skill::name).toList();
	}

	@Test
	void projectSkillBeatsUserSkillWithTheSameName() throws Exception {
		skill(this.project, "deploy-dir", "deploy", "from project");
		skill(this.user, "other-dir", "deploy", "from user");
		skill(this.user, "extra-dir", "extra", "only user");

		var registry = new SkillRegistry(List.of(this.project, this.user));

		assertThat(registry.find("deploy")).get().satisfies(found -> {
			assertThat(found.description()).isEqualTo("from project");
			assertThat(found.directory()).isEqualTo(this.project.resolve("deploy-dir"));
		});
		assertThat(names(registry)).containsExactly("deploy", "extra");
	}

	@Test
	void rootOrderDecidesPrecedenceNotTheDirectoryName() throws Exception {
		skill(this.project, "zzz", "same", "project copy");
		skill(this.user, "aaa", "same", "user copy");

		assertThat(new SkillRegistry(List.of(this.user, this.project)).find("same").orElseThrow().description())
			.isEqualTo("user copy");
	}

	@Test
	void skillsAreFoundDownToDepthThreeButNotBeyond() throws Exception {
		skill(this.project, "", "at-root", "root itself");
		skill(this.project, "a", "depth-two", "one folder down");
		skill(this.project, "a/b", "depth-three", "two folders down");
		skill(this.project, "a/b/c", "depth-four", "three folders down");

		var registry = new SkillRegistry(List.of(this.project));

		assertThat(names(registry)).containsExactlyInAnyOrder("at-root", "depth-two", "depth-three");
		assertThat(registry.find("depth-four")).isEmpty();
	}

	@Test
	void missingRootAndPlainFileRootAreSkipped() throws Exception {
		skill(this.user, "real", "real-skill", "present");
		Path notADirectory = Files.writeString(this.project.resolve("skills-file"), "not a folder");

		var registry = new SkillRegistry(List.of(this.project.resolve("absent"), notADirectory, this.user));

		assertThat(names(registry)).containsExactly("real-skill");
	}

	@Test
	void noRootsGiveAnEmptyRegistry() {
		var registry = new SkillRegistry(List.of());

		assertThat(registry.all()).isEmpty();
		assertThat(registry.find("anything")).isEmpty();
	}

	@Test
	void skillsWithinARootAreOrderedByPathAndRootsKeepTheirGivenOrder() throws Exception {
		skill(this.project, "b-dir", "apple", "second path");
		skill(this.project, "a-dir", "zebra", "first path");
		skill(this.project, "c-dir", "mango", "third path");
		skill(this.user, "a-dir", "user-first", "user root");

		var registry = new SkillRegistry(List.of(this.project, this.user));

		assertThat(names(registry)).containsExactly("zebra", "apple", "mango", "user-first");
	}

	@Test
	void symlinkedSkillFolderIsNotFollowed() throws Exception {
		skill(this.project, "plain", "plain-skill", "regular folder");
		skill(this.outside, "target", "linked-skill", "reached through a link");
		Files.createSymbolicLink(this.project.resolve("link"), this.outside.resolve("target"));

		var registry = new SkillRegistry(List.of(this.project));

		assertThat(names(registry)).containsExactly("plain-skill");
		assertThat(registry.find("linked-skill")).isEmpty();
	}

	@Test
	void findMatchesTheExactNameOnly() throws Exception {
		skill(this.project, "x", "Deploy", "capitalised");

		var registry = new SkillRegistry(List.of(this.project));

		assertThat(registry.find("Deploy")).isPresent();
		assertThat(registry.find("deploy")).isEmpty();
		assertThat(registry.find("Deploy ")).isEmpty();
	}

	@Test
	void allReturnsACopyThatCannotChangeTheRegistry() throws Exception {
		skill(this.project, "x", "kept", "stays");
		var registry = new SkillRegistry(List.of(this.project));

		registry.all().clear();

		assertThat(names(registry)).containsExactly("kept");
	}

}
