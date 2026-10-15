package com.example.miniagent.plugin.fs;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.example.miniagent.core.model.ToolContextKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileCheckpointsTest {

	@TempDir
	Path work;

	@TempDir
	Path state;

	@BeforeEach
	void canonicalPaths() throws Exception {
		this.work = this.work.toRealPath();
		this.state = this.state.toRealPath();
	}

	@Test
	void humanEditAfterLegacyWriteIsNotClobbered() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		try (FileCheckpoints checkpoints = checkpoints()) {
			tools(checkpoints).writeFile("notes.txt", "agent", turn("t1"));
			Files.writeString(file, "human");

			var outcome = checkpoints.undoWithOutcome("s");

			assertThat(outcome.turnId()).isEqualTo("t1");
			assertThat(outcome.restored()).isEmpty();
			assertThat(outcome.conflicts()).singleElement().satisfies(conflict -> {
				assertThat(conflict.change().file()).isEqualTo(file);
				assertThat(conflict.code()).isEqualTo(FileCheckpoints.ConflictCode.AFTER_CHANGED);
				assertThat(Files.exists(conflict.change().backup())).isTrue();
			});
			assertThat(Files.readString(file)).isEqualTo("human");
			assertThat(checkpoints.conflicts("s")).isEqualTo(outcome.conflicts());
			assertThat(checkpoints.lastUndoOutcome("s")).isEqualTo(outcome);
			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).hasSize(1);
			assertThat(Files.readString(file)).isEqualTo("human");
		}
	}

	@Test
	void repeatedWritesUpdateAfterButKeepOriginalBefore() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		try (FileCheckpoints checkpoints = checkpoints()) {
			FileTools tools = tools(checkpoints);
			tools.writeFile("notes.txt", "first", turn("t1"));
			tools.writeFile("notes.txt", "second", turn("t1"));
			tools.editFile("notes.txt", "second", "third", turn("t1"));
			try (Stream<Path> backups = Files.list(this.state.resolve("backups"))) {
				assertThat(backups).hasSize(1);
			}

			assertThat(checkpoints.undo("s")).extracting(FileCheckpoints.Change::file).containsExactly(file);
			assertThat(Files.readString(file)).isEqualTo("original");
			assertThat(checkpoints.conflicts("s")).isEmpty();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void failedWriteDoesNotOverwriteHumanContent(boolean existed) throws Exception {
		Path file = this.work.resolve("notes.txt");
		if (existed) Files.writeString(file, "original");
		try (FileCheckpoints checkpoints = checkpoints()) {
			assertThatThrownBy(() -> tools(checkpoints).writeFile("notes.txt", "bad\ud800", turn("t1")))
				.isInstanceOf(UncheckedIOException.class);
			if (existed) assertThat(Files.readString(file)).isEqualTo("original");
			else assertThat(file).doesNotExist();
			Files.writeString(file, "human");

			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).extracting(FileCheckpoints.Conflict::code)
				.containsExactly(FileCheckpoints.ConflictCode.UNKNOWN_COMPLETION);
			assertThat(Files.readString(file)).isEqualTo("human");
			try (Stream<Path> entries = Files.list(this.work)) {
				assertThat(entries).containsExactly(file);
			}
		}
	}

	@Test
	void beforeWriteAloneNeverAuthorizesUndo() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		try (FileCheckpoints checkpoints = checkpoints()) {
			checkpoints.beforeWrite("s", "t1", file);
			Files.writeString(file, "uncompleted or human");

			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).extracting(FileCheckpoints.Conflict::code)
				.containsExactly(FileCheckpoints.ConflictCode.UNKNOWN_COMPLETION);
			assertThat(Files.readString(file)).isEqualTo("uncompleted or human");
		}
	}

	@Test
	void failedRepeatedWriteInvalidatesPreviousSuccessfulAfter() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		try (FileCheckpoints checkpoints = checkpoints()) {
			FileTools tools = tools(checkpoints);
			tools.writeFile("notes.txt", "first", turn("t1"));
			assertThatThrownBy(() -> tools.writeFile("notes.txt", "\ud800", turn("t1")))
				.isInstanceOf(UncheckedIOException.class);

			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).extracting(FileCheckpoints.Conflict::code)
				.containsExactly(FileCheckpoints.ConflictCode.UNKNOWN_COMPLETION);
			assertThat(Files.readString(file)).isEqualTo("first");
		}
	}

	@Test
	void matchingUndoRestoresOriginalModeAndDeletesNewFile() throws Exception {
		Path file = Files.writeString(this.work.resolve("run.sh"), "original\r\n");
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-x---"));
		try (FileCheckpoints checkpoints = checkpoints()) {
			FileTools tools = tools(checkpoints);
			tools.writeFile("run.sh", "replacement", turn("t1"));
			tools.writeFile("new.txt", "new", turn("t1"));
			tools.editFile("new.txt", "new", "newer", turn("t1"));

			var outcome = checkpoints.undoWithOutcome("s");

			assertThat(outcome.restored()).extracting(FileCheckpoints.Change::file)
				.containsExactly(file, this.work.resolve("new.txt"));
			assertThat(outcome.conflicts()).isEmpty();
			assertThat(Files.readString(file)).isEqualTo("original\r\n");
			assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rwxr-x---");
			assertThat(this.work.resolve("new.txt")).doesNotExist();
			assertThat(this.state.resolve("backups")).isEmptyDirectory();
			try (Stream<Path> entries = Files.list(this.work)) {
				assertThat(entries).containsExactly(file);
			}
		}
	}

	@Test
	void unrelatedMatchingFilesRestoreAlongsideRetainedConflict() throws Exception {
		Path conflict = Files.writeString(this.work.resolve("conflict.txt"), "original conflict");
		Path matching = Files.writeString(this.work.resolve("matching.txt"), "original matching");
		try (FileCheckpoints checkpoints = checkpoints()) {
			FileTools tools = tools(checkpoints);
			tools.writeFile("conflict.txt", "agent conflict", turn("t1"));
			tools.writeFile("matching.txt", "agent matching", turn("t1"));
			tools.writeFile("new.txt", "new", turn("t1"));
			Files.writeString(conflict, "human");

			var outcome = checkpoints.undoWithOutcome("s");

			assertThat(outcome.restored()).extracting(FileCheckpoints.Change::file)
				.containsExactly(matching, this.work.resolve("new.txt"));
			assertThat(outcome.conflicts()).extracting(c -> c.change().file()).containsExactly(conflict);
			assertThat(Files.readString(conflict)).isEqualTo("human");
			assertThat(Files.readString(matching)).isEqualTo("original matching");
			assertThat(this.work.resolve("new.txt")).doesNotExist();
			Files.writeString(matching, "human after restore");
			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(Files.readString(matching)).isEqualTo("human after restore");
		}
	}

	@Test
	void equalBytesAtReplacedPathAreStillAConflict() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		try (FileCheckpoints checkpoints = checkpoints()) {
			tools(checkpoints).writeFile("notes.txt", "agent", turn("t1"));
			Path replacement = Files.writeString(this.work.resolve("replacement"), "agent");
			Files.setPosixFilePermissions(replacement, Files.getPosixFilePermissions(file));
			Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).extracting(FileCheckpoints.Conflict::code)
				.containsExactly(FileCheckpoints.ConflictCode.AFTER_CHANGED);
			assertThat(Files.readString(file)).isEqualTo("agent");
		}
	}

	@Test
	void humanModeChangeIsPreserved() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		try (FileCheckpoints checkpoints = checkpoints()) {
			tools(checkpoints).writeFile("notes.txt", "agent", turn("t1"));
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));

			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).extracting(FileCheckpoints.Conflict::code)
				.containsExactly(FileCheckpoints.ConflictCode.AFTER_CHANGED);
			assertThat(Files.readString(file)).isEqualTo("agent");
			assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rwx------");
		}
	}

	@Test
	void symlinkReplacementNeverOverwritesTarget() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		Path human = Files.writeString(this.work.resolve("human.txt"), "human");
		try (FileCheckpoints checkpoints = checkpoints()) {
			tools(checkpoints).writeFile("notes.txt", "agent", turn("t1"));
			Files.delete(file);
			Files.createSymbolicLink(file, human);

			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).extracting(FileCheckpoints.Conflict::code)
				.containsExactly(FileCheckpoints.ConflictCode.UNSAFE_PATH);
			assertThat(Files.isSymbolicLink(file)).isTrue();
			assertThat(Files.readString(human)).isEqualTo("human");
		}
	}

	@Test
	void symlinkedParentIsPreservedEvenWhenLeafIsUnchanged() throws Exception {
		Path parent = Files.createDirectory(this.work.resolve("nested"));
		Files.writeString(parent.resolve("notes.txt"), "original");
		try (FileCheckpoints checkpoints = checkpoints()) {
			tools(checkpoints).writeFile("nested/notes.txt", "agent", turn("t1"));
			Path held = this.work.resolve("held");
			Files.move(parent, held);
			Files.createSymbolicLink(parent, held);

			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).extracting(FileCheckpoints.Conflict::code)
				.containsExactly(FileCheckpoints.ConflictCode.UNSAFE_PATH);
			assertThat(Files.readString(held.resolve("notes.txt"))).isEqualTo("agent");
			assertThat(Files.isSymbolicLink(parent)).isTrue();
		}
	}

	@Test
	void missingLiveFileIsNotRecreated() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		try (FileCheckpoints checkpoints = checkpoints()) {
			tools(checkpoints).writeFile("notes.txt", "agent", turn("t1"));
			Files.delete(file);

			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).extracting(FileCheckpoints.Conflict::code)
				.containsExactly(FileCheckpoints.ConflictCode.UNSAFE_PATH);
			assertThat(file).doesNotExist();
		}
	}

	@Test
	void humanAddedDirectoryContentsAreNotDeleted() throws Exception {
		Path file = this.work.resolve("new.txt");
		try (FileCheckpoints checkpoints = checkpoints()) {
			tools(checkpoints).writeFile("new.txt", "agent", turn("t1"));
			Files.delete(file);
			Files.createDirectory(file);
			Path human = Files.writeString(file.resolve("human.txt"), "human");

			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).extracting(FileCheckpoints.Conflict::code)
				.containsExactly(FileCheckpoints.ConflictCode.AFTER_CHANGED);
			assertThat(Files.readString(human)).isEqualTo("human");
		}
	}

	@Test
	void changedBackupIsNotPublishedAndCloseDoesNotFollowBackupLink() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		Path human = Files.writeString(this.work.resolve("human.txt"), "human");
		Path backups = this.state.resolve("backups");
		try (FileCheckpoints checkpoints = checkpoints()) {
			tools(checkpoints).writeFile("notes.txt", "agent", turn("t1"));
			Path backup = backups.resolve("1.bak");
			Files.delete(backup);
			Files.createSymbolicLink(backup, human);

			assertThat(checkpoints.undo("s")).isEmpty();
			assertThat(checkpoints.conflicts("s")).extracting(FileCheckpoints.Conflict::code)
				.containsExactly(FileCheckpoints.ConflictCode.BACKUP_CHANGED);
			assertThat(Files.readString(file)).isEqualTo("agent");
			assertThat(Files.readString(human)).isEqualTo("human");
		}
		assertThat(backups).doesNotExist();
		assertThat(Files.readString(human)).isEqualTo("human");
	}

	@Test
	void existingCheckpointDirectoryAliasUsesPhysicalBackups() throws Exception {
		Path alias = Files.createSymbolicLink(this.work.resolve("state-alias"), this.state);
		Path file = Files.writeString(this.work.resolve("notes.txt"), "original");
		try (FileCheckpoints checkpoints = new FileCheckpoints(alias.resolve("backups"))) {
			tools(checkpoints).writeFile("notes.txt", "agent", turn("t1"));

			assertThat(checkpoints.undo("s")).extracting(FileCheckpoints.Change::file).containsExactly(file);
			assertThat(Files.readString(file)).isEqualTo("original");
			assertThat(checkpoints.conflicts("s")).isEmpty();
		}
		assertThat(Files.isSymbolicLink(alias)).isTrue();
		assertThat(this.state.resolve("backups")).doesNotExist();
	}

	private FileCheckpoints checkpoints() {
		return new FileCheckpoints(this.state.resolve("backups"));
	}

	private FileTools tools(FileCheckpoints checkpoints) {
		return new FileTools(List.of(), 1024 * 1024, checkpoints);
	}

	private ToolContext turn(String turnId) {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString(), ToolContextKeys.SESSION_ID, "s",
				ToolContextKeys.TURN_ID, turnId));
	}

}
