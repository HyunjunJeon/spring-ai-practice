package com.example.miniagent.app;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.*;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.run.*;
import com.example.miniagent.core.run.command.RunCommand;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.definition.ToolDefinition;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/run-fixture", "agent.plugins.memory.enabled=true",
		"agent.plugins.permission.allow-non-interactive=true", "agent.plugins.trace.level=off" })
@Import(RunCommandTest.Fixture.class)
class RunCommandTest {
	@TempDir static Path workspace;
	@TempDir static Path state;
	@DynamicPropertySource static void paths(DynamicPropertyRegistry properties) {
		properties.add("agent.home", () -> state.resolve(".home").toString());
		properties.add("agent.cwd", () -> workspace.toString());
	}
	@Autowired Agent agent;
	@Autowired AgentSession session;
	@Autowired AgentInputHandler handler;
	@Autowired RunCommand command;
	@Autowired RunStore runs;
	@Autowired RunExecutor executor;
	@Autowired RunRequestStore requests;
	@Autowired ApprovalBroker broker;
	@Autowired SessionModelStore selections;
	@Autowired JournalResumeContributor evidence;
	@Autowired ApplicationEventPublisher events;
	@Autowired TestConsole console;
	private ExecutorService worker;
	@BeforeEach void setup() {
		this.worker = Executors.newSingleThreadExecutor();
		this.console.setInteractive(false);
		this.console.questions.clear();
		this.console.output.reset();
	}
	@AfterEach void cleanup() throws Exception {
		for (RunId id : this.runs.list()) if (!this.runs.read(id).state().terminal()) this.executor.cancel(id);
		this.worker.shutdownNow();
		assertThat(this.worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		assertThat(this.broker.pending()).isEmpty();
	}
	private RunId start(String goal, boolean success) throws Exception {
		Set<RunId> before = Set.copyOf(this.runs.list());
		assertThat(this.handler.handle("/run start " + goal)).isEqualTo(success);
		return this.runs.list().stream().filter(id -> !before.contains(id)).findFirst().orElseThrow();
	}
	@Test void startCompletedRunUsesDedicatedDurableIdentity() throws Exception {
		String parent = this.session.id();
		RunId id = start("hello", true);
		RunRecord record = this.runs.read(id);
		assertThat(record.state()).isEqualTo(RunState.COMPLETED);
		assertThat(record.spec().conversationId()).isNotEqualTo(parent).isNotEqualTo(id.value());
		assertThat(this.selections.load(record.spec().conversationId())).contains(this.requests.load(record.spec()).selection());
		assertThat(this.handler.handle("/runs")).isTrue();
		assertThat(this.handler.handle("/run show " + id.value())).isTrue();
		assertThat(this.session.id()).isEqualTo(parent);
	}
	@Test void resumeCompletedRunRejected() throws Exception {
		RunId id = start("hello", true);
		String before = this.runs.export(id).eventsLog();
		assertThat(this.handler.handle("/run resume " + id.value())).isFalse();
		assertThat(this.runs.export(id).eventsLog()).isEqualTo(before);
	}
	@Test void interruptedRunResumesStoredSelectionNotActiveConsole() throws Exception {
		RunId id = start("!interrupt", false);
		var stored = this.requests.load(this.runs.read(id).spec()).selection();
		this.agent.selectModel(new ModelSelection(stored.ref(), Map.of("temperature", "0.2")));
		try {
			assertThat(this.handler.handle("/run resume " + id.value()))
					.as("Console output: %s", this.console.output).isTrue();
			assertThat(this.runs.read(id).attempts()).hasSize(2);
			assertThat(this.runs.read(id).spec().optionsFingerprint()).isEqualTo(stored.optionsFingerprint());
		}
		finally { this.agent.selectModel(stored); }
	}
	@Test void budgetInterruptedOutcomeIsFalseBeforePermissionOrMutation() throws Exception {
		RunCommand limited = new RunCommand(this.executor, this.runs, this.requests, this.broker,
				new RunProperties(Duration.ofMinutes(1), null, 0), workspace, this.evidence, this.events);
		Set<RunId> before = Set.copyOf(this.runs.list());
		assertThat(limited.executeWithStatus("start !tool write_file {\"path\":\"budget.txt\",\"content\":\"no\"}",
				new SlashCommand.CommandContext(this.agent, this.session, this.console))).isFalse();
		RunRecord record = this.runs.read(this.runs.list().stream().filter(id -> !before.contains(id)).findFirst().orElseThrow());
		assertThat(record.reason()).isEqualTo(RunEvent.Reason.BUDGET_EXHAUSTED);
		assertThat(workspace.resolve("budget.txt")).doesNotExist();
		assertThat(this.broker.pending()).isEmpty();
	}
	@Test void pendingDeferResolveDoesNotMutateUntilExplicitResume() throws Exception {
		Path file = workspace.resolve("sentinel.txt");
		Files.writeString(file, "before");
		RunId id = start("!tool write_file {\"path\":\"sentinel.txt\",\"content\":\"after\"}", false);
		assertThat(this.runs.read(id).state()).isEqualTo(RunState.WAITING_APPROVAL);
		var request = this.broker.pending().getFirst();
		assertThat(this.handler.handle("/run approve " + id.value() + " " + UUID.randomUUID())).isFalse();
		assertThat(this.handler.handle("/run approve " + id.value() + " " + request.requestId())).isTrue();
		assertThat(this.runs.read(id).state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(Files.readString(file)).isEqualTo("before");
		assertThat(this.handler.handle("/run resume " + id.value()))
				.as("Console output: %s", this.console.output).isTrue();
		assertThat(Files.readString(file)).isEqualTo("before");
		var store = new com.example.miniagent.core.change.FileChangeStore(state.resolve(".home"), workspace);
		var set = store.read().changes().stream().map(com.example.miniagent.core.change.ChangeStore.Snapshot::changeSet)
				.filter(c -> c.owner().runId().equals(id)).findFirst().orElseThrow();
		try (var lease = store.acquire()) {
			assertThat(Files.readString(lease.stagingDirectory(set.owner()).resolve("sentinel.txt"))).isEqualTo("after");
		}
	}
	@Test void cancelPendingReleasesWriterAndWorker() throws Exception {
		this.console.setInteractive(true);
		var finished = this.worker.submit(() -> this.handler.handle(
				"/run start !tool write_file {\"path\":\"cancel.txt\",\"content\":\"no\"}"));
		var request = this.console.questions.poll(5, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(this.handler.handle("/run cancel " + request.runId().value())).isTrue();
		assertThat(finished.get(5, TimeUnit.SECONDS)).isFalse();
		assertThat(this.runs.read(request.runId()).state()).isEqualTo(RunState.CANCELLED);
		try (RunWriter writer = this.runs.openWriter(request.runId())) {
			assertThat(writer.record().state()).isEqualTo(RunState.CANCELLED);
		}
		assertThat(workspace.resolve("cancel.txt")).doesNotExist();
	}
	@Test void exactSourceAcknowledgementRequiredAndNoBlanketDriftFlag() throws Exception {
		Path file = workspace.resolve("source.txt");
		Files.writeString(file, "before");
		RunId id = start("!interrupt", false);
		Files.writeString(file, "after");
		assertThat(this.handler.handle("/run resume " + id.value())).isFalse();
		var old = this.executor.inspectDrift(id);
		Files.writeString(file, "third");
		assertThat(this.handler.handle("/run resume " + id.value() + " --ack-drift "
				+ old.expectedDigest() + " " + old.actualDigest() + " review")).isFalse();
		var exact = this.executor.inspectDrift(id);
		assertThat(this.handler.handle("/run inspect-drift " + id.value())).isTrue();
		assertThat(this.handler.handle("/run resume " + id.value() + " --ack-drift "
				+ exact.expectedDigest() + " " + exact.actualDigest() + " review")).isTrue();
		assertThat(this.runs.export(id).eventsLog()).contains("DriftAccepted");
	}
	@Test void unknownRequiresExactOperatorInputAndNeverFabricatesFinish() throws Exception {
		for (String action : List.of("MUTATING_RETRY", "SKIP")) {
			RunId id = start("!unknown", false);
			var tools = this.runs.read(id).uncertainTools();
			assertThat(tools).as("Console output: %s", this.console.output).isNotEmpty();
			var unknown = tools.getFirst();
			assertThat(this.handler.handle("/run resume " + id.value())).isFalse();
			String prefix = "/run resume " + id.value() + " --resolve "
					+ unknown.attemptId() + " " + unknown.toolCallKey() + " ";
			assertThat(this.handler.handle(prefix + "READ_RETRY inspection")).isFalse();
			assertThat(this.handler.handle(prefix + action + " inspection")).isTrue();
			assertThat(this.runs.export(id).eventsLog()).contains(action, "console-").doesNotContain("ToolFinished");
		}
	}
	@Test void missingAuthFailsWithoutCreatingRun() throws Exception {
		this.selections.save(this.session.id(), new ModelSelection(new ModelRef("unavailable", "missing")));
		int before = this.runs.list().size();
		try {
			assertThat(this.handler.handle("/run start hello")).isFalse();
			assertThat(this.runs.list()).hasSize(before);
		}
		finally { this.selections.save(this.session.id(), new ModelSelection(new ModelRef("fake", "run-fixture"))); }
	}
	@Test void sessionResumeUntouched() throws Exception {
		String old = this.session.id();
		this.handler.handle("conversation");
		this.handler.handle("/new");
		assertThat(this.session.id()).isNotEqualTo(old);
		assertThat(this.handler.handle("/resume " + old)).isTrue();
		assertThat(this.session.id()).isEqualTo(old);
	}
	@Test void argumentValidationDoesNotDispatch() throws Exception {
		int before = this.runs.list().size();
		for (String input : List.of("/run", "/run start", "/runs extra", "/run show ../bad",
				"/run cancel", "/run wat x", "/run resume x --accept-drift"))
			assertThat(this.handler.handle(input)).isFalse();
		assertThat(this.runs.list()).hasSize(before);
	}
	@TestConfiguration static class Fixture {
		@Bean TestConsole agentConsole() { return new TestConsole(); }
		@Bean ModelProvider fakeModelProvider() {
			return new ModelProvider() {
				public String providerId() { return "fake"; }
				public ProviderAuth auth() { return () -> true; }
				public Set<String> supportedOptions() { return Set.of("temperature"); }
				public List<ModelEntry> models() {
					return List.of(new ModelEntry(new ModelRef("fake", "run-fixture"), "fake", null));
				}
				public ChatModel create(ModelRef ref, Map<String, String> options) {
					return new ScriptedChatModel() {
						@Override public ChatResponse call(Prompt prompt) {
							var last = prompt.getInstructions().getLast();
							if (last instanceof UserMessage user) {
								String input = user.getText();
								if (input.equals("!interrupt")) throw new Stop(Stop.Kind.INTERRUPTED);
								if (input.equals("!unknown")) {
									RunContext run = RunContext.current().orElseThrow();
									var definition = ToolDefinition.builder().name("fixture")
											.description("UNKNOWN fixture").inputSchema("{\"type\":\"object\"}").build();
									new ToolJournalInterceptor().intercept(new ToolInterceptor.ToolInvocation(definition,
											"{}", new ToolContext(Map.of(RunContext.TOOL_CONTEXT_KEY, run))),
											invocation -> { throw new Stop(Stop.Kind.INTERRUPTED); });
								}
								if (input.startsWith("Explicitly resume")) {
									int index = input.indexOf("!tool");
									var messages = new ArrayList<>(prompt.getInstructions());
									messages.set(messages.size() - 1, new UserMessage(index < 0 ? "resumed"
											: input.substring(index).split("\\n", 2)[0]));
									return super.call(new Prompt(messages, prompt.getOptions()));
								}
							}
							return super.call(prompt);
						}
					};
				}
			};
		}
		@Bean org.springframework.ai.tool.ToolCallback unknownFixtureTool() {
			return new org.springframework.ai.tool.ToolCallback() {
				public ToolDefinition getToolDefinition() {
					return ToolDefinition.builder().name("fixture").description("UNKNOWN fixture")
							.inputSchema("{\"type\":\"object\"}").build();
				}
				public String call(String input) { throw new AssertionError("Fixture explicitly intercepts"); }
			};
		}
		@Bean ModelProvider unavailableProvider() {
			return new ModelProvider() {
				public String providerId() { return "unavailable"; }
				public ProviderAuth auth() { return () -> false; }
				public List<ModelEntry> models() {
					return List.of(new ModelEntry(new ModelRef("unavailable", "missing"), "fake", ModelUnavailableException.Code.AUTH_MISSING));
				}
				public ChatModel create(ModelRef ref, Map<String, String> options) { throw new AssertionError("No auth"); }
			};
		}
	}
	static final class TestConsole extends AgentConsole {
		final ByteArrayOutputStream output;
		final BlockingQueue<ApprovalRequest> questions = new LinkedBlockingQueue<>();
		TestConsole() { this(new ByteArrayOutputStream()); }
		private TestConsole(ByteArrayOutputStream output) {
			super(new BufferedReader(new StringReader("")), new PrintStream(output), false);
			this.output = output;
		}
		@Override public void requestApproval(ApprovalRequest request, java.util.function.Consumer<ApprovalAnswer> response) {
			this.questions.add(request);
		}
	}
}
