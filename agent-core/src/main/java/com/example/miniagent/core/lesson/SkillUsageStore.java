package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.example.miniagent.core.change.AtomicFiles;
import com.example.miniagent.core.change.Fingerprinter;
import com.example.miniagent.core.run.OsMetadata;

/**
 * Export links and load counts of lesson-promoted skills, in {@code <home>/skill-usage}: one bounded, checksummed
 * atomic snapshot guarded by a stable never-renamed lock inode, the same shape as LessonStore.
 */
public final class SkillUsageStore {
	public static final int MAX_ENTRIES = 256;
	private static final int MAX_BYTES = 4 * 1024 * 1024;
	private static final Set<Path> OWNED = ConcurrentHashMap.newKeySet();
	private static final Object INITIALIZE = new Object();
	private final Path home;
	private final Path directory;
	private final Clock clock;
	/** trackingSince is when the store was created; usage older than that was never observed. */
	public record Snapshot(Instant trackingSince, List<SkillUsage> entries) {}
	private record Envelope(int schema, String sha256, Instant trackingSince, List<SkillUsage> entries) {}
	@FunctionalInterface interface Mutation<T> { T apply(List<SkillUsage> entries) throws IOException; }

	public SkillUsageStore(Path home, Clock clock) {
		this.home = home.toAbsolutePath().normalize();
		this.directory = this.home.resolve("skill-usage");
		this.clock = clock;
	}
	Clock clock() { return clock; }
	private static void ancestors(Path path) throws IOException {
		if (path.getParent() != null) ancestors(path.getParent());
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe skill usage directory");
	}
	private static void createDirectory(Path path) throws IOException {
		ancestors(path.getParent());
		Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		AtomicFiles.forceDirectory(path.getParent());
	}
	private void clean() throws IOException {
		ancestors(directory);
		if (!Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)
				.equals(PosixFilePermissions.fromString("rwx------"))) throw new IOException("Nonprivate skill usage");
		for (Path path : OsMetadata.entries(directory, 4))
			if (!Set.of("usage.json", "store.lock").contains(path.getFileName().toString()))
				throw new IOException("Skill usage storage recovery required");
		Path lock = directory.resolve("store.lock");
		if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)
				&& (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS) || Files.size(lock) != 0
				|| !Files.getPosixFilePermissions(lock, LinkOption.NOFOLLOW_LINKS)
						.equals(PosixFilePermissions.fromString("rw-------"))))
			throw new IOException("Unsafe skill usage lock");
	}
	/** A missing store reads as empty with no trackingSince, and nothing is created. */
	public Snapshot read() throws IOException {
		ancestors(home.getParent());
		if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) return new Snapshot(null, List.of());
		ancestors(home);
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return new Snapshot(null, List.of());
		clean();
		if (!Files.isRegularFile(directory.resolve("store.lock"), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Missing stable skill usage lock");
		try {
			var envelope = LessonStore.JSON.readValue(AtomicFiles.read(directory.resolve("usage.json"), MAX_BYTES),
					Envelope.class);
			if (envelope.schema() != 1 || !Fingerprinter.sha256(LessonStore.JSON.writeValueAsBytes(
					new Snapshot(envelope.trackingSince(), envelope.entries()))).equals(envelope.sha256()))
				throw new IOException("Corrupt skill usage checksum");
			validate(envelope.trackingSince(), envelope.entries());
			return new Snapshot(envelope.trackingSince(), List.copyOf(envelope.entries()));
		}
		catch (RuntimeException ex) { throw new IOException("Corrupt skill usage", ex); }
	}
	private static void validate(Instant trackingSince, List<SkillUsage> entries) throws IOException {
		if (trackingSince == null) throw new IOException("Corrupt skill usage");
		if (entries.size() > MAX_ENTRIES) throw new IOException("Skill usage entry limit");
		var keys = new HashSet<SkillUsage.Key>();
		for (var entry : entries) {
			boolean fits = switch (entry.scope()) {
				case PROJECT -> entry.workspace() != null && canonical(entry.workspace());
				case GLOBAL -> entry.workspace() == null;
				case SESSION -> false;
			};
			if (!fits) throw new IOException("Invalid skill usage scope");
			if (!keys.add(entry.key())) throw new IOException("Duplicate skill usage identity");
		}
	}
	/** Same rule as a lesson's workspace: absolute and already normalized, so identities compare as strings. */
	private static boolean canonical(String workspace) {
		var path = Path.of(workspace);
		return path.isAbsolute() && path.normalize().toString().equals(workspace);
	}
	private void write(Instant trackingSince, List<SkillUsage> entries) throws IOException {
		validate(trackingSince, entries);
		byte[] payload = LessonStore.JSON.writeValueAsBytes(new Snapshot(trackingSince, entries));
		AtomicFiles.write(directory.resolve("usage.json"), LessonStore.JSON.writeValueAsBytes(
				new Envelope(1, Fingerprinter.sha256(payload), trackingSince, entries)), MAX_BYTES);
	}
	<T> T mutate(Mutation<T> mutation) throws IOException {
		Path lockPath = directory.resolve("store.lock");
		boolean fresh = false;
		synchronized (INITIALIZE) {
			if (!OWNED.add(lockPath)) throw new IOException("Skill usage busy");
			try {
				if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) createDirectory(home);
				ancestors(home);
				if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
					createDirectory(directory);
					fresh = true;
				}
				clean();
				if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
					if (!fresh) throw new IOException("Missing stable skill usage lock");
					Files.createFile(lockPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
				}
			}
			catch (IOException | RuntimeException ex) { OWNED.remove(lockPath); throw ex; }
		}
		try {
			// Ownership is registered before opening even a read descriptor for the lock inode.
			AtomicFiles.read(lockPath, 0);
			try (var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				try (var lock = channel.tryLock()) {
					if (lock == null) throw new IOException("Skill usage busy");
					channel.force(true); AtomicFiles.forceDirectory(directory);
					if (fresh) write(clock.instant(), List.of());
					var snapshot = read();
					var entries = new ArrayList<>(snapshot.entries());
					var result = mutation.apply(entries);
					write(snapshot.trackingSince(), entries);
					return result;
				}
			}
		}
		finally { OWNED.remove(lockPath); }
	}
	/** Records a new export link. An identity already linked, live or retired, is refused. */
	public void link(SkillUsage entry) throws IOException {
		mutate(entries -> {
			if (entries.stream().anyMatch(existing -> existing.key().equals(entry.key())))
				throw new IOException("Skill usage link exists");
			return entries.add(entry);
		});
	}
}
