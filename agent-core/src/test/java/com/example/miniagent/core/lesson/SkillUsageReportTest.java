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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.SkillUseListener;
import com.example.miniagent.core.lesson.LessonRecord.Actor;
import com.example.miniagent.core.lesson.SkillUsageReport.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import static com.example.miniagent.core.lesson.LessonRecord.Scope.*;
import static org.assertj.core.api.Assertions.*;

/** Rules are driven through the report with a fixed clock; skills are exported by the real export or linked by hand. */
class SkillUsageReportTest {
	static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");
	static final Duration DAY = Duration.ofDays(1);
	@TempDir Path root;
	final AtomicReference<Instant> now = new AtomicReference<>(NOW);
	final Clock clock = SkillUsageStoreTest.clock(now::get);
	LessonFixture f;
	SkillUsageStore store;
	SkillUsageReport report;

	@BeforeEach void fixture() throws Exception {
		f = new LessonFixture(root);
		store = new SkillUsageStore(f.home, clock);
		report = new SkillUsageReport(f.service, store, f.home, clock, () -> 0);
	}

	private static String sha256(Path file) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
	}
	private Path projectFile(String name) {
		return Path.of(f.actor.workspace(), ".mini-agent", "skills", name, "SKILL.md");
	}
	private Path globalFile(String name) { return f.home.resolve("skills").resolve(name).resolve("SKILL.md"); }
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
	/** Moves the clock to {@code days} before NOW. */
	private void daysAgo(int days) { now.set(NOW.minus(DAY.multipliedBy(days))); }
	private void change(String name, UnaryOperator<SkillUsage> edit) throws Exception {
		store.mutate(entries -> {
			entries.replaceAll(e -> e.name().equals(name) ? edit.apply(e) : e);
			return null;
		});
	}
	private static SkillUsage restored(SkillUsage e, Instant at) {
		return new SkillUsage(e.scope(), e.workspace(), e.name(), e.lessonId(), e.lessonVersion(), e.exportedAt(),
				e.exportDigest(), e.chosenLoads(), e.forcedLoads(), e.lastLoadedAt(), e.retiredAt(), e.retiredReason(), at);
	}
	private static SkillUsage retired(SkillUsage e, Instant at, String reason) {
		return new SkillUsage(e.scope(), e.workspace(), e.name(), e.lessonId(), e.lessonVersion(), e.exportedAt(),
				e.exportDigest(), e.chosenLoads(), e.forcedLoads(), e.lastLoadedAt(), at, reason, e.restoredAt());
	}
	private List<Row> rows() throws Exception { return report.report(f.actor).skills(); }
	private Path projectRetiredFile(String name) {
		return Path.of(f.actor.workspace(), ".mini-agent", "skills-retired", name, "SKILL.md");
	}
	private Path globalRetiredFile(String name) { return f.home.resolve("skills-retired").resolve(name).resolve("SKILL.md"); }
	/** Retires an exported skill the way an operator does, from the values its report row printed. */
	private void unexport(String name, LessonRecord.Scope scope) throws Exception {
		var row = row(name);
		new LessonSkillExport(f.service, f.home, store, clock)
				.unexport(name, scope, row.expectedLoads(), row.expectedDigest(), "OPERATOR", f.actor);
	}
	private Row row(String name) throws Exception {
		return rows().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
	}

	@Test void aHandWrittenSkillWithoutALinkNeverAppears() throws Exception {
		Files.createDirectories(projectFile("code-tour").getParent());
		Files.writeString(projectFile("code-tour"), "---\nname: code-tour\ndescription: tour\n---\n\nbody\n");
		Files.createDirectories(globalFile("hand-written").getParent());
		Files.writeString(globalFile("hand-written"), "---\nname: hand-written\ndescription: mine\n---\n\nbody\n");
		assertThat(rows()).isEmpty();
		export(PROJECT, "parser-offsets");
		assertThat(rows()).extracting(Row::name).containsExactly("parser-offsets");
	}

	@Test void aLinkedSkillShowsItsLinkLoadsAndTheValuesToRepeat() throws Exception {
		var lesson = export(PROJECT, "parser-offsets");
		store.mutate(entries -> {
			var at = NOW.plusSeconds(5);
			entries.replaceAll(e -> e.loaded(SkillUseListener.Kind.CHOSEN, at).loaded(SkillUseListener.Kind.CHOSEN, at)
					.loaded(SkillUseListener.Kind.FORCED, at));
			return null;
		});
		var row = row("parser-offsets");
		assertThat(row).isEqualTo(new Row("parser-offsets", PROJECT, f.actor.workspace(), lesson.id(), 3, "ACCEPTED", 3,
				NOW, NOW.plusSeconds(5), null, null, null, 2, 1, List.of(), List.of(), false, 3,
				sha256(projectFile("parser-offsets"))));
	}

	@Test void anotherWorkspacesProjectRowIsHiddenButGlobalRowsShow() throws Exception {
		export(PROJECT, "project-skill");
		export(GLOBAL, "global-skill");
		store.link(new SkillUsage(PROJECT, root.resolve("elsewhere").toString(), "other-skill",
				UUID.randomUUID().toString(), 1, NOW, "b".repeat(64), 0, 0, null, null, null, null));
		assertThat(store.read().entries()).hasSize(3);
		assertThat(rows()).extracting(Row::name).containsExactly("project-skill", "global-skill");
	}

	@Test void aProjectRowShowsOnlyInTheWorkspaceItBelongsTo() throws Exception {
		export(PROJECT, "project-skill");
		export(GLOBAL, "global-skill");
		var elsewhere = new Actor("other-session", Files.createDirectory(root.resolve("elsewhere")).toRealPath().toString());
		assertThat(report.report(elsewhere).skills()).extracting(Row::name).containsExactly("global-skill");
	}

	@Test void aSkillExportedThirtyOneDaysAgoAndNeverLoadedIsAnUnusedCandidate() throws Exception {
		daysAgo(31);
		export(PROJECT, "parser-offsets");
		now.set(NOW);
		var report = this.report.report(f.actor);
		assertThat(report.trackingSince()).isEqualTo(NOW.minus(DAY.multipliedBy(31)));
		assertThat(report.unusedDays()).isEqualTo(30);
		var row = report.skills().getFirst();
		assertThat(row.reasons()).containsExactly(SkillUsageReport.Reason.UNUSED);
		assertThat(row.flags()).isEmpty();
		assertThat(row.candidate()).isTrue();
		assertThat(row.expectedLoads()).isZero();
		assertThat(row.expectedDigest()).isEqualTo(sha256(projectFile("parser-offsets")));
	}

	@Test void unusedBeginsAtExactlyThirtyDays() throws Exception {
		daysAgo(30);
		export(PROJECT, "thirty-days");
		now.set(NOW.minus(DAY.multipliedBy(30)).plusSeconds(1));
		export(PROJECT, "just-under");
		now.set(NOW);
		assertThat(row("thirty-days").reasons()).containsExactly(SkillUsageReport.Reason.UNUSED);
		assertThat(row("just-under").reasons()).isEmpty();
	}

	@Test void nothingIsUnusedUntilThirtyDaysAfterTheUsageWasFirstTracked() throws Exception {
		daysAgo(10);
		var lesson = lesson(PROJECT);
		var file = projectFile("old-export");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "---\nname: old-export\ndescription: old\n---\n\nbody\n");
		store.link(new SkillUsage(PROJECT, f.actor.workspace(), "old-export", lesson.id(), lesson.version(),
				NOW.minus(DAY.multipliedBy(31)), sha256(file), 0, 0, null, null, null, null));
		now.set(NOW);
		assertThat(row("old-export").reasons()).isEmpty();
		now.set(NOW.plus(DAY.multipliedBy(20)));
		assertThat(row("old-export").reasons()).containsExactly(SkillUsageReport.Reason.UNUSED);
	}

	@Test void aLoadInTheLastThirtyDaysKeepsASkillInUse() throws Exception {
		daysAgo(31);
		export(PROJECT, "parser-offsets");
		now.set(NOW);
		change("parser-offsets", e -> e.loaded(SkillUseListener.Kind.FORCED, NOW.minus(DAY.multipliedBy(5))));
		var row = row("parser-offsets");
		assertThat(row.reasons()).isEmpty();
		assertThat(row.candidate()).isFalse();
	}

	@Test void aRestoreStartsTheThirtyDaysAgain() throws Exception {
		daysAgo(31);
		export(PROJECT, "parser-offsets");
		now.set(NOW);
		change("parser-offsets", e -> restored(e, NOW.minus(DAY.multipliedBy(5))));
		var row = row("parser-offsets");
		assertThat(row.restoredAt()).isEqualTo(NOW.minus(DAY.multipliedBy(5)));
		assertThat(row.reasons()).isEmpty();
	}

	@Test void retiringTheLessonMakesItsSkillASourceRetiredCandidateWhateverItsUse() throws Exception {
		daysAgo(1);
		var lesson = export(PROJECT, "parser-offsets");
		f.service.retire(lesson.id(), lesson.version(), f.actor);
		now.set(NOW);
		var row = row("parser-offsets");
		assertThat(row.lessonState()).isEqualTo("RETIRED");
		assertThat(row.reasons()).containsExactly(SkillUsageReport.Reason.SOURCE_RETIRED);
		assertThat(row.candidate()).isTrue();
	}

	@Test void aRetiredLessonAndAnIdleSkillAreBothReasons() throws Exception {
		daysAgo(31);
		var lesson = export(PROJECT, "parser-offsets");
		f.service.retire(lesson.id(), lesson.version(), f.actor);
		now.set(NOW);
		var row = row("parser-offsets");
		assertThat(row.reasons()).containsExactly(SkillUsageReport.Reason.SOURCE_RETIRED, SkillUsageReport.Reason.UNUSED);
		// Retiring is itself a revision, and a changed lesson does not stop its skill from being a candidate.
		assertThat(row.flags()).containsExactly(SkillUsageReport.Flag.LESSON_CHANGED);
		assertThat(row.candidate()).isTrue();
	}

	@Test void aNewerLessonRevisionIsFlaggedButIsNoReasonByItself() throws Exception {
		var lesson = export(PROJECT, "parser-offsets");
		f.service.update(lesson.id(), lesson.version(), "verified-goal", f.content("parser keeps offsets and lines"), f.actor);
		var row = row("parser-offsets");
		assertThat(row.linkedVersion()).isEqualTo(3);
		assertThat(row.lessonVersion()).isEqualTo(4);
		assertThat(row.lessonState()).isEqualTo("ACCEPTED");
		assertThat(row.flags()).containsExactly(SkillUsageReport.Flag.LESSON_CHANGED);
		assertThat(row.reasons()).isEmpty();
		assertThat(row.candidate()).isFalse();
	}

	@Test void aLinkedLessonMissingFromTheStoreIsUnknownAndCountsAsASourceRetired() throws Exception {
		var file = globalFile("ghost");
		Files.createDirectories(file.getParent());
		Files.writeString(file, "---\nname: ghost\ndescription: g\n---\n\nbody\n");
		store.link(new SkillUsage(GLOBAL, null, "ghost", UUID.randomUUID().toString(), 2, NOW, sha256(file), 0, 0, null,
				null, null, null));
		var row = row("ghost");
		assertThat(row.lessonState()).isEqualTo("UNKNOWN");
		assertThat(row.lessonVersion()).isNull();
		assertThat(row.reasons()).containsExactly(SkillUsageReport.Reason.SOURCE_RETIRED);
		assertThat(row.flags()).isEmpty();
	}

	@Test void aSkillWhoseRetiredLessonWasEvictedStaysASourceRetiredCandidate() throws Exception {
		var lesson = export(PROJECT, "parser-offsets");
		var retired = f.service.retire(lesson.id(), lesson.version(), f.actor);
		// Accepted lessons are never evicted, so the retired source is the one entry a new proposal can push out.
		f.store.mutate(histories -> {
			while (histories.size() < LessonStore.MAX_ENTRIES)
				histories.add(List.of(new LessonRecord(UUID.randomUUID().toString(), 1, LessonRecord.State.ACCEPTED,
						retired.scope(), retired.session(), retired.workspace(), retired.content(), retired.proof(),
						retired.creator(), retired.operator(), "accept", null, retired.generation(), retired.createdAt())));
			return retired;
		});
		f.service.propose("verified-goal", f.content("parser another"), f.actor);
		assertThatThrownBy(() -> f.service.lookup(lesson.id(), f.actor)).hasMessage("Lesson not found");
		var row = row("parser-offsets");
		assertThat(row.lessonState()).isEqualTo("UNKNOWN");
		assertThat(row.lessonVersion()).isNull();
		assertThat(row.reasons()).containsExactly(SkillUsageReport.Reason.SOURCE_RETIRED);
		assertThat(row.flags()).isEmpty();
		assertThat(row.candidate()).isTrue();
	}

	@Test void anEditedSkillIsModifiedAndNeverACandidate() throws Exception {
		daysAgo(31);
		var lesson = export(PROJECT, "parser-offsets");
		f.service.retire(lesson.id(), lesson.version(), f.actor);
		now.set(NOW);
		Files.writeString(projectFile("parser-offsets"), "\nAn operator note.\n", StandardOpenOption.APPEND);
		var row = row("parser-offsets");
		assertThat(row.reasons()).containsExactly(SkillUsageReport.Reason.SOURCE_RETIRED, SkillUsageReport.Reason.UNUSED);
		assertThat(row.flags()).containsExactly(SkillUsageReport.Flag.MODIFIED, SkillUsageReport.Flag.LESSON_CHANGED);
		assertThat(row.candidate()).isFalse();
		assertThat(row.expectedDigest()).isEqualTo(sha256(projectFile("parser-offsets")))
				.isNotEqualTo(store.read().entries().getFirst().exportDigest());
	}

	@Test void aDeletedSkillIsMissingAndNeverACandidate() throws Exception {
		daysAgo(31);
		export(PROJECT, "parser-offsets");
		now.set(NOW);
		Files.delete(projectFile("parser-offsets"));
		Files.delete(projectFile("parser-offsets").getParent());
		var row = row("parser-offsets");
		assertThat(row.reasons()).containsExactly(SkillUsageReport.Reason.UNUSED);
		assertThat(row.flags()).containsExactly(SkillUsageReport.Flag.MISSING);
		assertThat(row.candidate()).isFalse();
		assertThat(row.expectedDigest()).isNull();
	}

	@Test void aSkillFileReplacedByALinkIsModifiedNotMissingAndHasNoDigest() throws Exception {
		daysAgo(31);
		export(PROJECT, "parser-offsets");
		now.set(NOW);
		var outside = Files.writeString(root.resolve("outside.md"), "elsewhere");
		Files.delete(projectFile("parser-offsets"));
		Files.createSymbolicLink(projectFile("parser-offsets"), outside);
		var row = row("parser-offsets");
		assertThat(row.flags()).containsExactly(SkillUsageReport.Flag.MODIFIED);
		assertThat(row.candidate()).isFalse();
		assertThat(row.expectedDigest()).isNull();
	}

	@Test void aRetiredSkillIsListedWithItsRetirementAndNoReasonsOrCandidacy() throws Exception {
		daysAgo(31);
		var lesson = export(PROJECT, "parser-offsets");
		f.service.retire(lesson.id(), lesson.version(), f.actor);
		now.set(NOW);
		var retiredAt = NOW.minus(DAY);
		change("parser-offsets", e -> retired(e, retiredAt, "SOURCE_RETIRED,UNUSED"));
		// A retired skill's folder has moved to the retired root, so the live file is gone.
		Files.delete(projectFile("parser-offsets"));
		Files.delete(projectFile("parser-offsets").getParent());
		var row = row("parser-offsets");
		assertThat(row.retiredAt()).isEqualTo(retiredAt);
		assertThat(row.retiredReason()).isEqualTo("SOURCE_RETIRED,UNUSED");
		assertThat(row.reasons()).isEmpty();
		assertThat(row.candidate()).isFalse();
	}

	@Test void aHealthyRetiredSkillHasNoFlagsAndNoDigestToRepeat() throws Exception {
		export(PROJECT, "parser-offsets");
		unexport("parser-offsets", PROJECT);
		assertThat(projectFile("parser-offsets")).doesNotExist();
		var row = row("parser-offsets");
		assertThat(row.retiredAt()).isEqualTo(NOW);
		assertThat(row.flags()).isEmpty();
		assertThat(row.expectedDigest()).isNull();
	}

	@Test void aRetiredSkillWhoseRetiredCopyWasDeletedIsMissing() throws Exception {
		export(PROJECT, "parser-offsets");
		unexport("parser-offsets", PROJECT);
		Files.delete(projectRetiredFile("parser-offsets"));
		Files.delete(projectRetiredFile("parser-offsets").getParent());
		var row = row("parser-offsets");
		assertThat(row.flags()).containsExactly(SkillUsageReport.Flag.MISSING);
		assertThat(row.expectedDigest()).isNull();
	}

	@Test void aRetiredSkillWhoseRetiredCopyWasEditedIsModified() throws Exception {
		export(PROJECT, "parser-offsets");
		unexport("parser-offsets", PROJECT);
		Files.writeString(projectRetiredFile("parser-offsets"), "\nAn operator note.\n", StandardOpenOption.APPEND);
		var row = row("parser-offsets");
		assertThat(row.flags()).containsExactly(SkillUsageReport.Flag.MODIFIED);
		assertThat(row.expectedDigest()).isNull();
	}

	@Test void aGlobalRetiredSkillIsJudgedAgainstTheHomesRetiredRoot() throws Exception {
		export(GLOBAL, "shared-tips");
		unexport("shared-tips", GLOBAL);
		assertThat(globalFile("shared-tips")).doesNotExist();
		assertThat(row("shared-tips").flags()).isEmpty();
		Files.writeString(globalRetiredFile("shared-tips"), "\nAn operator note.\n", StandardOpenOption.APPEND);
		assertThat(row("shared-tips").flags()).containsExactly(SkillUsageReport.Flag.MODIFIED);
	}

	@Test void skillsPrintsOneJsonObjectWithTheTrackingDateTheRecordFailuresAndEveryRow() throws Exception {
		daysAgo(31);
		var lesson = export(PROJECT, "parser-offsets");
		now.set(NOW);
		var out = new ByteArrayOutputStream();
		assertThat(f.command(clock, () -> 3).executeWithStatus("skills", f.console(out))).isTrue();
		assertThat(out.toString().strip()).isEqualTo("{\"trackingSince\":\"2026-09-19T09:00:00Z\",\"unusedDays\":30,"
				+ "\"recordFailures\":3,\"skills\":[{\"name\":\"parser-offsets\",\"scope\":\"PROJECT\","
				+ "\"workspace\":\"" + f.actor.workspace() + "\",\"lessonId\":\"" + lesson.id() + "\",\"linkedVersion\":3,"
				+ "\"lessonState\":\"ACCEPTED\",\"lessonVersion\":3,\"exportedAt\":\"2026-09-19T09:00:00Z\","
				+ "\"lastLoadedAt\":null,\"restoredAt\":null,\"retiredAt\":null,\"retiredReason\":null,"
				+ "\"chosenLoads\":0,\"forcedLoads\":0,\"reasons\":[\"UNUSED\"],\"flags\":[],\"candidate\":true,"
				+ "\"expectedLoads\":0,\"expectedDigest\":\"" + sha256(projectFile("parser-offsets")) + "\"}]}");
	}

	@Test void withoutAUsageStoreSkillsIsAnEmptyListAndCreatesNothing() throws Exception {
		var out = new ByteArrayOutputStream();
		assertThat(f.command().executeWithStatus("skills", f.console(out))).isTrue();
		assertThat(out.toString().strip()).isEqualTo(
				"{\"trackingSince\":null,\"unusedDays\":30,\"recordFailures\":0,\"skills\":[]}");
		assertThat(f.home).doesNotExist();
	}

	@Test void aCorruptUsageStoreFailsTheCommandInsteadOfPrintingAGuess() throws Exception {
		export(PROJECT, "parser-offsets");
		var file = f.home.resolve("skill-usage").resolve("usage.json");
		Files.writeString(file, Files.readString(file).replace("\"chosenLoads\":0", "\"chosenLoads\":9"));
		var out = new ByteArrayOutputStream();
		assertThat(f.command().executeWithStatus("skills", f.console(out))).isFalse();
		assertThat(out.toString()).contains("Lesson IOException: Corrupt skill usage checksum").doesNotContain("\"skills\"");
	}

	@Test void anUnsafeUsageStoreFailsTheCommandToo() throws Exception {
		export(PROJECT, "parser-offsets");
		Files.writeString(f.home.resolve("skill-usage").resolve("junk.txt"), "not a usage store file");
		var out = new ByteArrayOutputStream();
		assertThat(f.command().executeWithStatus("skills", f.console(out))).isFalse();
		assertThat(out.toString()).contains("Skill usage storage recovery required").doesNotContain("\"skills\"");
	}

	@Test void skillsTakesNoArgument() throws Exception {
		var out = new ByteArrayOutputStream();
		assertThat(f.command().executeWithStatus("skills {}", f.console(out))).isFalse();
		assertThat(out.toString()).contains("Unknown lesson operation");
	}

	@Test void theCommandDescriptionListsSkills() throws Exception {
		assertThat(f.command().description()).contains("list | skills |");
	}

	@Test void theConfigurationReportsTheFailuresOfTheRecorderItWires() throws Exception {
		var properties = new AgentProperties();
		properties.setCwd(Path.of(f.actor.workspace()));
		properties.setHome(f.home);
		var configuration = new LessonAutoConfiguration();
		var wiredStore = configuration.skillUsageStore(properties);
		var recorder = (SkillUsageRecorder) configuration.skillUseListener(wiredStore, properties);
		recorder.loaded(null, SkillUseListener.Kind.CHOSEN);
		var wired = new StaticListableBeanFactory(Map.of("skillUseListener", recorder)).getBeanProvider(SkillUseListener.class);
		assertThat(configuration.skillUsageReport(f.service, wiredStore, wired, properties).report(f.actor).recordFailures())
				.isEqualTo(1);
		var foreign = new StaticListableBeanFactory(Map.of("other", (SkillUseListener) (directory, kind) -> {}))
				.getBeanProvider(SkillUseListener.class);
		assertThat(configuration.skillUsageReport(f.service, wiredStore, foreign, properties).report(f.actor).recordFailures())
				.isZero();
		var none = new StaticListableBeanFactory().getBeanProvider(SkillUseListener.class);
		assertThat(configuration.skillUsageReport(f.service, wiredStore, none, properties).report(f.actor).recordFailures())
				.isZero();
	}
}
