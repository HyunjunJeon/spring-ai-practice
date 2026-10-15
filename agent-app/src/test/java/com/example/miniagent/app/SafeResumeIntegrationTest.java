package com.example.miniagent.app;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import com.example.miniagent.core.Agent;
import com.example.miniagent.core.approval.*;
import com.example.miniagent.core.model.FileSessionModelStore;
import com.example.miniagent.core.run.*;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.execution.StagingToolInterceptor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/approval-fixture", "agent.plugins.memory.enabled=false",
		"agent.plugins.permission.allow-non-interactive=true" })
@Import(ConsoleApprovalRoutingTest.Fixture.class)
class SafeResumeIntegrationTest {
	@TempDir static Path workspace;
	@TempDir static Path state;
	@DynamicPropertySource static void paths(DynamicPropertyRegistry properties) {
		properties.add("agent.home", () -> state.resolve("app-home").toString());
		properties.add("agent.cwd", () -> workspace.toString());
	}
	@Autowired Agent agent;
	@Autowired ApprovalBroker broker;
	@Autowired ConsoleApprovalRoutingTest.RoutingConsole console;
	@Autowired DriftGate gate;
	@Autowired StagingToolInterceptor staging;
	private FileChangeStore changes;
	private FileRunStore runs;
	private RunExecutor executor;
	private ExecutorService worker;
	@BeforeEach void setup() throws Exception {
		Path home = state.resolve("app-home");
		this.runs = new FileRunStore(home);
		this.changes = new FileChangeStore(home, workspace);
		this.executor = new RunExecutor(this.agent, this.agent.modelRegistry(), new FileSessionModelStore(home),
				this.runs, new RunRequestStore(home), Clock.systemUTC(), List.of(), this.broker, this.gate)
				.staging(this.staging, () -> this.changes);
		this.worker = Executors.newSingleThreadExecutor();
		this.console.questions.clear();
		this.console.setInteractive(true);
	}
	@AfterEach void cleanup() throws Exception {
		for (RunId id : this.runs.list()) if (!this.runs.read(id).state().terminal()) this.executor.cancel(id);
		this.worker.shutdownNow();
		assertThat(this.worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		assertThat(this.broker.pending()).isEmpty();
	}

	@Test void actualPermissionSettlesBeforeJournalAndRealFileEffect() throws Exception {
		Path sentinel = workspace.resolve("sentinel.txt");
		Files.writeString(sentinel, "unchanged");
		var finished = this.worker.submit(() -> this.executor.start(
				"!tool write_file {\"path\":\"sentinel.txt\",\"content\":\"changed\"}", "parent",
				this.agent.selectedModel(), workspace, RunBudget.defaults()));
		var request = this.console.questions.poll(5, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(this.runs.export(request.runId()).eventsLog()).contains("ApprovalRequested").doesNotContain("ToolStarted");
		assertThat(Files.readString(sentinel)).isEqualTo("unchanged");
		assertThat(this.broker.respond(request, ApprovalDecision.APPROVED)).isTrue();
		var result = finished.get(5, TimeUnit.SECONDS);
		assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(Files.readString(sentinel)).isEqualTo("unchanged");
		try (var lease = this.changes.acquire()) {
			var change = lease.read().changes().stream().map(ChangeStore.Snapshot::changeSet)
					.filter(c -> c.owner().runId().equals(result.record().spec().runId())).findFirst().orElseThrow();
			assertThat(change.status()).isEqualTo(ChangeStatus.PENDING_APPROVAL);
			assertThat(Files.readString(lease.stagingDirectory(change.owner()).resolve("sentinel.txt")))
					.isEqualTo("changed");
		}
		String log = this.runs.export(request.runId()).eventsLog();
		assertThat(log.indexOf("ApprovalResolved")).isLessThan(log.indexOf("ToolStarted"));
		assertThat(log.indexOf("ToolStarted")).isLessThan(log.indexOf("ToolFinished"));
		assertThat(log).doesNotContain("\"toolCallKey\":\"write_file\",\"toolName\"");
		assertThat(new JournalResumeContributor(this.runs, new ToolEvidenceStore(state.resolve("app-home")))
				.verifiedEvidence(result.record(), "goal")).contains("write_file", "MUTATING");
	}

	@Test void actualTrustedBuiltInReadOnlyAndNoninteractiveDenial() throws Exception {
		var read = this.executor.start("!tool list_dir {\"path\":\".\"}", "parent",
				this.agent.selectedModel(), workspace, RunBudget.defaults());
		assertThat(read.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(this.runs.export(read.record().spec().runId()).eventsLog()).contains("\"effect\":\"READ_ONLY\"");
		this.console.setInteractive(false);
		var deferred = this.executor.start("!tool write_file {\"path\":\"pending.txt\",\"content\":\"blocked\"}",
				"parent", this.agent.selectedModel(), workspace, RunBudget.defaults());
		assertThat(deferred.record().state()).isEqualTo(RunState.WAITING_APPROVAL);
		assertThat(workspace.resolve("pending.txt")).doesNotExist();
		assertThat(this.runs.export(deferred.record().spec().runId()).eventsLog()).doesNotContain("ToolStarted");
		this.executor.cancel(deferred.record().spec().runId());
	}

	@Test void actualBudgetGateDoesNotCreateApprovalOrJournalStart() throws Exception {
		var result = this.executor.start("!tool write_file {\"path\":\"budget.txt\",\"content\":\"blocked\"}",
				"parent", this.agent.selectedModel(), workspace,
				new RunBudget(java.time.Duration.ofMinutes(1), null, 0));
		assertThat(result.record().reason()).isEqualTo(RunEvent.Reason.BUDGET_EXHAUSTED);
		assertThat(this.console.questions).isEmpty();
		assertThat(this.runs.export(result.record().spec().runId()).eventsLog()).doesNotContain("ToolStarted");
		assertThat(workspace.resolve("budget.txt")).doesNotExist();
	}
}
