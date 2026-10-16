package com.example.miniagent.app;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.approval.ApprovalAnswer;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.change.FileChangeStore;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.execution.StagingToolInterceptor;
import com.example.miniagent.core.model.FileSessionModelStore;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunRequestStore;
import com.example.miniagent.core.run.RunState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 콘솔에서 [a]lways 로 답한 도구라도 관리되는 실행에서는 매번 승인 요청이 나가야 한다. */
@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false", "agent.plugins.memory.enabled=false",
		"agent.plugins.trace.level=off" })
@Import(PermissionAlwaysIsolationTest.Fixture.class)
class PermissionAlwaysIsolationTest {

	@TempDir
	static Path workspace;

	@TempDir
	static Path state;

	@DynamicPropertySource
	static void paths(DynamicPropertyRegistry properties) {
		properties.add("agent.home", () -> state.resolve("app-home").toString());
		properties.add("agent.cwd", () -> workspace.toString());
	}

	@Autowired
	Agent agent;

	@Autowired
	LegacyAlwaysConsole console;

	@Autowired
	ApprovalBroker broker;

	@Autowired
	StagingToolInterceptor staging;

	private FileRunStore runs;

	private RunExecutor executor;

	private ExecutorService worker;

	@BeforeEach
	void setup() throws Exception {
		Path home = Files.createTempDirectory(state, "run-");
		this.runs = new FileRunStore(home);
		FileChangeStore changes = new FileChangeStore(home, workspace);
		this.executor = new RunExecutor(this.agent, this.agent.modelRegistry(), new FileSessionModelStore(home),
				this.runs, new RunRequestStore(home), Clock.systemUTC(), List.of(), this.broker)
			.staging(this.staging, () -> changes);
		this.worker = Executors.newSingleThreadExecutor();
		this.console.legacyPrompts.clear();
		this.console.questions.clear();
	}

	@AfterEach
	void cleanup() throws Exception {
		for (RunId id : this.runs.list()) {
			if (!this.runs.read(id).state().terminal()) {
				this.executor.cancel(id);
			}
		}
		this.worker.shutdownNow();
		assertThat(this.worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		assertThat(this.broker.pending()).isEmpty();
	}

	@Test
	void legacyAlwaysAnswerDoesNotPreApproveTheSameToolInAManagedRun() throws Exception {
		Path sentinel = workspace.resolve("sentinel.txt");
		Files.writeString(sentinel, "unchanged");

		// 레거시 턴: 첫 write_file 은 묻고, [a]lways 로 답하면 두 번째는 묻지 않고 실행된다.
		this.agent.run("!tool write_file {\"path\":\"legacy-one.txt\",\"content\":\"one\"}");
		this.agent.run("!tool write_file {\"path\":\"legacy-two.txt\",\"content\":\"two\"}");
		assertThat(this.console.legacyPrompts).hasSize(1);
		assertThat(Files.readString(workspace.resolve("legacy-one.txt"))).isEqualTo("one");
		assertThat(Files.readString(workspace.resolve("legacy-two.txt"))).isEqualTo("two");

		// 관리되는 실행: 같은 도구는 승인 요청이 나가야 하고, 거절하면 파일은 그대로다.
		var completed = this.worker.submit(() -> this.executor.start(
				"!tool write_file {\"path\":\"sentinel.txt\",\"content\":\"managed\"}", "parent",
				this.agent.selectedModel(), workspace.toRealPath(), RunBudget.defaults()));
		ApprovalRequest request = this.console.questions.poll(5, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.toolCallKey()).isEqualTo("write_file");
		assertThat(request.argsDigest()).isEqualTo(ApprovalRequest.digest(
				"{\"path\":\"sentinel.txt\",\"content\":\"managed\"}"));
		assertThat(Files.readString(sentinel)).isEqualTo("unchanged");
		assertThat(this.broker.respond(request, ApprovalDecision.REJECTED)).isTrue();

		var result = completed.get(5, TimeUnit.SECONDS);
		assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(result.reply().text()).contains("Permission denied");
		assertThat(Files.readString(sentinel)).isEqualTo("unchanged");
		assertThat(this.console.legacyPrompts).hasSize(1);
	}

	/** 레거시 확인 질문에는 항상 "a" 로 답하고, 관리되는 실행의 승인 질문은 큐에 모은다. */
	static final class LegacyAlwaysConsole extends AgentConsole {

		final List<String> legacyPrompts = new CopyOnWriteArrayList<>();

		final BlockingQueue<ApprovalRequest> questions = new LinkedBlockingQueue<>();

		LegacyAlwaysConsole() {
			super(new BufferedReader(new StringReader("")), new PrintStream(OutputStream.nullOutputStream()), true);
		}

		@Override
		public synchronized String readLine(String prompt) {
			this.legacyPrompts.add(prompt);
			return "a";
		}

		@Override
		public void requestApproval(ApprovalRequest request, Consumer<ApprovalAnswer> response) {
			this.questions.add(request);
		}
	}

	@TestConfiguration
	static class Fixture {

		@Bean
		LegacyAlwaysConsole agentConsole() {
			return new LegacyAlwaysConsole();
		}
	}
}
