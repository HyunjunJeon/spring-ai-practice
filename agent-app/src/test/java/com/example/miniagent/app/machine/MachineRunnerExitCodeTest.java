package com.example.miniagent.app.machine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.SlashCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;

/** What scripts and CI read: the completion status and the process exit code of the machine runner. */
@Timeout(20)
class MachineRunnerExitCodeTest {
	private static final String SECRET = "secret-detail-7431";

	@TempDir Path home;
	private final List<String> reached = new CopyOnWriteArrayList<>();

	private MachineFixture json(SlashCommand... commands) throws IOException {
		return new MachineFixture(this.home, "json", MachineFixture.input(""), prompt -> {}, List.of(commands));
	}

	private MachineFixture rpc(SlashCommand... commands) throws IOException {
		return new MachineFixture(this.home, "rpc", MachineFixture.input(""), prompt -> {}, List.of(commands));
	}

	private static SlashCommand reportingFailure(String name) {
		return new SlashCommand() {
			@Override public String name() { return name; }
			@Override public String description() { return name; }
			@Override public void execute(String args, CommandContext context) {}
			@Override public boolean executeWithStatus(String args, CommandContext context) { return false; }
		};
	}

	@Test void completedTurnExitsZero() throws Exception {
		try (var fixture = json()) {
			fixture.runner.run(new DefaultApplicationArguments("hello"));
			var completion = fixture.output.await("completion", "one-shot");
			assertThat(completion.path("status").asString()).isEqualTo("completed");
			assertThat(fixture.output.errorCodes()).isEmpty();
			assertThat(fixture.runner.getExitCode()).isZero();
		}
	}

	@Test void allowlistedCommandRunsAndExitsZero() throws Exception {
		try (var fixture = json(SlashCommand.of("model", "model", (args, context) -> this.reached.add(args)))) {
			fixture.runner.run(new DefaultApplicationArguments("/model", "fake/other"));
			assertThat(fixture.output.await("completion", "one-shot").path("status").asString()).isEqualTo("completed");
			assertThat(this.reached).containsExactly("fake/other");
			assertThat(fixture.runner.getExitCode()).isZero();
		}
	}

	@Test void commandReportingFailureCompletesFailedAndExitsOne() throws Exception {
		try (var fixture = json(reportingFailure("model"))) {
			fixture.runner.run(new DefaultApplicationArguments("/model", "x"));
			assertThat(fixture.output.await("completion", "one-shot").path("status").asString()).isEqualTo("failed");
			assertThat(fixture.output.errorCodes()).isEmpty();
			assertThat(fixture.runner.getExitCode()).isEqualTo(1);
		}
	}

	@Test void allowlistedNameWithoutARegisteredCommandCompletesFailedAndExitsOne() throws Exception {
		try (var fixture = json()) {
			fixture.runner.run(new DefaultApplicationArguments("/model", "x"));
			assertThat(fixture.output.await("completion", "one-shot").path("status").asString()).isEqualTo("failed");
			assertThat(fixture.output.errorCodes()).containsExactly("execution_failed");
			assertThat(fixture.runner.getExitCode()).isEqualTo(1);
		}
	}

	@Test void throwingCommandCompletesFailedWithoutLeakingTheException() throws Exception {
		var throwing = SlashCommand.of("model", "model", (args, context) -> { throw new IllegalStateException(SECRET); });
		try (var fixture = json(throwing)) {
			fixture.runner.run(new DefaultApplicationArguments("/model", "x"));
			var error = fixture.output.await("error", "one-shot");
			assertThat(error.path("code").asString()).isEqualTo("execution_failed");
			assertThat(fixture.output.await("completion", "one-shot").path("status").asString()).isEqualTo("failed");
			assertThat(fixture.runner.getExitCode()).isEqualTo(1);
			assertThat(fixture.output.text()).doesNotContain(SECRET);
		}
	}

	@Test void consoleErrorAloneMarksTheTurnFailedEvenWhenTheCommandSucceeds() throws Exception {
		var noisy = SlashCommand.of("model", "model", (args, context) -> context.console().error(SECRET));
		try (var fixture = json(noisy)) {
			fixture.runner.run(new DefaultApplicationArguments("/model", "x"));
			assertThat(fixture.output.errorCodes()).containsExactly("execution_failed");
			assertThat(fixture.output.await("completion", "one-shot").path("status").asString()).isEqualTo("failed");
			assertThat(fixture.runner.getExitCode()).isEqualTo(1);
			assertThat(fixture.output.text()).doesNotContain(SECRET);
		}
	}

	@Test void handlerExceptionEscapingTheInputHandlerIsExecutionFailed() throws Exception {
		MachineFixture.HandlerFactory throwing = (agent, session, console, commands) ->
				new AgentInputHandler(agent, session, console, commands) {
					@Override public boolean handle(String input) { throw new IllegalStateException(SECRET); }
				};
		try (var fixture = new MachineFixture(this.home, "json", MachineFixture.input(""), prompt -> {},
				List.of(), throwing)) {
			fixture.runner.run(new DefaultApplicationArguments("hello"));
			assertThat(fixture.output.await("error", "one-shot").path("code").asString()).isEqualTo("execution_failed");
			assertThat(fixture.output.await("completion", "one-shot").path("status").asString()).isEqualTo("failed");
			assertThat(fixture.runner.getExitCode()).isEqualTo(1);
			assertThat(fixture.output.text()).doesNotContain(SECRET);
		}
	}

	@Test void controlCallbackThatThrowsIsReportedAsExecutionFailedWithoutDetail() throws Exception {
		try (var fixture = rpc()) {
			var request = MachineFixture.approvalRequest();
			fixture.console.requestApproval(request, answer -> { throw new IllegalStateException(SECRET); });
			fixture.send("answer", "approval", Map.of("approvalId", request.requestId().toString(),
					"decision", "APPROVED"));
			assertThat(fixture.output.await("error", "answer").path("code").asString()).isEqualTo("execution_failed");
			assertThat(fixture.output.text()).doesNotContain(SECRET);
		}
	}

	@Test void cancelledCompletionExitsOne() throws Exception {
		var entered = new CountDownLatch(1);
		try (var fixture = new MachineFixture(this.home, "rpc", MachineFixture.input(""), prompt -> {
			entered.countDown();
			try { new CountDownLatch(1).await(); }
			catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
		}, List.of())) {
			fixture.send("p", "prompt", Map.of("text", "hello"));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(fixture.runner.getExitCode()).isZero();
			fixture.send("stop", "cancel", Map.of("targetId", "p"));
			assertThat(fixture.output.await("completion", "p").path("status").asString()).isEqualTo("cancelled");
			assertThat(fixture.runner.getExitCode()).isEqualTo(1);
		}
	}

	@Test void failureStaysStickyAcrossALaterCompletedTurn() throws Exception {
		try (var fixture = rpc(reportingFailure("model"))) {
			fixture.send("bad", "command", Map.of("text", "/model x"));
			assertThat(fixture.output.await("completion", "bad").path("status").asString()).isEqualTo("failed");
			fixture.send("good", "prompt", Map.of("text", "hello"));
			assertThat(fixture.output.await("completion", "good").path("status").asString()).isEqualTo("completed");
			assertThat(fixture.runner.getExitCode()).isEqualTo(1);
		}
	}

	@Test void jsonTextThatIsBlankOrOverTheByteLimitIsInvalidFieldAndExitsOne() throws Exception {
		assertRefusedBeforeDispatch("invalid_field");
		assertRefusedBeforeDispatch("invalid_field", "   ");
		assertRefusedBeforeDispatch("invalid_field", "a".repeat(MachineProtocol.MAX_TEXT_BYTES + 1));
		// 10923 Hangul syllables are 32769 UTF-8 bytes while staying far below 32768 characters.
		assertRefusedBeforeDispatch("invalid_field", "\uac00".repeat(10923));
	}

	@Test void jsonTextAtExactlyTheByteLimitRunsAndExitsZero() throws Exception {
		// 10922 Hangul syllables (3 bytes each) plus two ASCII bytes are exactly 32768 bytes.
		String text = "\uac00".repeat(10922) + "ab";
		assertThat(text.getBytes(StandardCharsets.UTF_8)).hasSize(MachineProtocol.MAX_TEXT_BYTES);
		try (var fixture = json()) {
			fixture.runner.run(new DefaultApplicationArguments(text));
			assertThat(fixture.output.await("completion", "one-shot").path("status").asString()).isEqualTo("completed");
			assertThat(fixture.runner.getExitCode()).isZero();
		}
	}

	@Test void jsonSlashTextOutsideTheAllowlistIsRefusedAndExitsOne() throws Exception {
		assertRefusedBeforeDispatch("command_not_allowed", "/help");
		assertRefusedBeforeDispatch("command_not_allowed", "/unknown", "arg");
		assertThat(this.reached).isEmpty();
	}

	@Test void jsonRunApprovalIsRefusedAsAnExactControlMethodAndExitsOne() throws Exception {
		assertRefusedBeforeDispatch("use_exact_control_method", "/run", "approve", "1234");
		assertThat(this.reached).isEmpty();
	}

	/** The one-shot request is refused: one error frame without a request id, no response, no completion, exit 1. */
	private void assertRefusedBeforeDispatch(String code, String... args) throws Exception {
		var help = SlashCommand.of("help", "help", (commandArgs, context) -> this.reached.add("help"));
		var run = SlashCommand.of("run", "run", (commandArgs, context) -> this.reached.add("run"));
		try (var fixture = json(help, run)) {
			fixture.runner.run(new DefaultApplicationArguments(args));
			assertThat(fixture.output.errorCodes()).containsExactly(code);
			assertThat(fixture.output.ofType("error").getFirst().path("requestId").isNull()).isTrue();
			assertThat(fixture.output.ofType("response")).isEmpty();
			assertThat(fixture.output.ofType("completion")).isEmpty();
			assertThat(fixture.runner.getExitCode()).isEqualTo(1);
		}
	}
}
