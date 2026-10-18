package com.example.miniagent.core.artifact;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.OsMetadata;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Immutable UTF-8 callback results. Each private scope is a hash of canonical cwd and
 * session ID. A forced payload and manifest become visible together by atomic directory
 * rename. Abandoned pending entries fail closed and require operator recovery.
 */
public final class ArtifactStore {
	static final JsonMapper JSON = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
	// One monitor prevents overlapping JVM POSIX lock descriptors, including across store instances.
	private static final Object PUBLICATION_LOCK = new Object();
	private static final int MANIFEST_LIMIT = 1024;
	public static final int DEFAULT_READ_LIMIT = 1024;
	public static final int MAX_READ_LIMIT = 2048;
	public static final int DEFAULT_SEARCH_LIMIT = 10;
	public static final int MAX_SEARCH_LIMIT = 20;
	private final Path root;
	private final int maxArtifactBytes;
	private final long maxScopeBytes;
	private final int maxArtifacts;
	private final int previewChars;

	public enum Code { AUTHORITY_REQUIRED, INVALID_ID, INVALID_BOUNDS, INVALID_QUERY,
		NOT_FOUND, TOO_LARGE, QUOTA, UNSAFE_STORAGE, CORRUPT, LOCKED, RECOVERY_REQUIRED, IO }

	/** Messages intentionally contain neither payloads nor filesystem paths. */
	public static final class Failure extends RuntimeException {
		private final Code code;
		Failure(Code code, String detail) {
			super("Artifact " + code + ": " + detail);
			this.code = code;
		}
		public Code code() { return this.code; }
	}

	public record Descriptor(String artifactId, String sha256, long bytes, String preview, boolean truncated) {}
	/** All offsets and character limits are zero-based Unicode code-point counts. */
	public record Page(String artifactId, int offset, String content, int nextOffset, boolean truncated) {}
	public record Match(int offset, String preview) {}
	public record SearchPage(String artifactId, List<Match> matches, int nextOffset, boolean truncated) {
		public SearchPage { matches = List.copyOf(matches); }
	}
	private record Manifest(int schemaVersion, String artifactId, String owner, String sha256, long bytes) {}
	private record Scope(String owner, Path directory) {}

	public ArtifactStore(Path agentHome, ArtifactProperties properties) {
		Objects.requireNonNull(agentHome, "agentHome");
		properties.validate();
		this.maxArtifactBytes = properties.getMaxArtifactBytes();
		this.maxScopeBytes = properties.getMaxScopeBytes();
		this.maxArtifacts = properties.getMaxArtifacts();
		this.previewChars = properties.getPreviewChars();
		if (!agentHome.isAbsolute() || agentHome.normalize().getParent() == null)
			throw new IllegalArgumentException("agent.home must be absolute and not a filesystem root");
		this.root = agentHome.normalize().resolve("artifacts");
		// Lazy filesystem initialization: startup alone does not write or open artifacts.
	}

	public Descriptor put(TurnContext authority, String rawResult) {
		Scope scope = scope(authority);
		Objects.requireNonNull(rawResult, "rawResult");
		if (rawResult.length() > this.maxArtifactBytes)
			throw failure(Code.TOO_LARGE, "result exceeds per-artifact byte limit");
		byte[] bytes;
		try {
			ByteBuffer encoded = UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(rawResult));
			bytes = new byte[encoded.remaining()];
			encoded.get(bytes);
		}
		catch (CharacterCodingException ex) { throw failure(Code.CORRUPT, "result is not valid Unicode"); }
		if (bytes.length > this.maxArtifactBytes)
			throw failure(Code.TOO_LARGE, "result exceeds per-artifact byte limit");
		synchronized (PUBLICATION_LOCK) {
			try {
				ensureHome(this.root.getParent());
				directory(this.root);
				directory(scope.directory());
				Path lockPath = scope.directory().resolve("scope.lock");
				if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
					try { create(lockPath, new byte[0]); }
					catch (java.nio.file.FileAlreadyExistsException ex) { checkFile(lockPath); }
				}
				checkFile(lockPath);
				try (var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
						var lock = channel.tryLock()) {
					if (lock == null) throw failure(Code.LOCKED, "scope is publishing in another process");
					checkQuota(scope, bytes.length);
					String id = UUID.randomUUID().toString();
					String sha = digest(bytes);
					Path pending = scope.directory().resolve(".pending-" + id);
					directory(pending);
					create(pending.resolve("payload"), bytes);
					create(pending.resolve("manifest.json"), JSON.writeValueAsBytes(
							new Manifest(1, id, scope.owner(), sha, bytes.length)));
					forceDirectory(pending);
					Files.move(pending, scope.directory().resolve(id), StandardCopyOption.ATOMIC_MOVE);
					forceDirectory(scope.directory());
					String preview = prefix(rawResult, this.previewChars);
					return new Descriptor(id, sha, bytes.length, preview, preview.length() < rawResult.length());
				}
			}
			catch (OverlappingFileLockException ex) { throw failure(Code.LOCKED, "scope is already publishing"); }
			catch (IOException | UnsupportedOperationException ex) {
				throw failure(Code.IO, "durable publication failed; no reference acknowledged");
			}
		}
	}

	public Page read(TurnContext authority, String artifactId, int offset, int limit) {
		bounds(offset, limit, MAX_READ_LIMIT);
		String text = load(scope(authority), artifactId);
		int count = text.codePointCount(0, text.length());
		if (offset > count) throw failure(Code.INVALID_BOUNDS, "offset exceeds artifact length");
		int end = Math.min(count, offset + limit);
		return new Page(artifactId, offset, text.substring(text.offsetByCodePoints(0, offset),
				text.offsetByCodePoints(0, end)), end, end < count);
	}

	/** Literal, case-sensitive, non-overlapping matches; each excerpt is at most 120 code points. */
	public SearchPage search(TurnContext authority, String artifactId, String query, int offset, int limit) {
		bounds(offset, limit, MAX_SEARCH_LIMIT);
		if (query == null || query.isEmpty() || query.length() > 256 || !UTF_8.newEncoder().canEncode(query))
			throw failure(Code.INVALID_QUERY, "query must contain 1 to 256 UTF-16 characters");
		String text = load(scope(authority), artifactId);
		int count = text.codePointCount(0, text.length());
		if (offset > count) throw failure(Code.INVALID_BOUNDS, "offset exceeds artifact length");
		int cursor = text.offsetByCodePoints(0, offset);
		List<Match> matches = new ArrayList<>();
		while (matches.size() < limit) {
			int found = text.indexOf(query, cursor);
			if (found < 0) return new SearchPage(artifactId, matches, count, false);
			matches.add(new Match(text.codePointCount(0, found), text.substring(found,
					text.offsetByCodePoints(found, Math.min(120, text.codePointCount(found, text.length()))))));
			cursor = found + query.length();
		}
		boolean more = text.indexOf(query, cursor) >= 0;
		return new SearchPage(artifactId, matches, more ? text.codePointCount(0, cursor) : count, more);
	}

	private void checkQuota(Scope scope, int incoming) throws IOException {
		long total = 0;
		int count = 0;
		int entries = 0;
		try (var stream = Files.newDirectoryStream(scope.directory())) {
			for (Path entry : stream) {
				String name = entry.getFileName().toString();
				// OS metadata is neither opened nor counted toward the entry limit.
				if (OsMetadata.matches(name)) continue;
				if (++entries > this.maxArtifacts + 1)
					throw failure(Code.QUOTA, "scope entry limit exceeded");
				if (name.equals("scope.lock")) { checkFile(entry); continue; }
				if (name.startsWith(".pending-"))
					throw failure(Code.RECOVERY_REQUIRED, "incomplete publication requires operator recovery");
				validId(name);
				Manifest manifest = manifest(scope, name);
				checkFile(entry.resolve("payload"));
				if (Files.size(entry.resolve("payload")) != manifest.bytes())
					throw failure(Code.CORRUPT, "payload size differs from manifest");
				count++;
				total += manifest.bytes();
			}
		}
		if (count >= this.maxArtifacts || incoming > this.maxScopeBytes - total)
			throw failure(Code.QUOTA, "scope byte or artifact quota exceeded");
	}

	private String load(Scope scope, String id) {
		validId(id);
		try {
			Manifest manifest = manifest(scope, id);
			byte[] bytes = readFile(scope.directory().resolve(id).resolve("payload"), this.maxArtifactBytes);
			if (bytes.length != manifest.bytes() || !digest(bytes).equals(manifest.sha256()))
				throw failure(Code.CORRUPT, "payload integrity mismatch");
			return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		}
		catch (NoSuchFileException ex) { throw failure(Code.NOT_FOUND, "artifact not found in captured scope"); }
		catch (IOException | UnsupportedOperationException ex) { throw failure(Code.IO, "artifact read failed"); }
	}

	private Manifest manifest(Scope scope, String id) throws IOException {
		validId(id);
		Path entry = scope.directory().resolve(id);
		checkOwned(scope.directory());
		checkOwned(entry);
		Manifest manifest;
		try { manifest = JSON.readValue(readFile(entry.resolve("manifest.json"), MANIFEST_LIMIT), Manifest.class); }
		catch (RuntimeException ex) {
			if (ex instanceof Failure failure) throw failure;
			throw failure(Code.CORRUPT, "invalid manifest");
		}
		if (manifest == null || manifest.schemaVersion() != 1 || !id.equals(manifest.artifactId())
				|| !scope.owner().equals(manifest.owner()) || manifest.sha256() == null
				|| !manifest.sha256().matches("[0-9a-f]{64}") || manifest.bytes() < 0
				|| manifest.bytes() > this.maxArtifactBytes)
			throw failure(Code.CORRUPT, "manifest identity or limits mismatch");
		return manifest;
	}

	private Scope scope(TurnContext authority) {
		if (authority == null || authority.sessionId().isBlank())
			throw failure(Code.AUTHORITY_REQUIRED, "captured TurnContext is required");
		try {
			Path cwd = authority.cwd().toRealPath();
			if (!Files.isDirectory(cwd)) throw failure(Code.AUTHORITY_REQUIRED, "captured workspace is not a directory");
			// Length framing avoids ambiguous concatenation of arbitrary trusted session identifiers.
			String owner = digest(("artifact-v1:" + cwd.toString().length() + ":" + cwd
					+ ":" + authority.sessionId()).getBytes(UTF_8));
			return new Scope(owner, this.root.resolve(owner));
		}
		catch (IOException ex) { throw failure(Code.AUTHORITY_REQUIRED, "captured workspace is unavailable"); }
	}

	private void checkOwned(Path path) throws IOException {
		checkAncestors(path);
		for (Path current = path; current != null && current.startsWith(this.root); current = current.getParent())
			if (!Files.getPosixFilePermissions(current, LinkOption.NOFOLLOW_LINKS)
					.equals(PosixFilePermissions.fromString("rwx------")))
				throw failure(Code.UNSAFE_STORAGE, "artifact directory is not private");
	}

	private static void bounds(int offset, int limit, int maximum) {
		if (offset < 0 || limit < 1 || limit > maximum)
			throw failure(Code.INVALID_BOUNDS, "offset must be nonnegative and limit within tool maximum");
	}
	private static void validId(String id) {
		if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
			throw failure(Code.INVALID_ID, "expected canonical random UUID");
	}
	private static String prefix(String text, int count) {
		return text.substring(0, text.offsetByCodePoints(0, Math.min(count, text.codePointCount(0, text.length()))));
	}
	private static String digest(byte[] bytes) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
	}
	private static Failure failure(Code code, String message) { return new Failure(code, message); }

	private static void checkAncestors(Path path) throws IOException {
		if (path.getParent() != null) checkAncestors(path.getParent());
		var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isDirectory() || attributes.isSymbolicLink())
			throw failure(Code.UNSAFE_STORAGE, "non-directory or symbolic link in storage ancestry");
	}
	private static void ensureHome(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			ensureHome(path.getParent());
			directory(path);
		}
		else checkAncestors(path);
	}
	private static void directory(Path path) throws IOException {
		checkAncestors(path.getParent());
		try { Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			forceDirectory(path.getParent()); }
		catch (java.nio.file.FileAlreadyExistsException ex) { checkAncestors(path); }
		if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
				.equals(PosixFilePermissions.fromString("rwx------")))
			throw failure(Code.UNSAFE_STORAGE, "storage directory is not private");
	}
	private static void checkFile(Path path) throws IOException {
		checkAncestors(path.getParent());
		if (!Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS).isRegularFile()
				|| !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
						.equals(PosixFilePermissions.fromString("rw-------")))
			throw failure(Code.UNSAFE_STORAGE, "storage file is not a private regular file");
	}
	private static void create(Path path, byte[] bytes) throws IOException {
		checkAncestors(path.getParent());
		Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		try (var channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) channel.write(buffer);
			channel.force(true);
		}
	}
	private byte[] readFile(Path path, int limit) throws IOException {
		checkOwned(path.getParent());
		checkFile(path);
		try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			long size = channel.size();
			if (size > limit) throw failure(Code.CORRUPT, "stored file exceeds byte limit");
			ByteBuffer buffer = ByteBuffer.allocate((int) size + 1);
			while (buffer.hasRemaining() && channel.read(buffer) != -1) {}
			if (buffer.position() > limit || buffer.position() != channel.size())
				throw failure(Code.CORRUPT, "stored file changed during read");
			return Arrays.copyOf(buffer.array(), buffer.position());
		}
	}
	private static void forceDirectory(Path path) throws IOException {
		checkAncestors(path);
		try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			channel.force(true);
		}
	}
}
