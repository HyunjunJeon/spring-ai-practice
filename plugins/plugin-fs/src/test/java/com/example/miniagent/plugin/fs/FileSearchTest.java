package com.example.miniagent.plugin.fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSearchTest {

	@TempDir Path work;
	@TempDir Path outside;

	private final FileTools tools = new FileTools(List.of(), 1024 * 1024, null);

	private ToolContext context() {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString()));
	}

	private void file(String name, String content) throws Exception {
		Path file = this.work.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	@Test
	void literalResultsHaveStablePathLineOrderAndExactPages() throws Exception {
		file("z.txt", "hit\n");
		file("a/nested.txt", "hit\nmiss\nhit hit\n");
		file("a.txt", "hit\n");
		var first = this.tools.searchContent(".", "hit", 0, 2, context());
		assertThat(first.matches()).containsExactly(new FileSearch.Match("a.txt", 1, "hit"),
				new FileSearch.Match("a/nested.txt", 1, "hit"));
		assertThat(first.nextOffset()).isEqualTo(2);
		assertThat(this.tools.searchContent(".", "hit", 2, 2, context()).matches()).containsExactly(
				new FileSearch.Match("a/nested.txt", 3, "hit hit"), new FileSearch.Match("z.txt", 1, "hit"));
		assertThat(this.tools.searchContent(".", "hit", 2, 2, context()).nextOffset()).isNull();
		assertThat(this.tools.searchContent(".", "hit", 4, 2, context()).matches()).isEmpty();
		assertThat(this.tools.searchContent(".", "hit", 0, 2, context())).isEqualTo(first);
		file("literal.txt", "a.+b\r\nplain\rfinal a.+b");
		assertThat(this.tools.searchContent(".", "a.+b", 0, 10, context()).matches())
			.containsExactly(new FileSearch.Match("literal.txt", 1, "a.+b"), new FileSearch.Match("literal.txt", 3, "final a.+b"));
	}

	@Test
	void doubleStarIncludesRootFilesAndGlobPaginationIsStable() throws Exception {
		file("z/Z.java", "");
		file("Root.java", "");
		file("a/b/Nested.java", "");
		file("Other.txt", "");
		var page = this.tools.globPaths(".", "**/*.java", 0, 2, context());
		assertThat(page.matches()).extracting(FileSearch.Match::path).containsExactly("Root.java", "a/b/Nested.java");
		assertThat(page.nextOffset()).isEqualTo(2);
		assertThat(this.tools.globPaths(".", "**/*.java", 2, 2, context()).matches())
			.extracting(FileSearch.Match::path).containsExactly("z/Z.java");
		assertThat(this.tools.globPaths(".", "*.java", 0, 10, context()).matches())
			.extracting(FileSearch.Match::path).containsExactly("Root.java");
		assertThat(this.tools.globPaths(".", "a/**/N?sted.java", 0, 10, context()).matches())
			.extracting(FileSearch.Match::path).containsExactly("a/b/Nested.java");
		assertThat(this.tools.globPaths(".", "a/**/b/*.java", 0, 10, context()).matches()).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "target", "build", "dist", "out", "node_modules", "vendor", ".git", ".mini-agent",
			".omo", ".ssh", ".aws", ".config", "auth", "credentials", ".credentials", "KEYS", "runs", "changes", "logs" })
	void ignoredDirectoriesArePrunedEvenWhenExplicitlyRequested(String ignored) throws Exception {
		file(ignored + "/nested/data.txt", "needle");
		file("visible.txt", "needle");
		assertThat(this.tools.searchContent(".", "needle", null, null, context()).matches())
			.extracting(FileSearch.Match::path).containsExactly("visible.txt");
		assertThat(this.tools.globPaths(".", "**", null, null, context()).matches())
			.extracting(FileSearch.Match::path).containsExactly("visible.txt");
		assertThatThrownBy(() -> this.tools.globPaths(ignored, "**", null, null, context()))
			.hasMessageContaining("Ignored search root");
		assertThatThrownBy(() -> this.tools.searchContent(ignored + "/nested", "needle", null, null, context()))
			.hasMessageContaining("Ignored search root");
	}

	@Test
	void fixedIgnoresDoNotPretendToInterpretGitignore() throws Exception {
		for (String name : List.of(".env", ".env.local", ".npmrc", "auth.json", "id_rsa", "token.pem", "credentials.json"))
			file(name, "needle");
		file(".gitignore", "custom/\n");
		file("custom/visible.txt", "needle");
		file("CodexCredentials.java", "needle");
		file("SecretManager.java", "needle");
		assertThat(this.tools.searchContent(".", "needle", null, null, context()).matches())
			.extracting(FileSearch.Match::path)
			.containsExactly("CodexCredentials.java", "SecretManager.java", "custom/visible.txt");
	}

	@Test
	void linksAndOutsidePathsCannotExtendSearchScope() throws Exception {
		Files.writeString(this.outside.resolve("outside.txt"), "needle");
		file("inside/data.txt", "needle");
		Files.createSymbolicLink(this.work.resolve("escape"), this.outside);
		Files.createSymbolicLink(this.work.resolve("alias"), this.work.resolve("inside"));
		Files.createSymbolicLink(this.work.resolve("linked.txt"), this.outside.resolve("outside.txt"));
		Files.createSymbolicLink(this.work.resolve("inside/cycle"), this.work);
		assertThat(this.tools.searchContent(".", "needle", null, null, context()).matches())
			.extracting(FileSearch.Match::path).containsExactly("inside/data.txt");
		assertThat(this.tools.globPaths(".", "**", null, null, context()).matches())
			.extracting(FileSearch.Match::path).containsExactly("inside/data.txt");
		for (String path : List.of(this.outside.toString(), "escape")) {
			assertThatThrownBy(() -> this.tools.searchContent(path, "needle", null, null, context()))
				.hasMessageContaining("outside the allowed directories");
			assertThatThrownBy(() -> this.tools.globPaths(path, "**", null, null, context()))
				.hasMessageContaining("outside the allowed directories");
		}
		assertThatThrownBy(() -> this.tools.globPaths("alias", "**", null, null, context()))
			.hasMessageContaining("does not follow symlinks");
		var extended = new FileTools(List.of(this.outside), 1024, null);
		assertThat(extended.searchContent(this.outside.toString(), "needle", null, null, context()).matches())
			.extracting(FileSearch.Match::path).containsExactly("outside.txt");
	}

	@Test
	void skipsOversizedBinaryMalformedAndLongLinesButKeepsLaterLineNumbers() throws Exception {
		file("large.txt", "x".repeat(5001));
		file("binary.txt", "needle\u0000");
		Files.write(this.work.resolve("invalid.txt"), new byte[] { (byte) 0xc3, 0x28 });
		file("lines.txt", "needle" + "x".repeat(4091) + "\r\nneedle" + "x".repeat(1000) + "\r\nneedle\n");
		var small = new FileTools(List.of(), 5000, null);
		var page = small.searchContent(".", "needle", 0, 10, context());
		assertThat(page.skippedFiles()).isEqualTo(4);
		assertThat(page.skippedLines()).isEqualTo(0); // lines.txt itself exceeds this configured byte limit.
		assertThat(page.matches()).isEmpty();
		var full = this.tools.searchContent(".", "needle", 0, 10, context());
		assertThat(full.skippedLines()).isEqualTo(2); // large.txt and the first lines.txt line.
		assertThat(full.matches()).extracting(FileSearch.Match::line).containsExactly(2, 3);
		assertThat(full.matches().getFirst().text()).hasSize(512);
		assertThat(this.tools.globPaths(".", "*.txt", 0, 10, context()).matches()).hasSize(4);
	}

	@Test
	void hardFileAndAggregateByteLimitsAreIndependentOfConfiguredReadLimit() throws Exception {
		file("huge.txt", "x".repeat(1024 * 1024 + 1));
		var large = new FileTools(List.of(), 2L * 1024 * 1024, null);
		assertThat(large.searchContent(".", "needle", null, null, context()).skippedFiles()).isEqualTo(1);
		Files.delete(this.work.resolve("huge.txt"));
		String content = "x".repeat(1024 * 1024);
		for (int i = 0; i < 17; i++) file("%02d.txt".formatted(i), content);
		assertThat(large.searchContent(".", "needle", null, null, context()).byteLimited()).isTrue();
	}

	@Test
	void outputBoundProducesUsableOffsetsWithoutDroppingMatches() throws Exception {
		file("many.txt", ("needle" + "x".repeat(600) + "\n").repeat(100));
		var all = new ArrayList<FileSearch.Match>();
		Integer offset = 0;
		do {
			var page = this.tools.searchContent(".", "needle", offset, 100, context());
			assertThat(page.matches().stream().mapToInt(m -> m.path().length() + m.text().length()).sum()).isLessThanOrEqualTo(16000);
			assertThat(page.matches()).isNotEmpty();
			all.addAll(page.matches());
			offset = page.nextOffset();
		} while (offset != null);
		assertThat(all).hasSize(100);
		assertThat(all).extracting(FileSearch.Match::line).containsExactlyElementsOf(
				java.util.stream.IntStream.rangeClosed(1, 100).boxed().toList());
	}

	@Test
	void reachingOffsetCeilingReportsIncompletenessInsteadOfExhaustion() throws Exception {
		file("many.txt", "needle\n".repeat(10102));
		var page = this.tools.searchContent(".", "needle", 10000, 100, context());
		assertThat(page.matches()).hasSize(100);
		assertThat(page.matches().getFirst().line()).isEqualTo(10001);
		assertThat(page.offsetLimited()).isTrue();
		assertThat(page.nextOffset()).isNull();
	}

	@Test
	void depthAndEntryBoundsAreExplicitRatherThanNondeterministicPartialPages() throws Exception {
		String deep = "d/".repeat(16);
		file(deep + "deep.txt", "needle");
		file("shallow.txt", "needle");
		var page = this.tools.globPaths(".", "**", null, null, context());
		assertThat(page.depthLimited()).isTrue();
		assertThat(page.matches()).extracting(FileSearch.Match::path).containsExactly("shallow.txt");
		for (int i = 0; i < 10000; i++) file("entries/%05d.txt".formatted(i), "");
		assertThatThrownBy(() -> this.tools.globPaths(".", "**", null, null, context()))
			.hasMessageContaining("traversal exceeds 10000 entries");
	}

	@Test
	void rejectsUnboundedOrUnsupportedInputs() {
		for (int offset : List.of(-1, 10001))
			assertThatThrownBy(() -> this.tools.searchContent(".", "x", offset, 1, context())).isInstanceOf(IllegalArgumentException.class);
		for (int limit : List.of(0, 101))
			assertThatThrownBy(() -> this.tools.globPaths(".", "*", 0, limit, context())).isInstanceOf(IllegalArgumentException.class);
		for (String pattern : List.of("", "../*", "/**", "a/**b", "{a,b}", "[ab]", "x\\y", "x/".repeat(33)))
			assertThatThrownBy(() -> this.tools.globPaths(".", pattern, 0, 1, context())).isInstanceOf(IllegalArgumentException.class);
		for (String query : List.of("", "x".repeat(257), "x\n"))
			assertThatThrownBy(() -> this.tools.searchContent(".", query, 0, 1, context())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void providerPublishesCallableSearchSchemasAndReadonlyDeclarations() throws Exception {
		var properties = new AgentProperties();
		properties.setHome(this.outside);
		var config = new FsPluginAutoConfiguration();
		var provider = config.fileToolsProvider(properties,
				new DefaultListableBeanFactory().getBeanProvider(FileCheckpoints.class), List.of(this.outside.toString()), DataSize.ofBytes(1024));
		var declarations = config.stagedFilePolicy(provider, List.of(this.outside.toString())).declarations();
		file("Root.java", "needle");
		for (var callback : provider.getToolCallbacks()) {
			var definition = callback.getToolDefinition();
			if (!List.of("search_content", "glob_paths").contains(definition.name())) continue;
			var declaration = declarations.get(definition.name());
			assertThat(declaration.kind()).isEqualTo(StagingToolInterceptor.Kind.READ);
			assertThat(declaration.digest()).isEqualTo(SideEffectClass.definitionDigest(definition));
			assertThat(declaration.readRoots()).containsExactly(this.outside);
			var schema = JsonMapper.builder().build().readTree(definition.inputSchema());
			assertThat(schema.path("properties").has("path")).isTrue();
			assertThat(schema.path("properties").has("toolContext")).isFalse();
			String args = definition.name().equals("search_content")
				? "{\"path\":\".\",\"query\":\"needle\",\"offset\":0,\"limit\":1}"
				: "{\"path\":\".\",\"pattern\":\"**/*.java\",\"offset\":0,\"limit\":1}";
			var result = JsonMapper.builder().build().readTree(callback.call(args, context()));
			assertThat(result.path("matches").get(0).path("path").asString()).isEqualTo("Root.java");
			assertThatThrownBy(() -> callback.call(args.replace("\".\"", "\"" + this.work.getParent() + "\""), context()))
				.isInstanceOf(RuntimeException.class);
		}
		assertThat(declarations.keySet()).contains("search_content", "glob_paths", "read_file", "list_dir", "edit_file", "write_file");
		assertThat(declarations.get("write_file").kind()).isEqualTo(StagingToolInterceptor.Kind.FILE_WRITE);
	}

	@Test
	void managedSearchReadsStagedEditsAndNewPathsWithoutLeakingLiveOrPrivateContent() throws Exception {
		file("Source.java", "live");
		file(".env", "private");
		ChatModel model = prompt -> new ChatResponse(List.of());
		var binding = new ModelBinding(UUID.randomUUID(), new ModelRef("fake", "test"), "a".repeat(64),
				"fake", model, ChatClient.builder(model).build());
		var turn = new TurnContext("session", "turn", this.work.toRealPath(), binding, "run", "attempt");
		try (var stage = StagedWorkspace.open(new FileChangeStore(this.outside, this.work), turn, Clock.systemUTC())) {
			var context = new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString(), ToolContextKeys.STAGED_WORKSPACE, stage));
			this.tools.editFile("Source.java", "live", "staged", context);
			this.tools.writeFile("New.java", "staged", context);
			assertThat(this.tools.searchContent(this.work.toString(), "staged", 0, 10, context).matches())
				.extracting(FileSearch.Match::path).containsExactly("New.java", "Source.java");
			assertThat(this.tools.searchContent(".", "live", 0, 10, context).matches()).isEmpty();
			assertThat(this.tools.globPaths(".", "**/*.java", 0, 10, context).matches())
				.extracting(FileSearch.Match::path).containsExactly("New.java", "Source.java");
			assertThat(Files.readString(this.work.resolve("Source.java"))).isEqualTo("live");
			assertThat(this.work.resolve("New.java")).doesNotExist();
			for (String path : List.of(".env", "../", this.outside.toString())) {
				assertThatThrownBy(() -> this.tools.globPaths(path, "**", 0, 10, context)).isInstanceOf(RuntimeException.class);
				assertThatThrownBy(() -> this.tools.searchContent(path, "private", 0, 10, context)).isInstanceOf(RuntimeException.class);
			}
		}
	}
}
