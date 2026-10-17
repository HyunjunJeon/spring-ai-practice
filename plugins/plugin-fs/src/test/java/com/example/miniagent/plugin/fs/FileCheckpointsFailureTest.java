package com.example.miniagent.plugin.fs;

import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.example.miniagent.core.model.ToolContextKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** FileCheckpoints 의 실패 분기: 백업 실패는 쓰기를 막고, 기록 없는 afterWrite 는 거절되며, 되돌리기 실패는 남은 항목을 보존한다. */
class FileCheckpointsFailureTest {

	private static final String READ_ONLY = "r-x------";

	private static final String WRITABLE = "rwx------";

	@TempDir
	Path work;

	@TempDir
	Path state;

	@BeforeEach
	void canonicalPaths() throws Exception {
		this.work = this.work.toRealPath();
		this.state = this.state.toRealPath();
	}

	private FileTools tools(FileCheckpoints checkpoints) {
		return new FileTools(List.of(), 1024 * 1024, checkpoints);
	}

	private ToolContext turn(String turnId) {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString(), ToolContextKeys.SESSION_ID, "s",
				ToolContextKeys.TURN_ID, turnId));
	}

	private static void lock(Path directory) throws Exception {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(READ_ONLY));
		// 관리자 권한으로 돌면 권한 비트가 막지 못한다. 그때는 이 경로를 확인할 수 없으므로 건너뛴다.
		assumeFalse(Files.isWritable(directory), "directory permissions are not enforced for this user");
	}

	private static void unlock(Path directory) throws Exception {
		Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString(WRITABLE));
	}

	@Test
	void backupDirectoryThatCannotBeCreatedStopsTheWriteBeforeAnythingChanges() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		// 백업 디렉터리 자리에 일반 파일이 있으면 디렉터리를 만들 수 없다.
		Path blocked = Files.writeString(this.state.resolve("backups"), "not a directory");
		try (FileCheckpoints checkpoints = new FileCheckpoints(blocked)) {
			assertThatThrownBy(() -> tools(checkpoints).writeFile("notes.txt", "agent", turn("t1")))
				.isInstanceOf(UncheckedIOException.class).hasMessageContaining("Cannot checkpoint " + file);
			assertThatThrownBy(() -> tools(checkpoints).editFile("notes.txt", "original", "agent", turn("t1")))
				.isInstanceOf(UncheckedIOException.class).hasMessageContaining("Cannot checkpoint " + file);

			assertThat(Files.readString(file)).isEqualTo("original");
			try (Stream<Path> entries = Files.list(this.work)) {
				assertThat(entries).containsExactly(file);
			}
			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).isEmpty();
			assertThat(Files.readString(blocked)).isEqualTo("not a directory");
		}
	}

	@Test
	void backupCopyFailureStopsTheWriteAndLeavesNoPartialBackup() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		Path backups = Files.createDirectory(this.state.resolve("backups"));
		try (FileCheckpoints checkpoints = new FileCheckpoints(backups)) {
			lock(backups);
			try {
				assertThatThrownBy(() -> tools(checkpoints).writeFile("notes.txt", "agent", turn("t1")))
					.isInstanceOf(UncheckedIOException.class).hasMessageContaining("Cannot checkpoint " + file);
			}
			finally {
				unlock(backups);
			}

			assertThat(Files.readString(file)).isEqualTo("original");
			try (Stream<Path> entries = Files.list(this.work)) {
				assertThat(entries).containsExactly(file);
			}
			assertThat(backups).isEmptyDirectory();
			assertThat(checkpoints.undo("s")).isEmpty();
		}
	}

	@Test
	void newFileNeedsNoBackupSoAnUnusableBackupDirectoryDoesNotBlockIt() throws Exception {
		Path blocked = Files.writeString(this.state.resolve("backups"), "not a directory");
		try (FileCheckpoints checkpoints = new FileCheckpoints(blocked)) {
			tools(checkpoints).writeFile("fresh.txt", "created", turn("t1"));

			assertThat(Files.readString(this.work.resolve("fresh.txt"))).isEqualTo("created");
			assertThat(checkpoints.undo("s")).extracting(FileCheckpoints.Change::file)
				.containsExactly(this.work.resolve("fresh.txt"));
			assertThat(this.work.resolve("fresh.txt")).doesNotExist();
		}
	}

	@Test
	void afterWriteForAnUnknownSessionIsRefused() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		try (FileCheckpoints checkpoints = new FileCheckpoints(this.state.resolve("backups"))) {
			assertThatThrownBy(() -> checkpoints.afterWrite("s", "t1", file))
				.isInstanceOf(IllegalStateException.class).hasMessage("No checkpoint for t1: " + file);

			assertThat(checkpoints.undoWithOutcome("s").turnId()).isNull();
		}
	}

	@Test
	void afterWriteForAnotherTurnIsRefused() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		try (FileCheckpoints checkpoints = new FileCheckpoints(this.state.resolve("backups"))) {
			checkpoints.beforeWrite("s", "t1", file);

			assertThatThrownBy(() -> checkpoints.afterWrite("s", "t2", file))
				.isInstanceOf(IllegalStateException.class).hasMessage("No checkpoint for t2: " + file);
			// 거절된 afterWrite 는 t1 항목에 완료 기록을 남기지 않는다.
			Files.writeString(file, "agent");
			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).extracting(FileCheckpoints.Conflict::code)
				.containsExactly(FileCheckpoints.ConflictCode.UNKNOWN_COMPLETION);
		}
	}

	@Test
	void afterWriteForAFileWithoutABeforeEntryIsRefused() throws Exception {
		Path first = Files.writeString(this.work.resolve("first.txt"), "first");
		Path second = Files.writeString(this.work.resolve("second.txt"), "second");
		try (FileCheckpoints checkpoints = new FileCheckpoints(this.state.resolve("backups"))) {
			checkpoints.beforeWrite("s", "t1", first);
			Files.writeString(first, "agent");
			checkpoints.afterWrite("s", "t1", first);
			Files.writeString(second, "agent");

			assertThatThrownBy(() -> checkpoints.afterWrite("s", "t1", second))
				.isInstanceOf(IllegalStateException.class).hasMessage("No checkpoint for " + second);

			// first 의 기록은 그대로이고, 기록 없는 second 는 되돌림 대상이 아니다.
			assertThat(checkpoints.undo("s")).extracting(FileCheckpoints.Change::file).containsExactly(first);
			assertThat(Files.readString(first)).isEqualTo("first");
			assertThat(Files.readString(second)).isEqualTo("agent");
		}
	}

	@Test
	void afterWriteThatCannotObserveARegularFileKeepsTheEntryUnrecorded() throws Exception {
		Path file = this.work.resolve("fresh.txt");
		try (FileCheckpoints checkpoints = new FileCheckpoints(this.state.resolve("backups"))) {
			checkpoints.beforeWrite("s", "t1", file);
			Files.createDirectory(file);

			assertThatThrownBy(() -> checkpoints.afterWrite("s", "t1", file))
				.isInstanceOf(UncheckedIOException.class).hasMessage("Cannot record completed write " + file);

			// 완료를 증명하지 못했으므로 이 항목으로 디렉터리를 지우지 않는다.
			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).extracting(FileCheckpoints.Conflict::code)
				.containsExactly(FileCheckpoints.ConflictCode.UNKNOWN_COMPLETION);
			assertThat(file).isDirectory();
		}
	}

	@Test
	void failedRestoreKeepsTheRemainingEntriesAndRecordsWhatWasRestored() throws Exception {
		Path first = Files.writeString(this.work.resolve("first.txt"), "first original");
		Path locked = Files.createDirectory(this.work.resolve("locked"));
		Path second = Files.writeString(locked.resolve("second.txt"), "second original");
		Path third = Files.writeString(this.work.resolve("third.txt"), "third original");
		Path backups = this.state.resolve("backups");
		try (FileCheckpoints checkpoints = new FileCheckpoints(backups)) {
			FileTools tools = tools(checkpoints);
			tools.writeFile("first.txt", "first agent", turn("t1"));
			tools.writeFile("locked/second.txt", "second agent", turn("t1"));
			tools.writeFile("third.txt", "third agent", turn("t1"));

			lock(locked);
			try {
				assertThatThrownBy(() -> checkpoints.undoWithOutcome("s")).isInstanceOf(UncheckedIOException.class)
					.hasMessageContaining("Cannot undo " + second);
			}
			finally {
				unlock(locked);
			}

			// 실패 전에 되돌린 항목만 결과에 남고, 충돌로 취급되지는 않는다.
			var recorded = checkpoints.lastUndoOutcome("s");
			assertThat(recorded.turnId()).isEqualTo("t1");
			assertThat(recorded.restored()).extracting(FileCheckpoints.Change::file).containsExactly(first);
			assertThat(recorded.conflicts()).isEmpty();
			assertThat(Files.readString(first)).isEqualTo("first original");
			assertThat(Files.readString(second)).isEqualTo("second agent");

			// 권한을 되돌리면 남은 항목이 같은 턴으로 다시 되돌려진다.
			var retry = checkpoints.undoWithOutcome("s");
			assertThat(retry.turnId()).isEqualTo("t1");
			assertThat(retry.restored()).extracting(FileCheckpoints.Change::file).containsExactly(second, third);
			assertThat(retry.conflicts()).isEmpty();
			assertThat(Files.readString(second)).isEqualTo("second original");
			assertThat(Files.readString(third)).isEqualTo("third original");
			assertThat(backups).isEmptyDirectory();
			assertThat(checkpoints.undo("s")).isEmpty();
		}
	}

	@Test
	void failedDeleteOfANewFileKeepsItsEntryForTheNextUndo() throws Exception {
		Path locked = Files.createDirectory(this.work.resolve("locked"));
		Path created = locked.resolve("fresh.txt");
		try (FileCheckpoints checkpoints = new FileCheckpoints(this.state.resolve("backups"))) {
			tools(checkpoints).writeFile("locked/fresh.txt", "created", turn("t1"));

			lock(locked);
			try {
				assertThatThrownBy(() -> checkpoints.undoWithOutcome("s")).isInstanceOf(UncheckedIOException.class)
					.hasMessageContaining("Cannot undo " + created);
			}
			finally {
				unlock(locked);
			}

			assertThat(created).hasContent("created");
			assertThat(checkpoints.lastUndoOutcome("s").restored()).isEmpty();
			assertThat(checkpoints.undo("s")).extracting(FileCheckpoints.Change::file).containsExactly(created);
			assertThat(created).doesNotExist();
		}
	}

	@Test
	void failedBackupDoesNotSwallowTheNextUndoOfAnEarlierTurn() throws Exception {
		Path earlier = Files.writeString(this.work.resolve("earlier.txt"), "earlier original");
		Path later = Files.writeString(this.work.resolve("later.txt"), "later original");
		Path backups = this.state.resolve("backups");
		try (FileCheckpoints checkpoints = new FileCheckpoints(backups)) {
			FileTools tools = tools(checkpoints);
			tools.writeFile("earlier.txt", "earlier agent", turn("t1"));
			lock(backups);
			try {
				assertThatThrownBy(() -> tools.writeFile("later.txt", "later agent", turn("t2")))
					.isInstanceOf(UncheckedIOException.class);
			}
			finally {
				unlock(backups);
			}

			assertThat(checkpoints.undo("s")).extracting(FileCheckpoints.Change::file).containsExactly(earlier);
			assertThat(Files.readString(earlier)).isEqualTo("earlier original");
			assertThat(Files.readString(later)).isEqualTo("later original");
		}
	}

}
