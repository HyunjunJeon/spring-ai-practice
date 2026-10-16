package com.example.miniagent.core.messaging;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import com.example.miniagent.core.Agent;
import com.example.miniagent.core.delegation.runtime.DefaultDelegationRuntime;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunRecord;
import com.example.miniagent.core.run.RunSpec;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.example.miniagent.core.messaging.MessageStore.State;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The at-least-once contract: how a finished run is reported and which receipts stop the next send. */
class MessageServiceOutcomeTest {
	static final String DIGEST = "0".repeat(64);
	@TempDir Path root;
	private final DefaultDelegationRuntime runtime = mock(DefaultDelegationRuntime.class);
	private final RunContext actor = mock(RunContext.class);
	private MessageService service;

	@BeforeEach void openMailbox() throws IOException {
		root = root.toRealPath();
		service = new MessageService(new MessageStore(root.resolve("home")), runtime);
		when(runtime.messageTarget(actor, "plan", "child")).thenReturn(target("parent"));
	}

	/** Shared with the other messaging tests: the exact-parent target of the child thread. */
	static DefaultDelegationRuntime.MessageTarget target(String sender) {
		return new DefaultDelegationRuntime.MessageTarget("operator", new RunId("parent-run"),
			"parent-conversation", "child-conversation", new RunId("original-run"), sender);
	}

	/** Shared with the other messaging tests: a follow-up run that ended in the given state. */
	static RunExecutor.Result result(RunState state, String reply, int uncertainTools) {
		var spec = new RunSpec(RunSpec.SCHEMA_VERSION, new RunId("execution-run"), "execution-conversation",
			"parent-conversation", null, null, new ModelRef("fake", "test"), "fake", "/workspace",
			RunBudget.defaults(), DIGEST, DIGEST, Instant.EPOCH);
		var uncertain = IntStream.range(0, uncertainTools).mapToObj(i -> new RunEvent.ToolStarted(Instant.EPOCH,
			"attempt", "call-" + i, "write_file", DIGEST, RunEvent.Effect.MUTATING)).toList();
		var record = new RunRecord(spec, 1, state, RunEvent.Reason.NONE, List.of(), RunUsage.empty(), uncertain);
		return new RunExecutor.Result(record, reply == null ? null : new Agent.AgentReply(reply, null));
	}

	static Stream<Arguments> endedRuns() {
		return Stream.of(
			arguments(RunState.COMPLETED, State.DELIVERED),
			arguments(RunState.CANCELLED, State.FAILED),
			arguments(RunState.FAILED, State.UNCERTAIN),
			arguments(RunState.INTERRUPTED, State.UNCERTAIN),
			arguments(RunState.WAITING_APPROVAL, State.UNCERTAIN),
			arguments(RunState.RUNNING, State.UNCERTAIN),
			arguments(RunState.CREATED, State.UNCERTAIN));
	}

	@ParameterizedTest @MethodSource("endedRuns")
	void aRunEndedInAStateIsStoredAsTheMatchingReceiptState(RunState ended, State expected) throws Exception {
		when(runtime.followUp(actor, "plan", "child", "body")).thenReturn(result(ended, "reply", 0));
		var receipt = service.send(actor, "plan", "child", "id", 0, "body");
		assertThat(receipt.state()).isEqualTo(expected);
		assertThat(receipt.originalRun()).isEqualTo("original-run");
		assertThat(receipt.executionRun()).isEqualTo("execution-run");
		var fresh = new MessageService(new MessageStore(root.resolve("home")), runtime);
		assertThat(fresh.read(actor, "plan", "child", 0).entries()).containsExactly(receipt);
	}

	@ParameterizedTest @ValueSource(ints = {1, 3})
	void aCompletedRunWithUncertainToolsIsNotReportedDelivered(int uncertainTools) throws Exception {
		when(runtime.followUp(actor, "plan", "child", "body")).thenReturn(result(RunState.COMPLETED, "reply", uncertainTools));
		assertThat(service.send(actor, "plan", "child", "id", 0, "body").state()).isEqualTo(State.UNCERTAIN);
	}

	@Test void anUncertainReceiptRefusesTheNextParentSendWithoutExecutingIt() throws Exception {
		when(runtime.followUp(eq(actor), eq("plan"), eq("child"), anyString()))
			.thenReturn(result(RunState.FAILED, null, 0));
		var uncertain = service.send(actor, "plan", "child", "first", 0, "body");
		assertThat(uncertain.state()).isEqualTo(State.UNCERTAIN);
		var page = service.read(actor, "plan", "child", 0);
		assertThatThrownBy(() -> service.send(actor, "plan", "child", "second", page.version(), "next body"))
			.isInstanceOf(IOException.class).hasMessage("Thread busy or uncertain; no execution admitted");
		assertThat(service.read(actor, "plan", "child", 0)).isEqualTo(page);
		assertThat(service.send(actor, "plan", "child", "first", 0, "body")).isEqualTo(uncertain);
		verify(runtime, times(1)).followUp(eq(actor), eq("plan"), eq("child"), anyString());
	}

	static Stream<Arguments> settledRuns() {
		Callable<RunExecutor.Result> delivered = () -> result(RunState.COMPLETED, "reply", 0);
		Callable<RunExecutor.Result> cancelled = () -> result(RunState.CANCELLED, null, 0);
		Callable<RunExecutor.Result> refused = () -> { throw new IllegalStateException("child could not start"); };
		return Stream.of(arguments(delivered, State.DELIVERED), arguments(cancelled, State.FAILED),
			arguments(refused, State.BLOCKED));
	}

	@ParameterizedTest @MethodSource("settledRuns")
	void aSettledReceiptDoesNotBlockTheNextParentSend(Callable<RunExecutor.Result> first, State settled) throws Exception {
		when(runtime.followUp(eq(actor), eq("plan"), eq("child"), anyString()))
			.thenAnswer(call -> first.call()).thenReturn(result(RunState.COMPLETED, "again", 0));
		var firstReceipt = service.send(actor, "plan", "child", "first", 0, "one");
		assertThat(firstReceipt.state()).isEqualTo(settled);
		var next = service.send(actor, "plan", "child", "second", service.read(actor, "plan", "child", 0).version(), "two");
		assertThat(next.state()).isEqualTo(State.DELIVERED);
		assertThat(service.read(actor, "plan", "child", 0).entries()).extracting(MessageStore.Receipt::id)
			.containsExactly("first", "second");
		verify(runtime, times(2)).followUp(eq(actor), eq("plan"), eq("child"), anyString());
	}
}
