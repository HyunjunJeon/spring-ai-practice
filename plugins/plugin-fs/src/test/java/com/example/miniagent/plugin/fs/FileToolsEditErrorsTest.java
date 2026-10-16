package com.example.miniagent.plugin.fs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** edit_file 의 "정확히 한 번" 규칙: 거절된 편집은 파일, 임시 파일, 체크포인트 어디에도 흔적을 남기지 않는다. */
class FileToolsEditErrorsTest {

	@TempDir
	Path work;

	@TempDir
	Path state;

	private FileCheckpoints checkpoints;

	private FileTools tools;

	@BeforeEach
	void wire() throws Exception {
		this.work = this.work.toRealPath();
		this.state = this.state.toRealPath();
		this.checkpoints = new FileCheckpoints(this.state.resolve("backups"));
		this.tools = new FileTools(List.of(), 1024 * 1024, this.checkpoints);
	}

	private ToolContext turn() {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString(), ToolContextKeys.SESSION_ID, "s",
				ToolContextKeys.TURN_ID, "t1"));
	}

	private void assertNothingLeftBehind(Path file, byte[] original) throws Exception {
		assertThat(Files.readAllBytes(file)).isEqualTo(original);
		try (Stream<Path> entries = Files.list(this.work)) {
			assertThat(entries).containsExactly(file);
		}
		var outcome = this.checkpoints.undoWithOutcome("s");
		assertThat(outcome.turnId()).isNull();
		assertThat(outcome.restored()).isEmpty();
		assertThat(outcome.conflicts()).isEmpty();
		assertThat(this.state.resolve("backups")).doesNotExist();
	}

	@Test
	void oldTextThatIsNotInTheFileIsRefused() throws Exception {
		Path file = this.work.resolve("notes.txt");
		byte[] original = "alpha\nbeta\n".getBytes(StandardCharsets.UTF_8);
		Files.write(file, original);

		assertThatThrownBy(() -> this.tools.editFile("notes.txt", "gamma", "delta", turn()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("old_text not found in " + file);

		assertNothingLeftBehind(file, original);
	}

	@Test
	void oldTextThatOccursTwiceIsRefusedAndAsksForMoreContext() throws Exception {
		Path file = this.work.resolve("notes.txt");
		byte[] original = "item\nitem\n".getBytes(StandardCharsets.UTF_8);
		Files.write(file, original);

		assertThatThrownBy(() -> this.tools.editFile("notes.txt", "item", "thing", turn()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("old_text occurs more than once in " + file + "; include more context");

		assertNothingLeftBehind(file, original);
	}

	@Test
	void overlappingOccurrencesCountAsMoreThanOnce() throws Exception {
		Path file = this.work.resolve("notes.txt");
		byte[] original = "aaa".getBytes(StandardCharsets.UTF_8);
		Files.write(file, original);

		// "aa" 는 0 번과 1 번 위치에서 겹쳐 두 번 나온다. 겹침을 건너뛰고 세면 첫 번째가 바뀌어 버린다.
		assertThatThrownBy(() -> this.tools.editFile("notes.txt", "aa", "b", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than once");

		assertNothingLeftBehind(file, original);
	}

	@Test
	void emptyOldTextIsRefusedInsteadOfInsertingEverywhere() throws Exception {
		Path file = this.work.resolve("notes.txt");
		byte[] original = "alpha\n".getBytes(StandardCharsets.UTF_8);
		Files.write(file, original);

		assertThatThrownBy(() -> this.tools.editFile("notes.txt", "", "x", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("old_text");

		assertNothingLeftBehind(file, original);
	}

	@Test
	void ambiguityIsDecidedAfterCrlfNormalisation() throws Exception {
		Path file = this.work.resolve("crlf.txt");
		byte[] original = "a\r\nb\r\nc\r\na\r\nb\r\n".getBytes(StandardCharsets.UTF_8);
		Files.write(file, original);

		// 모델이 보낸 LF 문자열이 CRLF 파일에서 두 번 맞으면 모호하다.
		assertThatThrownBy(() -> this.tools.editFile("crlf.txt", "a\nb", "X", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than once");
		assertThatThrownBy(() -> this.tools.editFile("crlf.txt", "a\nz", "X", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("old_text not found");

		assertNothingLeftBehind(file, original);
	}

	@Test
	void addingContextToAnAmbiguousSnippetMakesTheEditApply() throws Exception {
		Path file = Files.writeString(this.work.resolve("notes.txt"), "item one\nitem two\n");

		assertThatThrownBy(() -> this.tools.editFile("notes.txt", "item", "thing", turn()))
			.hasMessageContaining("include more context");
		this.tools.editFile("notes.txt", "item two", "thing two", turn());

		assertThat(Files.readString(file)).isEqualTo("item one\nthing two\n");
		assertThat(this.checkpoints.undo("s")).extracting(FileCheckpoints.Change::file).containsExactly(file);
		assertThat(Files.readString(file)).isEqualTo("item one\nitem two\n");
	}

	@Test
	void missingFileAndDirectoryAreRefusedBeforeAnyRead() throws Exception {
		Path directory = Files.createDirectory(this.work.resolve("folder"));

		assertThatThrownBy(() -> this.tools.editFile("absent.txt", "a", "b", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("File not found: " + this.work.resolve("absent.txt"));
		assertThatThrownBy(() -> this.tools.editFile("folder", "a", "b", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("File not found: " + directory);

		try (Stream<Path> entries = Files.list(this.work)) {
			assertThat(entries).containsExactly(directory);
		}
		assertThat(this.checkpoints.undoWithOutcome("s").turnId()).isNull();
	}

}
