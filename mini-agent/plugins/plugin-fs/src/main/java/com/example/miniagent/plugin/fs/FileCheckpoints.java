package com.example.miniagent.plugin.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * write_file·edit_file 이 파일을 바꾸기 전에 원본을 보관해 두었다가 {@code /undo} 로 직전 턴의 변경을 되돌린다.
 *
 * <ul>
 *   <li>변경은 턴(사용자 입력 한 건) 단위로 묶는다. 한 턴에 같은 파일을 여러 번 바꿔도 그 턴의 처음 원본 하나만 남긴다.</li>
 *   <li>그 턴 전에 없던 파일은 되돌릴 때 지운다. 새로 만든 폴더는 남는다.</li>
 *   <li>원본 사본은 실행마다 만든 임시 디렉터리에 두고 앱이 끝나면 지운다. 그래서 {@code /undo} 는 같은 실행 안에서만 된다.</li>
 *   <li>bash 로 바꾼 파일은 대상이 아니다. 성공한 쓰기 직후의 내용·권한·경로와 다른 파일은 충돌로 남긴다.</li>
 * </ul>
 * <p>명시적 비관리(legacy) 모드다. 영속 journal, 동시 외부 writer에 대한 CAS, 다중 파일 원자성은 제공하지 않는다.
 */
public class FileCheckpoints implements AutoCloseable {

	/** 파일 하나의 변경. {@code backup} 이 null 이면 그 턴 전에는 없던 파일이다. */
	public record Change(Path file, @Nullable Path backup) {
	}

	public enum ConflictCode {
		UNKNOWN_COMPLETION, AFTER_CHANGED, UNSAFE_PATH, BACKUP_CHANGED
	}

	public record Conflict(String turnId, Change change, ConflictCode code, String detail) {
	}

	public record UndoOutcome(@Nullable String turnId, List<Change> restored, List<Conflict> conflicts) {
		public UndoOutcome {
			restored = List.copyOf(restored);
			conflicts = List.copyOf(conflicts);
		}
	}

	private record Fingerprint(boolean regular, String sha256, long size,
			@Nullable Set<PosixFilePermission> permissions, Object identity, Map<Path, Object> parents) {
		boolean sameContent(Fingerprint other) {
			return this.regular == other.regular && this.sha256.equals(other.sha256) && this.size == other.size
					&& java.util.Objects.equals(this.permissions, other.permissions);
		}
	}

	private static final class Entry {
		private final Change change;
		private final @Nullable Fingerprint before;
		private @Nullable Fingerprint after;
		private @Nullable Conflict conflict;

		Entry(Change change, @Nullable Fingerprint before) {
			this.change = change;
			this.before = before;
		}
	}

	private record Checkpoint(String turnId, List<Entry> entries) {
	}

	private final Path directory;

	private final Map<String, Deque<Checkpoint>> sessions = new HashMap<>();

	private final Map<String, UndoOutcome> outcomes = new HashMap<>();

	private long backups;

	public FileCheckpoints(Path directory) {
		Path absolute = directory.toAbsolutePath().normalize();
		Path existing = absolute;
		while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
			existing = existing.getParent();
		}
		try {
			// macOS temporary roots can themselves have an existing /var or /tmp alias.
			this.directory = existing.toRealPath().resolve(existing.relativize(absolute));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot resolve checkpoint directory " + directory, ex);
		}
	}

	/**
	 * 파일을 바꾸기 직전에 부른다. 이 턴에 처음 바꾸는 파일이면 원본을 복사해 둔다.
	 * 복사에 실패하면 예외를 던져 쓰기도 일어나지 않게 한다(되돌릴 수 없는 변경을 만들지 않도록).
	 */
	public synchronized void beforeWrite(String sessionId, String turnId, Path file) {
		Deque<Checkpoint> stack = this.sessions.computeIfAbsent(sessionId, id -> new ArrayDeque<>());
		Checkpoint current = stack.peekLast();
		if (current == null || !current.turnId().equals(turnId)) {
			current = new Checkpoint(turnId, new ArrayList<>());
			stack.addLast(current);
		}
		for (Entry entry : current.entries()) {
			if (entry.change.file().equals(file)) {
				// 새 시도가 실패하면 이전 성공 상태로 되돌릴 권한도 없다.
				entry.after = null;
				entry.conflict = null;
				return;
			}
		}
		Path backup = null;
		Fingerprint before = null;
		try {
			boolean exists;
			try {
				Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				exists = true;
			}
			catch (NoSuchFileException ex) {
				exists = false;
			}
			if (exists) {
				before = fingerprint(file);
				Files.createDirectories(this.directory);
				backup = this.directory.resolve(++this.backups + ".bak");
				Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
			}
		}
		catch (IOException ex) {
			if (backup != null) {
				try { Files.deleteIfExists(backup); }
				catch (IOException cleanup) { ex.addSuppressed(cleanup); }
			}
			throw new UncheckedIOException("Cannot checkpoint " + file + ": " + ex.getMessage(), ex);
		}
		current.entries().add(new Entry(new Change(file, backup), before));
	}

	/** 게시에 성공한 직후만 호출한다. 실패하거나 완료를 알 수 없으면 after는 비워 둔다. */
	public synchronized void afterWrite(String sessionId, String turnId, Path file) {
		Deque<Checkpoint> stack = this.sessions.get(sessionId);
		Checkpoint current = stack == null ? null : stack.peekLast();
		if (current == null || !current.turnId().equals(turnId)) {
			throw new IllegalStateException("No checkpoint for " + turnId + ": " + file);
		}
		Entry entry = current.entries().stream().filter(e -> e.change.file().equals(file)).findFirst()
			.orElseThrow(() -> new IllegalStateException("No checkpoint for " + file));
		entry.after = null;
		try {
			Fingerprint after = fingerprint(file);
			if (!after.regular()) {
				throw new IOException("Published entry is not a regular file: " + file);
			}
			entry.after = after;
			entry.conflict = null;
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot record completed write " + file, ex);
		}
	}

	/** 이 세션의 마지막 체크포인트를 되돌린다. 되돌릴 것이 없으면 빈 목록이다. */
	public synchronized List<Change> undo(String sessionId) {
		return undoWithOutcome(sessionId).restored();
	}

	public synchronized UndoOutcome lastUndoOutcome(String sessionId) {
		return this.outcomes.getOrDefault(sessionId, new UndoOutcome(null, List.of(), List.of()));
	}

	/** 충돌은 조회만으로 사라지지 않으며, 이전 턴을 조용히 건너뛰지 않는다. */
	public synchronized List<Conflict> conflicts(String sessionId) {
		Deque<Checkpoint> stack = this.sessions.get(sessionId);
		if (stack == null) return List.of();
		return stack.stream().flatMap(c -> c.entries().stream()).map(e -> e.conflict)
			.filter(java.util.Objects::nonNull).toList();
	}

	public synchronized UndoOutcome undoWithOutcome(String sessionId) {
		Deque<Checkpoint> stack = this.sessions.get(sessionId);
		Checkpoint last = (stack != null) ? stack.peekLast() : null;
		if (last == null) {
			UndoOutcome empty = new UndoOutcome(null, List.of(), List.of());
			this.outcomes.put(sessionId, empty);
			return empty;
		}
		List<Change> restored = new ArrayList<>();
		var pending = last.entries().iterator();
		try {
			while (pending.hasNext()) {
				Entry entry = pending.next();
				Change change = entry.change;
				entry.conflict = conflict(last.turnId(), entry);
				if (entry.conflict != null) continue;
				try {
					if (change.backup() == null) {
						Files.deleteIfExists(change.file());
						pending.remove();
						restored.add(change);
						continue;
					}
					// 같은 폴더의 임시 파일로 복사한 뒤 원자적으로 바꿔치기한다(쓰기 도구와 같은 방식, 권한 유지).
					Path temp = Files.createTempFile(change.file().getParent(), "." + change.file().getFileName() + ".",
							".undo");
					Throwable failure = null;
					try {
						Files.copy(change.backup(), temp, StandardCopyOption.REPLACE_EXISTING,
								StandardCopyOption.COPY_ATTRIBUTES);
						Files.move(temp, change.file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
						pending.remove();
						restored.add(change);
						advanceOlderIdentity(stack, last, entry, fingerprint(change.file()));
					}
					catch (IOException | RuntimeException | Error ex) {
						failure = ex;
						throw ex;
					}
					finally {
						try { Files.deleteIfExists(temp); }
						catch (IOException cleanup) {
							if (failure != null) failure.addSuppressed(cleanup);
							else throw cleanup;
						}
					}
					Files.delete(change.backup());
				}
				catch (IOException ex) {
					throw new UncheckedIOException("Cannot undo " + change.file() + ": " + ex.getMessage(), ex);
				}
			}
		}
		finally {
			this.outcomes.put(sessionId, new UndoOutcome(last.turnId(), restored, last.entries().stream()
				.map(e -> e.conflict).filter(java.util.Objects::nonNull).toList()));
			if (last.entries().isEmpty()) {
				stack.removeLast();
			}
		}
		return this.outcomes.get(sessionId);
	}

	private static @Nullable Conflict conflict(String turnId, Entry entry) {
		if (entry.after == null) {
			return new Conflict(turnId, entry.change, ConflictCode.UNKNOWN_COMPLETION,
					"No successful published write was recorded; live entry preserved.");
		}
		try {
			Fingerprint live = fingerprint(entry.change.file());
			if (!live.equals(entry.after)) {
				return new Conflict(turnId, entry.change, ConflictCode.AFTER_CHANGED,
						"Live bytes, mode or entry identity differ from the recorded successful write.");
			}
		}
		catch (IOException ex) {
			return new Conflict(turnId, entry.change, ConflictCode.UNSAFE_PATH, ex.toString());
		}
		if (entry.change.backup() != null) {
			try {
				if (entry.before == null || !entry.before.sameContent(fingerprint(entry.change.backup()))) {
					return new Conflict(turnId, entry.change, ConflictCode.BACKUP_CHANGED,
							"Original backup bytes, mode or type changed; live entry preserved.");
				}
			}
			catch (IOException ex) {
				return new Conflict(turnId, entry.change, ConflictCode.BACKUP_CHANGED, ex.toString());
			}
		}
		return null;
	}

	private static void advanceOlderIdentity(Deque<Checkpoint> stack, Checkpoint last, Entry restored,
			Fingerprint actual) {
		if (restored.before == null || !restored.before.sameContent(actual)) return;
		var older = stack.descendingIterator();
		while (older.hasNext()) {
			Checkpoint checkpoint = older.next();
			if (checkpoint == last) continue;
			for (Entry entry : checkpoint.entries()) {
				if (entry.change.file().equals(restored.change.file())) {
					if (restored.before.equals(entry.after)) entry.after = actual;
					return;
				}
			}
		}
	}

	private static Fingerprint fingerprint(Path file) throws IOException {
		Map<Path, Object> parents = new HashMap<>();
		for (Path parent = file.toAbsolutePath().getParent(); parent != null; parent = parent.getParent()) {
			BasicFileAttributes attrs = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.fileKey() == null) {
				throw new IOException("Unsafe or unidentifiable parent: " + parent);
			}
			parents.put(parent, attrs.fileKey());
		}
		BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (attrs.isSymbolicLink() || (!attrs.isRegularFile() && !attrs.isDirectory()) || attrs.fileKey() == null) {
			throw new IOException("Unsafe or unidentifiable entry: " + file);
		}
		Set<PosixFilePermission> permissions = null;
		var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (posix != null) permissions = Set.copyOf(posix.readAttributes().permissions());
		MessageDigest digest;
		try { digest = MessageDigest.getInstance("SHA-256"); }
		catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
		if (attrs.isRegularFile()) {
			try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
				byte[] buffer = new byte[8192];
				for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
			}
		}
		BasicFileAttributes observed = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attrs.fileKey().equals(observed.fileKey()) || attrs.size() != observed.size()
				|| !attrs.lastModifiedTime().equals(observed.lastModifiedTime())) {
			throw new IOException("Entry changed during observation: " + file);
		}
		return new Fingerprint(attrs.isRegularFile(), HexFormat.of().formatHex(digest.digest()),
				attrs.isRegularFile() ? attrs.size() : 0, permissions, attrs.fileKey(), Map.copyOf(parents));
	}

	/** 원본 사본을 지운다. 스프링 컨텍스트가 닫힐 때(앱 종료) 불린다. */
	@Override
	public synchronized void close() {
		this.sessions.clear();
		this.outcomes.clear();
		if (!Files.exists(this.directory)) {
			return;
		}
		try (Stream<Path> files = Files.walk(this.directory)) {
			for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
