package com.example.miniagent.plugin.tui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.InterceptingToolCallback;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.approval.*;
import com.example.miniagent.core.run.RunId;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.PasteEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TuiCancellationTest {

	private final ExecutorService worker = Executors.newSingleThreadExecutor();

	private final Agent agent = mock(Agent.class);

	private final AgentSession session = new AgentSession();

	private final ChatLog log = new ChatLog();

	private final TuiConsole console = new TuiConsole(this.log);

	private final TuiRunner runner = mock(TuiRunner.class);

	private final TuiFrontEnd frontEnd = new TuiFrontEnd(
			new AgentInputHandler(this.agent, this.session, this.console, List.of()), this.session, this.console,
			this.log, new TraceGraph(), () -> "test", Path.of("unused.log"));

	@AfterEach
	void stop() throws InterruptedException {
		this.console.deactivate();
		this.worker.shutdownNow();
		assertThat(this.worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void correlatedApprovalRejectsOldDraftAndLateIds() throws Exception {
		this.console.activate(this.frontEnd::requestRedraw);
		var first = new ApprovalRequest(java.util.UUID.randomUUID(), RunId.random(), "attempt", null, null,
				"write_file", "a".repeat(64), null);
		var second = new ApprovalRequest(java.util.UUID.randomUUID(), first.runId(), "attempt", null, null,
				"write_file", "b".repeat(64), null);
		var oldAnswer = new java.util.concurrent.CompletableFuture<ApprovalAnswer>();
		var newAnswer = new java.util.concurrent.CompletableFuture<ApprovalAnswer>();
		this.console.requestApproval(first, oldAnswer::complete);
		event(new PasteEvent(first.requestId() + " y"));
		this.console.dismissApproval(first.requestId());
		this.console.requestApproval(second, newAnswer::complete);
		event(KeyEvent.ofKey(KeyCode.ENTER));
		assertThat(oldAnswer).isNotDone();
		assertThat(newAnswer).isNotDone();
		assertThat(this.console.answer(first.requestId(), first.requestId() + " y")).isFalse();
		assertThat(this.console.answer(second.requestId(), first.requestId() + " y")).isFalse();
		event(new PasteEvent(second.requestId() + " n"));
		event(KeyEvent.ofKey(KeyCode.ENTER));
		assertThat(newAnswer.get(5, TimeUnit.SECONDS).decision()).isEqualTo(ApprovalDecision.REJECTED);
		this.console.dismissApproval(second.requestId());
	}

	@Test
	void busyTurnRejectsModelCommandWithoutInvokingSelection() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean selected = new AtomicBoolean();
		TuiFrontEnd ui = new TuiFrontEnd(new AgentInputHandler(this.agent, this.session, this.console,
				List.of(SlashCommand.of("model", "selection", (args, context) -> selected.set(true)))),
				this.session, this.console, this.log, new TraceGraph(), () -> "fake/a", Path.of("unused.log"));
		stubTurn("first", invocation -> {
			started.countDown();
			await(release);
			return new Agent.AgentReply("done", null);
		});
		this.console.activate(ui::requestRedraw);
		try {
			ReflectionTestUtils.invokeMethod(ui, "handle", new PasteEvent("first"), this.runner, this.worker);
			ReflectionTestUtils.invokeMethod(ui, "handle", KeyEvent.ofKey(KeyCode.ENTER), this.runner, this.worker);
			await(started);
			ReflectionTestUtils.invokeMethod(ui, "handle", new PasteEvent("/model fake/b"), this.runner, this.worker);
			ReflectionTestUtils.invokeMethod(ui, "handle", KeyEvent.ofKey(KeyCode.ENTER), this.runner, this.worker);
			assertThat(selected).isFalse();
			assertThat(ReflectionTestUtils.getField(ui, "busy")).isEqualTo(true);
		}
		finally {
			release.countDown();
		}
		drainWorker();
		assertThat(selected).isFalse();
		assertThat(ReflectionTestUtils.getField(ui, "busy")).isEqualTo(false);
	}

	@Test
	void escapeInterruptsRunningTurnAndAllowsNextTurn() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		AtomicReference<RuntimeException> failure = new AtomicReference<>();
		ToolCallback callback = FunctionToolCallback.<String, String>builder("blocking", (input, context) -> {
			started.countDown();
			try {
				new CountDownLatch(1).await();
				throw new AssertionError("Turn should be interrupted");
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				interrupted.set(Thread.currentThread().isInterrupted());
				throw new IllegalStateException("Interrupted", ex);
			}
		}).description("Blocking test tool").inputType(String.class).build();
		ToolCallback wrapped = InterceptingToolCallback.wrap(callback,
				List.of((invocation, chain) -> chain.proceed(invocation)));
		stubTurn("first", invocation -> {
			try {
				return new Agent.AgentReply(wrapped.call("\"first\""), null);
			}
			catch (RuntimeException ex) {
				failure.set(ex);
				throw ex;
			}
		});
		this.console.activate(this.frontEnd::requestRedraw);

		submit("first");
		await(started);
		event(KeyEvent.ofKey(KeyCode.ESCAPE));
		drainWorker();

		assertThat(interrupted).isTrue();
		assertThat(failure.get()).isInstanceOf(CancellationException.class);
		assertIdleWithoutError();
		assertNextTurnRuns();
	}

	@Test
	void escapeCancelsQueuedTurnWithoutExecutingIt() throws Exception {
		CountDownLatch occupied = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		this.worker.submit(() -> {
			occupied.countDown();
			try {
				release.await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		await(occupied);
		this.console.activate(this.frontEnd::requestRedraw);

		submit("first");
		event(KeyEvent.ofKey(KeyCode.ESCAPE));
		assertIdleWithoutError();
		stubTurn("next", invocation -> new Agent.AgentReply("done", null));
		submit("next");
		release.countDown();
		drainWorker();

		verify(this.agent, never()).stream(eq("first"), any());
		verify(this.agent).stream(eq("next"), any());
		assertIdleWithoutError();
		assertThat(this.log.snapshot()).anyMatch(entry -> entry.role() == ChatLog.Role.AGENT);
	}

	@Test
	void escapeReleasesPendingQuestionAndAllowsNextTurn() throws Exception {
		CountDownLatch question = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		AtomicBoolean continued = new AtomicBoolean();
		this.console.activate(() -> {
			this.frontEnd.requestRedraw();
			if (this.console.pendingQuestion() != null) {
				question.countDown();
			}
		});
		stubTurn("first", invocation -> {
			try {
				this.console.readLine("Allow?");
				continued.set(true);
				return new Agent.AgentReply("unexpected", null);
			}
			finally {
				interrupted.set(Thread.currentThread().isInterrupted());
			}
		});

		submit("first");
		await(question);
		event(KeyEvent.ofKey(KeyCode.ESCAPE));
		drainWorker();

		assertThat(interrupted).isTrue();
		assertThat(continued).isFalse();
		assertThat(this.log.snapshot()).noneMatch(entry -> entry.role() == ChatLog.Role.AGENT);
		assertThat(this.console.pendingQuestion()).isNull();
		assertIdleWithoutError();
		assertNextTurnRuns();
	}

	@Test
	void escapeWithDraftClearsInputWithoutCancellingTurn() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		stubTurn("first", invocation -> {
			started.countDown();
			await(release);
			return new Agent.AgentReply("done", null);
		});
		this.console.activate(this.frontEnd::requestRedraw);
		submit("first");
		await(started);

		event(new PasteEvent("draft"));
		event(KeyEvent.ofKey(KeyCode.ESCAPE));
		event(KeyEvent.ofKey(KeyCode.ENTER));
		assertThat(ReflectionTestUtils.getField(this.frontEnd, "busy")).isEqualTo(true);
		release.countDown();
		drainWorker();

		assertIdleWithoutError();
		verify(this.agent, never()).stream(eq("draft"), any());
		assertThat(this.log.snapshot()).anyMatch(entry -> entry.role() == ChatLog.Role.AGENT);
	}

	@Test
	void runControlDuringBusyApprovalDoesNotLaunchSecondWorker() throws Exception {
		AtomicReference<String> operation = new AtomicReference<>();
		CountDownLatch asked = new CountDownLatch(1);
		var request = new ApprovalRequest(java.util.UUID.randomUUID(), RunId.random(), "attempt", null, null,
				"write_file", "a".repeat(64), null);
		TuiFrontEnd ui = new TuiFrontEnd(new AgentInputHandler(this.agent, this.session, this.console,
				List.of(SlashCommand.of("run", "control", (args, context) -> operation.set(args)))),
				this.session, this.console, this.log, new TraceGraph(), () -> "fake/a", Path.of("unused.log"));
		this.console.activate(() -> {
			ui.requestRedraw();
			if (this.console.pendingQuestion() != null) asked.countDown();
		});
		stubTurn("first", invocation -> {
			this.console.requestApproval(request, answer -> {});
			new CountDownLatch(1).await();
			return new Agent.AgentReply("unexpected", null);
		});
		ReflectionTestUtils.invokeMethod(ui, "handle", new PasteEvent("first"), this.runner, this.worker);
		ReflectionTestUtils.invokeMethod(ui, "handle", KeyEvent.ofKey(KeyCode.ENTER), this.runner, this.worker);
		await(asked);
		ReflectionTestUtils.invokeMethod(ui, "handle", new PasteEvent("/run cancel " + request.runId().value()),
				this.runner, this.worker);
		ReflectionTestUtils.invokeMethod(ui, "handle", KeyEvent.ofKey(KeyCode.ENTER), this.runner, this.worker);
		assertThat(operation.get()).isEqualTo("cancel " + request.runId().value());
		assertThat(ReflectionTestUtils.getField(ui, "busy")).isEqualTo(true);
		assertThat(this.console.pendingQuestionId()).isEqualTo(request.requestId());
		ReflectionTestUtils.invokeMethod(ui, "handle", new PasteEvent("/run start unsafe"), this.runner, this.worker);
		ReflectionTestUtils.invokeMethod(ui, "handle", KeyEvent.ofKey(KeyCode.ENTER), this.runner, this.worker);
		assertThat(operation.get()).isEqualTo("cancel " + request.runId().value());
		ReflectionTestUtils.invokeMethod(ui, "cancelTask");
		drainWorker();
		this.console.dismissApproval(request.requestId());
	}

	@Test
	void persistedRunStateKeepsIdentifiersAndModelSnapshot() {
		var graph = new TraceGraph();
		var ref = new com.example.miniagent.core.model.ModelRef("fake", "snapshot");
		for (var state : List.of(com.example.miniagent.core.run.RunState.RUNNING,
				com.example.miniagent.core.run.RunState.WAITING_APPROVAL,
				com.example.miniagent.core.run.RunState.CANCELLED)) {
			graph.accept(new com.example.miniagent.core.TraceEvent.RunStateChanged(
					"run-id", "conversation-id", "attempt-id", state, ref));
			assertThat(graph.runStatus()).contains("run-id", "attempt-id", state.name(), "snapshot");
			assertThat(graph.render(20, 100).stream().map(TraceGraph.Line::plain).toList())
					.anyMatch(line -> line.contains(state.name()));
		}
	}

	@Test
	void ordinaryFailureRemainsAnErrorAndAllowsNextTurn() throws Exception {
		this.console.activate(this.frontEnd::requestRedraw);
		when(this.agent.stream(eq("first"), any())).thenThrow(new IllegalStateException("failure"));
		submit("first");
		drainWorker();

		assertThat(this.log.snapshot()).anyMatch(entry -> entry.role() == ChatLog.Role.ERROR);
		assertNextTurnRuns();
	}

	@SuppressWarnings("unchecked")
	private void stubTurn(String input, org.mockito.stubbing.Answer<Agent.AgentReply> body) {
		when(this.agent.stream(eq(input), any())).thenAnswer(invocation -> {
			Agent.AgentReply reply = body.answer(invocation);
			if (reply != null && reply.text() != null && !reply.text().isEmpty()) {
				((java.util.function.Consumer<String>) invocation.getArgument(1)).accept(reply.text());
			}
			return reply;
		});
	}

	private void assertNextTurnRuns() throws Exception {
		AtomicBoolean interrupted = new AtomicBoolean(true);
		stubTurn("next", invocation -> {
			interrupted.set(Thread.currentThread().isInterrupted());
			return new Agent.AgentReply("done", null);
		});
		submit("next");
		drainWorker();
		verify(this.agent).stream(eq("next"), any());
		assertThat(interrupted).isFalse();
		assertThat(ReflectionTestUtils.getField(this.frontEnd, "busy")).isEqualTo(false);
	}

	private void assertIdleWithoutError() {
		assertThat(ReflectionTestUtils.getField(this.frontEnd, "busy")).isEqualTo(false);
		assertThat(this.log.snapshot()).noneMatch(entry -> entry.role() == ChatLog.Role.ERROR);
	}

	private void submit(String text) {
		event(new PasteEvent(text));
		event(KeyEvent.ofKey(KeyCode.ENTER));
	}

	private void event(Event event) {
		ReflectionTestUtils.invokeMethod(this.frontEnd, "handle", event, this.runner, this.worker);
	}

	private void drainWorker() throws Exception {
		this.worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
	}

	private static void await(CountDownLatch latch) throws InterruptedException {
		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
	}

}
