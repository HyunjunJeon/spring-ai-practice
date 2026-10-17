package com.example.miniagent.core.change;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static com.example.miniagent.core.change.ChangeStore.Code.*;

/** Crash windows of the very first acquire, reproduced by cutting the finished layout back to the crash state. */
class FileChangeStoreInitCrashTest {
	@TempDir Path work;
	@TempDir Path state;

	private FileChangeStore store() throws Exception { return new FileChangeStore(this.state.resolve("home"), this.work); }
	private static List<String> names(Path directory) throws Exception {
		try (Stream<Path> paths = Files.list(directory)) { return paths.map(p -> p.getFileName().toString()).sorted().toList(); }
	}

	/** The state a crash leaves after workspace.json was published but before journal.log was created. */
	private FileChangeStore identityOnlyStore() throws Exception {
		FileChangeStore store = store();
		try (var lease = store.acquire()) { assertThat(lease.read().changes()).isEmpty(); }
		Files.delete(store.directory().resolve("journal.log"));
		for (String child : List.of("manifests", "blobs", "staging", "evidence"))
			Files.delete(store.directory().resolve(child));
		assertThat(names(store.directory())).containsExactly("workspace.json", "workspace.lock");
		return store;
	}

	private static void unsafeJournal(FileChangeStore store, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
			assertThat(ex.code()).isEqualTo(CORRUPT);
			assertThat(ex).hasMessage("Unsafe change file: " + store.directory().resolve("journal.log"));
		});
	}

	@Test void journalLostFromAnInitializedStoreBlocksEveryLaterAcquireAndChangesNothing() throws Exception {
		FileChangeStore store = store();
		try (var lease = store.acquire()) { assertThat(lease.read().changes()).isEmpty(); }
		Files.delete(store.directory().resolve("journal.log"));
		byte[] identity = Files.readAllBytes(store.directory().resolve("workspace.json"));
		// A repeat is CORRUPT again, not LOCKED: the failed acquire released its lock.
		unsafeJournal(store, store::acquire);
		unsafeJournal(store, store::acquire);
		unsafeJournal(store, store()::read);
		assertThat(Files.readAllBytes(store.directory().resolve("workspace.json"))).isEqualTo(identity);
		assertThat(names(store.directory())).containsExactly("blobs", "evidence", "manifests", "staging", "workspace.json", "workspace.lock");
	}

	@Test void strayPendingTempFromACrashDuringTheIdentityWriteIsPreservedAndBlocksInitialization() throws Exception {
		FileChangeStore store = store();
		Path pending = store.directory().resolve(".pending-1234.tmp");
		Files.writeString(pending, "{\"schemaVersion\":1,");
		assertThatThrownBy(store::acquire).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
			assertThat(ex.code()).isEqualTo(CORRUPT);
			assertThat(ex).hasMessage("Incomplete workspace initialization; bytes preserved");
		});
		assertThat(Files.readString(pending)).isEqualTo("{\"schemaVersion\":1,");
		assertThat(names(store.directory())).containsExactly(".pending-1234.tmp", "workspace.lock");
	}

	@Test void acquireRecreatesTheEmptyJournalWhenOnlyTheIdentityWasWritten() throws Exception {
		FileChangeStore store = identityOnlyStore();
		byte[] identity = Files.readAllBytes(store.directory().resolve("workspace.json"));
		try (var lease = store.acquire()) {
			assertThat(lease.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
			assertThat(lease.read().changes()).isEmpty();
		}
		assertThat(Files.size(store.directory().resolve("journal.log"))).isZero();
		assertThat(Files.readAllBytes(store.directory().resolve("workspace.json"))).isEqualTo(identity);
	}

	@ParameterizedTest
	// The Finder folder file, and the AppleDouble file macOS writes next to a file copied to a foreign volume.
	@ValueSource(strings = { ".DS_Store", "._workspace.json" })
	void anOsMetadataFileInAFreshDirectoryDoesNotBlockInitialization(String metadata) throws Exception {
		FileChangeStore store = store();
		Files.createFile(store.directory().resolve(metadata));
		try (var lease = store.acquire()) { assertThat(lease.read().changes()).isEmpty(); }
		assertThat(names(store.directory())).containsExactlyInAnyOrder(metadata, "blobs", "evidence", "journal.log",
				"manifests", "staging", "workspace.json", "workspace.lock");
	}

	@ParameterizedTest
	@ValueSource(strings = { ".DS_Store", "._journal.log" })
	void acquireRecreatesTheEmptyJournalWhenOnlyTheIdentityAndOsMetadataExist(String metadata) throws Exception {
		FileChangeStore store = identityOnlyStore();
		byte[] identity = Files.readAllBytes(store.directory().resolve("workspace.json"));
		Files.createFile(store.directory().resolve(metadata));
		try (var lease = store.acquire()) {
			assertThat(lease.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
			assertThat(lease.read().changes()).isEmpty();
		}
		assertThat(Files.size(store.directory().resolve("journal.log"))).isZero();
		assertThat(Files.readAllBytes(store.directory().resolve("workspace.json"))).isEqualTo(identity);
		assertThat(store.directory().resolve(metadata)).exists();
	}

	@Test void anotherUnknownFileInAFreshDirectoryStillBlocksInitializationNextToMetadata() throws Exception {
		FileChangeStore store = store();
		Files.createFile(store.directory().resolve(".DS_Store"));
		Files.createFile(store.directory().resolve("notes.txt"));
		assertThatThrownBy(store::acquire).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
			assertThat(ex.code()).isEqualTo(CORRUPT);
			assertThat(ex).hasMessage("Incomplete workspace initialization; bytes preserved");
		});
		assertThat(names(store.directory())).containsExactly(".DS_Store", "notes.txt", "workspace.lock");
	}

	@Test void anotherUnknownFileNextToTheIdentityStillKeepsTheJournalFromBeingRecreated() throws Exception {
		FileChangeStore store = identityOnlyStore();
		Files.createFile(store.directory().resolve(".DS_Store"));
		Files.createFile(store.directory().resolve("notes.txt"));
		unsafeJournal(store, store::acquire);
		assertThat(names(store.directory())).containsExactly(".DS_Store", "notes.txt", "workspace.json", "workspace.lock");
	}
}
