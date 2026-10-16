package com.example.miniagent.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.artifact.ArtifactStore;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunRequestStore;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunStore;
import com.example.miniagent.core.run.ToolEvidenceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool result over the default 8192-character threshold becomes a descriptor, and read_artifact returns the
 * original, through the application's real interceptor chain, journal and default permission list.
 */
@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.plugins.memory.enabled=false", "agent.plugins.trace.level=off" })
@Import(AgentTestSupport.class)
class ArtifactWiringIntegrationTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/** The fake model echoes at most 800 characters of a tool result, so every probe stays well below that. */
	private static final int PROBE = 250;
	private static final int ROWS = 300;
	/** ArtifactProperties.thresholdChars default. */
	private static final int THRESHOLD = 8192;
	@TempDir static Path workspace;
	@TempDir static Path state;
	@DynamicPropertySource static void paths(DynamicPropertyRegistry properties) {
		properties.add("agent.cwd", () -> workspace.toString());
		properties.add("agent.home", () -> state.resolve("home").toString());
	}
	@Autowired Agent agent;
	@Autowired AgentSession session;
	@Autowired RunExecutor executor;
	@Autowired RunStore runs;
	@Autowired RunRequestStore requests;
	@Autowired ArtifactStore artifacts;

	@BeforeEach void freshSession() throws IOException {
		this.session.startNew();
		Files.writeString(workspace.resolve("big.txt"), IntStream.rangeClosed(1, ROWS)
				.mapToObj(row -> "row %03d %s".formatted(row, "x".repeat(30))).collect(Collectors.joining("\n")));
		Files.writeString(workspace.resolve("small.txt"), "tiny file");
	}

	private String turn(String directive) { return this.agent.run(directive).text(); }
	/** The echoed tool result: everything after the fake model's one-line header. */
	private static String echoed(String reply) { return reply.substring(reply.indexOf('\n') + 1); }
	private static JsonNode json(String reply) {
		String result = echoed(reply);
		assertThat(result).as("tool result as the model sees it").startsWith("{");
		return JSON.readTree(result);
	}
	private JsonNode descriptorOfBigFile() {
		String reply = turn("!tool read_file {\"path\":\"big.txt\"}");
		assertThat(reply).doesNotContain("row 300");
		return json(reply);
	}
	/** The first characters of the same output while it is still small enough to come back inline. */
	private String inlinePrefix() {
		String slice = echoed(turn("!tool read_file {\"path\":\"big.txt\",\"offset\":1,\"limit\":8}"));
		assertThat(slice).hasSizeGreaterThan(PROBE);
		return slice.substring(0, PROBE);
	}
	private JsonNode page(String artifactId, long offset, int limit) {
		return json(turn("!tool read_artifact " + JSON.writeValueAsString(
				Map.of("artifactId", artifactId, "offset", offset, "limit", limit))));
	}

	@Test void largeReadFileResultBecomesADescriptorAndReadArtifactReturnsTheOriginal() {
		var descriptor = descriptorOfBigFile();
		String id = descriptor.path("artifactId").asString();
		long bytes = descriptor.path("bytes").asLong();
		assertThat(id).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
		assertThat(bytes).isGreaterThan(THRESHOLD);
		assertThat(descriptor.path("sha256").asString()).matches("[0-9a-f]{64}");
		assertThat(descriptor.path("truncated").asBoolean()).isTrue();
		String prefix = inlinePrefix();
		assertThat(descriptor.path("preview").asString()).startsWith(prefix);
		var head = page(id, 0, PROBE);
		assertThat(head.path("content").asString()).isEqualTo(prefix);
		assertThat(head.path("truncated").asBoolean()).isTrue();
		var tail = page(id, bytes - 100, 100);
		assertThat(tail.path("content").asString()).endsWith("row 300 " + "x".repeat(30));
		assertThat(tail.path("truncated").asBoolean()).isFalse();
		assertThat(tail.path("nextOffset").asLong()).isEqualTo(bytes);
	}

	@Test void searchArtifactFindsALiteralInTheStoredOriginal() {
		String id = descriptorOfBigFile().path("artifactId").asString();
		var found = json(turn("!tool search_artifact "
				+ JSON.writeValueAsString(Map.of("artifactId", id, "query", "row 300"))));
		assertThat(found.path("matches")).hasSize(1);
		assertThat(found.path("matches").path(0).path("preview").asString()).startsWith("row 300");
		assertThat(found.path("truncated").asBoolean()).isFalse();
	}

	/** Controlled negative: below the threshold nothing is replaced and nothing is stored. */
	@Test void smallResultStaysInlineAndStoresNothing() throws IOException {
		long stored = storedArtifacts();
		String reply = turn("!tool read_file {\"path\":\"small.txt\"}");
		assertThat(echoed(reply)).contains("tiny file").doesNotContain("artifactId");
		assertThat(storedArtifacts()).isEqualTo(stored);
	}

	@Test void managedRunJournalsTheDescriptorBecauseTheJournalSitsOutsideTheArtifactInterceptor() throws Exception {
		String prefix = inlinePrefix();
		var result = this.executor.start("!tool read_file {\"path\":\"big.txt\"}", this.session.id(),
				this.agent.selectedModel(), workspace, RunBudget.defaults());
		assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
		var events = FileRunStore.events(this.runs, result.record().spec().runId());
		var finished = events.stream().filter(RunEvent.ToolFinished.class::isInstance).map(RunEvent.ToolFinished.class::cast)
				.findFirst().orElseThrow();
		String journaled = this.requests.evidence().get(finished.resultDigest(), ToolEvidenceStore.ResultEvidence.class).result();
		assertThat(journaled).as("journaled result").startsWith("{").doesNotContain("row 300");
		var descriptor = JSON.readTree(journaled);
		var turn = new TurnContext(result.record().spec().conversationId(), "turn", workspace,
				this.agent.modelRegistry().bind(this.agent.selectedModel()));
		assertThat(this.artifacts.read(turn, descriptor.path("artifactId").asString(), 0, PROBE).content()).isEqualTo(prefix);
		assertThat(result.reply().text()).contains(descriptor.path("artifactId").asString());
	}

	private long storedArtifacts() throws IOException {
		Path root = state.resolve("home").resolve("artifacts");
		if (!Files.exists(root)) return 0;
		try (Stream<Path> entries = Files.walk(root, 2)) {
			return entries.filter(path -> path.getFileName().toString().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")).count();
		}
	}
}
