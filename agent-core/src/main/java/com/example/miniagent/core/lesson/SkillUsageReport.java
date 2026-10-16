package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import com.example.miniagent.core.StatePaths;
import com.example.miniagent.core.change.Fingerprinter;
import static com.example.miniagent.core.lesson.LessonRecord.*;

/**
 * Read-only retirement report over the skills {@code /lesson export} linked. It only marks candidates; the operator
 * decides skill by skill, and each row carries the values an unexport must repeat as its compare-and-set.
 */
public final class SkillUsageReport {
	/** Days without an export, restore or load after which a skill is reported unused. */
	public static final int UNUSED_DAYS = 30;
	private static final Duration UNUSED_AFTER = Duration.ofDays(UNUSED_DAYS);
	/** What {@link Row#lessonState()} says when the linked lesson is not visible to the actor. */
	private static final String UNKNOWN_LESSON = "UNKNOWN";
	public enum Reason { SOURCE_RETIRED, UNUSED }
	public enum Flag { MODIFIED, LESSON_CHANGED, MISSING }
	/** One export-linked skill. expectedLoads and expectedDigest are the compare-and-set values for the next command. */
	public record Row(String name, Scope scope, String workspace, String lessonId, int linkedVersion,
			String lessonState, Integer lessonVersion, Instant exportedAt, Instant lastLoadedAt, Instant restoredAt,
			Instant retiredAt, String retiredReason, long chosenLoads, long forcedLoads, List<Reason> reasons,
			List<Flag> flags, boolean candidate, long expectedLoads, String expectedDigest) {}
	public record Report(Instant trackingSince, int unusedDays, long recordFailures, List<Row> skills) {}
	private final LessonService lessons;
	private final SkillUsageStore usage;
	private final Path home;
	private final Clock clock;
	private final LongSupplier recordFailures;

	/** {@code recordFailures} says how many loads this process could not count; 0 when no counter is wired. */
	public SkillUsageReport(LessonService lessons, SkillUsageStore usage, Path home, Clock clock,
			LongSupplier recordFailures) {
		this.lessons = lessons; this.usage = usage; this.home = home; this.clock = clock;
		this.recordFailures = recordFailures;
	}
	/** Rows of every GLOBAL link and of the PROJECT links of the actor's workspace, in link order. */
	public Report report(Actor actor) throws IOException {
		var snapshot = usage.read();
		var now = clock.instant();
		var latest = new HashMap<String, LessonRecord>();
		for (var lesson : lessons.list(actor)) latest.put(lesson.id(), lesson);
		var rows = new ArrayList<Row>();
		for (var entry : snapshot.entries())
			if (entry.scope() == Scope.GLOBAL || entry.workspace().equals(actor.workspace()))
				rows.add(row(entry, latest.get(entry.lessonId()), snapshot.trackingSince(), now));
		return new Report(snapshot.trackingSince(), UNUSED_DAYS, recordFailures.getAsLong(), List.copyOf(rows));
	}
	/**
	 * {@code lesson} is the latest revision the actor can see, or null. MISSING and MODIFIED are judged against
	 * {@link #skillFile}: the live file, or for a retired skill its retired copy. A retired row repeats no digest,
	 * because an unexport refuses an entry that is already retired.
	 */
	private Row row(SkillUsage entry, LessonRecord lesson, Instant trackingSince, Instant now) throws IOException {
		boolean retired = entry.retiredAt() != null;
		Path file = skillFile(entry);
		String digest = digest(file);
		var reasons = EnumSet.noneOf(Reason.class);
		var flags = EnumSet.noneOf(Flag.class);
		// A skill that is already retired has nothing left to retire, so it gets no reasons.
		if (!retired) {
			if (lesson != null && lesson.state() == State.RETIRED) reasons.add(Reason.SOURCE_RETIRED);
			if (unused(entry, trackingSince, now)) reasons.add(Reason.UNUSED);
		}
		if (lesson != null && lesson.version() > entry.lessonVersion()) flags.add(Flag.LESSON_CHANGED);
		// A link or directory in place of the file is something else than the exported bytes, not an absence.
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) flags.add(Flag.MISSING);
		else if (!entry.exportDigest().equals(digest)) flags.add(Flag.MODIFIED);
		boolean candidate = !reasons.isEmpty() && !flags.contains(Flag.MODIFIED) && !flags.contains(Flag.MISSING);
		return new Row(entry.name(), entry.scope(), entry.workspace(), entry.lessonId(), entry.lessonVersion(),
				lesson == null ? UNKNOWN_LESSON : lesson.state().name(), lesson == null ? null : lesson.version(),
				entry.exportedAt(), entry.lastLoadedAt(), entry.restoredAt(), entry.retiredAt(), entry.retiredReason(),
				entry.chosenLoads(), entry.forcedLoads(), List.copyOf(reasons), List.copyOf(flags), candidate,
				entry.chosenLoads() + entry.forcedLoads(), retired ? null : digest);
	}
	/**
	 * Nothing is unused before the usage has been tracked for the whole period: loads earlier than trackingSince were
	 * never counted. After that, the latest of export, restore and load must be a whole period old.
	 */
	private static boolean unused(SkillUsage entry, Instant trackingSince, Instant now) {
		Instant lastSign = Stream.of(entry.exportedAt(), entry.restoredAt(), entry.lastLoadedAt())
				.filter(Objects::nonNull).max(Comparator.naturalOrder()).orElseThrow();
		return !trackingSince.plus(UNUSED_AFTER).isAfter(now) && !lastSign.plus(UNUSED_AFTER).isAfter(now);
	}
	/**
	 * The SKILL.md a row is judged against. A live skill is the file the skills plugin reads: in the workspace for
	 * PROJECT, in the home for GLOBAL. A retired skill has no live file by design, so it is its copy under the retired
	 * root next to that one.
	 */
	private Path skillFile(SkillUsage entry) {
		boolean retired = entry.retiredAt() != null;
		Path root;
		if (entry.scope() == Scope.PROJECT) {
			Path workspace = Path.of(entry.workspace());
			root = retired ? StatePaths.projectRetiredSkills(workspace) : StatePaths.projectSkills(workspace);
		}
		else root = retired ? StatePaths.userRetiredSkills(home) : StatePaths.userSkills(home);
		return root.resolve(entry.name()).resolve("SKILL.md");
	}
	/** SHA-256 of a regular file, or null when there is none. A link is not followed. */
	private static String digest(Path file) throws IOException {
		return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? Fingerprinter.sha256(Files.readAllBytes(file)) : null;
	}
}
