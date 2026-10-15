package com.example.miniagent.core.goal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.example.miniagent.core.change.AtomicFiles;
import com.example.miniagent.core.change.Fingerprinter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Atomic private checkpoints and stable, never-renamed exclusive claim inodes. */
public final class GoalStore {
	static final JsonMapper JSON = JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
			.streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder()
					.maxNestingDepth(24).maxStringLength(131072).maxNumberLength(24).build()).build())
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
	private static final Set<Path> OWNED = ConcurrentHashMap.newKeySet();
	private static final Object STORAGE = new Object();
	private static final int LIMIT = 24 * 1024 * 1024;
	private final Path home;
	private final Path directory;
	private record Envelope(int version, String sha256, GoalRecord record) {}
	public static final class Busy extends IOException {
		Busy() { super("Goal already claimed"); }
	}

	public GoalStore(Path home) {
		this.home = home.toAbsolutePath().normalize();
		this.directory = this.home.resolve("goals");
	}
	private static void ancestors(Path path) throws IOException {
		if (path.getParent() != null) ancestors(path.getParent());
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe goal ancestor");
	}
	private static void directory(Path path) throws IOException {
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) ancestors(path);
		else {
			ancestors(path.getParent());
			Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			AtomicFiles.forceDirectory(path.getParent());
		}
		if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
				.equals(PosixFilePermissions.fromString("rwx------"))) throw new IOException("Goal directory is not private");
	}
	private void initialize() throws IOException {
		// Application home can preexist with application-owned permissions; never chmod a shared directory.
		if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) directory(home);
		ancestors(home);
		directory(directory);
	}
	private Path path(String id) { return directory.resolve(GoalRecord.safe(id)); }
	private void checked(String id) throws IOException {
		initialize();
		directory(path(id));
	}
	private void clean(String id) throws IOException {
		ancestors(directory); ancestors(path(id));
		try (var entries = Files.list(path(id))) {
			for (Path entry : entries.limit(5).toList())
				if (!Set.of("state.json", "claim.lock", "control").contains(entry.getFileName().toString()))
					throw new IOException("Goal storage recovery required");
		}
	}
	public List<String> list() throws IOException {
		synchronized (STORAGE) {
			initialize();
			var ids = new ArrayList<String>();
			try (var entries = Files.list(directory)) {
				for (Path entry : entries.limit(1025).toList()) {
					if (ids.size() >= 1024) throw new IOException("Goal quota exceeded");
					GoalRecord.safe(entry.getFileName().toString());
					directory(entry);
					ids.add(entry.getFileName().toString());
				}
			}
			return ids.stream().sorted().toList();
		}
	}
	public void create(GoalRecord record) throws IOException {
		synchronized (STORAGE) {
			initialize();
			if (list().size() >= 1024 || Files.exists(path(record.id()), LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Goal exists or quota exceeded");
			directory(path(record.id()));
			directory(path(record.id()).resolve("control"));
			write(record);
		}
	}
	public GoalRecord read(String id) throws IOException {
		ancestors(directory); ancestors(path(id));
		try {
			var envelope = JSON.readValue(AtomicFiles.read(path(id).resolve("state.json"), LIMIT), Envelope.class);
			if (envelope.version() != 1 || !envelope.record().id().equals(id)
					|| !Fingerprinter.sha256(JSON.writeValueAsBytes(envelope.record())).equals(envelope.sha256()))
				throw new IOException("Corrupt goal checkpoint");
			return envelope.record();
		}
		catch (IllegalArgumentException ex) { throw new IOException("Corrupt goal checkpoint", ex); }
	}
	/** Requires this goal's claim (or create's unpublished directory). */
	void write(GoalRecord record) throws IOException {
		clean(record.id());
		byte[] payload = JSON.writeValueAsBytes(record);
		AtomicFiles.write(path(record.id()).resolve("state.json"),
				JSON.writeValueAsBytes(new Envelope(1, Fingerprinter.sha256(payload), record)), LIMIT);
	}
	/** A separate immutable intent file allows cancellation and inspection while a worker owns the claim. */
	public void requestCancel(String id) throws IOException {
		read(id);
		synchronized (STORAGE) {
			Path file = path(id).resolve("control").resolve("cancel");
			if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
				if (AtomicFiles.read(file, 1).length != 1) throw new IOException("Corrupt cancellation");
			}
			else AtomicFiles.write(file, new byte[] { 1 }, 1);
		}
	}
	public boolean cancelled(String id) throws IOException {
		ancestors(path(id));
		Path control = path(id).resolve("control");
		directory(control);
		Path file = control.resolve("cancel");
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
		byte[] value = AtomicFiles.read(file, 1);
		if (value.length != 1 || value[0] != 1) throw new IOException("Corrupt cancellation");
		return true;
	}
	public Cancellation watchCancellation(String id) throws IOException {
		ancestors(path(id));
		var watch = path(id).getFileSystem().newWatchService();
		directory(path(id).resolve("control"));
		path(id).resolve("control").register(watch, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
				java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
		var signal = new java.util.concurrent.CompletableFuture<Void>();
		Thread thread = Thread.ofVirtual().name("goal-cancellation").start(() -> {
			try {
				if (cancelled(id)) { signal.complete(null); return; }
				for (;;) {
					var key = watch.take();
					key.pollEvents();
					if (cancelled(id)) { signal.complete(null); return; }
					if (!key.reset()) throw new IOException("Goal cancellation watch lost");
				}
			}
			catch (java.nio.file.ClosedWatchServiceException ex) { /* Owner finished. */ }
			catch (IOException | InterruptedException ex) { signal.completeExceptionally(ex); }
		});
		return new Cancellation(watch, thread, signal);
	}
	public record Cancellation(java.nio.file.WatchService watch, Thread thread,
			java.util.concurrent.CompletableFuture<Void> signal) implements AutoCloseable {
		@Override public void close() throws IOException { watch.close(); }
	}
	public Claim claim(String id) throws IOException {
		synchronized (STORAGE) {
			read(id); checked(id); clean(id);
			Path lockPath = path(id).resolve("claim.lock");
			if (!OWNED.add(lockPath)) throw new Busy();
			FileChannel channel = null;
			try {
				if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS))
					Files.createFile(lockPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
				AtomicFiles.read(lockPath, 0);
				channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
				channel.force(true);
				AtomicFiles.forceDirectory(path(id));
				FileLock lock = channel.tryLock();
				if (lock == null) throw new Busy();
				return new Claim(lockPath, channel, lock);
			}
			catch (IOException | RuntimeException ex) {
				if (channel != null) channel.close();
				OWNED.remove(lockPath);
				throw ex;
			}
		}
	}
	public static final class Claim implements AutoCloseable {
		private final Path path;
		private final FileChannel channel;
		private final FileLock lock;
		private boolean closed;
		private Claim(Path path, FileChannel channel, FileLock lock) { this.path = path; this.channel = channel; this.lock = lock; }
		@Override public void close() throws IOException {
			synchronized (STORAGE) {
				if (closed) return;
				try { lock.release(); }
				finally { try { channel.close(); } finally { OWNED.remove(path); closed = true; } }
			}
		}
	}
}
