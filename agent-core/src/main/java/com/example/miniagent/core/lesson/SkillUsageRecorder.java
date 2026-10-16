package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import com.example.miniagent.core.SkillUseListener;
import com.example.miniagent.core.StatePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts loads of skills that {@code /lesson export} linked. Hand-written skills have no link and are never
 * recorded. Counting is best-effort: a worker JVM on the same home may hold the store, and a load must not fail
 * because of that, so a few short retries end in a WARN and an in-process failure count.
 */
public final class SkillUsageRecorder implements SkillUseListener {
	private static final Logger LOG = LoggerFactory.getLogger(SkillUsageRecorder.class);
	// Three attempts with two 25 ms pauses: about 50 ms of waiting at most on the load's own thread.
	private static final int ATTEMPTS = 3;
	private static final long PAUSE_MILLIS = 25;
	private final SkillUsageStore store;
	private final Path workspace;
	private final Path home;
	private final AtomicLong failures = new AtomicLong();

	/** Touches nothing on disk; paths are resolved when a load is reported. */
	public SkillUsageRecorder(SkillUsageStore store, Path workspace, Path home) {
		this.store = store;
		this.workspace = workspace;
		this.home = home;
	}
	/** Loads this process could not record. */
	public long failures() { return failures.get(); }

	@Override public void loaded(Path skillDirectory, Kind kind) {
		try {
			var keys = keys(skillDirectory);
			if (keys.isEmpty()) return;
			for (int attempt = 1; ; attempt++) {
				try {
					record(keys, kind);
					return;
				}
				catch (IOException ex) {
					if (attempt == ATTEMPTS) throw ex;
					Thread.sleep(PAUSE_MILLIS);
				}
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			failed(skillDirectory, ex);
		}
		catch (IOException | RuntimeException ex) { failed(skillDirectory, ex); }
	}
	private void failed(Path skillDirectory, Exception ex) {
		long count = failures.incrementAndGet();
		LOG.warn("Skill usage not recorded for {} ({} failures in this process): {}", skillDirectory, count, ex.toString());
	}
	/**
	 * Identities the directory can have, by the live root that holds it. Roots compare as real paths, so a
	 * discovered /var path matches a /private/var root; the PROJECT workspace is the real path a lesson records.
	 */
	private List<SkillUsage.Key> keys(Path skillDirectory) throws IOException {
		Path parent = skillDirectory.toAbsolutePath().getParent();
		if (!Files.isDirectory(parent)) return List.of();
		Path real = parent.toRealPath();
		String name = skillDirectory.getFileName().toString();
		var keys = new ArrayList<SkillUsage.Key>();
		// Started from the home's parent, both roots are one folder, so a skill there may be linked either way.
		if (holds(real, StatePaths.projectSkills(workspace)))
			keys.add(new SkillUsage.Key(LessonRecord.Scope.PROJECT, workspace.toRealPath().toString(), name));
		if (holds(real, StatePaths.userSkills(home))) keys.add(new SkillUsage.Key(LessonRecord.Scope.GLOBAL, null, name));
		return keys;
	}
	private static boolean holds(Path realParent, Path root) throws IOException {
		return Files.isDirectory(root) && realParent.equals(root.toRealPath());
	}
	private void record(List<SkillUsage.Key> keys, Kind kind) throws IOException {
		// Reading first keeps unlinked and retired skills from taking the lock or creating the store.
		if (store.read().entries().stream().noneMatch(entry -> live(entry, keys))) return;
		var at = store.clock().instant();
		store.mutate(entries -> {
			entries.replaceAll(entry -> live(entry, keys) ? entry.loaded(kind, at) : entry);
			return null;
		});
	}
	private static boolean live(SkillUsage entry, List<SkillUsage.Key> keys) {
		return entry.retiredAt() == null && keys.contains(entry.key());
	}
}
