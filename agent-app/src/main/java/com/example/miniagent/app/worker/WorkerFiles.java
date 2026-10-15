package com.example.miniagent.app.worker;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.miniagent.core.change.AtomicFiles;
import com.example.miniagent.core.change.Fingerprinter;
import com.example.miniagent.core.run.RunExecutor;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Stable claim inode; metadata and control are private projections, never ownership authority. */
final class WorkerFiles {
	static final int LIMIT = 65536;
	static final JsonMapper JSON = JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
			.streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder()
					.maxNestingDepth(16).maxStringLength(8192).maxNumberLength(24).build()).build())
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
	// Do not open a second descriptor for a lock owned by this JVM (POSIX close semantics).
	private static final Set<Path> OWNED = ConcurrentHashMap.newKeySet();
	private static final Object LOCKS = new Object();
	final Path workspace;
	final Path home;
	final Path directory;
	final Path inbox;
	final Path metadata;
	private final Path claim;
	private final UserPrincipal owner;

	record Metadata(int version, String instance, long pid, String workspace, String home,
			String state, String activeGoal, String lastGoal, String lastState, String error) {
		Metadata {
			if (version != 1 || pid < 1 || !Set.of("RUNNING", "DRAINING", "STOPPED").contains(state))
				throw new IllegalArgumentException("Invalid worker metadata");
			UUID.fromString(instance);
			for (String value : new String[] { activeGoal, lastGoal })
				if (!value.isEmpty()) goalId(value);
			if (!lastState.isEmpty()) com.example.miniagent.core.goal.GoalRecord.State.valueOf(lastState);
			if (!Set.of("", "GOAL_REFUSED", "STORAGE_FAILURE").contains(error))
				throw new IllegalArgumentException("Invalid worker error");
		}
	}
	record Request(int version, String instance, String id, String operation, String goal,
			RunExecutor.ResumeOptions options) {
		Request {
			if (version != 1) throw new IllegalArgumentException("Invalid worker request");
			UUID.fromString(instance); UUID.fromString(id);
			if (!Set.of("stop", "wakeup", "resume").contains(operation))
				throw new IllegalArgumentException("Invalid worker operation");
			if (operation.equals("resume")) {
				goalId(goal);
				java.util.Objects.requireNonNull(options);
			}
			else if (!goal.isEmpty() || options != null) throw new IllegalArgumentException("Unexpected options");
		}
	}

	WorkerFiles(Path workspace, Path home) throws IOException {
		this.workspace = workspace.toRealPath();
		Path absolute = home.toAbsolutePath().normalize();
		ancestors(absolute.getParent());
		owner = absolute.getFileSystem().getUserPrincipalLookupService()
				.lookupPrincipalByName(System.getProperty("user.name"));
		if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) privateDirectory(absolute);
		ancestors(absolute);
		if (!Files.getOwner(absolute, LinkOption.NOFOLLOW_LINKS).equals(owner))
			throw new IOException("Foreign worker home");
		this.home = absolute.toRealPath();
		Path workers = this.home.resolve("workers");
		privateDirectory(workers);
		String key = Fingerprinter.sha256((this.workspace + "\n" + this.home)
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		directory = workers.resolve(key);
		privateDirectory(directory);
		inbox = directory.resolve("inbox");
		privateDirectory(inbox);
		metadata = directory.resolve("state.json");
		claim = directory.resolve("claim.lock");
		synchronized (LOCKS) {
			if (!Files.exists(claim, LinkOption.NOFOLLOW_LINKS)) {
				try {
					Files.createFile(claim, PosixFilePermissions.asFileAttribute(
							PosixFilePermissions.fromString("rw-------")));
					AtomicFiles.forceDirectory(directory);
				}
				catch (FileAlreadyExistsException ex) { /* Concurrent initialization; validate below. */ }
			}
			file(claim, 0);
		}
		checkLayout();
	}

	static void goalId(String id) {
		if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
			throw new IllegalArgumentException("Unsafe goal identity");
	}
	static void ancestors(Path path) throws IOException {
		if (path.getParent() != null) ancestors(path.getParent());
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe worker ancestor");
	}
	void privateDirectory(Path path) throws IOException {
		ancestors(path.getParent());
		try {
			Files.createDirectory(path, PosixFilePermissions.asFileAttribute(
					PosixFilePermissions.fromString("rwx------")));
		}
		catch (FileAlreadyExistsException ex) { /* Validate, never chmod or replace. */ }
		ancestors(path);
		if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)
				|| !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
						.equals(PosixFilePermissions.fromString("rwx------")))
			throw new IOException("Worker directory is not UID-private");
	}
	void file(Path path, int limit) throws IOException {
		if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner))
			throw new IOException("Foreign worker file");
		// stat only: opening/closing ANY descriptor for our claim inode can drop its POSIX lock.
		var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile() || attributes.size() > limit
				|| !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
						.equals(PosixFilePermissions.fromString("rw-------")))
			throw new IOException("Unsafe worker file");
		Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
		if (((Number) links).longValue() != 1) throw new IOException("Linked worker file");
	}
	void checkLayout() throws IOException {
		privateDirectory(directory); privateDirectory(inbox); file(claim, 0);
		try (var entries = Files.list(directory)) {
			var paths = entries.limit(17).toList();
			if (paths.size() > 16) throw new IOException("Worker storage recovery required");
			for (Path entry : paths) {
				String name = entry.getFileName().toString();
				if (name.startsWith(".pending-")) continue; // Unpublished bytes are never authority.
				else if (!Set.of("claim.lock", "state.json", "inbox", "exit").contains(name))
					throw new IOException("Worker storage recovery required");
			}
		}
		if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) read();
		if (Files.exists(directory.resolve("exit"), LinkOption.NOFOLLOW_LINKS)) exitInstance();
	}
	String exitInstance() throws IOException {
		Path path = directory.resolve("exit");
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "";
		file(path, 36);
		String value = new String(AtomicFiles.read(path, 36), java.nio.charset.StandardCharsets.US_ASCII);
		try { UUID.fromString(value); }
		catch (IllegalArgumentException ex) { throw new IOException("Corrupt worker exit event", ex); }
		return value;
	}
	void exited(String instance) throws IOException {
		exitInstance(); // Validate previous bytes; never overwrite poisoned control state.
		AtomicFiles.write(directory.resolve("exit"),
				instance.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 36);
	}
	Metadata read() throws IOException {
		file(metadata, LIMIT);
		try {
			var result = JSON.readValue(AtomicFiles.read(metadata, LIMIT), Metadata.class);
			if (!result.workspace().equals(workspace.toString()) || !result.home().equals(home.toString()))
				throw new IOException("Foreign worker metadata");
			return result;
		}
		catch (tools.jackson.core.JacksonException | IllegalArgumentException ex) {
			throw new IOException("Corrupt worker metadata", ex);
		}
	}
	void write(Metadata value) throws IOException {
		if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) read();
		AtomicFiles.write(metadata, JSON.writeValueAsBytes(value), LIMIT);
	}
	void send(Request request) throws IOException {
		privateDirectory(inbox);
		try (var files = Files.list(inbox)) {
			if (files.limit(257).count() >= 256) throw new IOException("Worker inbox full");
		}
		Path path = inbox.resolve(request.id() + ".json");
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Duplicate request");
		AtomicFiles.write(path, JSON.writeValueAsBytes(request), LIMIT);
	}
	Request request(Path path) throws IOException {
		if (!path.getFileName().toString().matches("[0-9a-f-]{36}\\.json"))
			throw new IOException("Worker inbox recovery required");
		file(path, LIMIT);
		try {
			var request = JSON.readValue(AtomicFiles.read(path, LIMIT), Request.class);
			if (!path.getFileName().toString().equals(request.id() + ".json")) throw new IOException("Request mismatch");
			return request;
		}
		catch (tools.jackson.core.JacksonException | IllegalArgumentException ex) {
			throw new IOException("Corrupt worker request", ex);
		}
	}
	Claim acquire() throws IOException {
		synchronized (LOCKS) {
			if (OWNED.contains(claim)) return null;
			file(claim, 0);
			FileChannel channel = FileChannel.open(claim, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
			try {
				FileLock lock = channel.tryLock();
				if (lock == null) { channel.close(); return null; }
				OWNED.add(claim);
				return new Claim(channel, lock);
			}
			catch (IOException | RuntimeException ex) { channel.close(); throw ex; }
		}
	}
	boolean held() throws IOException {
		synchronized (LOCKS) {
			if (OWNED.contains(claim)) return true;
			try (var lease = acquire()) { return lease == null; }
		}
	}
	final class Claim implements AutoCloseable {
		private final FileChannel channel;
		private final FileLock lock;
		private boolean closed;
		Claim(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
		@Override public void close() throws IOException {
			synchronized (LOCKS) {
				if (closed) return;
				try { lock.release(); }
				finally { try { channel.close(); } finally { OWNED.remove(claim); closed = true; } }
			}
		}
	}
}
