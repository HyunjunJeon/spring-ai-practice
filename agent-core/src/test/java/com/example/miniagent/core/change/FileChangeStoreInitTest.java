package com.example.miniagent.core.change;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.*;
import static com.example.miniagent.core.change.ChangeStore.Code.*;

/** First-use initialization: the identity file binds a state directory to one workspace. */
class FileChangeStoreInitTest {
	@TempDir Path work;
	@TempDir Path state;
	private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
	private static final byte[] BEFORE = bytes("old\n");
	private static final byte[] AFTER = bytes("new\n");
	private static final String IDENTITY_REFUSAL = "Workspace identity mismatch";

	private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
	private Path home() { return this.state.resolve("home"); }
	private FileChangeStore store() throws Exception { return new FileChangeStore(home(), this.work); }
	private ChangeSet proposal() throws Exception {
		Fingerprint before = new Fingerprint("source", EntryKind.REGULAR_FILE, Fingerprinter.sha256(BEFORE), BEFORE.length, 0644, null);
		Fingerprint after = new Fingerprint("source", EntryKind.REGULAR_FILE, Fingerprinter.sha256(AFTER), AFTER.length, 0644, null);
		Diff diff = new DiffRenderer(new PathPolicy(this.work, home(), List.of())).render(before, BEFORE, after, AFTER);
		return ChangeSet.propose(UUID.randomUUID(), new ChangeOwner("session", new RunId("run"), "attempt", "turn"),
				this.work.toRealPath().toString(), new ModelRef("fake", "test"),
				List.of(UUID.randomUUID()), Map.of("source", before), Map.of("source", after), List.of(diff), AT);
	}
	private static void refused(ChangeStore.Code code, String message, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
			assertThat(ex.code()).isEqualTo(code);
			assertThat(ex).hasMessage(message);
		});
	}
	private static List<String> names(Path directory) throws Exception {
		try (Stream<Path> paths = Files.list(directory)) { return paths.map(p -> p.getFileName().toString()).sorted().toList(); }
	}

	@Test void firstAcquireWritesTheIdentityAnEmptyJournalAndThePrivateLayout() throws Exception {
		FileChangeStore store = store();
		try (var lease = store.acquire()) { assertThat(lease.read().changes()).isEmpty(); }
		assertThat(Files.readString(store.directory().resolve("workspace.json")))
				.isEqualTo("{\"schemaVersion\":1,\"workspaceHash\":\"" + store.workspaceHash()
						+ "\",\"cwd\":\"" + this.work.toRealPath() + "\"}");
		assertThat(Files.size(store.directory().resolve("journal.log"))).isZero();
		assertThat(names(store.directory())).containsExactly("blobs", "evidence", "journal.log", "manifests",
				"staging", "workspace.json", "workspace.lock");
		for (String child : List.of("blobs", "evidence", "manifests", "staging")) {
			assertThat(Files.getPosixFilePermissions(store.directory().resolve(child)))
					.isEqualTo(PosixFilePermissions.fromString("rwx------"));
		}
	}

	@Test void strayEntryBesideNoIdentityRefusesInitializationAndIsPreserved() throws Exception {
		FileChangeStore store = store();
		Path stray = store.directory().resolve("stray.txt");
		Files.writeString(stray, "left by someone else");
		refused(CORRUPT, "Incomplete workspace initialization; bytes preserved", store::acquire);
		refused(CORRUPT, "Incomplete workspace initialization; bytes preserved", store::read);
		assertThat(Files.readString(stray)).isEqualTo("left by someone else");
		assertThat(names(store.directory())).containsExactly("stray.txt", "workspace.lock");
		// The failed attempts released the lock, so removing the stray entry lets initialization proceed.
		Files.delete(stray);
		try (var lease = store.acquire()) { assertThat(lease.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN); }
	}

	@Test void journalWithoutItsIdentityIsRefusedAndKeptByteForByte() throws Exception {
		FileChangeStore store = store();
		try (var lease = store.acquire()) { lease.propose(proposal()); }
		Path identity = store.directory().resolve("workspace.json");
		byte[] originalIdentity = Files.readAllBytes(identity);
		byte[] journal = Files.readAllBytes(store.directory().resolve("journal.log"));
		Files.delete(identity);
		refused(CORRUPT, "Incomplete workspace initialization; bytes preserved", store::acquire);
		assertThat(identity).doesNotExist();
		assertThat(Files.readAllBytes(store.directory().resolve("journal.log"))).isEqualTo(journal);
		Files.write(identity, originalIdentity);
		Files.setPosixFilePermissions(identity, PosixFilePermissions.fromString("rw-------"));
		assertThat(store().read().changes()).hasSize(1);
	}

	static Stream<Arguments> damagedIdentities() {
		return Stream.of(
				Arguments.of("empty file", "", CORRUPT, "Missing storage schema"),
				Arguments.of("array instead of object", "[]", CORRUPT, "Missing storage schema"),
				Arguments.of("object without schema", "{}", CORRUPT, "Missing storage schema"),
				Arguments.of("string schema", "{\"schemaVersion\":\"1\"}", CORRUPT, "Missing storage schema"),
				Arguments.of("fractional schema", "{\"schemaVersion\":1.5}", CORRUPT, "Missing storage schema"),
				Arguments.of("zero schema", "{\"schemaVersion\":0}", CORRUPT, "Invalid change schema"),
				Arguments.of("negative schema", "{\"schemaVersion\":-1}", CORRUPT, "Invalid change schema"),
				Arguments.of("future schema", "{\"schemaVersion\":2}", FUTURE_SCHEMA, "Future change schema"),
				Arguments.of("not json", "not json", CORRUPT, "Invalid change serialization"),
				Arguments.of("unknown field", "{\"schemaVersion\":1,\"unknown\":true}", CORRUPT, "Invalid change serialization"),
				Arguments.of("missing hash and cwd", "{\"schemaVersion\":1}", CORRUPT, "Invalid change serialization"),
				Arguments.of("malformed hash", "{\"schemaVersion\":1,\"workspaceHash\":\"zz\",\"cwd\":\"/tmp\"}",
						CORRUPT, "Invalid change serialization"),
				Arguments.of("relative cwd", "{\"schemaVersion\":1,\"workspaceHash\":\"" + "0".repeat(64) + "\",\"cwd\":\"relative\"}",
						CORRUPT, "Invalid change serialization"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("damagedIdentities")
	void damagedIdentityIsRefusedWithItsTypedCodeAndLeftUntouched(String damage, String content,
			ChangeStore.Code code, String message) throws Exception {
		FileChangeStore store = store();
		try (var lease = store.acquire()) { lease.propose(proposal()); }
		Path identity = store.directory().resolve("workspace.json");
		byte[] originalIdentity = Files.readAllBytes(identity);
		byte[] journal = Files.readAllBytes(store.directory().resolve("journal.log"));
		Files.writeString(identity, content);
		refused(code, message, store::acquire);
		refused(code, message, store::read);
		assertThat(Files.readString(identity)).as(damage).isEqualTo(content);
		assertThat(Files.readAllBytes(store.directory().resolve("journal.log"))).as(damage).isEqualTo(journal);
		// The failed attempts released the lock: restoring the identity makes the store usable again.
		Files.write(identity, originalIdentity);
		assertThat(store().read().changes()).hasSize(1);
	}

	@Test void identityBoundToAnotherWorkspaceIsRefusedEvenWithTheRightHash() throws Exception {
		FileChangeStore store = store();
		try (var lease = store.acquire()) { assertThat(lease.read().changes()).isEmpty(); }
		Path identity = store.directory().resolve("workspace.json");
		String elsewhere = Files.createDirectory(this.state.resolve("elsewhere")).toRealPath().toString();
		String moved = Files.readString(identity).replace(this.work.toRealPath().toString(), elsewhere);
		assertThat(moved).contains(elsewhere).contains(store.workspaceHash());
		Files.writeString(identity, moved);
		refused(IDENTITY_MISMATCH, IDENTITY_REFUSAL, store::acquire);
		assertThat(Files.readString(identity)).isEqualTo(moved);
	}

	@Test void agentHomeInsideTheWorkspaceIsRefusedBeforeAnythingIsCreated() throws Exception {
		refused(IDENTITY_MISMATCH, "Change state must be outside the workspace",
				() -> new FileChangeStore(this.work.resolve(".mini-agent"), this.work));
		assertThat(names(this.work)).isEmpty();
	}

	@Test void agentHomeEqualToTheWorkspaceIsRefusedBeforeAnythingIsCreated() throws Exception {
		refused(IDENTITY_MISMATCH, "Change state must be outside the workspace",
				() -> new FileChangeStore(this.work, this.work));
		assertThat(names(this.work)).isEmpty();
	}

	@Test void agentHomeReachedThroughASymlinkIntoTheWorkspaceIsRefused() throws Exception {
		Path inner = Files.createDirectory(this.work.resolve("inner"));
		Path alias = Files.createSymbolicLink(this.state.resolve("alias"), inner);
		refused(IDENTITY_MISMATCH, "Change state must be outside the workspace",
				() -> new FileChangeStore(alias.resolve("state"), this.work));
		assertThat(names(inner)).isEmpty();
	}

	@Test void agentHomeBesideTheWorkspaceWithASharedNamePrefixIsAccepted() throws Exception {
		Path project = Files.createDirectory(this.state.resolve("project"));
		Path sibling = this.state.resolve("project-state");
		FileChangeStore store = new FileChangeStore(sibling, project);
		assertThat(store.directory()).startsWith(sibling.toRealPath().resolve("changes"));
		try (var lease = store.acquire()) { assertThat(lease.read().changes()).isEmpty(); }
		assertThat(names(project)).isEmpty();
	}
}
