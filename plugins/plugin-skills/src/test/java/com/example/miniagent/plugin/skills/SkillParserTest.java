package com.example.miniagent.plugin.skills;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** SKILL.md frontmatter 규격: 직접 쓴 스킬과 레슨 내보내기가 같은 파서를 거친다. */
class SkillParserTest {

	@TempDir
	Path root;

	private List<SkillRegistry.Skill> parse(String content) throws Exception {
		Path dir = Files.createDirectories(this.root.resolve("only"));
		Files.writeString(dir.resolve("SKILL.md"), content);
		return new SkillRegistry(List.of(this.root)).all();
	}

	@Test
	void readsNameDescriptionAndBodyFromLfFrontmatter() throws Exception {
		var skills = parse("---\nname: parser-offsets\ndescription: keep offsets\n---\n\nFirst line.\n\nSecond line.\n");

		assertThat(skills).singleElement().satisfies(skill -> {
			assertThat(skill.name()).isEqualTo("parser-offsets");
			assertThat(skill.description()).isEqualTo("keep offsets");
			assertThat(skill.instructions()).isEqualTo("First line.\n\nSecond line.");
			assertThat(skill.directory()).isEqualTo(this.root.resolve("only"));
		});
	}

	@Test
	void crlfFrontmatterLeavesNoCarriageReturnInTheFields() throws Exception {
		var skills = parse("---\r\nname: windows-skill\r\ndescription: written on windows\r\n---\r\nBody line.\r\n");

		assertThat(skills).singleElement().satisfies(skill -> {
			assertThat(skill.name()).isEqualTo("windows-skill");
			assertThat(skill.description()).isEqualTo("written on windows");
			assertThat(skill.instructions()).isEqualTo("Body line.");
		});
	}

	@Test
	void missingDescriptionGivesAnEmptyDescription() throws Exception {
		var skills = parse("---\nname: bare\n---\nBody.\n");

		assertThat(skills).singleElement().satisfies(skill -> {
			assertThat(skill.name()).isEqualTo("bare");
			assertThat(skill.description()).isEmpty();
			assertThat(skill.instructions()).isEqualTo("Body.");
		});
	}

	@Test
	void colonInsideTheDescriptionIsKept() throws Exception {
		var skills = parse("---\nname: lesson\ndescription: Parser lesson: keep offsets\n---\nBody.\n");

		assertThat(skills).singleElement()
			.extracting(SkillRegistry.Skill::description)
			.isEqualTo("Parser lesson: keep offsets");
	}

	@Test
	void horizontalRuleInsideTheBodyIsNotTakenForTheClosingMarker() throws Exception {
		var skills = parse("---\nname: ruled\ndescription: d\n---\nBefore.\n\n---\n\nAfter.\n");

		assertThat(skills).singleElement()
			.extracting(SkillRegistry.Skill::instructions)
			.isEqualTo("Before.\n\n---\n\nAfter.");
	}

	@Test
	void missingNameIsSkipped() throws Exception {
		assertThat(parse("---\ndescription: nameless\n---\nBody.\n")).isEmpty();
	}

	@Test
	void blankNameIsSkipped() throws Exception {
		assertThat(parse("---\nname:   \ndescription: nameless\n---\nBody.\n")).isEmpty();
	}

	@Test
	void unclosedFrontmatterIsSkipped() throws Exception {
		assertThat(parse("---\nname: open-ended\ndescription: never closed\nBody continues.\n")).isEmpty();
	}

	@Test
	void fileWithoutFrontmatterIsSkipped() throws Exception {
		assertThat(parse("name: plain\ndescription: no marker\n\nBody.\n")).isEmpty();
	}

	@Test
	void fileStartingWithAByteOrderMarkIsSkipped() throws Exception {
		assertThat(parse("﻿---\nname: bom\ndescription: d\n---\nBody.\n")).isEmpty();
	}

}
