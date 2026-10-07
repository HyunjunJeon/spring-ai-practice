package com.example.miniagent.core.run;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Private versioned content addressed evidence, with forced publication and bounded reads. */
public final class ToolEvidenceStore {
	static final JsonMapper JSON = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();
	private static final int MAX = 4 * 1024 * 1024;
	private final Path home;
	private final Path directory;

	public ToolEvidenceStore(Path agentHome) throws IOException {
		this.home = agentHome.toAbsolutePath().normalize();
		Files.createDirectories(this.home);
		checkDirectory(this.home);
		this.directory = this.home.toRealPath().resolve("tool-evidence");
		Files.createDirectories(this.directory, PosixFilePermissions.asFileAttribute(
				PosixFilePermissions.fromString("rwx------")));
		checkDirectory(this.directory);
		Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
	}

	public Path home() { return this.home; }

	public record ResultEvidence(int schemaVersion, String runId, String attemptId, String toolCallKey,
			String toolName, String argsDigest, String result, DriftGate.Snapshot after) {}

	public String put(Object value) throws IOException {
		var envelope = JSON.createObjectNode();
		envelope.put("schemaVersion", 1);
		envelope.set("payload", JSON.valueToTree(value));
		byte[] bytes = JSON.writeValueAsBytes(envelope);
		if (bytes.length > MAX) throw new IOException("Evidence too large");
		String digest = RunRequestStore.digest(bytes);
		Path target = path(digest);
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			readBytes(digest);
			return digest;
		}
		Path temp = Files.createTempFile(this.directory, ".evidence-", ".tmp",
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		try {
			try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) channel.write(buffer);
				channel.force(true);
			}
			checkDirectory(this.directory);
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
			try (FileChannel dir = FileChannel.open(this.directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
				dir.force(true);
			}
		}
		finally { Files.deleteIfExists(temp); }
		return digest;
	}

	public <T> T get(String digest, Class<T> type) throws IOException {
		try {
			var root = JSON.readTree(readBytes(digest));
			if (!root.isObject() || root.size() != 2 || !root.has("payload")
					|| !root.path("schemaVersion").isInt() || root.path("schemaVersion").intValue() != 1)
				throw new IOException("Unsupported evidence schema");
			return JSON.treeToValue(root.path("payload"), type);
		}
		catch (RuntimeException ex) { throw new IOException("Invalid evidence", ex); }
	}

	private byte[] readBytes(String digest) throws IOException {
		Path file = path(digest);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe/missing evidence");
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			if (channel.size() > MAX) throw new IOException("Evidence too large");
			ByteBuffer buffer = ByteBuffer.allocate(MAX + 1);
			while (buffer.hasRemaining() && channel.read(buffer) != -1) {}
			if (buffer.position() > MAX) throw new IOException("Evidence too large");
			byte[] bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
			if (!RunRequestStore.digest(bytes).equals(digest)) throw new IOException("Evidence hash mismatch");
			return bytes;
		}
	}

	private Path path(String digest) throws IOException {
		RunSpec.requireDigest(digest);
		checkDirectory(this.directory);
		return this.directory.resolve(digest + ".json");
	}

	private static void checkDirectory(Path path) throws IOException {
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe evidence directory");
	}
}
