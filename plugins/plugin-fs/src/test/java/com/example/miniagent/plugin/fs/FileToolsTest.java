package com.example.miniagent.plugin.fs;

import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.example.miniagent.core.model.ToolContextKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 파일 도구의 안전장치: 원문 보존, 원자적 쓰기, 작업 디렉터리 경계, 크기 상한. */
class FileToolsTest {

	@TempDir
	Path work;

	@TempDir
	Path outside;

	private final FileTools tools = new FileTools(List.of(), 1024 * 1024, null);

	private ToolContext context() {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString()));
	}

	@Test
	void newFileHasPrivatePermissions() throws Exception {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));

		this.tools.writeFile("private.txt", "private", context());

		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(this.work.resolve("private.txt"))))
			.isEqualTo("rw-------");
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void writePublishesCompleteUtf8AndCrlfWithoutTemporaryFiles(boolean existed) throws Exception {
		Path parent = this.work.resolve("nested");
		Path file = parent.resolve("text.txt");
		boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
		if (existed) {
			Files.createDirectories(parent);
			Files.writeString(file, "original");
			if (posix) {
				Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-x---"));
			}
		}
		String content = "\ud55c\uae00 \ud83d\ude80 caf\u00e9\r\n".repeat(8192) + "end\r\n";

		this.tools.writeFile("nested/text.txt", content, context());

		assertThat(Files.readAllBytes(file)).isEqualTo(content.getBytes(StandardCharsets.UTF_8));
		if (posix) {
			assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
				.isEqualTo(existed ? "rwxr-x---" : "rw-------");
		}
		try (Stream<Path> entries = Files.list(parent)) {
			assertThat(entries).containsExactly(file);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void failedUtf8WriteLeavesDestinationAbsentOrUnchanged(boolean existed) throws Exception {
		Path file = this.work.resolve("text.txt");
		if (existed) {
			Files.writeString(file, "original\r\n");
		}

		assertThatThrownBy(() -> this.tools.writeFile("text.txt", "prefix\ud800", context()))
			.isInstanceOf(UncheckedIOException.class).hasCauseInstanceOf(UnmappableCharacterException.class);

		if (existed) {
			assertThat(Files.readString(file)).isEqualTo("original\r\n");
		}
		else {
			assertThat(file).doesNotExist();
		}
		try (Stream<Path> entries = Files.list(this.work)) {
			assertThat(entries).containsExactlyElementsOf(existed ? List.of(file) : List.of());
		}
	}

	@Test
	void failedAtomicMoveKeepsNonemptyDestinationAndRemovesTemporaryFile() throws Exception {
		Path directory = Files.createDirectory(this.work.resolve("destination"));
		Path child = Files.writeString(directory.resolve("child.txt"), "original");
		// writeFile rejects directories before writing; invoke publication directly to exercise move failure.
		Method write = FileTools.class.getDeclaredMethod("write", Path.class, String.class);
		write.setAccessible(true);

		assertThatThrownBy(() -> write.invoke(null, directory, "replacement"))
			.isInstanceOf(InvocationTargetException.class).hasCauseInstanceOf(UncheckedIOException.class);

		assertThat(Files.readString(child)).isEqualTo("original");
		try (Stream<Path> entries = Files.list(directory)) {
			assertThat(entries).containsExactly(child);
		}
		try (Stream<Path> entries = Files.list(this.work)) {
			assertThat(entries).containsExactly(directory);
		}
	}

	@Test
	void editKeepsCrlfAndTrailingNewline() throws Exception {
		Path file = this.work.resolve("crlf.txt");
		Files.writeString(file, "one\r\ntwo\r\nthree\r\n");

		// 모델은 LF 로 보낸다. CRLF 파일에서도 찾아서 CRLF 로 바꿔 넣어야 한다.
		this.tools.editFile("crlf.txt", "one\ntwo", "ONE\nTWO", context());

		assertThat(Files.readString(file)).isEqualTo("ONE\r\nTWO\r\nthree\r\n");
	}

	@Test
	void editKeepsPermissionsAndLeavesNoTemporaryFile() throws Exception {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		Path script = this.work.resolve("run.sh");
		Files.writeString(script, "echo old\n");
		Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-x---"));

		this.tools.editFile("run.sh", "old", "new", context());

		assertThat(Files.readString(script)).isEqualTo("echo new\n");
		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(script))).isEqualTo("rwxr-x---");
		try (Stream<Path> entries = Files.list(this.work)) {
			assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("run.sh");
		}
	}

	@Test
	void pathsOutsideTheWorkingDirectoryAreRejected() throws Exception {
		Path secret = Files.writeString(this.outside.resolve("secret.txt"), "secret");

		assertThatThrownBy(() -> this.tools.readFile(secret.toString(), null, null, context()))
			.hasMessageContaining("outside the allowed directories");
		assertThatThrownBy(() -> this.tools.writeFile("../escape.txt", "x", context()))
			.hasMessageContaining("outside the allowed directories");
		assertThat(this.work.getParent().resolve("escape.txt")).doesNotExist();
	}

	@Test
	void symlinkLeadingOutsideIsRejected() throws Exception {
		Files.writeString(this.outside.resolve("secret.txt"), "secret");
		Files.createSymbolicLink(this.work.resolve("link"), this.outside);

		assertThatThrownBy(() -> this.tools.readFile("link/secret.txt", null, null, context()))
			.hasMessageContaining("outside the allowed directories");
	}

	@Test
	void editThroughSymlinkInsideTheWorkspaceKeepsTheLink() throws Exception {
		Path target = Files.writeString(this.work.resolve("real.txt"), "a\n");
		Path link = Files.createSymbolicLink(this.work.resolve("alias.txt"), target);

		this.tools.editFile("alias.txt", "a", "b", context());

		assertThat(Files.isSymbolicLink(link)).isTrue();
		assertThat(Files.readString(target)).isEqualTo("b\n");
	}

	@Test
	void allowedRootsExtendAccess() throws Exception {
		Path notes = Files.writeString(this.outside.resolve("notes.txt"), "hello");
		FileTools widened = new FileTools(List.of(this.outside), 1024, null);

		assertThat(widened.readFile(notes.toString(), null, null, context())).contains("hello");
	}

	@Test
	void filesOverTheSizeLimitAreRefused() throws Exception {
		Files.writeString(this.work.resolve("big.txt"), "x".repeat(100));
		FileTools small = new FileTools(List.of(), 50, null);

		assertThatThrownBy(() -> small.readFile("big.txt", null, null, context())).hasMessageContaining("File too large");
		assertThatThrownBy(() -> small.editFile("big.txt", "x", "y", context())).hasMessageContaining("File too large");
	}

	@Test
	void pagedReadReportsRemainingLines() throws Exception {
		Files.writeString(this.work.resolve("lines.txt"), "l1\nl2\nl3\nl4\nl5\n");

		assertThat(this.tools.readFile("lines.txt", 2, 2, context()))
			.isEqualTo("     2  l2\n     3  l3\n... (2 more lines, call again with offset=4)");
	}

	@Test
	void undoRevertsTheLastTurnAndDeletesFilesItCreated() throws Exception {
		FileCheckpoints checkpoints = new FileCheckpoints(this.outside.resolve("checkpoints"));
		FileTools tools = new FileTools(List.of(), 1024 * 1024, checkpoints);
		Path notes = Files.writeString(this.work.resolve("notes.txt"), "v0\n");

		tools.editFile("notes.txt", "v0", "v1", turn("t1"));
		tools.editFile("notes.txt", "v1", "v2", turn("t2"));
		tools.writeFile("new.txt", "fresh", turn("t2"));
		tools.editFile("notes.txt", "v2", "v3", turn("t2"));

		// t2 를 되돌리면 notes.txt 는 t2 가 시작될 때의 내용(v1)으로, t2 에 만든 new.txt 는 지워진다.
		assertThat(checkpoints.undo("s")).hasSize(2);
		assertThat(Files.readString(notes)).isEqualTo("v1\n");
		assertThat(this.work.resolve("new.txt")).doesNotExist();

		assertThat(checkpoints.undo("s")).hasSize(1);
		assertThat(Files.readString(notes)).isEqualTo("v0\n");
		assertThat(checkpoints.undo("s")).isEmpty();
	}

	@Test
	void closeRemovesBackups() throws Exception {
		Path directory = this.outside.resolve("checkpoints");
		FileCheckpoints checkpoints = new FileCheckpoints(directory);
		Files.writeString(this.work.resolve("a.txt"), "a");
		new FileTools(List.of(), 1024, checkpoints).editFile("a.txt", "a", "b", turn("t1"));
		assertThat(directory).isNotEmptyDirectory();

		checkpoints.close();

		assertThat(directory).doesNotExist();
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void partialUndoPreservesReplacedPathAndRestoresUnrelatedFiles(boolean firstExisted) throws Exception {
		this.work = this.work.toRealPath();
		Path directory = this.outside.resolve("checkpoints");
		FileCheckpoints checkpoints = new FileCheckpoints(directory);
		FileTools tools = new FileTools(List.of(), 1024, checkpoints);
		Path older = Files.writeString(this.work.resolve("older.txt"), "older original");
		tools.writeFile("older.txt", "older changed", turn("t1"));
		Path first = this.work.resolve("first.txt");
		if (firstExisted) {
			Files.writeString(first, "first original");
		}
		Path parent = Files.createDirectory(this.work.resolve("second"));
		Path second = Files.writeString(parent.resolve("second.txt"), "second original");
		Path third = Files.writeString(this.work.resolve("third.txt"), "third original");
		tools.writeFile("first.txt", "first changed", turn("t2"));
		tools.writeFile("second/second.txt", "second changed", turn("t2"));
		tools.writeFile("third.txt", "third changed", turn("t2"));
		Path other = Files.writeString(this.work.resolve("other.txt"), "other original");
		checkpoints.beforeWrite("other-session", "t2", other);
		Files.writeString(other, "other changed");
		checkpoints.afterWrite("other-session", "t2", other);

		Files.delete(second);
		Files.delete(parent);
		Files.writeString(parent, "blocked");
		assertThat(checkpoints.undo("s")).extracting(FileCheckpoints.Change::file).containsExactly(first, third);
		assertThat(checkpoints.conflicts("s")).singleElement().satisfies(conflict -> {
			assertThat(conflict.change().file()).isEqualTo(second);
			assertThat(conflict.code()).isEqualTo(FileCheckpoints.ConflictCode.UNSAFE_PATH);
		});
		if (firstExisted) {
			assertThat(Files.readString(first)).isEqualTo("first original");
		}
		else {
			assertThat(first).doesNotExist();
		}
		assertThat(Files.readString(third)).isEqualTo("third original");
		assertThat(Files.readString(older)).isEqualTo("older changed");
		assertThat(Files.readString(other)).isEqualTo("other changed");
		try (Stream<Path> backups = Files.list(directory)) {
			assertThat(backups).hasSize(3);
		}
		Files.writeString(first, "human edit");
		Files.delete(parent);
		Files.createDirectory(parent);
		Files.writeString(second, "human replacement");

		assertThat(checkpoints.undo("s")).isEmpty();
		assertThat(Files.readString(first)).isEqualTo("human edit");
		assertThat(Files.readString(second)).isEqualTo("human replacement");
		assertThat(Files.readString(third)).isEqualTo("third original");
		assertThat(Files.readString(older)).isEqualTo("older changed");
		assertThat(checkpoints.conflicts("s")).singleElement().satisfies(conflict ->
			assertThat(conflict.code()).isEqualTo(FileCheckpoints.ConflictCode.AFTER_CHANGED));
		assertThat(checkpoints.undo("other-session")).extracting(FileCheckpoints.Change::file).containsExactly(other);
		assertThat(Files.readString(other)).isEqualTo("other original");
		try (Stream<Path> backups = Files.list(directory)) {
			assertThat(backups).hasSize(2);
		}
		checkpoints.close();
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void uncompletedDirectoryWithHumanContentIsNotCalledRestored(boolean hasPendingChange) throws Exception {
		this.work = this.work.toRealPath();
		Path directory = this.outside.resolve("checkpoints");
		FileCheckpoints checkpoints = new FileCheckpoints(directory);
		Path older = Files.writeString(this.work.resolve("older.txt"), "older original");
		checkpoints.beforeWrite("s", "t1", older);
		Files.writeString(older, "older changed");
		checkpoints.afterWrite("s", "t1", older);
		// A before-only directory snapshot never proves that a mutation completed.
		Path first = Files.createDirectory(this.work.resolve("first"));
		checkpoints.beforeWrite("s", "t2", first);
		Path backup = directory.resolve("2.bak");
		Files.writeString(backup.resolve("block-cleanup"), "block");
		Files.writeString(first.resolve("human.txt"), "human edit");
		Path pending = Files.writeString(this.work.resolve("pending.txt"), "pending original");
		if (hasPendingChange) {
			checkpoints.beforeWrite("s", "t2", pending);
			Files.writeString(pending, "pending changed");
			checkpoints.afterWrite("s", "t2", pending);
		}

		assertThat(checkpoints.undo("s")).extracting(FileCheckpoints.Change::file)
			.containsExactlyElementsOf(hasPendingChange ? List.of(pending) : List.of());
		assertThat(checkpoints.conflicts("s")).singleElement().satisfies(conflict -> {
			assertThat(conflict.change().file()).isEqualTo(first);
			assertThat(conflict.code()).isEqualTo(FileCheckpoints.ConflictCode.UNKNOWN_COMPLETION);
		});
		assertThat(Files.readString(first.resolve("human.txt"))).isEqualTo("human edit");
		if (hasPendingChange) {
			assertThat(Files.readString(pending)).isEqualTo("pending original");
			assertThat(directory.resolve("3.bak")).doesNotExist();
		}
		assertThat(Files.readString(older)).isEqualTo("older changed");
		assertThat(Files.readString(first.resolve("human.txt"))).isEqualTo("human edit");
		assertThat(checkpoints.undo("s")).isEmpty();
		checkpoints.close();
	}

	private ToolContext turn(String turnId) {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString(), ToolContextKeys.SESSION_ID, "s",
				ToolContextKeys.TURN_ID, turnId));
	}

}
