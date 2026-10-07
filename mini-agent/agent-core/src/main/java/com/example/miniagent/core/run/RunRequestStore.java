package com.example.miniagent.core.run;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Private request companion; the RunStore remains authoritative for lifecycle. */
public final class RunRequestStore {
	private static final int MAX_BYTES = 1024 * 1024;
	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
	private final Path directory;
	private final ToolEvidenceStore evidence;

	public record Request(String goal, ModelSelection selection, String baselineDigest) {}
	public ToolEvidenceStore evidence() { return this.evidence; }

	public RunRequestStore(Path agentHome) throws IOException {
		Path home = agentHome.toAbsolutePath().normalize();
		Files.createDirectories(home);
		checkDirectory(home);
		this.directory = home.toRealPath().resolve("run-requests");
		Files.createDirectories(this.directory, PosixFilePermissions.asFileAttribute(
				PosixFilePermissions.fromString("rwx------")));
		checkDirectory(this.directory);
		Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
		this.evidence = new ToolEvidenceStore(home);
	}

	/** Call only while holding the run writer, once at creation. */
	public void save(RunSpec spec, String goal, ModelSelection selection) throws IOException {
		save(spec, goal, selection, null);
	}

	public void save(RunSpec spec, String goal, ModelSelection selection, String baselineDigest) throws IOException {
		validateGoal(goal);
		validateSelection(spec, selection);
		Path target = file(spec);
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Run request already exists");
		}
		var root = JSON.createObjectNode();
		root.put("schemaVersion", 1);
		root.put("runId", spec.runId().value());
		root.put("conversationId", spec.conversationId());
		root.put("specDigest", digest(JSON.writeValueAsBytes(spec)));
		root.put("goal", goal);
		root.put("inputDigest", digest(goal.getBytes(UTF_8)));
		root.put("baselineDigest", baselineDigest);
		var selected = root.putObject("selection");
		selected.put("provider", selection.ref().provider());
		selected.put("model", selection.ref().model());
		var options = selected.putObject("options");
		selection.options().forEach(options::put);
		byte[] bytes = JSON.writeValueAsBytes(root);
		if (bytes.length > MAX_BYTES) {
			throw new IOException("Run request too large");
		}
		Path temp = Files.createTempFile(this.directory, ".request-", ".tmp",
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		try {
			try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}
				channel.force(true);
			}
			checkDirectory(this.directory);
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
			try (FileChannel channel = FileChannel.open(this.directory, StandardOpenOption.READ,
					LinkOption.NOFOLLOW_LINKS)) {
				channel.force(true);
			}
		}
		finally {
			Files.deleteIfExists(temp);
		}
	}

	public Request load(RunSpec spec) throws IOException {
		Path file = file(spec);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Missing or unsafe private run request");
		}
		byte[] bytes;
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			if (channel.size() > MAX_BYTES) {
				throw new IOException("Run request too large");
			}
			ByteBuffer buffer = ByteBuffer.allocate(MAX_BYTES + 1);
			while (buffer.hasRemaining() && channel.read(buffer) != -1) {}
			if (buffer.position() > MAX_BYTES) {
				throw new IOException("Run request too large");
			}
			bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
		}
		try {
			JsonNode root = JSON.readTree(bytes);
			requireFields(root, Set.of("schemaVersion", "runId", "conversationId",
					"specDigest", "goal", "inputDigest", "selection", "baselineDigest"));
			if (!root.path("schemaVersion").isInt() || root.path("schemaVersion").intValue() != 1
					|| !text(root, "runId").equals(spec.runId().value())
					|| !text(root, "conversationId").equals(spec.conversationId())
					|| !text(root, "specDigest").equals(digest(JSON.writeValueAsBytes(spec)))) {
				throw new IOException("Run request identity or schema mismatch");
			}
			String goal = text(root, "goal");
			validateGoal(goal);
			if (!text(root, "inputDigest").equals(digest(goal.getBytes(UTF_8)))) {
				throw new IOException("Run input digest mismatch");
			}
			JsonNode selected = root.path("selection");
			requireFields(selected, Set.of("provider", "model", "options"));
			if (!selected.path("options").isObject()) {
				throw new IOException("Invalid run options tree");
			}
			Map<String, String> options = new HashMap<>();
			for (var entry : selected.path("options").properties()) {
				if (!entry.getValue().isString()) {
					throw new IOException("Invalid run option value");
				}
				options.put(entry.getKey(), entry.getValue().asString());
			}
			ModelSelection selection = new ModelSelection(
					new ModelRef(text(selected, "provider"), text(selected, "model")), options);
			validateSelection(spec, selection);
			String baseline = root.path("baselineDigest").isNull() ? null : text(root, "baselineDigest");
			if (baseline != null) RunSpec.requireDigest(baseline);
			return new Request(goal, selection, baseline);
		}
		catch (RuntimeException ex) {
			throw new IOException("Corrupt private run request", ex);
		}
	}

	static void validateGoal(String goal) throws IOException {
		if (goal == null || goal.isBlank() || goal.getBytes(UTF_8).length > MAX_BYTES / 2
				|| goal.indexOf('\0') >= 0
				|| !UTF_8.newEncoder().canEncode(goal)) {
			throw new IOException("Invalid run goal");
		}
	}

	private static void validateSelection(RunSpec spec, ModelSelection selection) throws IOException {
		if (selection == null || !spec.model().equals(selection.ref())
				|| !spec.optionsFingerprint().equals(selection.optionsFingerprint())) {
			throw new IOException("Run selection differs from spec");
		}
	}

	static String digest(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	private Path file(RunSpec spec) throws IOException {
		checkDirectory(this.directory);
		return this.directory.resolve(spec.conversationId() + ".json");
	}

	private static void checkDirectory(Path path) throws IOException {
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Not a real request directory");
		}
	}

	private static void requireFields(JsonNode node, Set<String> expected) throws IOException {
		if (node == null || !node.isObject() || !node.properties().stream().map(Map.Entry::getKey)
				.collect(java.util.stream.Collectors.toSet()).equals(expected)) {
			throw new IOException("Invalid run request tree");
		}
	}

	private static String text(JsonNode node, String field) throws IOException {
		if (!node.path(field).isString()) {
			throw new IOException("Invalid run request field: " + field);
		}
		return node.path(field).asString();
	}
}
