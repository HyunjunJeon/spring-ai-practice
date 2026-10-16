package com.example.miniagent.plugin.fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.change.FileChangeStore;
import com.example.miniagent.core.change.StagedWorkspace;
import com.example.miniagent.core.execution.StagingToolInterceptor;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ToolContextKeys;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.SideEffectClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/** 관리형(staged) 실행이 파일 도구를 분류하는 방식: 읽기 도구는 READ, 바꾸는 도구는 FILE_WRITE 이고, 읽기는 스테이지 내용을 본다. */
class FsStagedPolicyTest {

	@TempDir
	Path work;

	@TempDir
	Path home;

	@TempDir
	Path extra;

	private ToolCallbackProvider provider;

	private Map<String, StagingToolInterceptor.Declaration> declarations;

	@BeforeEach
	void wire() {
		var properties = new AgentProperties();
		properties.setHome(this.home);
		var config = new FsPluginAutoConfiguration();
		this.provider = config.fileToolsProvider(properties,
				new DefaultListableBeanFactory().getBeanProvider(FileCheckpoints.class), List.of(this.extra.toString()),
				DataSize.ofBytes(1024));
		this.declarations = config.stagedFilePolicy(this.provider, List.of(this.extra.toString())).declarations();
	}

	@Test
	void everyFileToolIsDeclaredAndNothingElse() {
		// 새 도구가 선언 없이 추가되면 기본값 READ 로 분류된다. 그 결정을 이 목록에서 의식적으로 내리게 한다.
		assertThat(this.declarations.keySet()).containsExactlyInAnyOrder("read_file", "write_file", "edit_file",
				"list_dir", "search_content", "glob_paths");
	}

	@ParameterizedTest
	@CsvSource({ "read_file,READ", "list_dir,READ", "search_content,READ", "glob_paths,READ", "edit_file,FILE_WRITE",
			"write_file,FILE_WRITE" })
	void toolIsDeclaredWithTheKindThatDecidesItsPathScope(String tool, StagingToolInterceptor.Kind kind) {
		var declaration = this.declarations.get(tool);

		assertThat(declaration.kind()).isEqualTo(kind);
		assertThat(declaration.digest()).isEqualTo(SideEffectClass.definitionDigest(definition(tool)));
		assertThat(declaration.readRoots()).containsExactly(this.extra);
	}

	@Test
	void managedReadFileReturnsTheStagedContentWhileTheLiveFileStaysUntouched() throws Exception {
		Path source = Files.writeString(this.work.resolve("Source.txt"), "live\n");
		FileTools tools = new FileTools(List.of(), 1024 * 1024, null);
		try (var stage = openStage()) {
			ToolContext managed = managed(stage);

			assertThat(tools.readFile("Source.txt", null, null, managed)).isEqualTo("     1  live");
			tools.editFile("Source.txt", "live", "staged", managed);

			assertThat(tools.readFile("Source.txt", null, null, managed)).isEqualTo("     1  staged");
			assertThat(Files.readString(source)).isEqualTo("live\n");
			assertThat(tools.readFile("Source.txt", null, null, legacy())).isEqualTo("     1  live");
		}
	}

	@Test
	void managedListDirShowsStagedCreationsThatAreNotYetLive() throws Exception {
		Files.writeString(this.work.resolve("Source.txt"), "live\n");
		FileTools tools = new FileTools(List.of(), 1024 * 1024, null);
		try (var stage = openStage()) {
			ToolContext managed = managed(stage);

			tools.writeFile("New.txt", "staged", managed);

			assertThat(tools.listDir(".", managed)).isEqualTo("New.txt\nSource.txt");
			assertThat(tools.readFile("New.txt", null, null, managed)).isEqualTo("     1  staged");
			assertThat(tools.listDir(".", legacy())).isEqualTo("Source.txt");
			assertThat(this.work.resolve("New.txt")).doesNotExist();
		}
	}

	private ToolDefinition definition(String tool) {
		return Arrays.stream(this.provider.getToolCallbacks()).map(ToolCallback::getToolDefinition)
			.filter(candidate -> candidate.name().equals(tool)).findFirst().orElseThrow();
	}

	private StagedWorkspace openStage() throws Exception {
		ChatModel model = new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				return new ChatResponse(List.of());
			}

			@Override
			public ChatOptions getOptions() {
				return ToolCallingChatOptions.builder().build();
			}
		};
		var binding = new ModelBinding(UUID.randomUUID(), new ModelRef("fake", "test"), "a".repeat(64), "fake", model,
				ChatClient.builder(model).build());
		var turn = new TurnContext("session", "turn", this.work.toRealPath(), binding, "run", "attempt");
		return StagedWorkspace.open(new FileChangeStore(this.home, this.work), turn, Clock.systemUTC());
	}

	private ToolContext managed(StagedWorkspace stage) {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString(), ToolContextKeys.STAGED_WORKSPACE, stage));
	}

	private ToolContext legacy() {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString()));
	}

}
