package com.example.miniagent.core.artifact;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.TurnContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import static java.nio.charset.StandardCharsets.UTF_8;
import static com.example.miniagent.core.artifact.ArtifactStore.Code.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Authored for the parent's validation run; no network or model invocation. */
class ArtifactStoreTest {
	@TempDir Path temp;

	private Path home() throws Exception { return this.temp.toRealPath().resolve("home"); }
	private TurnContext turn(String session, Path cwd) {
		var binding = new ModelBinding(UUID.randomUUID(), new ModelRef("fixture", "unused"), "fixture", "fixture",
				mock(ChatModel.class), mock(ChatClient.class));
		return new TurnContext(session, UUID.randomUUID().toString(), cwd, binding);
	}
	private TurnContext turn() throws Exception { return turn("session", this.temp.toRealPath()); }
	private ArtifactStore store(ArtifactProperties properties) throws Exception {
		return new ArtifactStore(home(), properties);
	}
	private Path entry(String id) throws Exception {
		try (var paths = Files.walk(home().resolve("artifacts"), 2)) {
			return paths.filter(path -> path.getFileName().toString().equals(id)).findFirst().orElseThrow();
		}
	}
	private static void code(ArtifactStore.Code expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ArtifactStore.Failure.class,
				ex -> assertThat(ex.code()).isEqualTo(expected));
	}

	@Test void exactUtf8JsonEncodingSurvivesReopenAndUnicodePagination() throws Exception {
		var properties = new ArtifactProperties();
		properties.setPreviewChars(3);
		var authority = turn();
		String raw = "\"line\\n\\\"quoted\\\" \uD83D\uDE80 \uD55C\uAE00\"\n";
		var saved = store(properties).put(authority, raw);
		assertThat(Files.readAllBytes(entry(saved.artifactId()).resolve("payload"))).isEqualTo(raw.getBytes(UTF_8));
		assertThat(saved.sha256()).isEqualTo(HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(raw.getBytes(UTF_8))));
		assertThat(saved.bytes()).isEqualTo(raw.getBytes(UTF_8).length);
		assertThat(saved.preview()).isEqualTo(raw.substring(0, 3));
		assertThat(saved.truncated()).isTrue();
		var reopened = store(properties);
		StringBuilder reconstructed = new StringBuilder();
		int cursor = 0;
		ArtifactStore.Page page;
		do {
			page = reopened.read(authority, saved.artifactId(), cursor, 1);
			reconstructed.append(page.content());
			cursor = page.nextOffset();
		} while (page.truncated());
		assertThat(reconstructed.toString()).isEqualTo(raw);
		assertThat(reopened.read(authority, saved.artifactId(), cursor, 1).content()).isEmpty();
		try (var paths = Files.walk(home().resolve("artifacts"))) {
			for (Path path : paths.toList())
				assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString(
						Files.isDirectory(path) ? "rwx------" : "rw-------"));
		}
	}

	@Test void knownIdsDoNotCrossSessionsChildrenForksOrWorkspaces() throws Exception {
		var store = store(new ArtifactProperties());
		var authority = turn();
		var saved = store.put(authority, "private result");
		code(NOT_FOUND, () -> store.read(turn("child", authority.cwd()), saved.artifactId(), 0, 20));
		code(NOT_FOUND, () -> store.read(turn("fork", authority.cwd()), saved.artifactId(), 0, 20));
		Path other = Files.createDirectory(this.temp.resolve("other")).toRealPath();
		code(NOT_FOUND, () -> store.read(turn(authority.sessionId(), other), saved.artifactId(), 0, 20));
		assertThat(store.read(turn(authority.sessionId(), authority.cwd()), saved.artifactId(), 0, 20).content())
				.isEqualTo("private result");
		Path alias = this.temp.resolve("workspace-alias");
		Files.createSymbolicLink(alias, authority.cwd());
		assertThat(store.read(turn(authority.sessionId(), alias), saved.artifactId(), 0, 20).content())
				.isEqualTo("private result");
	}

	@Test void literalSearchPaginatesWithoutUnboundedLinesOrRegexInterpretation() throws Exception {
		var store = store(new ArtifactProperties());
		var authority = turn();
		String text = "\uD83D\uDE80" + "a.*b ".repeat(40) + "x".repeat(5000);
		var saved = store.put(authority, text);
		var first = store.search(authority, saved.artifactId(), ".*", 0, 20);
		var second = store.search(authority, saved.artifactId(), ".*", first.nextOffset(), 20);
		assertThat(first.matches()).hasSize(20);
		assertThat(first.matches().getFirst().offset()).isEqualTo(2);
		assertThat(first.truncated()).isTrue();
		assertThat(second.matches()).hasSize(20);
		assertThat(second.truncated()).isFalse();
		assertThat(second.nextOffset()).isEqualTo(text.codePointCount(0, text.length()));
		assertThat(first.matches()).allSatisfy(match ->
				assertThat(match.preview().codePointCount(0, match.preview().length())).isLessThanOrEqualTo(120));
		assertThat(store.search(authority, saved.artifactId(), "nomatch", 0, 20).matches()).isEmpty();
		assertThat(store.read(authority, saved.artifactId(), 0, 2048).content().codePointCount(0,
				store.read(authority, saved.artifactId(), 0, 2048).content().length())).isEqualTo(2048);
	}

	@Test void invalidIdsBoundsQueriesAndMissingAuthorityFailExplicitly() throws Exception {
		var store = store(new ArtifactProperties());
		var authority = turn();
		var saved = store.put(authority, "abc");
		for (String id : new String[] { "../auth/token", "/etc/passwd", UUID.randomUUID().toString().toUpperCase(), "" })
			code(INVALID_ID, () -> store.read(authority, id, 0, 1));
		code(INVALID_BOUNDS, () -> store.read(authority, saved.artifactId(), -1, 1));
		code(INVALID_BOUNDS, () -> store.read(authority, saved.artifactId(), 0, 0));
		code(INVALID_BOUNDS, () -> store.read(authority, saved.artifactId(), 0, 2049));
		code(INVALID_BOUNDS, () -> store.read(authority, saved.artifactId(), Integer.MAX_VALUE, 1));
		code(INVALID_BOUNDS, () -> store.search(authority, saved.artifactId(), "a", 0, 21));
		code(INVALID_QUERY, () -> store.search(authority, saved.artifactId(), "", 0, 1));
		code(INVALID_QUERY, () -> store.search(authority, saved.artifactId(), "x".repeat(257), 0, 1));
		code(INVALID_QUERY, () -> store.search(authority, saved.artifactId(), "\uD800", 0, 1));
		code(AUTHORITY_REQUIRED, () -> store.read(null, saved.artifactId(), 0, 1));
		code(CORRUPT, () -> store.put(authority, "\uD800"));
	}

	@Test void oversizeAndScopeQuotasRefuseWithoutChangingExistingArtifact() throws Exception {
		var properties = new ArtifactProperties();
		properties.setMaxArtifactBytes(4);
		properties.setMaxScopeBytes(6);
		properties.setMaxArtifacts(2);
		var store = store(properties);
		var authority = turn();
		code(TOO_LARGE, () -> store.put(authority, "\uD55C\uAE00"));
		var first = store.put(authority, "four");
		code(QUOTA, () -> store.put(authority, "big"));
		store.put(authority, "ok");
		code(QUOTA, () -> store.put(authority, ""));
		assertThat(store.read(authority, first.artifactId(), 0, 4).content()).isEqualTo("four");
		var sibling = turn("sibling", authority.cwd());
		assertThat(store.put(sibling, "four").bytes()).isEqualTo(4);
	}

	@Test void tamperedPayloadAndOwnerManifestAreRejected() throws Exception {
		var store = store(new ArtifactProperties());
		var authority = turn();
		var saved = store.put(authority, "original");
		Path payload = entry(saved.artifactId()).resolve("payload");
		Files.writeString(payload, "modified");
		code(CORRUPT, () -> store.read(authority, saved.artifactId(), 0, 10));
		var second = store.put(authority, "second");
		Path manifest = entry(second.artifactId()).resolve("manifest.json");
		var node = ArtifactStore.JSON.readTree(Files.readAllBytes(manifest));
		((tools.jackson.databind.node.ObjectNode) node).put("owner", "a".repeat(64));
		Files.write(manifest, ArtifactStore.JSON.writeValueAsBytes(node));
		code(CORRUPT, () -> store.read(authority, second.artifactId(), 0, 10));
	}

	@Test void symlinkPayloadDirectoryAndHomeAreNeverFollowed() throws Exception {
		var store = store(new ArtifactProperties());
		var authority = turn();
		var saved = store.put(authority, "original");
		Path payload = entry(saved.artifactId()).resolve("payload");
		Path secret = Files.writeString(this.temp.resolve("secret"), "not readable through artifacts");
		Files.delete(payload);
		Files.createSymbolicLink(payload, secret);
		code(UNSAFE_STORAGE, () -> store.read(authority, saved.artifactId(), 0, 20));
		Path ownedEntry = entry(saved.artifactId());
		Path moved = ownedEntry.resolveSibling("moved");
		Files.move(ownedEntry, moved);
		Files.createSymbolicLink(ownedEntry, moved);
		code(UNSAFE_STORAGE, () -> store.read(authority, saved.artifactId(), 0, 20));
		Path homeAlias = this.temp.resolve("home-alias");
		Files.createSymbolicLink(homeAlias, home());
		var unsafe = new ArtifactStore(homeAlias, new ArtifactProperties());
		code(UNSAFE_STORAGE, () -> unsafe.put(authority, "no"));
	}

	@Test void pendingPublicationAndOversizedManifestFailClosed() throws Exception {
		var store = store(new ArtifactProperties());
		var authority = turn();
		var saved = store.put(authority, "result");
		Path entry = entry(saved.artifactId());
		Path pending = entry.resolveSibling(".pending-" + UUID.randomUUID());
		Files.createDirectory(pending, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		code(RECOVERY_REQUIRED, () -> store.put(authority, "no"));
		assertThat(store.read(authority, saved.artifactId(), 0, 10).content()).isEqualTo("result");
		Files.writeString(entry.resolve("manifest.json"), "x".repeat(1025));
		code(CORRUPT, () -> store.read(authority, saved.artifactId(), 0, 10));
	}

	@Test void concurrentStoreInstancesCannotOverrunFinalQuotaSlot() throws Exception {
		var properties = new ArtifactProperties();
		properties.setMaxArtifacts(1);
		var first = store(properties);
		var second = store(properties);
		var authority = turn();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var a = executor.submit(() -> {
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				try { return first.put(authority, "a"); }
				catch (ArtifactStore.Failure ex) { return ex; }
			});
			var b = executor.submit(() -> {
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				try { return second.put(authority, "b"); }
				catch (ArtifactStore.Failure ex) { return ex; }
			});
			start.countDown();
			var results = java.util.List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
			assertThat(results.stream().filter(ArtifactStore.Descriptor.class::isInstance).count()).isEqualTo(1);
			assertThat(results.stream().filter(ArtifactStore.Failure.class::isInstance)
					.map(ArtifactStore.Failure.class::cast).map(ArtifactStore.Failure::code).toList()).containsExactly(QUOTA);
		}
	}

	@Test void existingProcessLockAndUnsafeModesRefusePublication() throws Exception {
		var store = store(new ArtifactProperties());
		var authority = turn();
		var saved = store.put(authority, "result");
		Path scope = entry(saved.artifactId()).getParent();
		try (var channel = FileChannel.open(scope.resolve("scope.lock"), StandardOpenOption.WRITE);
				var lock = channel.lock()) {
			code(LOCKED, () -> store.put(authority, "no"));
		}
		Files.setPosixFilePermissions(scope, PosixFilePermissions.fromString("rwxr-xr-x"));
		code(UNSAFE_STORAGE, () -> store.read(authority, saved.artifactId(), 0, 10));
		code(UNSAFE_STORAGE, () -> store.put(authority, "no"));
	}
}
