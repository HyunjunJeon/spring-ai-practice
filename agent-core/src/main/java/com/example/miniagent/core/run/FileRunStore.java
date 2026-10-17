package com.example.miniagent.core.run;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.CRC32C;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static com.example.miniagent.core.run.RunStoreException.Code.*;

/** POSIX private, cooperating-writer store. The log, not a cached record, is authority. */
public final class FileRunStore implements RunStore {
	// Closing a second descriptor for a POSIX lock file can release the JVM's first
	// lock. Never even open another lock descriptor for a locally owned writer.
	private static final Set<Path> JVM_WRITERS = new java.util.HashSet<>();
	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
		.build();

	private final Path runs;

	public FileRunStore(Path agentHome) throws RunStoreException {
		try {
			Path home = agentHome.toAbsolutePath().normalize();
			Files.createDirectories(home);
			checkDirectory(home);
			this.runs = home.toRealPath().resolve("runs");
			Files.createDirectories(this.runs, PosixFilePermissions.asFileAttribute(
					PosixFilePermissions.fromString("rwx------")));
			checkDirectory(this.runs);
			Files.setPosixFilePermissions(this.runs, PosixFilePermissions.fromString("rwx------"));
		}
		catch (IOException | UnsupportedOperationException ex) {
			throw failure(ex);
		}
	}

	@Override
	public RunWriter create(RunSpec spec) throws RunStoreException {
		return writer(spec.runId(), spec);
	}

	@Override
	public RunWriter openWriter(RunId id) throws RunStoreException {
		return writer(id, null);
	}

	private RunWriter writer(RunId id, RunSpec newSpec) throws RunStoreException {
		Writer writer = new Writer(id);
		try {
			await(writer.executor.submit(() -> {
				writer.initialize(newSpec);
				return null;
			}));
			return writer;
		}
		catch (RunStoreException ex) {
			try {
				writer.close();
			}
			catch (RunStoreException cleanup) {
				ex.addSuppressed(cleanup);
			}
			throw ex;
		}
	}

	@Override
	public RunRecord read(RunId id) throws RunStoreException {
		synchronized (JVM_WRITERS) {
		try {
			Path directory = directory(id);
			Path lockPath = directory.resolve("writer.lock");
			if (JVM_WRITERS.contains(lockPath)) {
				Loaded loaded = load(directory, id);
				return RunProjector.project(loaded.spec(), loaded.events(), true);
			}
			checkFile(lockPath);
			try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				FileLock lock = tryLock(channel);
				try {
					Loaded loaded = load(directory, id);
					return RunProjector.project(loaded.spec(), loaded.events(), lock == null);
				}
				finally {
					if (lock != null) {
						lock.release();
					}
				}
			}
		}
		catch (IOException | UnsupportedOperationException ex) {
			throw failure(ex);
		}
		}
	}

	@Override
	public List<RunId> list() throws RunStoreException {
		try {
			checkDirectory(this.runs);
			try (var paths = Files.list(this.runs)) {
				List<RunId> ids = new ArrayList<>();
				for (Path path : paths.sorted().toList()) {
					if (OsMetadata.matches(path.getFileName().toString())) continue;
					checkDirectory(path);
					ids.add(new RunId(path.getFileName().toString()));
				}
				return List.copyOf(ids);
			}
		}
		catch (IOException | IllegalArgumentException ex) {
			throw failure(ex);
		}
	}

	/** Lineage comes from spec.json alone, so damage in an unrelated run's journal cannot fail this scan. */
	@Override
	public List<RunId> children(RunId parent) throws RunStoreException {
		// Every spec and frame carries its writing build's schema, and a child is created only inside an
		// attempt its parent journaled. Reading the parent proves no newer build ever drove it, so a
		// future-schema spec names some other parent. Any other unreadable spec may be a child.
		read(parent);
		List<RunId> children = new ArrayList<>();
		for (RunId id : list()) {
			if (id.equals(parent)) continue;
			try {
				if (parent.equals(spec(id).parentRunId())) children.add(id);
			}
			catch (RunStoreException ex) {
				if (ex.code() != FUTURE_SCHEMA) throw ex;
			}
		}
		return List.copyOf(children);
	}

	private RunSpec spec(RunId id) throws RunStoreException {
		try {
			Path specPath = directory(id).resolve("spec.json");
			checkFile(specPath);
			return parseSpec(readBytes(specPath), id);
		}
		catch (IOException ex) {
			throw failure(ex);
		}
		catch (RuntimeException ex) {
			throw new RunStoreException(CORRUPT, "Invalid run serialization", ex);
		}
	}

	@Override
	public OpaqueRun export(RunId id) throws RunStoreException {
		try {
			Path directory = directory(id);
			checkFile(directory.resolve("spec.json"));
			checkFile(directory.resolve("events.log"));
			return new OpaqueRun(id, new String(readBytes(directory.resolve("spec.json")), UTF_8),
					new String(readBytes(directory.resolve("events.log")), UTF_8));
		}
		catch (IOException ex) {
			throw failure(ex);
		}
	}

	private Path directory(RunId id) throws IOException {
		checkDirectory(this.runs);
		Path directory = this.runs.resolve(id.value());
		if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
			throw new RunStoreException(NOT_FOUND, "Run not found: " + id.value());
		}
		checkDirectory(directory);
		return directory;
	}

	private record Loaded(RunSpec spec, List<RunEvent> events, long completeBytes, boolean partial) {}

	private static Loaded load(Path directory, RunId id) throws IOException {
		Path specPath = directory.resolve("spec.json");
		Path logPath = directory.resolve("events.log");
		checkFile(specPath);
		checkFile(logPath);
		try {
			RunSpec spec = parseSpec(readBytes(specPath), id);
			byte[] log = readBytes(logPath);
			List<RunEvent> events = new ArrayList<>();
			int start = 0;
			for (int i = 0; i < log.length; i++) {
				if (log[i] == '\n') {
					String row = new String(log, start, i - start, UTF_8);
					String[] parts = row.split("\t", 4);
					if (parts.length != 4 || !parts[0].matches("[1-9][0-9]*")
							|| !parts[1].matches("[1-9][0-9]*") || !parts[2].matches("[0-9a-f]{8}")) {
						throw new RunStoreException(CORRUPT, "Invalid event frame");
					}
					int version = Integer.parseInt(parts[0]);
					long sequence = Long.parseLong(parts[1]);
					String checksumInput = parts[0] + "\t" + parts[1] + "\t" + parts[3];
					if (sequence != events.size() + 1L || !crc(checksumInput).equals(parts[2])) {
						throw new RunStoreException(CORRUPT, "Event CRC/sequence mismatch");
					}
					checkSchema(version);
					events.add(decode(parts[3]));
					start = i + 1;
				}
			}
			// An identifiable future tail cannot be discarded as a current partial frame.
			if (start < log.length) {
				String tail = new String(log, start, log.length - start, UTF_8);
				int tab = tail.indexOf('\t');
				if (tab > 0 && tail.substring(0, tab).matches("[1-9][0-9]*")) {
					checkSchema(Integer.parseInt(tail.substring(0, tab)));
				}
			}
			RunProjector.project(spec, events, true);
			return new Loaded(spec, events, start, start != log.length);
		}
		catch (RunStoreException ex) {
			if (ex.code() == INVALID_TRANSITION || ex.code() == TERMINAL) {
				throw new RunStoreException(CORRUPT, "Invalid journal projection", ex);
			}
			throw ex;
		}
		catch (RuntimeException ex) {
			throw new RunStoreException(CORRUPT, "Invalid run serialization", ex);
		}
	}

	private static RunSpec parseSpec(byte[] json, RunId id) throws RunStoreException {
		JsonNode node = JSON.readTree(json);
		if (node == null || !node.isObject() || !node.path("schemaVersion").isInt()) {
			throw new RunStoreException(CORRUPT, "Invalid run spec");
		}
		checkSchema(node.path("schemaVersion").intValue());
		RunSpec spec = JSON.treeToValue(node, RunSpec.class);
		if (!spec.runId().equals(id)) {
			throw new RunStoreException(CORRUPT, "Spec ID differs from directory");
		}
		return spec;
	}

	private static void checkSchema(int version) throws RunStoreException {
		if (version > RunSpec.SCHEMA_VERSION) {
			throw new RunStoreException(FUTURE_SCHEMA, "Future run schema: " + version);
		}
		if (version != RunSpec.SCHEMA_VERSION) {
			throw new RunStoreException(CORRUPT, "Invalid schema version");
		}
	}

	static RunEvent decode(String json) {
		JsonNode root = JSON.readTree(json);
		if (root == null || !root.isObject()
				|| !root.properties().stream().map(java.util.Map.Entry::getKey).collect(
						java.util.stream.Collectors.toSet()).equals(Set.of("type", "payload"))
				|| !root.path("type").isString() || !root.path("payload").isObject()) {
			throw new IllegalArgumentException("Invalid event envelope");
		}
		Class<? extends RunEvent> type = switch (root.path("type").asString()) {
			case "StateChanged" -> RunEvent.StateChanged.class;
			case "AttemptStarted" -> RunEvent.AttemptStarted.class;
			case "UsageRecorded" -> RunEvent.UsageRecorded.class;
			case "AttemptEnded" -> RunEvent.AttemptEnded.class;
			case "ApprovalRequested" -> RunEvent.ApprovalRequested.class;
			case "ApprovalResolved" -> RunEvent.ApprovalResolved.class;
			case "ToolStarted" -> RunEvent.ToolStarted.class;
			case "ToolFinished" -> RunEvent.ToolFinished.class;
			case "ToolResolution" -> RunEvent.ToolResolution.class;
			case "DriftAccepted" -> RunEvent.DriftAccepted.class;
			default -> throw new IllegalArgumentException("Unknown current-schema event type");
		};
		// Schema 1 legacy ToolStarted rows lack the newly optional snapshot reference.
		// No other missing creator field is relaxed.
		if (type == RunEvent.ToolStarted.class && !root.path("payload").has("snapshotDigest")) {
			((tools.jackson.databind.node.ObjectNode) root.path("payload")).putNull("snapshotDigest");
		}
		return JSON.treeToValue(root.path("payload"), type);
	}

	public static List<RunEvent> events(RunStore store, RunId id) throws RunStoreException {
		RunRecord record = store.read(id);
		List<RunEvent> result = new ArrayList<>();
		String log = store.export(id).eventsLog();
		int complete = log.lastIndexOf('\n') + 1;
		String prefix = log.substring(0, complete);
		for (String row : prefix.isEmpty() ? List.<String>of() : prefix.lines().toList()) {
			String[] parts = row.split("\t", 4);
			if (parts.length != 4) throw new RunStoreException(CORRUPT, "Invalid evidence frame");
			try {
				checkSchema(Integer.parseInt(parts[0]));
				if (Long.parseLong(parts[1]) != result.size() + 1L
						|| !crc(parts[0] + "\t" + parts[1] + "\t" + parts[3]).equals(parts[2]))
					throw new RunStoreException(CORRUPT, "Evidence frame mismatch");
				result.add(decode(parts[3]));
			}
			catch (RuntimeException ex) { throw new RunStoreException(CORRUPT, "Invalid evidence frame", ex); }
		}
		RunProjector.project(record.spec(), result, true);
		return List.copyOf(result);
	}

	/**
	 * Active time a lost attempt's rows prove: from its start to its last row, never past the loss.
	 * An approval request pauses the meter until its decision row, as the approval broker does.
	 * A parent is paused while its child runs; the child's own rows charge that time on import.
	 */
	private static long lostActiveMillis(List<RunEvent> events, String attemptId) {
		long active = 0;
		Instant previous = null;
		Set<String> waiting = new HashSet<>();
		for (RunEvent event : events) {
			if (event instanceof RunEvent.AttemptStarted start && start.attemptId().equals(attemptId))
				previous = start.at();
			if (previous == null) continue;
			boolean childImport = event instanceof RunEvent.UsageRecorded usage && usage.callId().startsWith("child-");
			if (waiting.isEmpty() && !childImport) active += Duration.between(previous, event.at()).toMillis();
			previous = event.at();
			switch (event) {
				case RunEvent.ApprovalRequested request -> waiting.add(request.requestId());
				case RunEvent.ApprovalResolved resolved -> waiting.remove(resolved.requestId());
				// The attempt's own ACTIVE_TIME row already charged everything before it.
				case RunEvent.UsageRecorded usage when usage.kind() == RunEvent.UsageKind.ACTIVE_TIME -> active = 0;
				default -> {}
			}
		}
		return active;
	}

	private static byte[] frame(long sequence, RunEvent event) {
		var root = JSON.createObjectNode();
		root.put("type", event.getClass().getSimpleName());
		root.set("payload", JSON.valueToTree(event));
		String payload = JSON.writeValueAsString(root);
		String prefix = RunSpec.SCHEMA_VERSION + "\t" + sequence;
		return (prefix + "\t" + crc(prefix + "\t" + payload) + "\t" + payload + "\n").getBytes(UTF_8);
	}

	private static String crc(String content) {
		CRC32C crc = new CRC32C();
		crc.update(content.getBytes(UTF_8));
		return String.format(java.util.Locale.ROOT, "%08x", crc.getValue());
	}

	private static FileLock tryLock(FileChannel channel) throws IOException {
		try {
			return channel.tryLock();
		}
		catch (OverlappingFileLockException ex) {
			return null;
		}
	}

	private static void checkDirectory(Path path) throws IOException {
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new RunStoreException(IO, "Not a real run directory: " + path);
		}
	}

	private static void checkFile(Path path) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new RunStoreException(IO, "Not a regular run file: " + path);
		}
	}

	private static FileChannel privateFile(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			Files.createFile(path, PosixFilePermissions.asFileAttribute(
					PosixFilePermissions.fromString("rw-------")));
		}
		checkFile(path);
		Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
		return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
	}

	private static byte[] readBytes(Path path) throws IOException {
		// A pending interrupt closes the channel; a cancelled caller still settles from the journal.
		boolean interrupted = Thread.interrupted();
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			if (channel.size() > Integer.MAX_VALUE - 8) {
				throw new RunStoreException(IO, "Run file exceeds supported size");
			}
			ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
			while (buffer.hasRemaining() && channel.read(buffer) != -1) {
				// Snapshot the complete prefix; a live append may add an incomplete final frame.
			}
			return Arrays.copyOf(buffer.array(), buffer.position());
		}
		finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		while (buffer.hasRemaining()) {
			channel.write(buffer);
		}
	}

	private static void forceDirectory(Path directory) throws IOException {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			channel.force(true);
		}
	}

	private static void saveSpec(Path directory, RunSpec spec) throws IOException {
		Path temp = Files.createTempFile(directory, ".spec-", ".tmp", PosixFilePermissions.asFileAttribute(
				PosixFilePermissions.fromString("rw-------")));
		// Failed files are preserved for diagnosis, not silently deleted.
		try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			writeFully(channel, JSON.writeValueAsBytes(spec));
			channel.force(true);
		}
		Files.move(temp, directory.resolve("spec.json"), StandardCopyOption.ATOMIC_MOVE);
		forceDirectory(directory);
	}

	private static RunStoreException failure(Exception ex) {
		if (ex instanceof RunStoreException error) {
			return error;
		}
		return new RunStoreException(ex instanceof AtomicMoveNotSupportedException
				|| ex instanceof UnsupportedOperationException ? UNSUPPORTED_FILESYSTEM : IO,
				"Run store operation failed", ex);
	}

	private static <T> T await(Future<T> future) throws RunStoreException {
		boolean interrupted = false;
		try {
			for (;;) {
				try {
					return future.get();
				}
				catch (InterruptedException ex) {
					// An accepted write cannot be cancelled: learn its outcome, then restore interrupt.
					interrupted = true;
				}
				catch (ExecutionException ex) {
					Throwable cause = ex.getCause();
					if (cause instanceof RunStoreException error) {
						throw error;
					}
					if (cause instanceof Exception error) {
						throw failure(error);
					}
					throw new RunStoreException(IO, "Writer failed", cause);
				}
			}
		}
		finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private final class Writer implements RunWriter {
		private final RunId id;
		private final ExecutorService executor;
		private FileChannel lockChannel;
		private Path lockPath;
		private FileLock lock;
		private FileChannel logChannel;
		private RunSpec spec;
		private final List<RunEvent> events = new ArrayList<>();
		private volatile RunRecord record;
		private RunStoreException poisoned;
		private Future<Void> closing;

		Writer(RunId id) {
			this.id = id;
			this.executor = Executors.newSingleThreadExecutor(r -> {
				Thread thread = new Thread(r, "run-writer-" + id.value());
				thread.setDaemon(true);
				return thread;
			});
		}

		void initialize(RunSpec newSpec) throws IOException {
			checkDirectory(runs);
			Path directory = runs.resolve(this.id.value());
			if (newSpec != null) {
				try {
					Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
							PosixFilePermissions.fromString("rwx------")));
				}
				catch (java.nio.file.FileAlreadyExistsException ex) {
					throw new RunStoreException(ALREADY_EXISTS, "Run already exists", ex);
				}
				forceDirectory(runs);
			}
			else {
				directory = directory(this.id);
			}
			synchronized (JVM_WRITERS) {
				Path candidate = directory.resolve("writer.lock");
				if (JVM_WRITERS.contains(candidate)) {
					throw new RunStoreException(RUN_LOCKED, "Run has a live JVM writer");
				}
				this.lockChannel = privateFile(candidate);
				this.lock = tryLock(this.lockChannel);
				if (this.lock == null) {
					throw new RunStoreException(RUN_LOCKED, "Run has a live process writer");
				}
				this.lockPath = candidate;
				JVM_WRITERS.add(candidate);
			}
			if (newSpec != null) {
				saveSpec(directory, newSpec);
				this.logChannel = privateFile(directory.resolve("events.log"));
				this.logChannel.force(true);
				forceDirectory(directory);
				this.spec = newSpec;
			}
			else {
				Loaded loaded = load(directory, this.id);
				this.spec = loaded.spec();
				this.events.addAll(loaded.events());
				this.logChannel = privateFile(directory.resolve("events.log"));
				if (loaded.partial()) {
					this.logChannel.truncate(loaded.completeBytes());
					this.logChannel.force(true);
				}
			}
			this.logChannel.position(this.logChannel.size());
			this.record = RunProjector.project(this.spec, this.events, true);
			if (this.record.state() == RunState.RUNNING || this.record.state() == RunState.WAITING_APPROVAL) {
				Instant at = this.events.isEmpty() ? this.spec.createdAt() : this.events.getLast().at();
				appendOwned(new RunEvent.StateChanged(at, RunState.INTERRUPTED, RunEvent.Reason.PROCESS_LOST));
				Attempt active = this.record.attempts().isEmpty() ? null : this.record.attempts().getLast();
				if (active != null && active.endedAt() == null) {
					// The lost process never journaled this attempt's active time; charge what its rows prove.
					long proven = lostActiveMillis(this.events, active.attemptId());
					appendOwned(new RunEvent.UsageRecorded(at, active.attemptId(), RunEvent.UsageKind.MODEL_ROUND,
							"process-lost-" + this.record.sequence(), new RunUsage(0, true, 0, proven, 0)));
					appendOwned(new RunEvent.AttemptEnded(at, active.attemptId()));
				}
			}
		}

		@Override
		public RunRecord record() {
			return this.record;
		}

		private synchronized <T> Future<T> submit(Callable<T> work) throws RunStoreException {
			if (this.closing != null) {
				throw new RunStoreException(CLOSED, "Run writer is closed");
			}
			return this.executor.submit(work);
		}

		@Override
		public RunRecord append(RunEvent event) throws RunStoreException {
			return await(submit(() -> appendOwned(event)));
		}

		private RunRecord appendOwned(RunEvent event) throws RunStoreException {
			if (this.poisoned != null) {
				throw this.poisoned;
			}
			List<RunEvent> proposed = new ArrayList<>(this.events);
			proposed.add(event);
			RunRecord projected = RunProjector.project(this.spec, proposed, true);
			byte[] bytes = frame(projected.sequence(), event);
			try {
				writeFully(this.logChannel, bytes);
				this.logChannel.force(true);
			}
			catch (IOException ex) {
				this.poisoned = failure(ex);
				throw this.poisoned;
			}
			this.events.add(event);
			this.record = projected;
			return projected;
		}

		@Override
		public void close() throws RunStoreException {
			Future<Void> future;
			synchronized (this) {
				if (this.closing == null) {
					this.closing = this.executor.submit(() -> {
						IOException failure = null;
						try {
							if (this.logChannel != null) {
								this.logChannel.close();
							}
						}
						catch (IOException ex) {
							failure = ex;
						}
						synchronized (JVM_WRITERS) {
							try {
								if (this.lock != null) {
									this.lock.release();
								}
							}
							catch (IOException ex) {
								if (failure == null) {
									failure = ex;
								}
								else {
									failure.addSuppressed(ex);
								}
							}
							try {
								if (this.lockChannel != null) {
									this.lockChannel.close();
								}
							}
							catch (IOException ex) {
								if (failure == null) {
									failure = ex;
								}
								else {
									failure.addSuppressed(ex);
								}
							}
							if (this.lockPath != null) {
								JVM_WRITERS.remove(this.lockPath);
							}
						}
						if (failure != null) {
							throw failure;
						}
						return null;
					});
					this.executor.shutdown();
				}
				future = this.closing;
			}
			await(future);
		}
	}
}
