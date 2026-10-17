package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.example.miniagent.core.change.Fingerprinter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;
import static com.example.miniagent.core.lesson.LessonRecord.Scope.*;
import static org.assertj.core.api.Assertions.*;

class SkillUsageStoreTest {
	static final Instant START = Instant.parse("2026-10-01T00:00:00.123456789Z");
	@TempDir Path root;
	Path home;
	String workspace;
	final AtomicReference<Instant> now = new AtomicReference<>(START);
	final Clock clock = clock(now::get);
	@BeforeEach void canonicalPaths() throws Exception {
		root = root.toRealPath();
		home = root.resolve("home");
		workspace = Files.createDirectory(root.resolve("workspace")).toString();
	}

	/** A clock the test moves by hand. */
	static Clock clock(Supplier<Instant> now) {
		return new Clock() {
			@Override public ZoneId getZone() { return ZoneOffset.UTC; }
			@Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
			@Override public Instant instant() { return now.get(); }
		};
	}
	/** Holds the store's lock from another thread until {@code release}. */
	static CompletableFuture<Void> hold(SkillUsageStore store, CountDownLatch entered, CountDownLatch release) {
		var finished = new CompletableFuture<Void>();
		Thread.ofVirtual().start(() -> {
			try {
				store.mutate(entries -> {
					entered.countDown();
					try {
						if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test release not delivered");
					}
					catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
					return null;
				});
				finished.complete(null);
			}
			catch (Throwable ex) { finished.completeExceptionally(ex); }
		});
		return finished;
	}
	static SkillUsage link(LessonRecord.Scope scope, String workspace, String name) {
		return new SkillUsage(scope, workspace, name, UUID.randomUUID().toString(), 2,
				Instant.parse("2026-09-01T10:00:00Z"), "b".repeat(64), 0, 0, null, null, null, null);
	}

	@Test void aMissingHomeReadsEmptyAndCreatesNothing() throws Exception {
		var snapshot = new SkillUsageStore(home, clock).read();
		assertThat(snapshot.trackingSince()).isNull();
		assertThat(snapshot.entries()).isEmpty();
		assertThat(home).doesNotExist();
		Files.createDirectory(home);
		assertThat(new SkillUsageStore(home, clock).read().entries()).isEmpty();
		assertThat(home.resolve("skill-usage")).doesNotExist();
	}

	@Test void everyFieldRoundTripsThroughAPrivateTwoFileStore() throws Exception {
		var project = new SkillUsage(PROJECT, workspace, "parser-fix", UUID.randomUUID().toString(), 3,
				Instant.parse("2026-09-01T10:00:00.000000001Z"), "c".repeat(64), 7, 2,
				Instant.parse("2026-09-20T08:30:00.5Z"), Instant.parse("2026-09-25T00:00:00Z"), "UNUSED",
				Instant.parse("2026-09-26T12:00:00Z"));
		var global = link(GLOBAL, null, "parser-fix");
		var store = new SkillUsageStore(home, clock);
		store.link(project);
		store.link(global);
		var reread = new SkillUsageStore(home, clock).read();
		assertThat(reread.entries()).containsExactly(project, global);
		assertThat(reread.trackingSince()).isEqualTo(START);
		Path directory = home.resolve("skill-usage");
		try (var files = Files.list(directory)) {
			assertThat(files.map(f -> f.getFileName().toString())).containsExactlyInAnyOrder("usage.json", "store.lock");
		}
		assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
		assertThat(Files.getPosixFilePermissions(directory.resolve("store.lock")))
				.isEqualTo(PosixFilePermissions.fromString("rw-------"));
		assertThat(Files.size(directory.resolve("store.lock"))).isZero();
	}

	@Test void trackingSinceIsTheClockAtCreationAndNeverMoves() throws Exception {
		var store = new SkillUsageStore(home, clock);
		store.link(link(GLOBAL, null, "first"));
		now.set(START.plusSeconds(86_400));
		store.link(link(GLOBAL, null, "second"));
		store.mutate(entries -> entries.removeIf(entry -> entry.name().equals("first")));
		var snapshot = store.read();
		assertThat(snapshot.trackingSince()).isEqualTo(START);
		assertThat(snapshot.entries()).extracting(SkillUsage::name).containsExactly("second");
	}

	/** Rewrites usage.json with a checksum that matches it, so only validation can refuse the edit. */
	private void reseal(Consumer<ObjectNode> edit) throws Exception {
		Path file = home.resolve("skill-usage/usage.json");
		var envelope = LessonStore.JSON.readValue(Files.readAllBytes(file), ObjectNode.class);
		edit.accept(envelope);
		var payload = LessonStore.JSON.createObjectNode();
		payload.set("trackingSince", envelope.get("trackingSince"));
		payload.set("entries", envelope.get("entries"));
		envelope.put("sha256", Fingerprinter.sha256(LessonStore.JSON.writeValueAsBytes(payload)));
		Files.write(file, LessonStore.JSON.writeValueAsBytes(envelope));
	}

	@Test void aSecondOwnerFailsFastAndTheLockInodeNeverChanges() throws Exception {
		var store = new SkillUsageStore(home, clock);
		store.link(link(GLOBAL, null, "parser-fix"));
		Path lock = home.resolve("skill-usage/store.lock");
		Object inode = Files.getAttribute(lock, "unix:ino");
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var finished = hold(new SkillUsageStore(home, clock), entered, release);
		try {
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> store.link(link(GLOBAL, null, "other"))).isInstanceOf(IOException.class)
					.hasMessage("Skill usage busy");
		}
		finally { release.countDown(); }
		finished.get(5, TimeUnit.SECONDS);
		store.link(link(GLOBAL, null, "other"));
		assertThat(store.read().entries()).hasSize(2);
		assertThat(Files.getAttribute(lock, "unix:ino")).isEqualTo(inode);
	}

	@Test void aChecksumOrSchemaMismatchIsRefusedAndLeftInPlace() throws Exception {
		var store = new SkillUsageStore(home, clock);
		store.link(link(GLOBAL, null, "parser-fix"));
		reseal(envelope -> {});
		assertThat(store.read().entries()).hasSize(1);
		Path file = home.resolve("skill-usage/usage.json");
		Files.writeString(file, Files.readString(file).replace("\"chosenLoads\":0", "\"chosenLoads\":5"));
		byte[] tampered = Files.readAllBytes(file);
		assertThatThrownBy(store::read).isInstanceOf(IOException.class).hasMessage("Corrupt skill usage checksum");
		assertThatThrownBy(() -> store.link(link(GLOBAL, null, "other"))).isInstanceOf(IOException.class)
				.hasMessage("Corrupt skill usage checksum");
		assertThat(Files.readAllBytes(file)).isEqualTo(tampered);
		reseal(envelope -> envelope.put("schema", 2));
		assertThatThrownBy(store::read).isInstanceOf(IOException.class).hasMessage("Corrupt skill usage checksum");
	}

	@Test void anyOtherFileInTheStoreRequiresRecovery() throws Exception {
		var store = new SkillUsageStore(home, clock);
		store.link(link(GLOBAL, null, "parser-fix"));
		Path directory = home.resolve("skill-usage");
		byte[] snapshot = Files.readAllBytes(directory.resolve("usage.json"));
		Files.createFile(directory.resolve("notes.txt"));
		assertThatThrownBy(store::read).isInstanceOf(IOException.class).hasMessage("Skill usage storage recovery required");
		assertThatThrownBy(() -> store.link(link(GLOBAL, null, "other"))).isInstanceOf(IOException.class)
				.hasMessage("Skill usage storage recovery required");
		assertThat(Files.readAllBytes(directory.resolve("usage.json"))).isEqualTo(snapshot);
		Files.delete(directory.resolve("notes.txt"));
		assertThat(store.read().entries()).extracting(SkillUsage::name).containsExactly("parser-fix");
	}

	@ParameterizedTest
	// The Finder folder file, and the AppleDouble file macOS writes next to a file copied to a foreign volume.
	@ValueSource(strings = { ".DS_Store", "._usage.json" })
	void anOsMetadataFileIsIgnoredByReadsAndLinksAndLeftInPlace(String metadata) throws Exception {
		var store = new SkillUsageStore(home, clock);
		store.link(link(GLOBAL, null, "parser-fix"));
		Path directory = home.resolve("skill-usage");
		Files.createFile(directory.resolve(metadata));
		assertThat(store.read().entries()).extracting(SkillUsage::name).containsExactly("parser-fix");
		store.link(link(GLOBAL, null, "other"));
		assertThat(store.read().entries()).extracting(SkillUsage::name).containsExactly("parser-fix", "other");
		assertThat(directory.resolve(metadata)).exists();
	}

	@Test void metadataFilesDoNotCrowdAStrayFileOutOfTheCheck() throws Exception {
		var store = new SkillUsageStore(home, clock);
		store.link(link(GLOBAL, null, "parser-fix"));
		Path directory = home.resolve("skill-usage");
		Files.createFile(directory.resolve(".DS_Store"));
		for (int i = 0; i < 300; i++) Files.createFile(directory.resolve("._copy-" + i));
		Files.createFile(directory.resolve("notes.txt"));
		assertThatThrownBy(store::read).isInstanceOf(IOException.class).hasMessage("Skill usage storage recovery required");
		assertThatThrownBy(() -> store.link(link(GLOBAL, null, "other"))).isInstanceOf(IOException.class)
				.hasMessage("Skill usage storage recovery required");
	}

	@Test void aNonprivateDirectoryALinkedHomeOrAnUnsafeLockIsRefused() throws Exception {
		var store = new SkillUsageStore(home, clock);
		store.link(link(GLOBAL, null, "parser-fix"));
		Path directory = home.resolve("skill-usage");
		Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
		assertThatThrownBy(store::read).isInstanceOf(IOException.class).hasMessage("Nonprivate skill usage");
		assertThatThrownBy(() -> store.link(link(GLOBAL, null, "other"))).isInstanceOf(IOException.class)
				.hasMessage("Nonprivate skill usage");
		Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
		Path alias = Files.createSymbolicLink(root.resolve("alias"), home);
		assertThatThrownBy(() -> new SkillUsageStore(alias, clock).read()).isInstanceOf(IOException.class)
				.hasMessage("Unsafe skill usage directory");
		Files.setPosixFilePermissions(directory.resolve("store.lock"), PosixFilePermissions.fromString("rw-r--r--"));
		assertThatThrownBy(store::read).isInstanceOf(IOException.class).hasMessage("Unsafe skill usage lock");
		assertThatThrownBy(() -> store.link(link(GLOBAL, null, "other"))).isInstanceOf(IOException.class)
				.hasMessage("Unsafe skill usage lock");
		Files.setPosixFilePermissions(directory.resolve("store.lock"), PosixFilePermissions.fromString("rw-------"));
		assertThat(store.read().entries()).hasSize(1);
	}

	@Test void theEntryLimitRefusesTheNextLinkWithoutEvictingOne() throws Exception {
		var store = new SkillUsageStore(home, clock);
		store.mutate(entries -> {
			for (int i = 0; i < SkillUsageStore.MAX_ENTRIES; i++) entries.add(link(GLOBAL, null, "skill-" + i));
			return null;
		});
		assertThat(SkillUsageStore.MAX_ENTRIES).isEqualTo(256);
		assertThatThrownBy(() -> store.link(link(GLOBAL, null, "one-more"))).isInstanceOf(IOException.class)
				.hasMessage("Skill usage entry limit");
		assertThat(store.read().entries()).hasSize(256).extracting(SkillUsage::name).doesNotContain("one-more");
	}

	@Test void linkRefusesAnIdentityThatIsAlreadyLinked() throws Exception {
		var store = new SkillUsageStore(home, clock);
		var first = link(PROJECT, workspace, "parser-fix");
		store.link(first);
		assertThatThrownBy(() -> store.link(link(PROJECT, workspace, "parser-fix"))).isInstanceOf(IOException.class)
				.hasMessage("Skill usage link exists");
		// Scope and workspace are part of the identity.
		store.link(link(GLOBAL, null, "parser-fix"));
		store.link(link(PROJECT, root.resolve("other").toString(), "parser-fix"));
		assertThat(store.read().entries()).hasSize(3).first().isEqualTo(first);
	}

	@Test void writesRefuseASessionScopeOrAWorkspaceThatDoesNotFitTheScope() throws Exception {
		var store = new SkillUsageStore(home, clock);
		store.link(link(GLOBAL, null, "parser-fix"));
		for (var invalid : List.of(link(SESSION, workspace, "a"), link(PROJECT, null, "b"), link(GLOBAL, workspace, "c"),
				link(PROJECT, "relative/workspace", "d"), link(PROJECT, workspace + "/../workspace", "e")))
			assertThatThrownBy(() -> store.link(invalid)).as(invalid.toString()).isInstanceOf(IOException.class)
					.hasMessage("Invalid skill usage scope");
		var duplicate = link(GLOBAL, null, "twin");
		assertThatThrownBy(() -> store.mutate(entries -> entries.addAll(List.of(duplicate, duplicate))))
				.isInstanceOf(IOException.class).hasMessage("Duplicate skill usage identity");
		assertThat(store.read().entries()).extracting(SkillUsage::name).containsExactly("parser-fix");
	}

	@Test void readsRefuseAnInvalidEntryEvenUnderAMatchingChecksum() throws Exception {
		var store = new SkillUsageStore(home, clock);
		store.link(link(GLOBAL, null, "parser-fix"));
		reseal(envelope -> envelope.withObject("/entries/0").put("scope", "SESSION"));
		assertThatThrownBy(store::read).isInstanceOf(IOException.class).hasMessage("Invalid skill usage scope");
		reseal(envelope -> envelope.withObject("/entries/0").put("scope", "PROJECT"));
		assertThatThrownBy(store::read).isInstanceOf(IOException.class).hasMessage("Invalid skill usage scope");
		reseal(envelope -> envelope.withArray("entries")
				.add(envelope.withObject("/entries/0").put("scope", "GLOBAL").deepCopy()));
		assertThatThrownBy(store::read).isInstanceOf(IOException.class).hasMessage("Duplicate skill usage identity");
		reseal(envelope -> envelope.withArray("entries").remove(1));
		assertThat(store.read().entries()).hasSize(1);
		reseal(envelope -> envelope.withObject("/entries/0").putNull("name"));
		assertThatThrownBy(store::read).isInstanceOf(IOException.class).hasMessage("Corrupt skill usage");
		reseal(envelope -> envelope.withObject("/entries/0").put("name", "parser-fix"));
		reseal(envelope -> envelope.putNull("trackingSince"));
		assertThatThrownBy(store::read).isInstanceOf(IOException.class).hasMessage("Corrupt skill usage");
	}
}
