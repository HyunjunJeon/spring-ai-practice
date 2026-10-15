package com.example.miniagent.app;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.approval.*;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.run.*;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.execution.StagingToolInterceptor;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/approval-fixture", "agent.plugins.memory.enabled=false",
		"agent.plugins.permission.allow-non-interactive=true" })
@Import(ConsoleApprovalRoutingTest.Fixture.class)
class ConsoleApprovalRoutingTest {
	@TempDir static Path workspace;
	@TempDir static Path state;
	@DynamicPropertySource static void paths(DynamicPropertyRegistry properties) {
		properties.add("agent.home", () -> state.resolve("app-home").toString());
		properties.add("agent.cwd", () -> workspace.toString());
	}
	@Autowired Agent agent;
	@Autowired RoutingConsole console;
	@Autowired ApprovalBroker broker;
	@Autowired StagingToolInterceptor staging;
	private FileChangeStore changes;
	private FileRunStore runs;
	private RunExecutor executor;
	private ExecutorService worker;
	private Path sentinel;

	@BeforeEach void setup() throws Exception {
		Path home = Files.createTempDirectory(state, "run-");
		this.runs = new FileRunStore(home);
		this.changes = new FileChangeStore(home, workspace);
		this.executor = new RunExecutor(this.agent, this.agent.modelRegistry(), new FileSessionModelStore(home),
				this.runs, new RunRequestStore(home), Clock.systemUTC(), List.of(), this.broker)
				.staging(this.staging, () -> this.changes);
		this.worker = Executors.newSingleThreadExecutor();
		this.console.questions.clear();
		this.console.setInteractive(true);
		this.sentinel = workspace.resolve("sentinel.txt");
		Files.writeString(this.sentinel, "unchanged");
	}

	@AfterEach void cleanup() throws Exception {
		for (RunId id : this.runs.list()) {
			if (!this.runs.read(id).state().terminal()) this.executor.cancel(id);
		}
		this.worker.shutdownNow();
		assertThat(this.worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		assertThat(this.broker.pending()).isEmpty();
	}

	private RunExecutor.Result start() throws Exception {
		return this.executor.start("!tool write_file {\"path\":\"sentinel.txt\",\"content\":\"changed\"}", "parent",
				this.agent.selectedModel(), workspace.toRealPath(), RunBudget.defaults());
	}

	private ApprovalRequest question() throws Exception {
		ApprovalRequest request = this.console.questions.poll(5, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		return request;
	}

	@Test void unattendedMutatingToolDefersWithoutImplicitYes() throws Exception {
		this.console.setInteractive(false);
		var result = start();
		assertThat(result.record().state()).isEqualTo(RunState.WAITING_APPROVAL);
		assertThat(result.record().reason()).isNotEqualTo(RunEvent.Reason.USER_CANCELLED);
		assertThat(Files.readString(this.sentinel)).isEqualTo("unchanged");
		assertThat(this.broker.pending()).hasSize(1);
		assertThatThrownBy(() -> this.runs.openWriter(result.record().spec().runId()))
				.isInstanceOf(RunStoreException.class);
		assertThat(this.runs.export(result.record().spec().runId()).eventsLog())
				.contains("ApprovalRequested", "DEFERRED").doesNotContain("ToolFinished");
	}

	@Test void deliberateRejectPreservesFileAndReturnsPermissionResult() throws Exception {
		var completed = this.worker.submit(this::start);
		ApprovalRequest request = question();
		assertThat(this.broker.respond(request, ApprovalDecision.REJECTED)).isTrue();
		var result = completed.get(5, TimeUnit.SECONDS);
		assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(result.reply().text()).contains("Permission denied");
		assertThat(Files.readString(this.sentinel)).isEqualTo("unchanged");
	}

	@Test void approvedRealPermissionChainStagesFileWithoutApplying() throws Exception {
		var completed = this.worker.submit(this::start);
		ApprovalRequest request = question();
		assertThat(request.argsDigest()).isEqualTo(ApprovalRequest.digest(
				"{\"path\":\"sentinel.txt\",\"content\":\"changed\"}"));
		this.broker.respond(request, ApprovalDecision.APPROVED);
		var record = completed.get(5, TimeUnit.SECONDS).record();
		assertThat(record.state()).isEqualTo(RunState.COMPLETED);
		assertStagedWrite(record.spec().runId());
	}

	@Test void lateIdCannotWriteDuringNewQuestion() throws Exception {
		var first = this.worker.submit(this::start);
		ApprovalRequest old = question();
		this.broker.respond(old, ApprovalDecision.REJECTED);
		first.get(5, TimeUnit.SECONDS);
		var next = this.worker.submit(this::start);
		ApprovalRequest current = question();
		assertThat(this.broker.respond(old, ApprovalDecision.APPROVED)).isFalse();
		assertThat(next).isNotDone();
		assertThat(Files.readString(this.sentinel)).isEqualTo("unchanged");
		this.broker.respond(current, ApprovalDecision.REJECTED);
		next.get(5, TimeUnit.SECONDS);
	}

	@Test void deferredResolutionReleasesLeaseButOnlyExplicitResumeExecutesExactGrant() throws Exception {
		this.console.setInteractive(false);
		var waiting = start().record();
		ApprovalRequest request = this.broker.pending().getFirst();
		assertThatThrownBy(() -> this.executor.resume(waiting.spec().runId(), this.agent.selectedModel(), workspace))
				.isInstanceOf(RunExecutor.Failure.class);
		var resolved = this.executor.resolveApproval(request, ApprovalDecision.APPROVED);
		assertThat(resolved.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(resolved.attempts().getFirst().endedAt()).isNotNull();
		assertThat(Files.readString(this.sentinel)).isEqualTo("unchanged");
		try (RunWriter writer = this.runs.openWriter(waiting.spec().runId())) {
			assertThat(writer.record()).isEqualTo(resolved);
		}
		var resumed = this.executor.resume(waiting.spec().runId(), this.agent.selectedModel(), workspace);
		assertThat(resumed.record().state()).isEqualTo(RunState.COMPLETED);
		assertStagedWrite(resumed.record().spec().runId());
		assertThat(this.broker.respond(request, ApprovalDecision.APPROVED)).isFalse();
	}

	@Test void cancelWhileWaitingReleasesWriterAndInvalidatesQuestion() throws Exception {
		var completed = this.worker.submit(this::start);
		ApprovalRequest request = question();
		this.executor.cancel(request.runId());
		assertThat(completed.get(5, TimeUnit.SECONDS).record().state()).isEqualTo(RunState.CANCELLED);
		assertThat(this.broker.respond(request, ApprovalDecision.APPROVED)).isFalse();
		assertThat(Files.readString(this.sentinel)).isEqualTo("unchanged");
		try (RunWriter writer = this.runs.openWriter(request.runId())) {
			assertThat(writer.record().reason()).isEqualTo(RunEvent.Reason.USER_CANCELLED);
		}
		assertThat(this.runs.export(request.runId()).eventsLog()).contains("\"decision\":\"CANCELLED\"");
	}

	@Test void deferredRejectionIsPersistedAndExplicitResumeCannotWrite() throws Exception {
		this.console.setInteractive(false);
		var waiting = start().record();
		ApprovalRequest request = this.broker.pending().getFirst();
		this.executor.resolveApproval(request, ApprovalDecision.REJECTED);
		var resumed = this.executor.resume(waiting.spec().runId(), this.agent.selectedModel(), workspace);
		assertThat(resumed.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(resumed.reply().text()).contains("Permission denied");
		assertThat(Files.readString(this.sentinel)).isEqualTo("unchanged");
	}

	@Test void zeroBudgetStopsBeforePermissionTransport() throws Exception {
		var result = this.executor.start("!tool write_file {\"path\":\"sentinel.txt\",\"content\":\"changed\"}",
				"parent", this.agent.selectedModel(), workspace, new RunBudget(java.time.Duration.ofMinutes(1), null, 0));
		assertThat(result.record().reason()).isEqualTo(RunEvent.Reason.BUDGET_EXHAUSTED);
		assertThat(this.console.questions).isEmpty();
		assertThat(this.broker.pending()).isEmpty();
		assertThat(Files.readString(this.sentinel)).isEqualTo("unchanged");
	}

	private void assertStagedWrite(RunId id) throws Exception {
		assertThat(Files.readString(this.sentinel)).isEqualTo("unchanged");
		try (var lease = this.changes.acquire()) {
			var change = lease.read().changes().stream().map(ChangeStore.Snapshot::changeSet)
					.filter(c -> c.owner().runId().equals(id) && c.status() == ChangeStatus.PENDING_APPROVAL)
					.findFirst().orElseThrow();
			assertThat(change.diffs()).extracting(Diff::relativePath).containsExactly("sentinel.txt");
			assertThat(Files.readString(lease.stagingDirectory(change.owner()).resolve("sentinel.txt")))
					.isEqualTo("changed");
		}
	}

	@TestConfiguration static class Fixture {
		@Bean RoutingConsole agentConsole() { return new RoutingConsole(); }
		@Bean ModelProvider fakeModelProvider() {
			return new ModelProvider() {
				@Override public String providerId() { return "fake"; }
				@Override public ProviderAuth auth() { return () -> true; }
				@Override public List<ModelEntry> models() {
					return List.of(new ModelEntry(new ModelRef("fake", "approval-fixture"), "fake", null));
				}
				@Override public ChatModel create(ModelRef ref, Map<String, String> options) {
					return new ScriptedChatModel() {
						@Override public ChatResponse call(Prompt prompt) {
							var messages = new java.util.ArrayList<>(prompt.getInstructions());
							var last = messages.getLast();
							if (last instanceof UserMessage user && user.getText().startsWith("Explicitly resume")) {
								String original = user.getText().substring(user.getText().indexOf("!tool"));
								messages.set(messages.size() - 1, new UserMessage(original));
								return super.call(new Prompt(messages, prompt.getOptions()));
							}
							return super.call(prompt);
						}
					};
				}
			};
		}
	}

	static final class RoutingConsole extends AgentConsole {
		final BlockingQueue<ApprovalRequest> questions = new LinkedBlockingQueue<>();
		RoutingConsole() {
			super(new BufferedReader(new StringReader("")), new PrintStream(java.io.OutputStream.nullOutputStream()), true);
		}
		@Override public void requestApproval(ApprovalRequest request, Consumer<ApprovalAnswer> response) {
			this.questions.add(request);
		}
	}
}
