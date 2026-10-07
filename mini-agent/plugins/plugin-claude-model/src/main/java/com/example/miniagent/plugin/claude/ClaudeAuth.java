package com.example.miniagent.plugin.claude;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** mini-agent 자체 Claude OAuth 저장소와 갱신 조정. */
public class ClaudeAuth {

	private static final java.nio.file.attribute.FileAttribute<java.util.Set<java.nio.file.attribute.PosixFilePermission>> OWNER_ONLY =
			PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

	private final JsonMapper json = JsonMapper.builder().build();

	private final Path store;

	private final Path lockFile;

	private final ClaudeOAuth oauth;

	private final Clock clock;

	public ClaudeAuth(Path store, ClaudeOAuth oauth) {
		this(store, oauth, Clock.systemUTC());
	}

	ClaudeAuth(Path store, ClaudeOAuth oauth, Clock clock) {
		this.store = store;
		this.lockFile = store.resolveSibling(store.getFileName() + ".lock");
		this.oauth = oauth;
		this.clock = clock;
	}

	public String accessToken() {
		synchronized (ClaudeAuth.class) {
			return withLock(() -> {
				ClaudeCredentials credentials = readUnlocked()
					.orElseThrow(() -> new IllegalStateException("Claude login is required; run /login"));
				if (!credentials.isExpiring(this.clock)) {
					return credentials.accessToken();
				}
				ClaudeCredentials refreshed = this.oauth.refresh(credentials.refreshToken());
				saveUnlocked(refreshed);
				return refreshed.accessToken();
			});
		}
	}

	public ClaudeOAuth oauth() {
		return this.oauth;
	}

	public void save(ClaudeCredentials credentials) {
		synchronized (ClaudeAuth.class) {
			withLock(() -> {
				saveUnlocked(credentials);
				return null;
			});
		}
	}

	public Optional<ClaudeCredentials> read() {
		return readUnlocked();
	}

	public boolean logout() {
		synchronized (ClaudeAuth.class) {
			return withLock(() -> {
				try {
					return Files.deleteIfExists(this.store);
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			});
		}
	}

	public Path store() {
		return this.store;
	}

	/** Listing examines presence, not credentials or token expiry. */
	public boolean available() {
		return Files.isRegularFile(this.store);
	}

	private void saveUnlocked(ClaudeCredentials credentials) {
		ObjectNode node = this.json.createObjectNode();
		node.put("access_token", credentials.accessToken());
		node.put("refresh_token", credentials.refreshToken());
		node.put("expires_at", credentials.expiresAtMillis());
		Path parent = parent();
		try {
			Files.createDirectories(parent);
			Path temp = Files.createTempFile(parent, "." + this.store.getFileName() + ".", ".tmp", OWNER_ONLY);
			Throwable failure = null;
			try {
				Files.writeString(temp, this.json.writeValueAsString(node), StandardCharsets.UTF_8);
				Files.move(temp, this.store, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException | RuntimeException | Error ex) {
				failure = ex;
				throw ex;
			}
			finally {
				try {
					Files.deleteIfExists(temp);
				}
				catch (IOException | RuntimeException | Error ex) {
					if (failure != null) {
						failure.addSuppressed(ex);
					}
					else {
						throw ex;
					}
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private Optional<ClaudeCredentials> readUnlocked() {
		if (!Files.isRegularFile(this.store)) {
			return Optional.empty();
		}
		try {
			JsonNode root = this.json.readTree(Files.readString(this.store, StandardCharsets.UTF_8));
			String access = text(root, "access_token");
			String refresh = text(root, "refresh_token");
			long expiresAt = root.path("expires_at").asLong(0);
			if (access == null || access.isBlank() || refresh == null || refresh.isBlank() || expiresAt <= 0) {
				throw new IllegalStateException("Claude credential store is invalid");
			}
			return Optional.of(new ClaudeCredentials(access, refresh, expiresAt));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (RuntimeException ex) {
			if (ex instanceof IllegalStateException) {
				throw ex;
			}
			throw new IllegalStateException("Claude credential store is invalid");
		}
	}

	private <T> T withLock(LockedOperation<T> operation) {
		Path parent = parent();
		try {
			Files.createDirectories(parent);
			try {
				Files.createFile(this.lockFile, OWNER_ONLY);
			}
			catch (FileAlreadyExistsException ignored) {
			}
			Files.setPosixFilePermissions(this.lockFile, PosixFilePermissions.fromString("rw-------"));
			try (FileChannel channel = FileChannel.open(this.lockFile, StandardOpenOption.WRITE);
					FileLock ignored = channel.lock()) {
				return operation.run();
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private Path parent() {
		Path parent = this.store.toAbsolutePath().getParent();
		if (parent == null) {
			throw new IllegalStateException("Claude credential store must have a parent directory");
		}
		return parent;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value == null || value.isNull()) ? null : value.asString();
	}

	@FunctionalInterface
	private interface LockedOperation<T> {

		T run();

	}

}
