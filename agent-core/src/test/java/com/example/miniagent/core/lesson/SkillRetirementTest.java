package com.example.miniagent.core.lesson;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import com.example.miniagent.core.SkillUseListener;
import com.example.miniagent.core.lesson.SkillUsageReport.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.miniagent.core.lesson.LessonRecord.Scope.*;
import static org.assertj.core.api.Assertions.*;

/** /lesson unexport and /lesson reinstate through the operator command, with a fixed clock and temp workspace and home. */
class SkillRetirementTest {
	static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");
	static final Duration DAY = Duration.ofDays(1);
	@TempDir Path root;
	final AtomicReference<Instant> now = new AtomicReference<>(NOW);
	final Clock clock = SkillUsageStoreTest.clock(now::get);
	LessonFixture f;
	SkillUsageStore store;
	SkillUsageReport report;
	LessonCommand command;

	@BeforeEach void fixture() throws Exception {
		f = new LessonFixture(root);
		store = new SkillUsageStore(f.home, clock);
		report = new SkillUsageReport(f.service, store, f.home, clock, () -> 0);
		command = f.command(clock, () -> 0);
	}

	private static String sha256(Path file) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
	}
	private Path projectFolder(String name) { return Path.of(f.actor.workspace(), ".mini-agent", "skills", name); }
	private Path projectRetired(String name) {
		return Path.of(f.actor.workspace(), ".mini-agent", "skills-retired", name);
	}
	/** Moves the clock to {@code days} before NOW. */
	private void daysAgo(int days) { now.set(NOW.minus(DAY.multipliedBy(days))); }
	/** An accepted lesson widened to the scope an export needs. */
	private LessonRecord lesson(LessonRecord.Scope scope) throws Exception {
		var lesson = f.service.promote(f.accepted("parser keeps source offsets").id(), 2, PROJECT, f.actor);
		return scope == GLOBAL ? f.service.promote(lesson.id(), lesson.version(), GLOBAL, f.actor) : lesson;
	}
	/** Exports a new lesson through the real export at the clock's current time. */
	private LessonRecord export(LessonRecord.Scope scope, String name) throws Exception {
		var lesson = lesson(scope);
		new LessonSkillExport(f.service, f.home, store, clock).export(lesson.id(), lesson.version(), name, f.actor);
		return lesson;
	}
	private Row row(String name) throws Exception {
		return report.report(f.actor).skills().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
	}
	/** The unexport command an operator writes from a report row. */
	private static String unexport(Row row) {
		return "unexport {\"name\":\"" + row.name() + "\",\"scope\":\"" + row.scope() + "\",\"expectedLoads\":"
				+ row.expectedLoads() + ",\"expectedDigest\":\"" + row.expectedDigest() + "\"}";
	}
	/** Runs one /lesson command that must succeed and returns what it printed. */
	private String accepted(String args) { return run(args, true); }
	/** Runs one /lesson command that must fail and returns what it printed. */
	private String refused(String args) { return run(args, false); }
	private String run(String args, boolean status) {
		var out = new ByteArrayOutputStream();
		assertThat(command.executeWithStatus(args, f.console(out))).as(out.toString()).isEqualTo(status);
		return out.toString().strip();
	}

	@Test void aLoadAfterTheReportRefusesTheUnexport() throws Exception {
		export(PROJECT, "parser-offsets");
		var row = row("parser-offsets");
		new SkillUsageRecorder(store, Path.of(f.actor.workspace()), f.home)
				.loaded(projectFolder("parser-offsets"), SkillUseListener.Kind.CHOSEN);
		assertThat(refused(unexport(row))).contains("Lesson IOException: Stale expectedLoads");
		assertThat(projectFolder("parser-offsets").resolve("SKILL.md")).isRegularFile();
		assertThat(store.read().entries()).singleElement().satisfies(entry -> {
			assertThat(entry.retiredAt()).isNull();
			assertThat(entry.chosenLoads()).isEqualTo(1);
		});
	}

	@Test void anEditAfterTheReportRefusesTheUnexport() throws Exception {
		export(PROJECT, "parser-offsets");
		var row = row("parser-offsets");
		Files.writeString(projectFolder("parser-offsets").resolve("SKILL.md"), "\nAn operator note.\n",
				StandardOpenOption.APPEND);
		assertThat(refused(unexport(row))).contains("Lesson IOException: Stale expectedDigest");
		assertThat(projectFolder("parser-offsets").resolve("SKILL.md")).isRegularFile();
		assertThat(store.read().entries().getFirst().retiredAt()).isNull();
	}

	@Test void aHandWrittenSkillHasNoExportLinkAndTheStoreIsNotCreated() throws Exception {
		var file = Files.createDirectories(projectFolder("code-tour")).resolve("SKILL.md");
		Files.writeString(file, "---\nname: code-tour\ndescription: tour\n---\n\nbody\n");
		assertThat(refused("unexport {\"name\":\"code-tour\",\"scope\":\"PROJECT\",\"expectedLoads\":0,"
				+ "\"expectedDigest\":\"" + sha256(file) + "\"}"))
				.contains("Lesson IOException: No export link for skill: code-tour");
		assertThat(file).isRegularFile();
		assertThat(f.home.resolve("skill-usage")).doesNotExist();
	}

	@Test void aProjectUnexportMovesTheFolderByteForByteAndRecordsTheReportsReasons() throws Exception {
		daysAgo(31);
		var lesson = export(PROJECT, "parser-offsets");
		now.set(NOW);
		f.service.retire(lesson.id(), lesson.version(), f.actor);
		byte[] exported = Files.readAllBytes(projectFolder("parser-offsets").resolve("SKILL.md"));
		var row = row("parser-offsets");
		assertThat(row.reasons()).containsExactly(SkillUsageReport.Reason.SOURCE_RETIRED, SkillUsageReport.Reason.UNUSED);
		assertThat(accepted(unexport(row))).isEqualTo("{\"skill\":\"parser-offsets\",\"from\":\""
				+ projectFolder("parser-offsets") + "\",\"to\":\"" + projectRetired("parser-offsets")
				+ "\",\"loads\":\"next start\",\"warning\":\"PROJECT skills live inside the workspace, so this changes "
				+ "the workspace fingerprint: succeeded goals in this workspace lose their verified evidence and can no "
				+ "longer ground new lessons; run a new goal to verify again.\"}");
		assertThat(projectFolder("parser-offsets")).doesNotExist();
		assertThat(Files.readAllBytes(projectRetired("parser-offsets").resolve("SKILL.md"))).isEqualTo(exported);
		assertThat(store.read().entries()).singleElement().satisfies(entry -> {
			assertThat(entry.retiredAt()).isEqualTo(NOW);
			assertThat(entry.retiredReason()).isEqualTo("SOURCE_RETIRED,UNUSED");
		});
	}

	@Test void aGlobalUnexportMovesIntoTheHomesRetiredRootWithoutAWarning() throws Exception {
		export(GLOBAL, "shared-tips");
		var folder = f.home.resolve("skills").resolve("shared-tips");
		byte[] exported = Files.readAllBytes(folder.resolve("SKILL.md"));
		var row = row("shared-tips");
		assertThat(row.reasons()).isEmpty();
		assertThat(accepted(unexport(row))).isEqualTo("{\"skill\":\"shared-tips\",\"from\":\"" + folder
				+ "\",\"to\":\"" + f.home.resolve("skills-retired").resolve("shared-tips")
				+ "\",\"loads\":\"next start\",\"warning\":null}");
		assertThat(folder).doesNotExist();
		assertThat(Files.readAllBytes(f.home.resolve("skills-retired").resolve("shared-tips").resolve("SKILL.md")))
				.isEqualTo(exported);
		// No rule applied, so the retirement is the operator's own call.
		assertThat(store.read().entries().getFirst().retiredReason()).isEqualTo("OPERATOR");
		assertThat(Path.of(f.actor.workspace(), ".mini-agent")).doesNotExist();
	}

	@Test void theReasonComesFromTheRowOfTheSameScope() throws Exception {
		daysAgo(31);
		export(GLOBAL, "parser-offsets");
		// A GLOBAL folder deleted by hand frees the name for a PROJECT export; the GLOBAL link stays.
		var global = f.home.resolve("skills").resolve("parser-offsets");
		Files.delete(global.resolve("SKILL.md"));
		Files.delete(global);
		now.set(NOW);
		export(PROJECT, "parser-offsets");
		var project = report.report(f.actor).skills().stream().filter(r -> r.scope() == PROJECT).findFirst()
				.orElseThrow();
		accepted(unexport(project));
		assertThat(store.read().entries()).extracting(SkillUsage::scope, SkillUsage::retiredReason)
				.containsExactly(tuple(GLOBAL, null), tuple(PROJECT, "OPERATOR"));
	}

	@Test void anUnexportedNameCannotBeExportedAgain() throws Exception {
		export(PROJECT, "parser-offsets");
		accepted(unexport(row("parser-offsets")));
		var other = lesson(PROJECT);
		assertThatThrownBy(() -> new LessonSkillExport(f.service, f.home, store, clock)
				.export(other.id(), other.version(), "parser-offsets", f.actor))
				.hasMessage("Skill is retired, use /lesson reinstate: " + projectRetired("parser-offsets"));
		assertThat(projectFolder("parser-offsets")).doesNotExist();
		assertThat(store.read().entries()).singleElement().extracting(SkillUsage::retiredAt).isEqualTo(NOW);
	}

	@Test void anUnexportedSkillIsAlreadyRetired() throws Exception {
		export(PROJECT, "parser-offsets");
		var row = row("parser-offsets");
		accepted(unexport(row));
		assertThat(refused(unexport(row))).contains("Lesson IOException: Skill already retired: parser-offsets");
		assertThat(projectRetired("parser-offsets").resolve("SKILL.md")).isRegularFile();
	}

	@Test void anExistingRetiredCopyRefusesTheUnexport() throws Exception {
		export(PROJECT, "parser-offsets");
		var row = row("parser-offsets");
		Files.writeString(Files.createDirectories(projectRetired("parser-offsets")).resolve("SKILL.md"), "older copy");
		assertThat(refused(unexport(row)))
				.contains("Lesson IOException: Retired copy exists: " + projectRetired("parser-offsets"));
		assertThat(projectFolder("parser-offsets").resolve("SKILL.md")).isRegularFile();
		assertThat(projectRetired("parser-offsets").resolve("SKILL.md")).hasContent("older copy");
		assertThat(store.read().entries().getFirst().retiredAt()).isNull();
	}

	@Test void aSkillFolderReplacedByALinkIsNeverMoved() throws Exception {
		export(PROJECT, "parser-offsets");
		var outside = Files.createDirectory(root.resolve("outside"));
		Files.move(projectFolder("parser-offsets").resolve("SKILL.md"), outside.resolve("SKILL.md"));
		Files.delete(projectFolder("parser-offsets"));
		Files.createSymbolicLink(projectFolder("parser-offsets"), outside);
		// The report reads through the folder link, so its digest matches the exported bytes.
		var row = row("parser-offsets");
		assertThat(row.flags()).isEmpty();
		assertThat(refused(unexport(row))).contains("Lesson IOException: Stale expectedDigest");
		assertThat(projectFolder("parser-offsets")).isSymbolicLink();
		assertThat(projectRetired("parser-offsets")).doesNotExist();
	}

	@Test void aLinkedRetiredRootIsRefused() throws Exception {
		export(PROJECT, "parser-offsets");
		var row = row("parser-offsets");
		var outside = Files.createDirectory(root.resolve("outside"));
		Files.createSymbolicLink(projectRetired("parser-offsets").getParent(), outside);
		assertThat(refused(unexport(row))).contains("Lesson IOException: Unsafe skill directory");
		assertThat(outside).isEmptyDirectory();
		assertThat(projectFolder("parser-offsets").resolve("SKILL.md")).isRegularFile();
	}

	@Test void reinstateMovesTheFolderBackAndRestartsTheThirtyDays() throws Exception {
		daysAgo(31);
		export(PROJECT, "parser-offsets");
		now.set(NOW);
		byte[] exported = Files.readAllBytes(projectFolder("parser-offsets").resolve("SKILL.md"));
		accepted(unexport(row("parser-offsets")));
		var restoredAt = NOW.plus(DAY);
		now.set(restoredAt);
		assertThat(accepted("reinstate {\"name\":\"parser-offsets\",\"scope\":\"PROJECT\"}"))
				.isEqualTo("{\"skill\":\"parser-offsets\",\"from\":\"" + projectRetired("parser-offsets")
				+ "\",\"to\":\"" + projectFolder("parser-offsets") + "\",\"loads\":\"next start\",\"warning\":\""
				+ "PROJECT skills live inside the workspace, so this changes the workspace fingerprint: succeeded goals "
				+ "in this workspace lose their verified evidence and can no longer ground new lessons; run a new goal "
				+ "to verify again.\"}");
		assertThat(projectRetired("parser-offsets")).doesNotExist();
		assertThat(Files.readAllBytes(projectFolder("parser-offsets").resolve("SKILL.md"))).isEqualTo(exported);
		assertThat(store.read().entries()).singleElement().satisfies(entry -> {
			assertThat(entry.retiredAt()).isNull();
			assertThat(entry.retiredReason()).isNull();
			assertThat(entry.restoredAt()).isEqualTo(restoredAt);
		});
		// The export is 32 days old, but the restore counts as the latest sign of use.
		now.set(restoredAt.plus(DAY.multipliedBy(30)).minusSeconds(1));
		assertThat(row("parser-offsets").reasons()).isEmpty();
		now.set(restoredAt.plus(DAY.multipliedBy(30)));
		assertThat(row("parser-offsets").reasons()).containsExactly(SkillUsageReport.Reason.UNUSED);
	}

	@Test void aGlobalSkillIsReinstatedIntoTheUserRootWithoutAWarning() throws Exception {
		export(GLOBAL, "shared-tips");
		accepted(unexport(row("shared-tips")));
		assertThat(accepted("reinstate {\"name\":\"shared-tips\",\"scope\":\"GLOBAL\"}"))
				.isEqualTo("{\"skill\":\"shared-tips\",\"from\":\""
				+ f.home.resolve("skills-retired").resolve("shared-tips") + "\",\"to\":\""
				+ f.home.resolve("skills").resolve("shared-tips")
				+ "\",\"loads\":\"next start\",\"warning\":null}");
		assertThat(f.home.resolve("skills").resolve("shared-tips").resolve("SKILL.md")).isRegularFile();
		assertThat(store.read().entries().getFirst().restoredAt()).isEqualTo(NOW);
	}

	@Test void reinstateIsRefusedWhileEitherLiveRootHasTheName() throws Exception {
		export(PROJECT, "parser-offsets");
		accepted(unexport(row("parser-offsets")));
		byte[] retired = Files.readAllBytes(projectRetired("parser-offsets").resolve("SKILL.md"));
		String reinstate = "reinstate {\"name\":\"parser-offsets\",\"scope\":\"PROJECT\"}";
		// Project skills shadow user skills of the same name, so a hand-written skill in either root keeps the name.
		var user = Files.createDirectories(f.home.resolve("skills").resolve("parser-offsets"));
		assertThat(refused(reinstate)).contains("Lesson IOException: Skill already exists: parser-offsets");
		Files.delete(user);
		Files.writeString(Files.createDirectories(projectFolder("parser-offsets")).resolve("SKILL.md"), "hand-written");
		assertThat(refused(reinstate)).contains("Lesson IOException: Skill already exists: parser-offsets");
		assertThat(projectFolder("parser-offsets").resolve("SKILL.md")).hasContent("hand-written");
		assertThat(Files.readAllBytes(projectRetired("parser-offsets").resolve("SKILL.md"))).isEqualTo(retired);
		assertThat(store.read().entries().getFirst().retiredAt()).isEqualTo(NOW);
	}

	@Test void reinstateNeedsARetiredSkillWithARealRetiredCopy() throws Exception {
		export(PROJECT, "parser-offsets");
		String reinstate = "reinstate {\"name\":\"parser-offsets\",\"scope\":\"PROJECT\"}";
		assertThat(refused(reinstate)).contains("Lesson IOException: Skill is not retired: parser-offsets");
		assertThat(refused("reinstate {\"name\":\"code-tour\",\"scope\":\"PROJECT\"}"))
				.contains("Lesson IOException: No export link for skill: code-tour");
		accepted(unexport(row("parser-offsets")));
		var outside = Files.move(projectRetired("parser-offsets"), root.resolve("outside"));
		Files.createSymbolicLink(projectRetired("parser-offsets"), outside);
		assertThat(refused(reinstate))
				.contains("Lesson IOException: No retired copy: " + projectRetired("parser-offsets"));
		assertThat(projectFolder("parser-offsets")).doesNotExist();
		assertThat(store.read().entries().getFirst().retiredAt()).isEqualTo(NOW);
	}

	@Test void bothCommandsTakeStrictJsonWithAProjectOrGlobalScope() throws Exception {
		export(PROJECT, "parser-offsets");
		var row = row("parser-offsets");
		assertThat(refused("unexport {\"name\":\"parser-offsets\",\"scope\":\"PROJECT\",\"expectedDigest\":\""
				+ row.expectedDigest() + "\"}")).contains("expectedLoads");
		assertThat(refused(unexport(row).replace("}", ",\"force\":true}"))).contains("force");
		assertThat(refused(unexport(row).replace("\"PROJECT\"", "\"SESSION\"")))
				.contains("Lesson IOException: Skill scope must be PROJECT or GLOBAL");
		assertThat(refused("reinstate {\"name\":\"parser-offsets\",\"scope\":null}"))
				.contains("Lesson IOException: Skill scope must be PROJECT or GLOBAL");
		assertThat(refused("reinstate {\"name\":\"../escape\",\"scope\":\"PROJECT\"}"))
				.contains("Lesson IOException: Invalid skill name");
		assertThat(projectFolder("parser-offsets").resolve("SKILL.md")).isRegularFile();
		assertThat(store.read().entries().getFirst().retiredAt()).isNull();
	}

	@Test void theCommandListsBothMovesAndNeitherIsAControlInput() {
		assertThat(command.description()).contains("/export/unexport/reinstate <JSON>");
		assertThat(LessonCommand.isControlInput("/lesson unexport {\"name\":\"parser-offsets\"}")).isFalse();
		assertThat(LessonCommand.isControlInput("/lesson reinstate {\"name\":\"parser-offsets\"}")).isFalse();
	}

	@Test void aRetirementTheStoreCannotPersistMovesTheFolderBack() throws Exception {
		var lesson = lesson(PROJECT);
		// The usage home lives inside the skill folder, so the move takes the store along and the write after it fails.
		var inside = projectFolder("parser-offsets").resolve("state");
		var usage = new SkillUsageStore(inside, clock);
		var export = new LessonSkillExport(f.service, inside, usage, clock);
		export.export(lesson.id(), lesson.version(), "parser-offsets", f.actor);
		byte[] exported = Files.readAllBytes(projectFolder("parser-offsets").resolve("SKILL.md"));
		var linked = usage.read();
		var row = new SkillUsageReport(f.service, usage, inside, clock, () -> 0).report(f.actor).skills().getFirst();
		assertThatThrownBy(() -> export.unexport("parser-offsets", PROJECT, row.expectedLoads(), row.expectedDigest(),
				"OPERATOR", f.actor)).hasMessageStartingWith("Unsafe change directory").hasNoSuppressedExceptions();
		assertThat(Files.readAllBytes(projectFolder("parser-offsets").resolve("SKILL.md"))).isEqualTo(exported);
		assertThat(projectRetired("parser-offsets")).doesNotExist();
		assertThat(usage.read()).isEqualTo(linked);
	}

	@Test void aReinstateTheStoreCannotPersistMovesTheFolderBack() throws Exception {
		// Here the usage home lives inside the retired copy, so the move back takes the store along.
		var retired = Files.createDirectories(projectRetired("parser-offsets"));
		Files.writeString(retired.resolve("SKILL.md"), "---\nname: parser-offsets\ndescription: tips\n---\n\nbody\n");
		byte[] bytes = Files.readAllBytes(retired.resolve("SKILL.md"));
		var inside = retired.resolve("state");
		var usage = new SkillUsageStore(inside, clock);
		usage.link(new SkillUsage(PROJECT, f.actor.workspace(), "parser-offsets", "lesson-1", 3, NOW.minus(DAY),
				sha256(retired.resolve("SKILL.md")), 0, 0, null, NOW, "OPERATOR", null));
		var unexported = usage.read();
		assertThatThrownBy(() -> new LessonSkillExport(f.service, inside, usage, clock)
				.reinstate("parser-offsets", PROJECT, f.actor))
				.hasMessageStartingWith("Unsafe change directory").hasNoSuppressedExceptions();
		assertThat(Files.readAllBytes(retired.resolve("SKILL.md"))).isEqualTo(bytes);
		assertThat(projectFolder("parser-offsets")).doesNotExist();
		assertThat(usage.read()).isEqualTo(unexported);
	}
}
