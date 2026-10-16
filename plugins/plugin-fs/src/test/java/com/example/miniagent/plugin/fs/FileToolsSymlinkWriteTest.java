package com.example.miniagent.plugin.fs;

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

/** 쓰기 방향의 심볼릭 링크 경계: 링크가 작업 영역 밖을 가리키면 write_file 과 edit_file 모두 거절한다. */
class FileToolsSymlinkWriteTest {

	@TempDir
	Path work;

	@TempDir
	Path outside;

	@TempDir
	Path state;

	private FileCheckpoints checkpoints;

	private FileTools tools;

	@BeforeEach
	void wire() throws Exception {
		this.work = this.work.toRealPath();
		this.outside = this.outside.toRealPath();
		this.state = this.state.toRealPath();
		this.checkpoints = new FileCheckpoints(this.state.resolve("backups"));
		this.tools = new FileTools(List.of(), 1024 * 1024, this.checkpoints);
	}

	private ToolContext turn() {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString(), ToolContextKeys.SESSION_ID, "s",
				ToolContextKeys.TURN_ID, "t1"));
	}

	private void assertNothingCheckpointed() {
		assertThat(this.checkpoints.undoWithOutcome("s").turnId()).isNull();
		assertThat(this.state.resolve("backups")).doesNotExist();
	}

	@Test
	void fileLinkToAnOutsideFileRefusesBothWritersAndKeepsTheBytes() throws Exception {
		Path secret = Files.writeString(this.outside.resolve("secret.txt"), "outside original");
		Path link = Files.createSymbolicLink(this.work.resolve("linked.txt"), secret);

		assertThatThrownBy(() -> this.tools.writeFile("linked.txt", "overwritten", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside the allowed directories");
		assertThatThrownBy(() -> this.tools.editFile("linked.txt", "outside", "inside", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside the allowed directories");

		assertThat(Files.readString(secret)).isEqualTo("outside original");
		assertThat(Files.isSymbolicLink(link)).isTrue();
		assertThat(Files.readSymbolicLink(link)).isEqualTo(secret);
		try (Stream<Path> entries = Files.list(this.outside)) {
			assertThat(entries).containsExactly(secret);
		}
		try (Stream<Path> entries = Files.list(this.work)) {
			assertThat(entries).containsExactly(link);
		}
		assertNothingCheckpointed();
	}

	@Test
	void directoryLinkToAnOutsideFolderRefusesNewAndNestedFiles() throws Exception {
		Files.createSymbolicLink(this.work.resolve("escape"), this.outside);

		assertThatThrownBy(() -> this.tools.writeFile("escape/new.txt", "x", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside the allowed directories");
		assertThatThrownBy(() -> this.tools.writeFile("escape/sub/deeper/new.txt", "x", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside the allowed directories");

		try (Stream<Path> entries = Files.list(this.outside)) {
			assertThat(entries).isEmpty();
		}
		assertNothingCheckpointed();
	}

	@Test
	void directoryLinkToAnOutsideFolderRefusesEditsOfFilesInsideIt() throws Exception {
		Path secret = Files.writeString(this.outside.resolve("secret.txt"), "outside original");
		Files.createSymbolicLink(this.work.resolve("escape"), this.outside);

		assertThatThrownBy(() -> this.tools.editFile("escape/secret.txt", "outside", "inside", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside the allowed directories");

		assertThat(Files.readString(secret)).isEqualTo("outside original");
		assertNothingCheckpointed();
	}

	@Test
	void danglingLinkInsideTheWorkspaceIsNotFollowedToCreateItsTarget() throws Exception {
		Path target = this.work.resolve("missing.txt");
		Path link = Files.createSymbolicLink(this.work.resolve("dangling"), target);

		assertThatThrownBy(() -> this.tools.writeFile("dangling", "x", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cannot resolve");
		assertThatThrownBy(() -> this.tools.editFile("dangling", "a", "b", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cannot resolve");

		assertThat(target).doesNotExist();
		assertThat(Files.isSymbolicLink(link)).isTrue();
		try (Stream<Path> entries = Files.list(this.work)) {
			assertThat(entries).containsExactly(link);
		}
		assertNothingCheckpointed();
	}

	@Test
	void danglingLinkToAnOutsidePathIsNotFollowedToCreateItsTarget() throws Exception {
		Path target = this.outside.resolve("missing.txt");
		Files.createSymbolicLink(this.work.resolve("dangling"), target);

		assertThatThrownBy(() -> this.tools.writeFile("dangling", "x", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cannot resolve");
		assertThatThrownBy(() -> this.tools.writeFile("dangling/child.txt", "x", turn()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cannot resolve");

		assertThat(target).doesNotExist();
		try (Stream<Path> entries = Files.list(this.outside)) {
			assertThat(entries).isEmpty();
		}
		assertNothingCheckpointed();
	}

	@Test
	void writeThroughALinkToAWorkspaceFileChangesTheTargetAndKeepsTheLink() throws Exception {
		Path target = Files.writeString(this.work.resolve("real.txt"), "before");
		Path link = Files.createSymbolicLink(this.work.resolve("alias.txt"), target);

		this.tools.writeFile("alias.txt", "after", turn());

		assertThat(Files.isSymbolicLink(link)).isTrue();
		assertThat(Files.readString(target)).isEqualTo("after");
		assertThat(this.checkpoints.undo("s")).extracting(FileCheckpoints.Change::file).containsExactly(target);
		assertThat(Files.readString(target)).isEqualTo("before");
	}

}
