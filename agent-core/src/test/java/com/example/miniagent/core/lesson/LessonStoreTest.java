package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class LessonStoreTest {
	@TempDir Path root;
	@org.junit.jupiter.api.BeforeEach void canonicalPaths() throws IOException { root = root.toRealPath(); }

	@Test void constructionAndEmptyReadsLeaveStorageAbsent() throws Exception {
		Path home = root.resolve("home");
		var store = new LessonStore(home);
		assertThat(store.read()).isEmpty();
		assertThat(Files.exists(home)).isFalse();
	}

	@Test void corruptionIsNotReplacedWithAnEmptyStore() throws Exception {
		var f = new LessonFixture(root);
		f.accepted("parser lesson");
		Path state = root.resolve("home/lessons/lessons.json");
		Files.writeString(state, "{}");
		assertThatThrownBy(f.store::read).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> f.service.propose("verified-goal", f.content("parser another"), f.actor))
				.isInstanceOf(Exception.class);
		assertThat(Files.readString(state)).isEqualTo("{}");
	}

	@Test void symlinkHomeAndSymlinkStateAreRejected() throws Exception {
		var f = new LessonFixture(root);
		f.accepted("parser lesson");
		Path alias = root.resolve("alias");
		Files.createSymbolicLink(alias, root.resolve("home"));
		assertThatThrownBy(() -> new LessonStore(alias).read()).isInstanceOf(IOException.class);
		Path state = root.resolve("home/lessons/lessons.json");
		Path moved = root.resolve("outside.json");
		Files.move(state, moved);
		Files.createSymbolicLink(state, moved);
		assertThatThrownBy(f.store::read).isInstanceOf(IOException.class);
	}

	@Test void privateStableLockRejectsConcurrentOwnersBeforeOpeningAnotherDescriptor() throws Exception {
		var f = new LessonFixture(root);
		var accepted = f.accepted("parser lesson");
		Path lock = root.resolve("home/lessons/store.lock");
		Object inode = Files.getAttribute(lock, "unix:ino");
		assertThat(Files.getPosixFilePermissions(lock)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var finished = new CompletableFuture<Void>();
		Thread.ofVirtual().start(() -> {
			try {
				f.store.mutate(histories -> {
					entered.countDown();
					try {
						if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test release not delivered");
					}
					catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
					return histories.getFirst().getLast();
				});
				finished.complete(null);
			}
			catch (Throwable ex) { finished.completeExceptionally(ex); }
		});
		try {
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> f.service.promote(accepted.id(), 2, LessonRecord.Scope.PROJECT, f.actor))
					.isInstanceOf(IOException.class).hasMessageContaining("busy");
		}
		finally { release.countDown(); }
		finished.get(5, TimeUnit.SECONDS);
		f.service.promote(accepted.id(), 2, LessonRecord.Scope.PROJECT, f.actor);
		assertThat(Files.getAttribute(lock, "unix:ino")).isEqualTo(inode);
	}

	@Test void boundedHistoryDoesNotDiscardEarlierVersions() throws Exception {
		var f = new LessonFixture(root);
		var record = f.accepted("parser initial");
		for (int version = 2; version < LessonStore.MAX_VERSIONS; version++)
			record = f.service.update(record.id(), version, "verified-goal", f.content("parser version " + version), f.actor);
		var last = record;
		assertThatThrownBy(() -> f.service.update(last.id(), LessonStore.MAX_VERSIONS, "verified-goal",
				f.content("parser excess"), f.actor)).isInstanceOf(IOException.class);
		assertThat(f.service.history(last.id(), f.actor)).hasSize(LessonStore.MAX_VERSIONS);
	}

	@Test void entryQuotaFailsWithoutEvictingAcceptedKnowledge() throws Exception {
		var f = new LessonFixture(root);
		var accepted = f.accepted("parser retained");
		f.store.mutate(histories -> {
			for (int i = 1; i < LessonStore.MAX_ENTRIES; i++) {
				// Accepted copies: a full store evicts a candidate or retired lesson instead of refusing.
				var original = histories.getFirst().getLast();
				var copy = new LessonRecord(java.util.UUID.randomUUID().toString(), 1, original.state(), original.scope(),
						original.session(), original.workspace(), original.content(), original.proof(), original.creator(),
						original.operator(), original.operation(), null, original.generation(), original.createdAt());
				histories.add(java.util.List.of(copy));
			}
			return accepted;
		});
		assertThatThrownBy(() -> f.service.propose("verified-goal", f.content("parser excess"), f.actor))
				.isInstanceOf(IOException.class);
		assertThat(f.service.list(f.actor)).hasSize(LessonStore.MAX_ENTRIES);
		assertThat(f.service.lookup(accepted.id(), f.actor)).isEqualTo(accepted);
	}
}
