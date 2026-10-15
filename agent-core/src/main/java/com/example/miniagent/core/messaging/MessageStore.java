package com.example.miniagent.core.messaging;

import java.io.IOException;
import java.nio.channels.FileChannel;
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
import com.example.miniagent.core.run.RunId;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Bounded private snapshots. The lock inode is never renamed or reopened while JVM-owned. */
public final class MessageStore {
	public static final int MAX_THREADS = 128, MAX_ENTRIES = 128, MAX_BODY = 4096, PAGE_SIZE = 16;
	private static final int MAX_BYTES = 96 * 1024 * 1024;
	static final JsonMapper JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
	private static final Set<Path> OWNED = ConcurrentHashMap.newKeySet();
	private static final Object INITIALIZE = new Object();
	private final Path home, directory, lockPath;
	public enum State { QUEUED, RUNNING, DELIVERED, BLOCKED, FAILED, UNCERTAIN }
	public record Key(String parentRun, String plan, String child) {
		public Key { new RunId(parentRun); new RunId(plan); new RunId(child); }
	}
	public record Receipt(String id, String sender, String body, State state, String originalRun,
			String executionRun, String response, boolean responseTruncated, String claim) {
		public Receipt(String id, String sender, String body, State state, String originalRun,
				String executionRun, String response, String claim) {
			this(id, sender, body, state, originalRun, executionRun, response, false, claim);
		}
		public Receipt {
			new RunId(id); new RunId(sender); new RunId(originalRun);
			if (executionRun != null) new RunId(executionRun);
			if (body == null || body.isBlank() || body.length() > MAX_BODY
					|| response != null && response.length() > MAX_BODY
					|| responseTruncated && response == null || state == null)
				throw new IllegalArgumentException("Invalid bounded receipt");
		}
	}
	public record ThreadBox(Key key, long version, List<Receipt> entries) {
		public ThreadBox { entries = List.copyOf(entries); }
	}
	public record Page(long version, int nextOffset, List<Receipt> entries) {}
	private record Envelope(int schema, String digest, List<ThreadBox> threads) {}
	@FunctionalInterface interface Mutation<T> { T apply(List<ThreadBox> threads) throws IOException; }
	public MessageStore(Path home) {
		this.home = home.toAbsolutePath().normalize();
		this.directory = this.home.resolve("messages");
		this.lockPath = this.directory.resolve("store.lock");
	}
	static ThreadBox find(List<ThreadBox> threads, Key key) {
		return threads.stream().filter(t -> t.key().equals(key)).findFirst().orElse(new ThreadBox(key, 0, List.of()));
	}
	static void replace(List<ThreadBox> threads, ThreadBox old, List<Receipt> entries) {
		threads.removeIf(t -> t.key().equals(old.key()));
		threads.add(new ThreadBox(old.key(), Math.addExact(old.version(), 1), entries));
	}
	public Page read(Key key, int offset) throws IOException {
		if (offset < 0) throw new IllegalArgumentException("Invalid offset");
		return transact(threads -> {
			var box = find(threads, key);
			int from = Math.min(offset, box.entries().size()), to = Math.min(from + PAGE_SIZE, box.entries().size());
			return new Page(box.version(), to, box.entries().subList(from, to));
		});
	}
	<T> T transact(Mutation<T> mutation) throws IOException {
		synchronized (INITIALIZE) {
			if (!OWNED.add(lockPath)) throw new IOException("Mailbox busy");
			try {
				ancestors(home.getParent());
				if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS)) createDirectory(home);
				else ancestors(home);
				createDirectory(directory);
				if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
					if (Files.exists(directory.resolve("mailboxes.json"), LinkOption.NOFOLLOW_LINKS))
						throw new IOException("Missing stable mailbox lock");
					Files.createFile(lockPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
					AtomicFiles.forceDirectory(directory);
				}
			}
			catch (IOException | RuntimeException ex) { OWNED.remove(lockPath); throw ex; }
		}
		try {
			AtomicFiles.read(lockPath, 0);
			try (var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
					var lease = channel.tryLock()) {
				if (lease == null) throw new IOException("Mailbox busy");
				var threads = new ArrayList<ThreadBox>();
				Path data = directory.resolve("mailboxes.json");
				try (var paths = Files.list(directory)) {
					if (paths.limit(4).anyMatch(p -> !Set.of("store.lock", "mailboxes.json").contains(p.getFileName().toString())))
						throw new IOException("Mailbox storage recovery required");
				}
				if (Files.exists(data, LinkOption.NOFOLLOW_LINKS)) {
					var envelope = JSON.readValue(AtomicFiles.read(data, MAX_BYTES), Envelope.class);
					// Schema 1 cannot distinguish complete responses from silently truncated previews.
					if (envelope.schema() != 2) throw new IOException("Unsupported mailbox schema; response completeness unknown");
					if (!Fingerprinter.sha256(JSON.writeValueAsBytes(envelope.threads()))
							.equals(envelope.digest())) throw new IOException("Corrupt mailbox checksum");
					threads.addAll(envelope.threads());
				}
				validate(threads);
				T result = mutation.apply(threads);
				validate(threads);
				AtomicFiles.write(data, JSON.writeValueAsBytes(new Envelope(2,
					Fingerprinter.sha256(JSON.writeValueAsBytes(threads)), threads)), MAX_BYTES);
				return result;
			}
		}
		catch (RuntimeException ex) { throw new IOException("Mailbox transaction refused", ex); }
		finally { OWNED.remove(lockPath); }
	}
	private static void validate(List<ThreadBox> threads) throws IOException {
		if (threads.size() > MAX_THREADS) throw new IOException("Mailbox thread capacity");
		var keys = new java.util.HashSet<Key>();
		for (var t : threads) {
			if (!keys.add(t.key()) || t.version() < 0 || t.entries().size() > MAX_ENTRIES)
				throw new IOException("Invalid bounded mailbox");
			var ids = new java.util.HashSet<String>();
			for (var e : t.entries()) if (!ids.add(e.id())) throw new IOException("Duplicate receipt");
		}
	}
	private static void ancestors(Path path) throws IOException {
		if (path.getParent() != null) ancestors(path.getParent());
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe mailbox path");
	}
	private static void createDirectory(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			AtomicFiles.forceDirectory(path.getParent());
		}
		ancestors(path);
		if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
				.equals(PosixFilePermissions.fromString("rwx------"))) throw new IOException("Mailbox directory is not private");
	}
}
