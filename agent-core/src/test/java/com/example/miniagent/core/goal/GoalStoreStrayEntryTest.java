package com.example.miniagent.core.goal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * goal-lesson:G8. Entries the store did not write, and the entry quota, must fail as IOException. OS metadata files
 * (.DS_Store, AppleDouble ._ files) are not such entries: they are ignored and left where they are.
 */
class GoalStoreStrayEntryTest {
	@TempDir Path home;
	@TempDir Path work;
	@org.junit.jupiter.api.BeforeEach void canonicalPaths() throws IOException {
		home = home.toRealPath(); work = work.toRealPath();
	}
	private GoalRecord pending() throws IOException {
		var spec = new GoalRecord.Spec(new GoalRequest("goal", List.of(new GoalRequest.Gate(
				List.of("/usr/bin/true"), 1000, 1024)), 6, 10000, null, 5),
				new ModelSelection(new ModelRef("codex", "gpt-6.1-sol")), "parent", work.toString(), RunId.random());
		return new GoalRecord(1, UUID.randomUUID().toString(), spec, GoalRecord.State.PENDING,
				GoalRecord.Reason.NONE, 0, GoalRecord.Phase.READY, null, List.of(), false, "");
	}
	private GoalService service(GoalStore store) {
		return new GoalService(store, () -> { throw new AssertionError("Agent resolved"); },
				() -> { throw new AssertionError("Executor resolved"); },
				() -> { throw new AssertionError("Runs resolved"); },
				() -> { throw new AssertionError("Changes resolved"); },
				() -> { throw new AssertionError("Verifier resolved"); });
	}
	private static void privateDirectory(Path path) throws IOException {
		Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "-leading-dash", "has space", "ünï" })
	void anEntryWithAnUnsafeNameFailsListAndCreateAsIOExceptionAndKeepsStoredGoalsUsable(String stray) throws Exception {
		var store = new GoalStore(home);
		var stored = pending();
		store.create(stored);
		Files.createFile(home.resolve("goals").resolve(stray));
		assertThatThrownBy(store::list).isInstanceOf(IOException.class).hasMessage("Goal storage recovery required");
		assertThatThrownBy(() -> store.create(pending())).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> service(store).list()).isInstanceOf(IOException.class);
		try (var entries = Files.list(home.resolve("goals"))) {
			assertThat(entries.map(p -> p.getFileName().toString()).toList())
					.containsExactlyInAnyOrder(stored.id(), stray);
		}
		// Reading and cancelling a stored goal never scans the directory.
		assertThat(store.read(stored.id())).isEqualTo(stored);
		assertThat(service(store).inspect(stored.id())).isEqualTo(stored);
		assertThat(service(store).cancel(stored.id()).state()).isEqualTo(GoalRecord.State.CANCELLED);
		assertThat(store.cancelled(stored.id())).isTrue();
	}

	@ParameterizedTest
	// The Finder folder file, and the AppleDouble file macOS writes next to a file copied to a foreign volume.
	@ValueSource(strings = { ".DS_Store", "._goals" })
	void anOsMetadataFileIsIgnoredByListAndCreateAndLeftInPlace(String metadata) throws Exception {
		var store = new GoalStore(home);
		var stored = pending();
		store.create(stored);
		Files.createFile(home.resolve("goals").resolve(metadata));
		assertThat(store.list()).containsExactly(stored.id());
		var next = pending();
		store.create(next);
		assertThat(store.list()).containsExactlyInAnyOrder(stored.id(), next.id());
		assertThat(service(store).list()).extracting(GoalRecord::id).containsExactlyInAnyOrder(stored.id(), next.id());
		assertThat(home.resolve("goals").resolve(metadata)).exists();
	}

	@ParameterizedTest
	@ValueSource(strings = { ".DS_Store", "._state.json" })
	void anOsMetadataFileInsideAGoalIsIgnoredByClaimAndWriteAndLeftInPlace(String metadata) throws Exception {
		var store = new GoalStore(home);
		var stored = pending();
		store.create(stored);
		Path goal = home.resolve("goals").resolve(stored.id());
		Files.createFile(goal.resolve(metadata));
		try (var claim = store.claim(stored.id())) {
			store.write(stored);
		}
		assertThat(store.read(stored.id())).isEqualTo(stored);
		assertThat(goal.resolve(metadata)).exists();
	}

	@Test void anotherUnknownFileInsideAGoalStillFailsClaimAndWriteClosed() throws Exception {
		var store = new GoalStore(home);
		var stored = pending();
		store.create(stored);
		Path goal = home.resolve("goals").resolve(stored.id());
		Files.createFile(goal.resolve("notes.txt"));
		assertThatThrownBy(() -> store.claim(stored.id())).isInstanceOf(IOException.class)
				.hasMessage("Goal storage recovery required");
		assertThatThrownBy(() -> store.write(stored)).isInstanceOf(IOException.class)
				.hasMessage("Goal storage recovery required");
		assertThat(goal.resolve("notes.txt")).exists();
	}

	@Test void metadataFilesDoNotCrowdAStrayFileOutOfTheGoalCheck() throws Exception {
		var store = new GoalStore(home);
		var stored = pending();
		store.create(stored);
		Path goal = home.resolve("goals").resolve(stored.id());
		Files.createFile(goal.resolve(".DS_Store"));
		for (int i = 0; i < 300; i++) Files.createFile(goal.resolve("._copy-" + i));
		Files.createFile(goal.resolve("notes.txt"));
		assertThatThrownBy(() -> store.claim(stored.id())).isInstanceOf(IOException.class)
				.hasMessage("Goal storage recovery required");
	}

	@Test void metadataFilesDoNotCountAgainstTheEntryQuotaOrHideAnEntryBeyondIt() throws Exception {
		var store = new GoalStore(home);
		var stored = pending();
		store.create(stored);
		Path goals = home.resolve("goals");
		for (int i = 1; i < 1024; i++) privateDirectory(goals.resolve("g" + i));
		Files.createFile(goals.resolve(".DS_Store"));
		for (int i = 0; i < 8; i++) Files.createFile(goals.resolve("._copy-" + i));
		assertThat(store.list()).hasSize(1024).contains(stored.id()).isSorted();
		// The 1025th real goal is the one entry past the quota. Metadata must neither be counted nor shadow it.
		privateDirectory(goals.resolve("g1024"));
		assertThatThrownBy(store::list).isInstanceOf(IOException.class).hasMessage("Goal quota exceeded");
	}

	@Test void aStrayEntryWithASafeNameAlreadyFailsAsIOException() throws Exception {
		var store = new GoalStore(home);
		store.create(pending());
		Files.createFile(home.resolve("goals").resolve("notes"));
		assertThatThrownBy(store::list).isInstanceOf(IOException.class).hasMessageContaining("Unsafe goal ancestor");
	}

	@Test void theEntryQuotaIsPinnedAtTheFirstRefusedGoalAndTheFirstUnlistableEntry() throws Exception {
		var store = new GoalStore(home);
		var stored = pending();
		store.create(stored);
		Path goals = home.resolve("goals");
		for (int i = 1; i < 1024; i++) privateDirectory(goals.resolve("g" + i));
		assertThat(store.list()).hasSize(1024).contains(stored.id()).isSorted();
		assertThatThrownBy(() -> store.create(pending())).isInstanceOf(IOException.class)
				.hasMessage("Goal exists or quota exceeded");
		privateDirectory(goals.resolve("g1024"));
		assertThatThrownBy(store::list).isInstanceOf(IOException.class).hasMessage("Goal quota exceeded");
		assertThatThrownBy(() -> service(store).list()).isInstanceOf(IOException.class).hasMessage("Goal quota exceeded");
		assertThatThrownBy(() -> store.create(pending())).isInstanceOf(IOException.class).hasMessage("Goal quota exceeded");
		assertThat(store.read(stored.id())).isEqualTo(stored);
		assertThat(service(store).cancel(stored.id()).state()).isEqualTo(GoalRecord.State.CANCELLED);
	}
}
