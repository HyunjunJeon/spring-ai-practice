package com.example.miniagent.core.change;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assumptions.assumeThat;
import static com.example.miniagent.core.change.ChangeStore.Code.*;

/** Private state layout guards and the write corners of AtomicFiles, seen through the store and the helper. */
class AtomicFilesTest {
	@TempDir Path work;
	@TempDir Path state;
	private static final byte[] OLD = bytes("old\n");

	private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
	private Path home() { return this.state.resolve("home"); }
	private static void chmod(Path path, String mode) throws Exception {
		Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
	}
	private static void mode(Path path, String expected) throws Exception {
		assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString(expected));
	}
	private static void code(ChangeStore.Code expected, String message, ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
			assertThat(ex.code()).isEqualTo(expected);
			assertThat(ex).hasMessageContaining(message);
		});
	}
	private static List<String> names(Path directory) throws Exception {
		try (var paths = Files.list(directory)) { return paths.map(p -> p.getFileName().toString()).sorted().toList(); }
	}
	/** A store whose first acquire already created every state file and directory. */
	private FileChangeStore initialized() throws Exception {
		FileChangeStore store = new FileChangeStore(home(), this.work);
		try (var lease = store.acquire()) { assertThat(lease.read().changes()).isEmpty(); }
		return store;
	}
	private void assertAcquirable(FileChangeStore store) throws Exception {
		// A refused attempt must release the OS lock and leave no in-JVM lease behind.
		try (var lease = store.acquire()) { assertThat(lease.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN); }
	}
	/** Moves a state directory aside and puts a symlink to it in its place. */
	private Path swapForSymlink(Path directory) throws Exception {
		Path moved = this.state.resolve("moved-" + directory.getFileName());
		Files.move(directory, moved);
		Files.createSymbolicLink(directory, moved);
		return moved;
	}
	private Path privateDirectory() throws Exception {
		Path directory = this.state.toRealPath().resolve("out");
		Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		return directory;
	}

	@ParameterizedTest
	@ValueSource(strings = { "journal.log", "workspace.json", "workspace.lock" })
	void stateFileThatIsNotPrivateIsRefusedAndNeverRepaired(String name) throws Exception {
		FileChangeStore store = initialized();
		Path file = store.directory().resolve(name);
		byte[] saved = Files.readAllBytes(file);
		chmod(file, "rw-r--r--");
		code(CORRUPT, "not private", store::acquire);
		code(CORRUPT, "not private", store::read);
		mode(file, "rw-r--r--");
		assertThat(Files.readAllBytes(file)).isEqualTo(saved);
		chmod(file, "rw-------");
		assertAcquirable(store);
	}

	@ParameterizedTest
	@ValueSource(strings = { "manifests", "blobs", "staging", "evidence" })
	void stateSubdirectoryThatIsNotPrivateIsRefusedAndNeverRepaired(String name) throws Exception {
		FileChangeStore store = initialized();
		Path directory = store.directory().resolve(name);
		chmod(directory, "rwxr-xr-x");
		code(CORRUPT, "Change directory is not private", store::acquire);
		mode(directory, "rwxr-xr-x");
		chmod(directory, "rwx------");
		assertAcquirable(store);
	}

	@Test void storeAndChangesDirectoriesThatAreNotPrivateAreRefusedAtConstruction() throws Exception {
		FileChangeStore store = initialized();
		for (Path directory : List.of(store.directory().getParent(), store.directory())) {
			chmod(directory, "rwxr-xr-x");
			code(CORRUPT, "Change directory is not private", () -> new FileChangeStore(home(), this.work));
			mode(directory, "rwxr-xr-x");
			chmod(directory, "rwx------");
		}
		assertAcquirable(new FileChangeStore(home(), this.work));
	}

	@Test void storeDirectorySwappedForSymlinkIsRefusedWithoutFollowingIt() throws Exception {
		FileChangeStore store = initialized();
		byte[] journal = Files.readAllBytes(store.directory().resolve("journal.log"));
		List<String> entries = names(store.directory());
		Path moved = swapForSymlink(store.directory());
		code(CORRUPT, "Unsafe change directory", store::acquire);
		code(CORRUPT, "Unsafe change directory", store::read);
		assertThat(Files.isSymbolicLink(store.directory())).isTrue();
		assertThat(Files.readAllBytes(moved.resolve("journal.log"))).isEqualTo(journal);
		assertThat(names(moved)).isEqualTo(entries);
	}

	@Test void changesAncestorSwappedForSymlinkIsRefusedByLeaseAndByNewInstance() throws Exception {
		FileChangeStore store = initialized();
		byte[] journal = Files.readAllBytes(store.directory().resolve("journal.log"));
		Path changes = store.directory().getParent();
		Path moved = swapForSymlink(changes);
		code(CORRUPT, "Unsafe change directory", store::acquire);
		code(CORRUPT, "Unsafe change directory", () -> new FileChangeStore(home(), this.work));
		assertThat(Files.isSymbolicLink(changes)).isTrue();
		assertThat(Files.readAllBytes(moved.resolve(store.workspaceHash()).resolve("journal.log"))).isEqualTo(journal);
	}

	@Test void agentHomeSwappedForSymlinkIsRefusedAsAnAncestorOfTheStore() throws Exception {
		FileChangeStore store = initialized();
		Path moved = swapForSymlink(store.directory().getParent().getParent());
		code(CORRUPT, "Unsafe change directory", store::acquire);
		assertThat(Files.isSymbolicLink(store.directory().getParent().getParent())).isTrue();
		assertThat(moved.resolve("changes")).isDirectory();
	}

	@ParameterizedTest
	@ValueSource(strings = { "journal.log", "workspace.json", "workspace.lock" })
	void stateFileReplacedByDirectoryIsRefused(String name) throws Exception {
		FileChangeStore store = initialized();
		Path file = store.directory().resolve(name);
		Files.delete(file);
		Files.createDirectory(file);
		code(CORRUPT, "Unsafe change file", store::acquire);
		assertThat(file).isDirectory();
		assertThat(Files.isSymbolicLink(file)).isFalse();
	}

	@Test void workspaceLockReplacedByOutsideSymlinkIsRefusedAndTheTargetStaysUntouched() throws Exception {
		FileChangeStore store = initialized();
		Path lock = store.directory().resolve("workspace.lock");
		Path outside = this.state.resolve("outside.lock");
		Files.write(outside, OLD);
		chmod(outside, "rw-------");
		Files.delete(lock);
		Files.createSymbolicLink(lock, outside);
		code(CORRUPT, "Unsafe change file", store::acquire);
		assertThat(Files.isSymbolicLink(lock)).isTrue();
		assertThat(Files.readAllBytes(outside)).isEqualTo(OLD);
	}

	@Test void writeAboveLimitIsRefusedBeforeAnythingReachesDisk() throws Exception {
		Path directory = privateDirectory();
		Path target = directory.resolve("target");
		AtomicFiles.write(target, OLD, 32);
		code(LIMIT, "write limit", () -> AtomicFiles.write(target, new byte[33], 32));
		assertThat(Files.readAllBytes(target)).isEqualTo(OLD);
		assertThat(names(directory)).containsExactly("target");
		Path fresh = directory.resolve("fresh");
		code(LIMIT, "write limit", () -> AtomicFiles.write(fresh, new byte[33], 32));
		assertThat(names(directory)).containsExactly("target");
	}

	@Test void writeAtTheLimitReplacesTargetPrivatelyAndLeavesNoTemporaryFile() throws Exception {
		Path directory = privateDirectory();
		Path target = directory.resolve("target");
		AtomicFiles.write(target, OLD, 32);
		byte[] exact = new byte[32];
		Arrays.fill(exact, (byte) 'x');
		AtomicFiles.write(target, exact, 32);
		assertThat(Files.readAllBytes(target)).isEqualTo(exact);
		assertThat(AtomicFiles.read(target, 32)).isEqualTo(exact);
		mode(target, "rw-------");
		assertThat(names(directory)).containsExactly("target");
	}

	@Test void unwritableDirectoryFailsWithIoAndLeavesTheTargetUnchanged() throws Exception {
		Path directory = privateDirectory();
		Path target = directory.resolve("target");
		AtomicFiles.write(target, OLD, 32);
		chmod(directory, "r-x------");
		try {
			assumeThat(Files.isWritable(directory)).as("owner can still write a 0500 directory (running as root?)").isFalse();
			assertThatThrownBy(() -> AtomicFiles.write(target, bytes("new\n"), 32))
					.isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> assertThat(ex.code()).isEqualTo(IO))
					.hasMessage("Change persistence failed")
					.hasCauseInstanceOf(AccessDeniedException.class);
			assertThat(Files.readAllBytes(target)).isEqualTo(OLD);
		}
		finally { chmod(directory, "rwx------"); }
		assertThat(names(directory)).containsExactly("target");
	}

	@Test void failedWriteRetainsItsPendingTemporaryFileAndKeepsTheTarget() throws Exception {
		Path directory = privateDirectory();
		Path target = directory.resolve("target");
		AtomicFiles.write(target, OLD, 32);
		// An interrupted thread makes the channel write fail after the private temp file exists.
		Thread.currentThread().interrupt();
		try {
			assertThatThrownBy(() -> AtomicFiles.write(target, bytes("new\n"), 32))
					.isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> assertThat(ex.code()).isEqualTo(IO))
					.hasCauseInstanceOf(ClosedByInterruptException.class);
		}
		finally { Thread.interrupted(); }
		assertThat(Files.readAllBytes(target)).isEqualTo(OLD);
		List<String> names = names(directory);
		assertThat(names).hasSize(2).contains("target");
		String pending = names.stream().filter(name -> !name.equals("target")).findFirst().orElseThrow();
		assertThat(pending).startsWith(".pending-").endsWith(".tmp");
		mode(directory.resolve(pending), "rw-------");
		// The retained bytes do not wedge later writes.
		AtomicFiles.write(target, bytes("new\n"), 32);
		assertThat(Files.readAllBytes(target)).isEqualTo(bytes("new\n"));
		assertThat(names(directory)).contains(pending);
	}

	@Test void filesystemWithoutPosixAttributesFailsTypedAsUnsupported() throws Exception {
		Path zip = this.state.resolve("unsupported.zip");
		try (var fs = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true"))) {
			Path target = fs.getPath("/target");
			code(UNSUPPORTED_FILESYSTEM, "POSIX attributes", () -> AtomicFiles.write(target, OLD, 32));
			code(UNSUPPORTED_FILESYSTEM, "POSIX attributes", () -> AtomicFiles.read(target, 32));
			assertThat(target).doesNotExist();
		}
	}

	@Test void failureMapsUnsupportedAtomicityToUnsupportedFilesystemAndEverythingElseToIo() {
		var cause = new AtomicMoveNotSupportedException("a", "b", "reason");
		ChangeStore.Failure atomic = AtomicFiles.failure(cause);
		assertThat(atomic.code()).isEqualTo(UNSUPPORTED_FILESYSTEM);
		assertThat(atomic).hasCause(cause).hasMessage("Change persistence failed");
		assertThat(AtomicFiles.failure(new UnsupportedOperationException("no posix")).code()).isEqualTo(UNSUPPORTED_FILESYSTEM);
		assertThat(AtomicFiles.failure(new PathPolicy.Violation(PathPolicy.Code.UNSUPPORTED_FILESYSTEM, "p")).code())
				.isEqualTo(UNSUPPORTED_FILESYSTEM);
		assertThat(AtomicFiles.failure(new PathPolicy.Violation(PathPolicy.Code.EXCLUDED, "p")).code()).isEqualTo(IO);
		var plain = new IOException("disk");
		ChangeStore.Failure io = AtomicFiles.failure(plain);
		assertThat(io.code()).isEqualTo(IO);
		assertThat(io).hasCause(plain).hasMessage("Change persistence failed");
		var typed = new ChangeStore.Failure(LOCKED, "already typed");
		assertThat(AtomicFiles.failure(typed)).isSameAs(typed);
	}
}
