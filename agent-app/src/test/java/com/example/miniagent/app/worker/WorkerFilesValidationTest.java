package com.example.miniagent.app.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The detached worker takes stop and resume authority from these files. Every unsafe shape must be refused
 * with its own reason and must be left exactly as found: validation never repairs, chmods or deletes.
 */
@Timeout(30)
class WorkerFilesValidationTest {
	private static final String UNSAFE_FILE = "Unsafe worker file";
	private static final String UNSAFE_ANCESTOR = "Unsafe worker ancestor";
	private static final String NOT_PRIVATE = "Worker directory is not UID-private";
	private static final String RECOVERY = "Worker storage recovery required";
	private static final String GROUP_READABLE = "rw-r--r--";
	private static final String WORLD_EXECUTABLE_DIRECTORY = "rwxr-xr-x";

	@TempDir Path temporary;

	private Path root;
	private Path home;

	@BeforeEach
	void rootsWithoutSymlinkedAncestors() throws IOException {
		this.root = this.temporary.toRealPath();
		this.home = this.root.resolve("home");
	}

	private WorkerFiles files() throws IOException {
		return new WorkerFiles(this.root, this.home);
	}

	private static void assertRefused(ThrowingCallable call, String message) {
		assertThatThrownBy(call).isInstanceOf(IOException.class).hasMessage(message);
	}

	private static void chmod(Path path, String permissions) throws IOException {
		Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
	}

	private static Set<PosixFilePermission> permissions(Path path) throws IOException {
		return Files.getPosixFilePermissions(path);
	}

	private static WorkerFiles.Metadata metadata(String workspace, String home) {
		return new WorkerFiles.Metadata(1, UUID.randomUUID().toString(), 4242, workspace, home, "STOPPED", "", "", "", "");
	}

	private static WorkerFiles.Request stopRequest() {
		return new WorkerFiles.Request(1, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "stop", "", null);
	}

	/** A private regular file made by the fixture, standing in for bytes a hostile or crashed process left. */
	private static Path plant(Path path, byte[] content) throws IOException {
		Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		return Files.write(path, content);
	}

	@ParameterizedTest
	@ValueSource(strings = { "workers", "worker", "inbox" })
	void directoryThatIsNotPrivateIsRefusedAndNotRepaired(String which) throws Exception {
		var files = files();
		Path target = switch (which) {
			case "workers" -> files.directory.getParent();
			case "worker" -> files.directory;
			default -> files.inbox;
		};
		chmod(target, WORLD_EXECUTABLE_DIRECTORY);
		assertRefused(this::files, NOT_PRIVATE);
		assertThat(permissions(target)).isEqualTo(PosixFilePermissions.fromString(WORLD_EXECUTABLE_DIRECTORY));
	}

	@Test
	void claimFileThatIsGroupReadableIsRefusedAndNotRepaired() throws Exception {
		Path claim = files().directory.resolve("claim.lock");
		chmod(claim, GROUP_READABLE);
		assertRefused(this::files, UNSAFE_FILE);
		assertThat(permissions(claim)).isEqualTo(PosixFilePermissions.fromString(GROUP_READABLE));
	}

	@Test
	void metadataThatIsGroupReadableIsRefusedAndNotRepaired() throws Exception {
		var files = files();
		files.write(metadata(files.workspace.toString(), files.home.toString()));
		byte[] before = Files.readAllBytes(files.metadata);
		chmod(files.metadata, GROUP_READABLE);
		assertRefused(files::read, UNSAFE_FILE);
		assertThat(permissions(files.metadata)).isEqualTo(PosixFilePermissions.fromString(GROUP_READABLE));
		assertThat(Files.readAllBytes(files.metadata)).isEqualTo(before);
	}

	@Test
	void requestThatIsGroupReadableIsRefusedAndKept() throws Exception {
		var files = files();
		var request = stopRequest();
		files.send(request);
		Path control = files.inbox.resolve(request.id() + ".json");
		byte[] before = Files.readAllBytes(control);
		chmod(control, GROUP_READABLE);
		assertRefused(() -> files.request(control), UNSAFE_FILE);
		assertThat(Files.readAllBytes(control)).isEqualTo(before);
	}

	@Test
	void exitMarkerThatIsGroupReadableIsRefusedAndNotRepaired() throws Exception {
		var files = files();
		files.exited(UUID.randomUUID().toString());
		Path exit = files.directory.resolve("exit");
		chmod(exit, GROUP_READABLE);
		assertRefused(files::exitInstance, UNSAFE_FILE);
		assertThat(permissions(exit)).isEqualTo(PosixFilePermissions.fromString(GROUP_READABLE));
	}

	@Test
	void claimFileWithAnyContentIsRefused() throws Exception {
		Path claim = files().directory.resolve("claim.lock");
		Files.write(claim, new byte[] { 'x' });
		assertRefused(this::files, UNSAFE_FILE);
		assertThat(Files.readAllBytes(claim)).containsExactly('x');
	}

	@Test
	void metadataOverTheSizeLimitIsRefusedUnread() throws Exception {
		var files = files();
		byte[] oversize = new byte[WorkerFiles.LIMIT + 1];
		plant(files.metadata, oversize);
		assertRefused(files::read, UNSAFE_FILE);
		assertThat(Files.readAllBytes(files.metadata)).isEqualTo(oversize);
	}

	@Test
	void requestOverTheSizeLimitIsRefusedUnread() throws Exception {
		var files = files();
		Path control = plant(files.inbox.resolve(UUID.randomUUID() + ".json"), new byte[WorkerFiles.LIMIT + 1]);
		assertRefused(() -> files.request(control), UNSAFE_FILE);
		assertThat(Files.size(control)).isEqualTo(WorkerFiles.LIMIT + 1);
	}

	@Test
	void exitMarkerLongerThanOneUuidIsRefused() throws Exception {
		var files = files();
		Path exit = plant(files.directory.resolve("exit"), new byte[37]);
		assertRefused(files::exitInstance, UNSAFE_FILE);
		assertThat(Files.size(exit)).isEqualTo(37);
	}

	@Test
	void symlinkedParentOfTheHomeIsRefusedBeforeAnythingIsCreated() throws Exception {
		Path real = Files.createDirectory(this.root.resolve("real"));
		Path link = Files.createSymbolicLink(this.root.resolve("link"), real);
		assertRefused(() -> new WorkerFiles(this.root, link.resolve("home")), UNSAFE_ANCESTOR);
		try (var entries = Files.list(real)) { assertThat(entries).isEmpty(); }
	}

	@Test
	void symlinkedHomeIsRefusedWithoutWritingThroughIt() throws Exception {
		Path real = Files.createDirectory(this.root.resolve("real-home"));
		Files.createSymbolicLink(this.home, real);
		assertRefused(this::files, UNSAFE_ANCESTOR);
		try (var entries = Files.list(real)) { assertThat(entries).isEmpty(); }
	}

	@Test
	void symlinkedWorkersDirectoryIsRefusedAndLeftInPlace() throws Exception {
		var files = files();
		Path workers = files.directory.getParent();
		Path moved = Files.move(workers, this.home.resolve("workers-moved"));
		Files.createSymbolicLink(workers, moved);
		assertRefused(this::files, UNSAFE_ANCESTOR);
		assertThat(Files.isSymbolicLink(workers)).isTrue();
	}

	@Test
	void unknownEntryInTheWorkerDirectoryRequiresRecoveryAndIsKept() throws Exception {
		Path stray = plant(files().directory.resolve("stray.txt"), new byte[] { 's' });
		assertRefused(this::files, RECOVERY);
		assertThat(Files.readAllBytes(stray)).containsExactly('s');
	}

	@Test
	void seventeenEntriesRequireRecoveryAndNothingIsDeleted() throws Exception {
		Path directory = files().directory;
		// claim.lock and inbox already count: 15 more make 17, one over the limit.
		for (int i = 0; i < 15; i++) plant(directory.resolve(".pending-" + i), new byte[0]);
		assertRefused(this::files, RECOVERY);
		try (var entries = Files.list(directory)) { assertThat(entries).hasSize(17); }
	}

	@Test
	void sixteenEntriesAreStillAcceptedAndUnpublishedBytesAreNeverAuthority() throws Exception {
		Path directory = files().directory;
		for (int i = 0; i < 14; i++) plant(directory.resolve(".pending-" + i), new byte[0]);
		assertThat(files().directory).isEqualTo(directory);
		try (var entries = Files.list(directory)) { assertThat(entries).hasSize(16); }
	}

	@ParameterizedTest
	// The Finder folder file, and the AppleDouble file macOS writes next to a file copied to a foreign volume.
	@ValueSource(strings = { ".DS_Store", "._state.json" })
	void anOsMetadataFileInTheWorkerDirectoryIsIgnoredAndKept(String name) throws Exception {
		Path metadata = files().directory.resolve(name);
		Files.createFile(metadata);
		assertThat(files().directory).isEqualTo(metadata.getParent());
		assertThat(metadata).exists();
	}

	@Test
	void metadataFilesDoNotCountTowardTheSixteenEntryLimit() throws Exception {
		Path directory = files().directory;
		for (int i = 0; i < 14; i++) plant(directory.resolve(".pending-" + i), new byte[0]);
		Files.createFile(directory.resolve(".DS_Store"));
		for (int i = 0; i < 8; i++) Files.createFile(directory.resolve("._copy-" + i));
		assertThat(files().directory).isEqualTo(directory);
		// One more real entry is still one over the limit, whatever metadata sits beside it.
		plant(directory.resolve(".pending-14"), new byte[0]);
		assertRefused(this::files, RECOVERY);
	}

	@Test
	void osMetadataInTheInboxNeitherFillsItNorBlocksASend() throws Exception {
		var files = files();
		Files.createFile(files.inbox.resolve(".DS_Store"));
		for (int i = 0; i < 300; i++) Files.createFile(files.inbox.resolve("._copy-" + i));
		var request = stopRequest();
		files.send(request);
		assertThat(files.request(files.inbox.resolve(request.id() + ".json"))).isEqualTo(request);
	}

	@Test
	void requestFileNamedAfterAnotherIdIsRefusedAndKept() throws Exception {
		var files = files();
		var request = stopRequest();
		files.send(request);
		Path renamed = Files.move(files.inbox.resolve(request.id() + ".json"),
				files.inbox.resolve(UUID.randomUUID() + ".json"));
		byte[] before = Files.readAllBytes(renamed);
		assertRefused(() -> files.request(renamed), "Request mismatch");
		assertThat(Files.readAllBytes(renamed)).isEqualTo(before);
	}

	@Test
	void requestFileOutsideTheUuidNamePatternRequiresRecovery() throws Exception {
		var files = files();
		Path notes = plant(files.inbox.resolve("notes.json"), new byte[] { '{', '}' });
		assertRefused(() -> files.request(notes), "Worker inbox recovery required");
		assertThat(notes).exists();
	}

	@Test
	void metadataOfAnotherWorkspaceIsRefusedAndNotRewritten() throws Exception {
		var files = files();
		files.write(metadata(this.root.resolve("elsewhere").toString(), files.home.toString()));
		byte[] before = Files.readAllBytes(files.metadata);
		assertRefused(this::files, "Foreign worker metadata");
		assertThat(Files.readAllBytes(files.metadata)).isEqualTo(before);
	}

	@Test
	void metadataOfAnotherHomeIsRefusedAndNotRewritten() throws Exception {
		var files = files();
		files.write(metadata(files.workspace.toString(), this.root.resolve("elsewhere").toString()));
		byte[] before = Files.readAllBytes(files.metadata);
		assertRefused(this::files, "Foreign worker metadata");
		assertThat(Files.readAllBytes(files.metadata)).isEqualTo(before);
	}

	@Test
	void metadataOfThisWorkspaceAndHomeIsAccepted() throws Exception {
		var files = files();
		var own = metadata(files.workspace.toString(), files.home.toString());
		files.write(own);
		assertThat(files().read()).isEqualTo(own);
	}
}
