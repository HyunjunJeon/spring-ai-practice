package com.example.miniagent.core.messaging;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.delegation.runtime.DefaultDelegationRuntime;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunSpec;
import com.example.miniagent.core.run.RunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.example.miniagent.core.messaging.MessageStore.State;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** The operator console: parsing, verb dispatch, printed output and the one-shot exit status. */
class MessageCommandTest {
	private static final String CONVERSATION = "console-conversation";
	private static final int MAX_COMMAND = 16384;
	@TempDir Path root;
	private final DefaultDelegationRuntime runtime = mock(DefaultDelegationRuntime.class);
	private final RunContext operator = mock(RunContext.class);
	private final RecordingConsole console = new RecordingConsole();
	private Path home, workspace;
	private FileRunStore runs;
	private MessageService service;
	private MessageCommand command;

	/** Splits what the operator sees from what is reported as an error. */
	static final class RecordingConsole extends AgentConsole {
		final List<String> printed = new ArrayList<>();
		final List<String> errors = new ArrayList<>();
		RecordingConsole() {
			super(new BufferedReader(new StringReader("")), new PrintStream(OutputStream.nullOutputStream()), false);
		}
		@Override public void println(String text) { printed.add(text); }
		@Override public void error(String text) { errors.add(text); }
	}

	@BeforeEach void openCommand() throws Exception {
		root = root.toRealPath();
		home = root.resolve("agent-home");
		workspace = Files.createDirectory(root.resolve("workspace"));
		runs = new FileRunStore(home);
		service = new MessageService(new MessageStore(home), runtime);
		command = new MessageCommand(() -> service, () -> runs, workspace);
		when(runtime.messageOperator(CONVERSATION, "plan")).thenReturn(operator);
		when(runtime.messageTarget(operator, "plan", "child")).thenReturn(MessageServiceOutcomeTest.target("parent"));
	}

	private SlashCommand.CommandContext context() {
		var session = new AgentSession();
		session.switchTo(CONVERSATION);
		return new SlashCommand.CommandContext(null, session, console);
	}

	private boolean run(String args) { return command.executeWithStatus(args, context()); }

	private void childAnswers(String reply) throws Exception {
		when(runtime.followUp(any(), anyString(), anyString(), anyString()))
			.thenReturn(MessageServiceOutcomeTest.result(RunState.COMPLETED, reply, 0));
	}

	@Test void theCommandIsNamedMessageAndItsUsageNamesEveryVerb() {
		assertThat(command.name()).isEqualTo("message");
		assertThat(command.description())
			.contains("archive <parent-run> <plan> <child> [offset]", "read <plan> <child> [offset]",
				"send <plan> <child> <id> <version> <body>");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "read plan", "bogus plan child", "read plan child 1 extra", "send plan child id 0"})
	void aMalformedCommandLinePrintsTheUsageAndFails(String args) throws Exception {
		assertThat(run(args)).isFalse();
		assertThat(console.errors).containsExactly(command.description());
		assertThat(console.printed).isEmpty();
		verify(runtime, never()).followUp(any(), anyString(), anyString(), anyString());
	}

	@ParameterizedTest
	@ValueSource(strings = {"read plan child not-a-number", "send plan child m-0 not-a-number hello",
		"archive parent-run plan child not-a-number"})
	void aNonNumericOffsetOrVersionPrintsAnErrorAndFails(String args) throws Exception {
		assertThat(run(args)).isFalse();
		assertThat(console.errors).singleElement().asString().contains("not-a-number");
		assertThat(console.printed).isEmpty();
		verify(runtime, never()).followUp(any(), anyString(), anyString(), anyString());
	}

	@Test void aCommandLineOverTheSizeLimitIsRefusedButTheLimitItselfIsAccepted() {
		String read = "read plan child 0";
		assertThat(run(read + " ".repeat(MAX_COMMAND + 1 - read.length()))).isFalse();
		assertThat(console.errors).containsExactly("Message command too large");
		assertThat(console.printed).isEmpty();
		console.errors.clear();
		assertThat(run(read + " ".repeat(MAX_COMMAND - read.length()))).isTrue();
		assertThat(console.errors).isEmpty();
		assertThat(console.printed).hasSize(1);
	}

	@Test void readPrintsTheOperatorsPageAndHonoursTheOptionalOffset() throws Exception {
		childAnswers("ok");
		for (int i = 0; i < 3; i++) service.send(operator, "plan", "child", "m-" + i, i * 2L, "body " + i);
		assertThat(run("read plan child")).isTrue();
		var firstPage = MessageStore.JSON.readTree(console.printed.getFirst());
		assertThat(firstPage.get("version").asLong()).isEqualTo(6);
		assertThat(firstPage.get("entries").size()).isEqualTo(3);
		assertThat(run("read plan child 2")).isTrue();
		var tail = MessageStore.JSON.readTree(console.printed.getLast());
		assertThat(tail.get("entries").size()).isEqualTo(1);
		assertThat(tail.get("entries").get(0).get("id").asString()).isEqualTo("m-2");
		assertThat(tail.get("nextOffset").asInt()).isEqualTo(3);
		assertThat(console.errors).isEmpty();
	}

	@Test void sendKeepsTheSpacesOfTheBodyAndPassesTheIdAndVersion() throws Exception {
		when(runtime.followUp(operator, "plan", "child", "hello   spaced  body"))
			.thenReturn(MessageServiceOutcomeTest.result(RunState.COMPLETED, "answer", 0));
		assertThat(run("  send plan child msg-1 0 hello   spaced  body  ")).isTrue();
		var receipt = MessageStore.JSON.readTree(console.printed.getFirst());
		assertThat(receipt.get("id").asString()).isEqualTo("msg-1");
		assertThat(receipt.get("body").asString()).isEqualTo("hello   spaced  body");
		assertThat(receipt.get("state").asString()).isEqualTo("DELIVERED");
		assertThat(run("send plan child msg-2 0 again")).isFalse();
		assertThat(console.errors).containsExactly("Stale mailbox version");
	}

	static Stream<Arguments> sendOutcomes() {
		Callable<RunExecutor.Result> delivered = () -> MessageServiceOutcomeTest.result(RunState.COMPLETED, "ok", 0);
		Callable<RunExecutor.Result> cancelled = () -> MessageServiceOutcomeTest.result(RunState.CANCELLED, null, 0);
		Callable<RunExecutor.Result> lost = () -> MessageServiceOutcomeTest.result(RunState.FAILED, null, 0);
		Callable<RunExecutor.Result> refused = () -> { throw new IllegalStateException("child could not start"); };
		return Stream.of(
			arguments("parent", delivered, State.DELIVERED, true),
			arguments("child", delivered, State.QUEUED, true),
			arguments("parent", cancelled, State.FAILED, false),
			arguments("parent", lost, State.UNCERTAIN, false),
			arguments("parent", refused, State.BLOCKED, false));
	}

	@ParameterizedTest @MethodSource("sendOutcomes")
	void theExitStatusIsTrueOnlyForDeliveredAndQueuedReceipts(String sender, Callable<RunExecutor.Result> followUp,
			State state, boolean succeeds) throws Exception {
		when(runtime.messageTarget(operator, "plan", "child")).thenReturn(MessageServiceOutcomeTest.target(sender));
		when(runtime.followUp(operator, "plan", "child", "body")).thenAnswer(call -> followUp.call());
		assertThat(run("send plan child msg-1 0 body")).isEqualTo(succeeds);
		assertThat(MessageStore.JSON.readTree(console.printed.getFirst()).get("state").asString()).isEqualTo(state.name());
		assertThat(console.errors).isEmpty();
	}

	@Test void executeRunsTheSameDispatchAsExecuteWithStatus() throws Exception {
		childAnswers("ok");
		command.execute("send plan child msg-1 0 body", context());
		assertThat(MessageStore.JSON.readTree(console.printed.getFirst()).get("state").asString()).isEqualTo("DELIVERED");
		command.execute("read plan", context());
		assertThat(console.errors).containsExactly(command.description());
	}

	@Test void aRefusalFromTheServiceIsPrintedAsAnErrorAndFails() throws Exception {
		when(runtime.messageOperator(CONVERSATION, "foreign")).thenThrow(new IllegalArgumentException("No exact owned plan"));
		assertThat(run("read foreign child")).isFalse();
		assertThat(console.errors).containsExactly("No exact owned plan");
		assertThat(run("read plan child 0")).isTrue();
		assertThat(run("send plan child msg-1 3 body")).isFalse();
		assertThat(console.errors).containsExactly("No exact owned plan", "Stale mailbox version");
	}

	private void createParentRun(String conversation) throws Exception {
		String digest = "0".repeat(64);
		runs.create(new RunSpec(RunSpec.SCHEMA_VERSION, new RunId("parent-run"), "conversation-parent-run", conversation,
			null, null, new ModelRef("fake", "test"), "fake", workspace.toString(), RunBudget.defaults(), digest,
			digest, Instant.EPOCH)).close();
	}

	@Test void archivePrintsTheStoredPageOfAnOwnedParentRunWithoutTheRuntime() throws Exception {
		createParentRun(CONVERSATION);
		var entries = new ArrayList<MessageStore.Receipt>();
		for (int i = 0; i < 3; i++) entries.add(new MessageStore.Receipt("m-" + i, "parent", "body " + i,
			State.DELIVERED, "original-run", "execution-run", "answer", "claim"));
		new MessageStore(home).transact(threads -> {
			MessageStore.replace(threads, MessageStore.find(threads, new MessageStore.Key("parent-run", "plan", "child")), entries);
			return null;
		});
		assertThat(run("archive parent-run plan child")).isTrue();
		assertThat(MessageStore.JSON.readTree(console.printed.getFirst()).get("entries").size()).isEqualTo(3);
		assertThat(run("archive parent-run plan child 2")).isTrue();
		var tail = MessageStore.JSON.readTree(console.printed.getLast());
		assertThat(tail.get("entries").size()).isEqualTo(1);
		assertThat(tail.get("entries").get(0).get("id").asString()).isEqualTo("m-2");
		verifyNoInteractions(runtime);
	}

	@Test void archiveOfAParentRunFromAnotherConversationPrintsTheRefusalAndFails() throws Exception {
		createParentRun("someone-elses-conversation");
		assertThat(run("archive parent-run plan child")).isFalse();
		assertThat(console.errors).containsExactly("Foreign message archive");
		assertThat(console.printed).isEmpty();
	}
}
