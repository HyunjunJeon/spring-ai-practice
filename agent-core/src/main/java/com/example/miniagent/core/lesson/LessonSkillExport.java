package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import com.example.miniagent.core.StatePaths;
import com.example.miniagent.core.change.AtomicFiles;
import com.example.miniagent.core.change.Fingerprinter;
import static com.example.miniagent.core.lesson.LessonRecord.*;

/**
 * Operator-only promotion of an accepted lesson to a SKILL.md in the roots the skills plugin reads.
 * A skill is instructions, not untrusted advisory data, so only lessons already widened past SESSION qualify.
 */
public final class LessonSkillExport {
	private static final int MAX_BYTES = 64 * 1024;
	private final LessonService lessons;
	private final Path home;
	private final SkillUsageStore usage;
	private final Clock clock;
	private final SkillWriter writer;
	/** Writes the rendered SKILL.md; a test hands in one that fails, because no file system state fails that write. */
	@FunctionalInterface interface SkillWriter { void write(Path file, byte[] bytes) throws IOException; }
	/** Links exports in the usage store of {@code home}, stamped with the system clock. */
	public LessonSkillExport(LessonService lessons, Path home) {
		this(lessons, home, new SkillUsageStore(home, Clock.systemUTC()), Clock.systemUTC());
	}
	public LessonSkillExport(LessonService lessons, Path home, SkillUsageStore usage, Clock clock) {
		this(lessons, home, usage, clock, (file, bytes) -> AtomicFiles.write(file, bytes, MAX_BYTES));
	}
	LessonSkillExport(LessonService lessons, Path home, SkillUsageStore usage, Clock clock, SkillWriter writer) {
		this.lessons = lessons; this.home = home; this.usage = usage; this.clock = clock; this.writer = writer;
	}

	/** Writes {@code <root>/<name>/SKILL.md}. The skills plugin scans its roots at startup, so it loads on the next start. */
	public Path export(String id, int expectedVersion, String name, Actor actor) throws IOException {
		checkName(name);
		var lesson = lessons.lookup(id, actor);
		if (lesson.version() != expectedVersion) throw new IOException("Stale lesson expectedVersion");
		if (lesson.state() != State.ACCEPTED) throw new IOException("Accepted lesson required");
		var project = StatePaths.projectSkills(Path.of(actor.workspace()));
		var user = StatePaths.userSkills(home);
		// Every session that reads a root sees its skills, so the root follows the lesson's scope.
		Path root = switch (lesson.scope()) {
			case PROJECT -> project;
			case GLOBAL -> user;
			case SESSION -> throw new IOException("Promote the lesson to PROJECT or GLOBAL before exporting it");
		};
		// Project skills shadow user skills of the same name, so either root blocks the name.
		for (Path existing : List.of(project, user))
			if (Files.exists(existing.resolve(name), LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Skill already exists: " + name);
		// A retired name comes back only through reinstate, which keeps its link and its counts.
		for (Path retired : List.of(StatePaths.projectRetiredSkills(Path.of(actor.workspace())),
				StatePaths.userRetiredSkills(home)))
			if (Files.exists(retired.resolve(name), LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Skill is retired, use /lesson reinstate: " + retired.resolve(name));
		directories(root);
		Path directory = Files.createDirectory(root.resolve(name));
		Path file = directory.resolve("SKILL.md");
		byte[] bytes = render(lesson, name).getBytes(StandardCharsets.UTF_8);
		try {
			writer.write(file, bytes);
			// Usage identity matches the root: a PROJECT skill belongs to this workspace, a GLOBAL one to the home.
			String workspace = lesson.scope() == Scope.PROJECT ? actor.workspace() : null;
			usage.link(new SkillUsage(lesson.scope(), workspace, name, lesson.id(), lesson.version(), clock.instant(),
					Fingerprinter.sha256(bytes), 0, 0, null, null, null, null));
		}
		catch (IOException | RuntimeException ex) {
			// A failed write must not leave the name taken, and a skill without a link could never be retired.
			try {
				Files.deleteIfExists(file);
				Files.deleteIfExists(directory);
			}
			catch (IOException cleanup) { ex.addSuppressed(cleanup); }
			throw ex;
		}
		return file;
	}
	/** Where a moved skill folder was and where it is now. */
	public record Move(Path from, Path to) {}

	/**
	 * Moves an export-linked skill folder to the sibling retired root, which the skills plugin never reads, so the skill
	 * is gone from the next start. expectedLoads and expectedDigest repeat the {@code /lesson skills} row: a load or an
	 * edit since then refuses the move. {@code reason} becomes retiredReason; the source lesson is left as it is.
	 */
	public Move unexport(String name, Scope scope, long expectedLoads, String expectedDigest, String reason, Actor actor)
			throws IOException {
		checkName(name);
		var roots = roots(scope, actor);
		return move(linked(name, scope, actor), (entries, index) -> {
			var entry = entries.get(index);
			if (entry.retiredAt() != null) throw new IOException("Skill already retired: " + name);
			if (entry.chosenLoads() + entry.forcedLoads() != expectedLoads) throw new IOException("Stale expectedLoads");
			Path from = roots.live().resolve(name);
			Path to = roots.retired().resolve(name);
			String digest = digest(from);
			if (digest == null || !digest.equals(expectedDigest)) throw new IOException("Stale expectedDigest");
			// rename(2) replaces an empty directory, so a copy already there is refused before the move.
			if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Retired copy exists: " + to);
			entries.set(index, entry.retired(clock.instant(), reason));
			return new Move(from, to);
		});
	}
	/**
	 * Moves a retired skill folder back to its live root, where the next start loads it again. restoredAt starts the
	 * unused period again, so the skill is not reported unused right after it returns.
	 */
	public Move reinstate(String name, Scope scope, Actor actor) throws IOException {
		checkName(name);
		var roots = roots(scope, actor);
		return move(linked(name, scope, actor), (entries, index) -> {
			var entry = entries.get(index);
			if (entry.retiredAt() == null) throw new IOException("Skill is not retired: " + name);
			// Project skills shadow user skills of the same name, so either live root blocks the name.
			for (Path live : List.of(StatePaths.projectSkills(Path.of(actor.workspace())), StatePaths.userSkills(home)))
				if (Files.exists(live.resolve(name), LinkOption.NOFOLLOW_LINKS))
					throw new IOException("Skill already exists: " + name);
			Path from = roots.retired().resolve(name);
			if (!real(from)) throw new IOException("No retired copy: " + from);
			entries.set(index, entry.reinstated(clock.instant()));
			return new Move(from, roots.live().resolve(name));
		});
	}
	/** Checks and replaces the linked entry at {@code index}, and names the folder move that goes with it. */
	@FunctionalInterface private interface Plan { Move apply(List<SkillUsage> entries, int index) throws IOException; }
	/**
	 * Plans and moves under the store lock. The roots are siblings, so the move is one rename on one filesystem. The
	 * record and the folder change together: if the store cannot persist the entry after the move, the folder moves back.
	 */
	private Move move(SkillUsage.Key key, Plan plan) throws IOException {
		var moved = new AtomicReference<Move>();
		try {
			return usage.mutate(entries -> {
				var move = plan.apply(entries, index(entries, key));
				directories(move.to().getParent());
				Files.move(move.from(), move.to(), StandardCopyOption.ATOMIC_MOVE);
				moved.set(move);
				return move;
			});
		}
		catch (IOException | RuntimeException ex) {
			var move = moved.get();
			if (move != null) {
				try { Files.move(move.to(), move.from(), StandardCopyOption.ATOMIC_MOVE); }
				catch (IOException back) { ex.addSuppressed(back); }
			}
			throw ex;
		}
	}
	/** A name is one path segment, so it never reaches outside its root. */
	private static void checkName(String name) throws IOException {
		if (name == null || name.length() > 64 || !name.matches("[a-z0-9]+(-[a-z0-9]+)*"))
			throw new IOException("Invalid skill name: lowercase letters, digits and single hyphens, at most 64");
	}
	/** The live root the skills plugin reads for this scope, and its sibling retired root, which it never reads. */
	private record Roots(Path live, Path retired) {}
	private Roots roots(Scope scope, Actor actor) throws IOException {
		Path workspace = Path.of(actor.workspace());
		return switch (scope) {
			case PROJECT -> new Roots(StatePaths.projectSkills(workspace), StatePaths.projectRetiredSkills(workspace));
			case GLOBAL -> new Roots(StatePaths.userSkills(home), StatePaths.userRetiredSkills(home));
			case null, default -> throw new IOException("Skill scope must be PROJECT or GLOBAL");
		};
	}
	/**
	 * The usage identity of a linked skill; its workspace follows the same rule as the link the export wrote. Reading
	 * first keeps a hand-written skill, which never has a link, from creating the store.
	 */
	private SkillUsage.Key linked(String name, Scope scope, Actor actor) throws IOException {
		var key = new SkillUsage.Key(scope, scope == Scope.PROJECT ? actor.workspace() : null, name);
		index(usage.read().entries(), key);
		return key;
	}
	private static int index(List<SkillUsage> entries, SkillUsage.Key key) throws IOException {
		for (int i = 0; i < entries.size(); i++) if (entries.get(i).key().equals(key)) return i;
		throw new IOException("No export link for skill: " + key.name());
	}
	/** SHA-256 of the folder's SKILL.md, or null unless the folder is real and the file is a regular file. */
	private static String digest(Path folder) throws IOException {
		Path file = folder.resolve("SKILL.md");
		return real(folder) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
				? Fingerprinter.sha256(Files.readAllBytes(file)) : null;
	}
	/** The folder, its root and the root's parent are directories, and none of them is a link. */
	private static boolean real(Path folder) {
		return Stream.of(folder.getParent().getParent(), folder.getParent(), folder)
				.allMatch(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
	}
	/** Creates the root and its parent when missing, and never follows a link out of the workspace or home. */
	private static void directories(Path root) throws IOException {
		for (Path path : List.of(root.getParent(), root)) {
			if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path);
			else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Unsafe skill directory: " + path);
		}
	}
	private static String render(LessonRecord lesson, String name) {
		// SkillRegistry reads one "key: value" pair per frontmatter line.
		String description = lesson.content().title().replaceAll("[\\r\\n]+", " ").strip();
		return "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + lesson.content().content()
				+ "\n\nSource: accepted lesson " + lesson.id() + " v" + lesson.version() + " (" + lesson.scope()
				+ "), grounded in goal " + lesson.proof().goalId() + ".\n";
	}
}
