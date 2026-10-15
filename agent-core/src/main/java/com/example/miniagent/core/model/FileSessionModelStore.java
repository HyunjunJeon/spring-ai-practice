package com.example.miniagent.core.model;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Versioned sidecars. Requires a POSIX filesystem with atomic rename and directory force. */
public final class FileSessionModelStore implements SessionModelStore {

	private static final int SCHEMA_VERSION = 1;

	private static final int MAX_BYTES = 64 * 1024;

	private static final Set<String> FIELDS = Set.of("schemaVersion", "provider", "model", "options");

	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
		.build();

	private final Path directory;

	public FileSessionModelStore(Path agentHome) throws IOException {
		Path home = agentHome.toAbsolutePath().normalize();
		// The application owns home; this store only owns its private subdirectory.
		Files.createDirectories(home);
		if (Files.isSymbolicLink(home)) {
			throw new IOException("Agent home must not be a symbolic link");
		}
		this.directory = home.toRealPath().resolve("model-selections");
		Files.createDirectories(this.directory, PosixFilePermissions.asFileAttribute(
				PosixFilePermissions.fromString("rwx------")));
		checkDirectory();
		Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
	}

	@Override
	public Optional<ModelSelection> load(String sessionId) throws IOException {
		Path file = file(sessionId);
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
			return Optional.empty();
		}
		checkFile(file);
		byte[] bytes;
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			if (channel.size() > MAX_BYTES) {
				throw new IOException("Model selection is too large");
			}
			ByteBuffer buffer = ByteBuffer.allocate(MAX_BYTES + 1);
			while (channel.read(buffer) != -1 && buffer.hasRemaining()) {
				// Bound the read even if another writer replaces/grows the file.
			}
			if (buffer.position() > MAX_BYTES) {
				throw new IOException("Model selection is too large");
			}
			bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
		}
		try {
			JsonNode root = JSON.readTree(bytes);
			if (root == null || !root.isObject() || !fields(root).equals(FIELDS)
					|| !root.path("schemaVersion").isInt() || root.path("schemaVersion").intValue() != SCHEMA_VERSION
					|| !root.path("provider").isString() || !root.path("model").isString()
					|| !root.path("options").isObject()) {
				throw new IOException("Invalid model selection schema");
			}
			Map<String, String> options = new HashMap<>();
			for (Map.Entry<String, JsonNode> option : root.path("options").properties()) {
				if (!option.getValue().isString()) {
					throw new IOException("Invalid model selection option");
				}
				options.put(option.getKey(), option.getValue().asString());
			}
			return Optional.of(new ModelSelection(new ModelRef(root.path("provider").asString(),
					root.path("model").asString()), options));
		}
		catch (RuntimeException ex) {
			throw new IOException("Corrupt model selection", ex);
		}
	}

	@Override
	public void save(String sessionId, ModelSelection selection) throws IOException {
		Path target = file(sessionId);
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			checkFile(target);
		}
		ObjectNode root = JSON.createObjectNode();
		root.put("schemaVersion", SCHEMA_VERSION);
		root.put("provider", selection.ref().provider());
		root.put("model", selection.ref().model());
		ObjectNode options = root.putObject("options");
		selection.options().forEach(options::put);
		byte[] bytes = JSON.writeValueAsBytes(root);
		if (bytes.length > MAX_BYTES) {
			throw new IOException("Model selection is too large");
		}
		Path temp = Files.createTempFile(this.directory, ".selection-", ".tmp",
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		try {
			try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}
				channel.force(true);
			}
			checkDirectory();
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			try (FileChannel channel = FileChannel.open(this.directory, StandardOpenOption.READ)) {
				channel.force(true);
			}
		}
		finally {
			Files.deleteIfExists(temp);
		}
	}

	private Path file(String sessionId) throws IOException {
		if (sessionId == null || !sessionId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
			throw new IllegalArgumentException("Invalid session ID");
		}
		checkDirectory();
		return this.directory.resolve(sessionId + ".json");
	}

	private void checkDirectory() throws IOException {
		if (!Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Model selection directory is not a real directory");
		}
	}

	private static void checkFile(Path file) throws IOException {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Model selection is not a regular file");
		}
	}

	private static Set<String> fields(JsonNode node) {
		Set<String> fields = new HashSet<>();
		node.properties().forEach(field -> fields.add(field.getKey()));
		return fields;
	}

}
