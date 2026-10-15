package com.example.miniagent.plugin.claude;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeAuthTest {

	private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@TempDir
	Path dir;

	@Test
	void credentialsUseFiveMinuteSafetyMargin() {
		assertThat(new ClaudeCredentials("access", "refresh", NOW.plus(Duration.ofMinutes(5)).toEpochMilli())
			.isExpiring(CLOCK)).isTrue();
		assertThat(new ClaudeCredentials("access", "refresh",
				NOW.plus(Duration.ofMinutes(5)).plusMillis(1).toEpochMilli()).isExpiring(CLOCK)).isFalse();
	}

	@Test
	void saveAndReadUsePrivateAtomicReplacement() throws Exception {
		Path store = this.dir.resolve("auth/claude.json");
		ClaudeAuth auth = new ClaudeAuth(store, refusingOAuth(), CLOCK);
		auth.save(credentials("old", Duration.ofHours(1)));
		Path original = Files.createLink(store.resolveSibling("original.json"), store);
		byte[] previous = Files.readAllBytes(original);
		Files.setPosixFilePermissions(store, PosixFilePermissions.fromString("rw-r--r--"));

		auth.save(credentials("new", Duration.ofHours(2)));

		assertThat(auth.read()).contains(credentials("new", Duration.ofHours(2)));
		assertThat(Files.readAllBytes(original)).isEqualTo(previous);
		assertThat(Files.isSameFile(store, original)).isFalse();
		assertThat(Files.getPosixFilePermissions(store)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
		Path lock = store.resolveSibling("claude.json.lock");
		assertThat(Files.getPosixFilePermissions(lock)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
		try (Stream<Path> files = Files.list(store.getParent())) {
			assertThat(files).containsExactlyInAnyOrder(store, original, lock);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void failedWritePreservesPreviousOrAbsentStoreAndCleansTemp(boolean existed) throws Exception {
		Path store = this.dir.resolve("claude.json");
		ClaudeAuth auth = new ClaudeAuth(store, refusingOAuth(), CLOCK);
		if (existed) {
			auth.save(credentials("old", Duration.ofHours(1)));
		}
		byte[] previous = existed ? Files.readAllBytes(store) : null;
		ClaudeCredentials invalid = new ClaudeCredentials("bad\ud800", "refresh", NOW.plusSeconds(60).toEpochMilli());

		assertThatThrownBy(() -> auth.save(invalid)).isInstanceOf(UncheckedIOException.class);

		if (existed) {
			assertThat(Files.readAllBytes(store)).isEqualTo(previous);
		}
		else {
			assertThat(store).doesNotExist();
		}
		try (Stream<Path> files = Files.list(this.dir)) {
			assertThat(files).containsExactlyElementsOf(existed
					? List.of(store.resolveSibling("claude.json.lock"), store)
					: List.of(store.resolveSibling("claude.json.lock")));
		}
	}

	@Test
	void failedAtomicMovePreservesDestinationAndCleansTemp() throws Exception {
		Path store = Files.createDirectory(this.dir.resolve("claude.json"));
		Path child = Files.writeString(store.resolve("child"), "original");
		ClaudeAuth auth = new ClaudeAuth(store, refusingOAuth(), CLOCK);

		assertThatThrownBy(() -> auth.save(credentials("new", Duration.ofHours(1))))
			.isInstanceOf(UncheckedIOException.class);

		assertThat(Files.readString(child)).isEqualTo("original");
		try (Stream<Path> files = Files.list(this.dir)) {
			assertThat(files).containsExactlyInAnyOrder(store, store.resolveSibling("claude.json.lock"));
		}
	}

	@Test
	void concurrentInstancesRefreshExactlyOnceAndPersistRotation() throws Exception {
		Path store = this.dir.resolve("claude.json");
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		ClaudeOAuth oauth = new ClaudeOAuth(Duration.ofSeconds(5)) {
			@Override
			public ClaudeCredentials refresh(String refreshToken) {
				calls.incrementAndGet();
				entered.countDown();
				try {
					assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
				return new ClaudeCredentials("new-access", "rotated-refresh",
						NOW.plus(Duration.ofHours(1)).toEpochMilli());
			}
		};
		ClaudeAuth first = new ClaudeAuth(store, oauth, CLOCK);
		ClaudeAuth second = new ClaudeAuth(store, oauth, CLOCK);
		first.save(new ClaudeCredentials("old-access", "old-refresh", NOW.plusSeconds(30).toEpochMilli()));
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<String> one = callers.submit(() -> access(first, ready, start));
			Future<String> two = callers.submit(() -> access(second, ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			release.countDown();

			assertThat(one.get(5, TimeUnit.SECONDS)).isEqualTo("new-access");
			assertThat(two.get(5, TimeUnit.SECONDS)).isEqualTo("new-access");
		}
		assertThat(calls).hasValue(1);
		assertThat(first.read()).contains(new ClaudeCredentials("new-access", "rotated-refresh",
				NOW.plus(Duration.ofHours(1)).toEpochMilli()));
	}

	private static String access(ClaudeAuth auth, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
		return auth.accessToken();
	}

	private static ClaudeCredentials credentials(String value, Duration lifetime) {
		return new ClaudeCredentials(value + "-access", value + "-refresh", NOW.plus(lifetime).toEpochMilli());
	}

	private static ClaudeOAuth refusingOAuth() {
		return new ClaudeOAuth(Duration.ofSeconds(5)) {
			@Override
			public ClaudeCredentials refresh(String refreshToken) {
				throw new AssertionError("refresh must not be called");
			}
		};
	}

}
