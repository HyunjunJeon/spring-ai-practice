package com.example.miniagent.app;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.change.PathPolicy;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real permission interceptor (order +20) is enabled and write_file is not on its allow-list.
 * Staging (+5) must refuse an excluded path before the operator is ever asked.
 */
@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/staged-apply", "agent.plugins.memory.enabled=false",
		"agent.plugins.trace.level=off", "agent.plugins.permission.auto-allow-tools=read_file" })
@Import(StagedApplyTest.Fixture.class)
class PermissionOrderingTest {
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
	@Autowired ApprovalBroker approvals;
	@Autowired ChangeCommandsE2ETest.Screen screen;

	private ExecutorService worker;

	@BeforeEach void freshConsole() {
		this.worker = Executors.newSingleThreadExecutor();
		this.screen.setInteractive(true);
		this.screen.questions.clear();
		this.screen.lines.clear();
		this.session.startNew();
	}
	@AfterEach void cleanup() throws Exception {
		for (var request : this.approvals.pending()) this.approvals.cancel(request);
		this.worker.shutdownNow();
		assertThat(this.worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		assertThat(this.approvals.pending()).isEmpty();
	}
	private Future<RunExecutor.Result> start(String goal) {
		return this.worker.submit(() -> this.executor.start(goal, this.session.id(), this.agent.selectedModel(),
				workspace, RunBudget.defaults()));
	}

	@Test void excludedPathIsRefusedByStagingBeforeAnyApprovalQuestion() throws Exception {
		var before = this.runs.list();
		String goal = "!tool write_file {\"path\":\".env\",\"content\":\"SECRET=1\"}";
		var run = start(goal);
		// A bounded wait: if permission ran first, the run would sit on the question instead of failing.
		assertThatThrownBy(() -> run.get(15, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
				.rootCause().isInstanceOfSatisfying(PathPolicy.Violation.class,
						ex -> assertThat(ex.code()).isEqualTo(PathPolicy.Code.EXCLUDED));
		assertThat(this.screen.questions).isEmpty();
		assertThat(this.approvals.pending()).isEmpty();
		assertThat(workspace.resolve(".env")).doesNotExist();
		var created = this.runs.list().stream().filter(id -> !before.contains(id)).toList();
		assertThat(created).hasSize(1);
		var record = this.runs.read(created.getFirst());
		assertThat(record.state()).isEqualTo(RunState.FAILED);
		assertThat(record.usage().toolCalls()).isZero();
		assertThat(FileRunStore.events(this.runs, record.spec().runId()))
				.noneMatch(event -> event instanceof RunEvent.ToolStarted || event instanceof RunEvent.ApprovalRequested);
	}

	/** Without this control the test above would also pass if the permission interceptor were silently off. */
	@Test void permittedPathStillReachesTheApprovalQuestion() throws Exception {
		String goal = "!tool write_file {\"path\":\"allowed.txt\",\"content\":\"data\"}";
		var run = start(goal);
		var question = this.screen.questions.poll(10, TimeUnit.SECONDS);
		assertThat(question).isNotNull();
		assertThat(this.approvals.respond(question, ApprovalDecision.REJECTED)).isTrue();
		var result = run.get(15, TimeUnit.SECONDS);
		assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(question.runId()).isEqualTo(result.record().spec().runId());
		assertThat(result.reply().text()).contains("Permission denied", "write_file");
		assertThat(workspace.resolve("allowed.txt")).doesNotExist();
		assertThat(this.screen.questions).isEmpty();
	}
}
