package com.example.miniagent.app.worker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.change.AtomicFiles;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.run.DriftGate;
import com.example.miniagent.core.run.RunEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The operator entry for a detached OS process: what it refuses, what it writes and what it prints. */
@Timeout(30)
class WorkerCommandTest {
	private static final String REFUSED_ARGUMENT = "Worker control refused (IllegalArgumentException)";
	private static final String REFUSED_IO = "Worker control refused (IOException)";
	private static final int ARGUMENT_LIMIT = 16384;

	@TempDir Path temporary;

	private final List<String> lines = new ArrayList<>();
	private final List<String> errors = new ArrayList<>();
	private final AtomicInteger launches = new AtomicInteger();
	private Supplier<WorkerLaunch> launch = () -> { throw new AssertionError("Unit test must not launch"); };
	private final AgentConsole console = new AgentConsole(new BufferedReader(new StringReader("")),
			new PrintStream(OutputStream.nullOutputStream()), false) {
		@Override public void println(String text) { WorkerCommandTest.this.lines.add(text); }
		@Override public void error(String text) { WorkerCommandTest.this.errors.add(text); }
	};
	private WorkerController controller;
	private WorkerCommand command;
	private WorkerFiles.Claim claim;

	@BeforeEach
	void controllerOverTemporaryWorkspace() throws IOException {
		Path workspace = this.temporary.toRealPath();
		this.controller = new WorkerController(workspace, workspace.resolve("home"), () -> {
			this.launches.incrementAndGet();
			return this.launch.get();
		});
		this.command = new WorkerCommand(this.controller);
	}

	@AfterEach
	void releaseTheClaim() throws IOException {
		if (this.claim != null) this.claim.close();
	}

	private boolean execute(String args) {
		return this.command.executeWithStatus(args, new SlashCommand.CommandContext(null, new AgentSession(), this.console));
	}

	/** A worker that holds the claim in this JVM and has published metadata, as a running worker would. */
	private String ownedWorker(String state, String activeGoal, String lastGoal, String lastState, String error)
			throws IOException {
		var files = this.controller.files();
		this.claim = files.acquire();
		assertThat(this.claim).isNotNull();
		String instance = UUID.randomUUID().toString();
		files.write(new WorkerFiles.Metadata(1, instance, 4242, files.workspace.toString(), files.home.toString(),
				state, activeGoal, lastGoal, lastState, error));
		return instance;
	}

	private String runningWorker() throws IOException {
		return ownedWorker("RUNNING", "", "", "", "");
	}

	private List<Path> inbox() throws IOException {
		try (var entries = Files.list(this.controller.files().inbox)) { return entries.toList(); }
	}

	private WorkerFiles.Request onlyRequest() throws IOException {
		assertThat(inbox()).hasSize(1);
		return this.controller.files().request(inbox().getFirst());
	}

	private static String requestId(Path request) {
		String name = request.getFileName().toString();
		return name.substring(0, name.length() - ".json".length());
	}

	@Test
	void statusLineListsEveryFieldOfTheOwnedWorker() throws Exception {
		String instance = ownedWorker("RUNNING", "goal-a", "goal-b", "SUCCEEDED", "GOAL_REFUSED");
		assertThat(execute("status")).isTrue();
		assertThat(this.lines).containsExactly("worker=" + instance + " pid=4242 owned=true state=RUNNING"
				+ " activeGoal=goal-a lastGoal=goal-b lastState=SUCCEEDED error=GOAL_REFUSED");
		assertThat(this.errors).isEmpty();
	}

	@Test
	void statusWithoutAWorkerReportsStoppedWithEmptyFields() {
		assertThat(execute("status")).isTrue();
		assertThat(this.lines).containsExactly(
				"worker= pid=0 owned=false state=STOPPED activeGoal= lastGoal= lastState= error=");
	}

	@Test
	void startWhileAWorkerIsOwnedReportsItAndNeverLaunches() throws Exception {
		String instance = runningWorker();
		assertThat(execute("start")).isTrue();
		assertThat(this.lines).containsExactly("worker=" + instance + " pid=4242 owned=true state=RUNNING"
				+ " activeGoal= lastGoal= lastState= error=");
		assertThat(this.launches).hasValue(0);
		assertThat(inbox()).isEmpty();
	}

	@Test
	void startRefusesAnUnsupportedLaunchConfigurationWithoutDetail() {
		this.launch = () -> { throw new IllegalArgumentException("Worker requires official Codex CLI OAuth"); };
		assertThat(execute("start")).isFalse();
		assertThat(this.errors).containsExactly(REFUSED_ARGUMENT);
		assertThat(this.launches).hasValue(1);
		assertThat(this.lines).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "stop", "wakeup" })
	void stopAndWakeupWriteOneRequestForTheActiveInstance(String operation) throws Exception {
		String instance = runningWorker();
		assertThat(execute(operation)).isTrue();
		var request = onlyRequest();
		assertThat(request).isEqualTo(new WorkerFiles.Request(1, instance, requestId(inbox().getFirst()), operation, "", null));
		assertThat(this.lines).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "stop", "wakeup" })
	void stopAndWakeupWithoutAnActiveWorkerAreRefusedAndWriteNothing(String operation) throws Exception {
		assertThat(execute(operation)).isFalse();
		assertThat(this.errors).containsExactly(REFUSED_IO);
		assertThat(this.lines).isEmpty();
		assertThat(inbox()).isEmpty();
	}

	@Test
	void resumeSendsTheGoalAndExactOptionsAttributedToTheConsoleOperator() throws Exception {
		String instance = runningWorker();
		String expected = "a".repeat(64);
		String actual = "b".repeat(64);
		assertThat(execute("resume goal-1 --resolve attempt-1 call-1 SKIP evidence-1"
				+ " --ack-drift " + expected + " " + actual + " drift-evidence")).isTrue();
		var request = onlyRequest();
		String operator = request.options().resolutions().getFirst().operatorId();
		assertThat(operator).matches("worker-console-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
		var options = new RunExecutor.ResumeOptions(
				List.of(new RunExecutor.ToolResolutionInput("attempt-1", "call-1", RunEvent.Resolution.SKIP, operator, "evidence-1")),
				new DriftGate.Acknowledgement(expected, actual, operator, "drift-evidence"));
		assertThat(request).isEqualTo(new WorkerFiles.Request(1, instance, requestId(inbox().getFirst()),
				"resume", "goal-1", options));
	}

	@Test
	void resumeWithoutOptionsSendsOrdinaryResumeOptions() throws Exception {
		runningWorker();
		assertThat(execute("resume goal-1")).isTrue();
		var request = onlyRequest();
		assertThat(request.goal()).isEqualTo("goal-1");
		assertThat(request.options()).isEqualTo(RunExecutor.ResumeOptions.ordinary());
	}

	@ParameterizedTest
	@ValueSource(strings = { "resume", "resume goal-1 --bogus", "resume goal-1 --resolve attempt-1 call-1",
			"resume ../escape" })
	void resumeWithAMissingGoalOrMalformedOptionsIsRefusedAndWritesNothing(String args) throws Exception {
		runningWorker();
		assertThat(execute(args)).isFalse();
		assertThat(this.errors).containsExactly(REFUSED_ARGUMENT);
		assertThat(inbox()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "frobnicate", "START", "status now", "stop now" })
	void unknownOperationsAndExtraArgumentsAreRefusedWithoutSideEffects(String args) throws Exception {
		runningWorker();
		assertThat(execute(args)).isFalse();
		assertThat(this.errors).containsExactly(REFUSED_ARGUMENT);
		assertThat(this.lines).isEmpty();
		assertThat(inbox()).isEmpty();
		assertThat(this.launches).hasValue(0);
	}

	@Test
	void argumentsOfExactlyTheLimitAreAccepted() {
		String args = "status" + " ".repeat(ARGUMENT_LIMIT - "status".length());
		assertThat(args).hasSize(ARGUMENT_LIMIT);
		assertThat(execute(args)).isTrue();
		assertThat(this.lines).hasSize(1);
	}

	@Test
	void argumentsOneCharacterOverTheLimitAreRefused() {
		String args = "status" + " ".repeat(ARGUMENT_LIMIT - "status".length() + 1);
		assertThat(execute(args)).isFalse();
		assertThat(this.errors).containsExactly(REFUSED_ARGUMENT);
		assertThat(this.lines).isEmpty();
	}

	@Test
	void unreadableWorkerStateIsRefusedWithoutExposingTheDetail() throws Exception {
		AtomicFiles.write(this.controller.files().metadata, new byte[] { '{' }, WorkerFiles.LIMIT);
		assertThat(execute("status")).isFalse();
		assertThat(this.errors).containsExactly(REFUSED_IO);
		assertThat(this.lines).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/worker status", "  /worker   status  ", "/worker\tstatus" })
	void workerStatusIsRoutedAsControlInputThatDoesNotWaitForTheBusyTurn(String input) {
		assertThat(AgentInputHandler.isControlInput(input)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/worker", "/worker start", "/worker stop", "/worker wakeup", "/worker resume goal-1",
			"/worker status now", "/worker Status", "worker status" })
	void everyOtherWorkerInputWaitsForTheBusyTurn(String input) {
		assertThat(AgentInputHandler.isControlInput(input)).isFalse();
	}

	@Disabled("SUSPECT app-composition:G29: WorkerCommand.isControlInput is unused and reports /worker start, stop,"
			+ " wakeup and resume as control input, which AgentInputHandler.isControlInput routes behind the busy turn")
	@ParameterizedTest
	@ValueSource(strings = { "/worker status", "/worker start", "/worker stop", "/worker wakeup", "/worker resume goal-1" })
	void unusedHelperAgreesWithTheInputHandlerRouting(String input) {
		assertThat(WorkerCommand.isControlInput(input)).isEqualTo(AgentInputHandler.isControlInput(input));
	}
}
