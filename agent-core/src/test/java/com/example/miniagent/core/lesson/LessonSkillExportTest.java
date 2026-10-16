package com.example.miniagent.core.lesson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.miniagent.core.lesson.LessonRecord.*;
import static org.assertj.core.api.Assertions.*;

class LessonSkillExportTest {
	@TempDir Path root;

	private static LessonRecord promoted(LessonFixture f, Scope scope) throws Exception {
		var lesson = f.accepted("parser keeps source offsets");
		lesson = f.service.promote(lesson.id(), 2, Scope.PROJECT, f.actor);
		return scope == Scope.GLOBAL ? f.service.promote(lesson.id(), 3, Scope.GLOBAL, f.actor) : lesson;
	}

	@Test void projectLessonBecomesAProjectSkillWithItsProvenance() throws Exception {
		var f = new LessonFixture(root);
		var lesson = promoted(f, Scope.PROJECT);
		var file = new LessonSkillExport(f.service, f.home).export(lesson.id(), 3, "parser-offsets", f.actor);
		assertThat(file).isEqualTo(Path.of(f.actor.workspace(), ".mini-agent", "skills", "parser-offsets", "SKILL.md"));
		assertThat(Files.readString(file)).isEqualTo("---\nname: parser-offsets\ndescription: Parser lesson\n---\n\n"
				+ "parser keeps source offsets\n\nSource: accepted lesson " + lesson.id()
				+ " v3 (PROJECT), grounded in goal verified-goal.\n");
		// Export leaves the lesson as it was: no revision, still injected.
		assertThat(f.service.lookup(lesson.id(), f.actor)).isEqualTo(lesson);
		assertThat(f.service.lookup(f.actor, "parser")).extracting(LessonRecord::id).containsExactly(lesson.id());
	}

	@Test void globalLessonBecomesAUserSkill() throws Exception {
		var f = new LessonFixture(root);
		var lesson = promoted(f, Scope.GLOBAL);
		var file = new LessonSkillExport(f.service, f.home).export(lesson.id(), 4, "parser-offsets", f.actor);
		assertThat(file).isEqualTo(f.home.resolve("skills").resolve("parser-offsets").resolve("SKILL.md"));
		assertThat(Files.readString(file)).contains(" v4 (GLOBAL)");
		assertThat(Path.of(f.actor.workspace(), ".mini-agent")).doesNotExist();
	}

	@Test void aMultiLineTitleBecomesAOneLineDescription() throws Exception {
		var f = new LessonFixture(root);
		var candidate = f.service.propose("verified-goal", new Content("Parser\r\nlesson", "parser body", List.of()), f.actor);
		f.service.accept(candidate.id(), 1, Scope.PROJECT, f.actor);
		var file = new LessonSkillExport(f.service, f.home).export(candidate.id(), 2, "parser-body", f.actor);
		assertThat(Files.readString(file)).startsWith("---\nname: parser-body\ndescription: Parser lesson\n---\n\n");
	}

	@Test void exportNeverWidensVisibilityOrOverwrites() throws Exception {
		var f = new LessonFixture(root);
		var export = new LessonSkillExport(f.service, f.home);
		var session = f.accepted("parser session lesson");
		assertThatThrownBy(() -> export.export(session.id(), 2, "parser-session", f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("Promote");
		var candidate = f.service.propose("verified-goal", f.content("parser candidate"), f.actor);
		assertThatThrownBy(() -> export.export(candidate.id(), 1, "parser-candidate", f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("Accepted lesson required");
		var project = f.service.promote(session.id(), 2, Scope.PROJECT, f.actor);
		assertThatThrownBy(() -> export.export(project.id(), 2, "parser-project", f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("expectedVersion");
		for (String name : List.of("../escape", "Parser", "a_b", "--a", "a--b", "a-", "", "a".repeat(65)))
			assertThatThrownBy(() -> export.export(project.id(), 3, name, f.actor))
					.isInstanceOf(IOException.class).hasMessageContaining("skill name");
		// Project skills shadow user skills of the same name, so either root blocks the name.
		Files.createDirectories(f.home.resolve("skills").resolve("taken-elsewhere"));
		assertThatThrownBy(() -> export.export(project.id(), 3, "taken-elsewhere", f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("already exists");
		var first = export.export(project.id(), 3, "parser-project", f.actor);
		assertThatThrownBy(() -> export.export(project.id(), 3, "parser-project", f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("already exists");
		assertThat(Files.readString(first)).contains("parser session lesson");
		var retired = f.service.retire(project.id(), 3, f.actor);
		assertThatThrownBy(() -> export.export(retired.id(), 4, "parser-retired", f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("Accepted lesson required");
	}

	@Test void aLinkedSkillFolderIsRefused() throws Exception {
		var f = new LessonFixture(root);
		var lesson = promoted(f, Scope.PROJECT);
		var outside = Files.createDirectory(root.resolve("outside"));
		Files.createSymbolicLink(Path.of(f.actor.workspace(), ".mini-agent"), outside);
		assertThatThrownBy(() -> new LessonSkillExport(f.service, f.home).export(lesson.id(), 3, "parser-offsets", f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("Unsafe skill directory");
		assertThat(outside).isEmptyDirectory();
	}

	@Test void exportCommandTakesStrictJsonAndSaysWhenTheSkillLoads() throws Exception {
		var f = new LessonFixture(root);
		var lesson = promoted(f, Scope.PROJECT);
		var out = new ByteArrayOutputStream();
		assertThat(f.command().executeWithStatus("export {\"id\":\"" + lesson.id() + "\",\"expectedVersion\":3}",
				f.console(out))).isFalse();
		assertThat(f.command().executeWithStatus("export {\"id\":\"" + lesson.id()
				+ "\",\"expectedVersion\":3,\"name\":\"parser-offsets\"}", f.console(out))).isTrue();
		assertThat(out.toString()).contains("\"skill\":\"parser-offsets\"").contains("SKILL.md").contains("next start");
	}
}
