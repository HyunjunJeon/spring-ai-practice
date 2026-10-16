package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import com.example.miniagent.core.StatePaths;
import com.example.miniagent.core.change.AtomicFiles;
import static com.example.miniagent.core.lesson.LessonRecord.*;

/**
 * Operator-only promotion of an accepted lesson to a SKILL.md in the roots the skills plugin reads.
 * A skill is instructions, not untrusted advisory data, so only lessons already widened past SESSION qualify.
 */
public final class LessonSkillExport {
	private static final int MAX_BYTES = 64 * 1024;
	private final LessonService lessons;
	private final Path home;
	public LessonSkillExport(LessonService lessons, Path home) { this.lessons = lessons; this.home = home; }

	/** Writes {@code <root>/<name>/SKILL.md}. The skills plugin scans its roots at startup, so it loads on the next start. */
	public Path export(String id, int expectedVersion, String name, Actor actor) throws IOException {
		if (name == null || name.length() > 64 || !name.matches("[a-z0-9]+(-[a-z0-9]+)*"))
			throw new IOException("Invalid skill name: lowercase letters, digits and single hyphens, at most 64");
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
		directories(root);
		Path directory = Files.createDirectory(root.resolve(name));
		Path file = directory.resolve("SKILL.md");
		try { AtomicFiles.write(file, render(lesson, name).getBytes(StandardCharsets.UTF_8), MAX_BYTES); }
		catch (IOException | RuntimeException ex) {
			// A failed write must not leave the name taken.
			try { Files.deleteIfExists(directory); }
			catch (IOException cleanup) { ex.addSuppressed(cleanup); }
			throw ex;
		}
		return file;
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
