package com.example.miniagent.core.lesson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import com.example.miniagent.core.change.AtomicFiles;
import com.example.miniagent.core.change.ChangeStore;
import static com.example.miniagent.core.lesson.LessonRecord.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;

class LessonSkillExportTest {
	private static final Instant NOW = Instant.parse("2026-10-01T09:30:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	@TempDir Path root;

	private static LessonSkillExport linking(LessonFixture f) {
		return new LessonSkillExport(f.service, f.home, new SkillUsageStore(f.home, CLOCK), CLOCK);
	}
	private static String sha256(Path file) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
	}
	private static List<SkillUsage> links(LessonFixture f) throws Exception {
		return new SkillUsageStore(f.home, CLOCK).read().entries();
	}

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

	@Test void aProjectExportLinksTheSkillToItsLessonAndTheWrittenBytes() throws Exception {
		var f = new LessonFixture(root);
		var lesson = promoted(f, Scope.PROJECT);
		var file = linking(f).export(lesson.id(), 3, "parser-offsets", f.actor);
		assertThat(links(f)).containsExactly(new SkillUsage(Scope.PROJECT, f.actor.workspace(), "parser-offsets",
				lesson.id(), 3, NOW, sha256(file), 0, 0, null, null, null, null));
	}

	@Test void aGlobalExportLinksWithoutAWorkspace() throws Exception {
		var f = new LessonFixture(root);
		var lesson = promoted(f, Scope.GLOBAL);
		var file = linking(f).export(lesson.id(), 4, "parser-offsets", f.actor);
		assertThat(links(f)).containsExactly(new SkillUsage(Scope.GLOBAL, null, "parser-offsets", lesson.id(), 4, NOW,
				sha256(file), 0, 0, null, null, null, null));
	}

	@Test void aRefusedNameAddsNoLink() throws Exception {
		var f = new LessonFixture(root);
		var first = promoted(f, Scope.PROJECT);
		var second = promoted(f, Scope.PROJECT);
		var export = linking(f);
		Files.createDirectories(f.home.resolve("skills").resolve("taken-elsewhere"));
		assertThatThrownBy(() -> export.export(first.id(), 3, "taken-elsewhere", f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("already exists");
		assertThat(new SkillUsageStore(f.home, CLOCK).read()).isEqualTo(new SkillUsageStore.Snapshot(null, List.of()));
		export.export(first.id(), 3, "parser-offsets", f.actor);
		assertThatThrownBy(() -> export.export(second.id(), 3, "parser-offsets", f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("already exists");
		assertThat(links(f)).extracting(SkillUsage::lessonId).containsExactly(first.id());
	}

	@Test void anExportThatCannotBeLinkedLeavesNoSkill() throws Exception {
		var f = new LessonFixture(root);
		var lesson = promoted(f, Scope.PROJECT);
		var store = Files.createDirectory(f.home.resolve("skill-usage"),
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		Files.writeString(store.resolve("junk.txt"), "not a usage store file");
		assertThatThrownBy(() -> linking(f).export(lesson.id(), 3, "parser-offsets", f.actor))
				.isInstanceOf(IOException.class).hasMessage("Skill usage storage recovery required");
		assertThat(Path.of(f.actor.workspace(), ".mini-agent", "skills", "parser-offsets")).doesNotExist();
	}

	@Test void anExportWhoseSkillFileCannotBeWrittenLeavesNoLink() throws Exception {
		var f = new LessonFixture(root);
		var lesson = promoted(f, Scope.PROJECT);
		var folder = Path.of(f.actor.workspace(), ".mini-agent", "skills", "parser-offsets");
		// No file system state fails a write into the folder the export has just created, so that one write is made to throw.
		try (var files = Mockito.mockStatic(AtomicFiles.class, Mockito.CALLS_REAL_METHODS)) {
			files.when(() -> AtomicFiles.write(argThat(path -> path != null && path.endsWith("SKILL.md")), any(), anyInt()))
					.thenThrow(new ChangeStore.Failure(ChangeStore.Code.IO, "SKILL.md write failed"));
			assertThatThrownBy(() -> linking(f).export(lesson.id(), 3, "parser-offsets", f.actor))
					.isInstanceOf(ChangeStore.Failure.class).hasMessage("SKILL.md write failed")
					.hasNoSuppressedExceptions();
		}
		assertThat(links(f)).isEmpty();
		assertThat(folder).doesNotExist();
		// The name is free again: the same export now succeeds and is linked.
		var file = linking(f).export(lesson.id(), 3, "parser-offsets", f.actor);
		assertThat(file).isEqualTo(folder.resolve("SKILL.md")).isRegularFile();
		assertThat(links(f)).extracting(SkillUsage::lessonId).containsExactly(lesson.id());
	}

	@Test void onlyAProjectExportWarnsThatVerifiedGoalsLoseTheirEvidence() throws Exception {
		var f = new LessonFixture(root);
		var project = promoted(f, Scope.PROJECT);
		var global = promoted(f, Scope.GLOBAL);
		var projectOut = new ByteArrayOutputStream();
		var globalOut = new ByteArrayOutputStream();
		assertThat(f.command().executeWithStatus("export {\"id\":\"" + project.id()
				+ "\",\"expectedVersion\":3,\"name\":\"parser-project\"}", f.console(projectOut))).isTrue();
		assertThat(f.command().executeWithStatus("export {\"id\":\"" + global.id()
				+ "\",\"expectedVersion\":4,\"name\":\"parser-global\"}", f.console(globalOut))).isTrue();
		assertThat(LessonStore.JSON.readTree(projectOut.toString()).path("warning").asString()).isEqualTo(
				"PROJECT skills live inside the workspace, so this changes the workspace fingerprint: succeeded goals "
				+ "in this workspace lose their verified evidence and can no longer ground new lessons; "
				+ "run a new goal to verify again.");
		assertThat(LessonStore.JSON.readTree(globalOut.toString()).path("warning").isNull()).isTrue();
	}

	@Test void aNameInEitherRetiredRootIsReservedForReinstate() throws Exception {
		var f = new LessonFixture(root);
		var lesson = promoted(f, Scope.PROJECT);
		var export = linking(f);
		var userRetired = Files.createDirectories(f.home.resolve("skills-retired").resolve("user-retired"));
		assertThatThrownBy(() -> export.export(lesson.id(), 3, "user-retired", f.actor))
				.isInstanceOf(IOException.class).hasMessage("Skill is retired, use /lesson reinstate: " + userRetired);
		var projectRetired = Files.createDirectories(
				Path.of(f.actor.workspace(), ".mini-agent", "skills-retired", "project-retired"));
		assertThatThrownBy(() -> export.export(lesson.id(), 3, "project-retired", f.actor))
				.isInstanceOf(IOException.class).hasMessage("Skill is retired, use /lesson reinstate: " + projectRetired);
		assertThat(Path.of(f.actor.workspace(), ".mini-agent", "skills")).doesNotExist();
		assertThat(links(f)).isEmpty();
	}
}
