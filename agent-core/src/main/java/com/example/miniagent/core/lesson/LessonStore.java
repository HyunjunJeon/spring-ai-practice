package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.example.miniagent.core.change.AtomicFiles;
import com.example.miniagent.core.change.Fingerprinter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** One bounded, checksummed atomic snapshot, guarded by a stable never-renamed lock inode. */
public final class LessonStore {
	public static final int MAX_ENTRIES = 128;
	public static final int MAX_VERSIONS = 32;
	private static final int MAX_BYTES = 32 * 1024 * 1024;
	static final JsonMapper JSON = JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
			.streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder()
					.maxNestingDepth(16).maxStringLength(8192).maxNumberLength(24).build()).build())
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
	private static final Set<Path> OWNED = ConcurrentHashMap.newKeySet();
	private static final Object INITIALIZE = new Object();
	private final Path home;
	private final Path directory;
	private record Envelope(int schema, String sha256, List<List<LessonRecord>> histories) {}
	@FunctionalInterface interface Mutation { LessonRecord apply(List<List<LessonRecord>> histories) throws IOException; }

	public LessonStore(Path home) {
		this.home = home.toAbsolutePath().normalize();
		this.directory = this.home.resolve("lessons");
	}
	private static void ancestors(Path path) throws IOException {
		if (path.getParent() != null) ancestors(path.getParent());
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe lesson directory");
	}
	private static void privateDirectory(Path path) throws IOException {
		ancestors(path);
		if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
				.equals(PosixFilePermissions.fromString("rwx------"))) throw new IOException("Nonprivate lessons");
	}
	private static void createDirectory(Path path) throws IOException {
		ancestors(path.getParent());
		Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		AtomicFiles.forceDirectory(path.getParent());
	}
	/** Finder and AppleDouble files the OS drops into any folder. The store never opens or deletes them. */
	private static boolean osMetadata(String name) {
		return name.equals(".DS_Store") || name.startsWith("._");
	}
	private void clean() throws IOException {
		privateDirectory(directory);
		try (var paths = Files.list(directory)) {
			// Skipped before the bound, so metadata files cannot crowd a real stray file out of the check.
			for (String name : paths.map(path -> path.getFileName().toString()).filter(name -> !osMetadata(name))
					.limit(4).toList())
				if (!Set.of("lessons.json", "store.lock").contains(name))
					throw new IOException("Lesson storage recovery required");
		}
		Path lock = directory.resolve("store.lock");
		if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)
				&& (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS) || Files.size(lock) != 0
				|| !Files.getPosixFilePermissions(lock, LinkOption.NOFOLLOW_LINKS)
						.equals(PosixFilePermissions.fromString("rw-------"))))
			throw new IOException("Unsafe lesson lock");
	}
	/** Missing store is an empty read, not an invitation to create directories at startup. */
	public List<List<LessonRecord>> read() throws IOException {
		ancestors(home.getParent());
		if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) return List.of();
		ancestors(home);
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
		clean();
		if (!Files.isRegularFile(directory.resolve("store.lock"), LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Missing stable lesson lock");
		try {
			var envelope = JSON.readValue(AtomicFiles.read(directory.resolve("lessons.json"), MAX_BYTES), Envelope.class);
			if (envelope.schema() != 1 || !Fingerprinter.sha256(JSON.writeValueAsBytes(envelope.histories()))
					.equals(envelope.sha256())) throw new IOException("Corrupt lesson checksum");
			validate(envelope.histories());
			return envelope.histories().stream().map(List::copyOf).toList();
		}
		catch (RuntimeException ex) { throw new IOException("Corrupt lessons", ex); }
	}
	private static void validate(List<List<LessonRecord>> histories) throws IOException {
		if (histories.size() > MAX_ENTRIES) throw new IOException("Lesson entry limit");
		var ids = new HashSet<String>();
		for (var history : histories) {
			if (history.isEmpty() || history.size() > MAX_VERSIONS || !ids.add(history.getFirst().id()))
				throw new IOException("Invalid lesson history");
			var first = history.getFirst();
			for (int i = 0; i < history.size(); i++) {
				var r = history.get(i);
				if (r.version() != i + 1 || !r.id().equals(first.id()) || !r.creator().equals(first.creator())
						|| !r.workspace().equals(first.workspace()) || !r.session().equals(first.session()))
					throw new IOException("Invalid lesson revision chain");
			}
		}
	}
	private void write(List<List<LessonRecord>> histories) throws IOException {
		validate(histories);
		byte[] payload = JSON.writeValueAsBytes(histories);
		AtomicFiles.write(directory.resolve("lessons.json"),
				JSON.writeValueAsBytes(new Envelope(1, Fingerprinter.sha256(payload), histories)), MAX_BYTES);
	}
	LessonRecord mutate(Mutation mutation) throws IOException {
		Path lockPath = directory.resolve("store.lock");
		boolean fresh = false;
		synchronized (INITIALIZE) {
			if (!OWNED.add(lockPath)) throw new IOException("Lessons busy");
			try {
				if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) createDirectory(home);
				ancestors(home);
				if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
					createDirectory(directory);
					fresh = true;
				}
				clean();
				if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
					if (!fresh) throw new IOException("Missing stable lesson lock");
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
					if (lock == null) throw new IOException("Lessons busy");
					channel.force(true); AtomicFiles.forceDirectory(directory);
					if (fresh) write(List.of());
					var histories = new ArrayList<List<LessonRecord>>(read());
					var result = mutation.apply(histories);
					write(histories);
					return result;
				}
			}
		}
		finally { OWNED.remove(lockPath); }
	}
}
