package com.example.miniagent.app;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.*;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.model.*;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/changes", "agent.plugins.memory.enabled=false",
		"agent.plugins.trace.level=off", "agent.plugins.permission.auto-allow-tools=write_file",
		"agent.plugins.permission.allow-non-interactive=true" })
@Import(ChangeCommandsE2ETest.Fixture.class)
class ChangeCommandsE2ETest {
	@TempDir static Path workspace;
	@TempDir static Path state;
	@DynamicPropertySource static void paths(DynamicPropertyRegistry properties) {
		properties.add("agent.cwd", () -> workspace.toString());
		properties.add("agent.home", () -> state.resolve("home").toString());
	}
	@Autowired AgentInputHandler handler;
	@Autowired ChangeStore changes;
	@Autowired ApprovalBroker broker;
	@Autowired Screen console;
	@Autowired AgentSession session;
	private ExecutorService worker;
	private Path file;

	@BeforeEach void setup() throws Exception {
		this.worker = Executors.newSingleThreadExecutor();
		this.console.setInteractive(false);
		this.console.questions.clear();
		this.console.lines.clear();
		this.console.output.reset();
		this.file = workspace.resolve("fixture-" + UUID.randomUUID() + ".txt");
		Files.writeString(this.file, "before");
	}
	@AfterEach void cleanup() throws Exception {
		for (var request : this.broker.pending()) this.broker.cancel(request);
		this.worker.shutdownNow();
		assertThat(this.worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		assertThat(this.broker.pending()).isEmpty();
	}
	private ChangeSet stage() throws Exception {
		var before = this.changes.read().changes().stream().map(s -> s.changeSet().changeSetId()).toList();
		String input = "/run start !tool write_file {\"path\":\"" + this.file.getFileName() + "\",\"content\":\"after\"}";
		assertThat(this.handler.handle(input)).as("Console: %s", this.console.output).isTrue();
		assertThat(Files.readString(this.file)).isEqualTo("before");
		return this.changes.read().changes().stream().map(ChangeStore.Snapshot::changeSet)
				.filter(c -> !before.contains(c.changeSetId())).findFirst().orElseThrow();
	}
	private String ownership(ChangeSet set) { return "exclusive " + set.changeSetId() + " " + set.digest(); }
	private void apply(ChangeSet set) throws Exception {
		this.console.setInteractive(true);
		this.console.lines.add(ownership(set));
		var task = this.worker.submit(() -> this.handler.handle("/apply " + set.changeSetId()));
		var question = this.console.questions.poll(10, TimeUnit.SECONDS);
		assertThat(question).isNotNull();
		assertThat(question.diffDigest()).isEqualTo(set.digest());
		assertThat(question.runId()).isEqualTo(set.owner().runId());
		assertThat(question.attemptId()).isEqualTo(set.owner().attemptId());
		assertThat(this.broker.respond(question, ApprovalDecision.APPROVED)).isTrue();
		assertThat(task.get(15, TimeUnit.SECONDS)).as("Console: %s", this.console.output).isTrue();
		assertThat(Files.readString(this.file)).isEqualTo("after");
	}
	@Test void turnChangesApprovedApplyRealVerificationAndUndo() throws Exception {
		String parent = this.session.id();
		var set = stage();
		assertThat(this.handler.handle("/changes " + set.changeSetId())).isTrue();
		assertThat(this.console.output.toString()).contains(set.digest(), this.file.getFileName().toString());
		apply(set);
		assertThat(this.changes.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.APPLIED);
		assertThat(this.handler.handle("/verify " + set.changeSetId() + " [\"java\",\"-version\"]"))
				.as("Console: %s", this.console.output).isTrue();
		var evidence = this.changes.read().get(set.changeSetId()).evidence().getLast();
		assertThat(evidence.argv()).containsExactly("java", "-version");
		assertThat(evidence.sourceCwd()).isEqualTo(workspace.toRealPath().toString());
		assertThat(evidence.cwd()).isNotEqualTo(evidence.sourceCwd());
		assertThat(evidence.exitCode()).isZero();
		assertThat(evidence.cancelled()).isFalse();
		try (var lease = this.changes.acquire()) {
			assertThat(Fingerprinter.sha256(lease.readLog(set.changeSetId(), set.owner(), set.digest(), evidence.evidenceId())))
					.isEqualTo(evidence.logSha256());
		}
		this.console.lines.add(ownership(set));
		assertThat(this.handler.handle("/undo " + set.changeSetId())).as("Console: %s", this.console.output).isTrue();
		assertThat(Files.readString(this.file)).isEqualTo("before");
		assertThat(this.changes.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		assertThat(this.session.id()).isEqualTo(parent);
	}
	@Test void unattendedApplyStaysPendingWithoutSourceWrites() throws Exception {
		var set = stage();
		assertThat(this.handler.handle("/apply " + set.changeSetId())).isFalse();
		assertThat(this.changes.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.PENDING_APPROVAL);
		assertThat(Files.readString(this.file)).isEqualTo("before");
		assertThat(this.broker.pending()).hasSize(1);
	}
	@Test void rejectedApplyPreservesLiveAndRecordsExactOwner() throws Exception {
		var set = stage();
		this.console.setInteractive(true);
		var task = this.worker.submit(() -> this.handler.handle("/apply " + set.changeSetId()));
		var question = this.console.questions.poll(10, TimeUnit.SECONDS);
		assertThat(question).isNotNull();
		this.broker.respond(question, ApprovalDecision.REJECTED);
		assertThat(task.get(10, TimeUnit.SECONDS)).isFalse();
		var decided = this.changes.read().get(set.changeSetId()).changeSet();
		assertThat(decided.status()).isEqualTo(ChangeStatus.REJECTED);
		assertThat(decided.approval().owner()).isEqualTo(set.owner());
		assertThat(Files.readString(this.file)).isEqualTo("before");
	}
	@Test void applyWhileBusyRejected() throws Exception {
		var set = stage();
		this.console.setInteractive(true);
		var first = this.worker.submit(() -> this.handler.handle("/apply " + set.changeSetId()));
		var question = this.console.questions.poll(10, TimeUnit.SECONDS);
		assertThat(question).isNotNull();
		try {
			assertThat(this.handler.handle("/apply " + set.changeSetId())).isFalse();
			assertThat(this.handler.handle("/changes " + set.changeSetId())).isTrue();
			assertThat(this.console.questions).isEmpty();
			assertThat(this.broker.pending()).containsExactly(question);
			assertThat(Files.readString(this.file)).isEqualTo("before");
		}
		finally { this.broker.respond(question, ApprovalDecision.REJECTED); }
		assertThat(first.get(10, TimeUnit.SECONDS)).isFalse();
	}
	@Test void humanEditAfterApplyIsReportedAndPreservedByUndo() throws Exception {
		var set = stage();
		apply(set);
		Files.writeString(this.file, "human");
		this.console.lines.add(ownership(set));
		assertThat(this.handler.handle("/undo " + set.changeSetId())).isFalse();
		assertThat(Files.readString(this.file)).isEqualTo("human");
		assertThat(this.changes.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.PARTIALLY_UNDONE);
		assertThat(this.console.output.toString()).contains("CONFLICT");
	}
	@Test void nonzeroVerificationKeepsActualArgvExitAndSource() throws Exception {
		var set = stage();
		assertThat(this.handler.handle("/verify " + set.changeSetId() + " [\"/bin/sh\",\"-c\",\"exit 7\"]")).isFalse();
		var evidence = this.changes.read().get(set.changeSetId()).evidence().getLast();
		assertThat(evidence.argv()).containsExactly("/bin/sh", "-c", "exit 7");
		assertThat(evidence.exitCode()).isEqualTo(7);
		assertThat(evidence.successful()).isFalse();
		assertThat(Files.readString(this.file)).isEqualTo("before");
	}
	@Test void legacyUndoRetainsHumanEditAndReturnsConflict() throws Exception {
		assertThat(this.handler.handle("!tool write_file {\"path\":\"" + this.file.getFileName() + "\",\"content\":\"legacy\"}")).isTrue();
		assertThat(Files.readString(this.file)).isEqualTo("legacy");
		Files.writeString(this.file, "human");
		assertThat(this.handler.handle("/undo")).isFalse();
		assertThat(Files.readString(this.file)).isEqualTo("human");
		assertThat(this.console.output.toString()).contains("CONFLICT");
	}
	@TestConfiguration static class Fixture {
		@Bean Screen agentConsole() { return new Screen(); }
		@Bean ModelProvider fakeModelProvider() {
			return new ModelProvider() {
				public String providerId() { return "fake"; }
				public ProviderAuth auth() { return () -> true; }
				public List<ModelEntry> models() { return List.of(new ModelEntry(new ModelRef("fake", "changes"), "fake", null)); }
				public ChatModel create(ModelRef ref, Map<String, String> options) { return new ScriptedChatModel(); }
			};
		}
	}
	static final class Screen extends AgentConsole {
		final ByteArrayOutputStream output;
		final BlockingQueue<ApprovalRequest> questions = new LinkedBlockingQueue<>();
		final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		Screen() { this(new ByteArrayOutputStream()); }
		private Screen(ByteArrayOutputStream output) {
			super(new BufferedReader(new StringReader("")), new PrintStream(output), false);
			this.output = output;
		}
		@Override public void requestApproval(ApprovalRequest request, Consumer<ApprovalAnswer> response) { this.questions.add(request); }
		@Override public String readLine(String prompt) {
			try { return this.lines.poll(10, TimeUnit.SECONDS); }
			catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new CancellationException(); }
		}
	}
}
