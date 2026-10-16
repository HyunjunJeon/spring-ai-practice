package com.example.miniagent.plugin.tui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunState;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.PasteEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 관리형 {@code /run}, {@code /goal} 작업 중 Esc 가 어느 취소 경로를 타는지 확인한다.
 * 실행 중인 run 이 있으면 작업 스레드를 건드리지 않고 {@code /run cancel <runId>} 를 보내야 한다.
 */
class TuiManagedRunCancelTest {

	private static final String CANCEL_NOTICE = "작업 취소를 요청했습니다.";

	private final ExecutorService worker = Executors.newSingleThreadExecutor();

	private final Agent agent = mock(Agent.class);

	private final AgentSession session = new AgentSession();

	private final ChatLog log = new ChatLog();

	private final TuiConsole console = new TuiConsole(this.log);

	private final TuiRunner runner = mock(TuiRunner.class);

	private final TraceGraph graph = new TraceGraph();

	private final String runId = RunId.random().value();

	/** /run, /goal 명령이 실제로 받은 호출을 "이름 인자" 꼴로 쌓는다. */
	private final List<String> invocations = new CopyOnWriteArrayList<>();

	private final CountDownLatch started = new CountDownLatch(1);

	private final CountDownLatch release = new CountDownLatch(1);

	private final AtomicBoolean interrupted = new AtomicBoolean();

	private final AtomicBoolean completed = new AtomicBoolean();

	private final TuiFrontEnd frontEnd = new TuiFrontEnd(
			new AgentInputHandler(this.agent, this.session, this.console,
					List.of(managedCommand("run"), managedCommand("goal"))),
			this.session, this.console, this.log, this.graph, () -> "test", Path.of("unused.log"));

	@AfterEach
	void stop() throws InterruptedException {
		this.release.countDown();
		this.console.deactivate();
		this.worker.shutdownNow();
		assertThat(this.worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/run start x", "/run resume x", "/goal start x", "/goal resume x" })
	void escapeDuringManagedTaskSendsRunCancelAndLeavesTheWorkerAlone(String command) throws Exception {
		seed(RunState.RUNNING);
		this.console.activate(this.frontEnd::requestRedraw);
		submit(command);
		await(this.started);

		event(KeyEvent.ofKey(KeyCode.ESCAPE));

		assertThat(this.invocations).containsExactly(command.substring(1), "run cancel " + this.runId);
		assertBusy(true);
		assertThat(this.log.snapshot()).noneMatch(entry -> entry.text().contains(CANCEL_NOTICE));
		this.release.countDown();
		drainWorker();
		assertThat(this.interrupted).isFalse();
		assertThat(this.completed).isTrue();
		assertBusy(false);
	}

	@Test
	void escapeBeforeTheManagedTaskStartsCancelsTheQueuedTaskInsteadOfSendingRunCancel() throws Exception {
		CountDownLatch occupied = new CountDownLatch(1);
		CountDownLatch free = new CountDownLatch(1);
		occupyWorker(occupied, free);
		await(occupied);
		seed(RunState.RUNNING);
		this.console.activate(this.frontEnd::requestRedraw);

		submit("/run start x");
		event(KeyEvent.ofKey(KeyCode.ESCAPE));

		assertBusy(false);
		assertThat(this.log.snapshot()).anyMatch(
				entry -> entry.role() == ChatLog.Role.INFO && entry.text().contains(CANCEL_NOTICE));
		free.countDown();
		drainWorker();
		assertThat(this.invocations).isEmpty();
		this.release.countDown();
		submit("/run start y");
		await(this.started);
		drainWorker();
		assertThat(this.invocations).containsExactly("run start y");
		assertThat(this.completed).isTrue();
	}

	@ParameterizedTest
	@NullSource
	@EnumSource(value = RunState.class, names = "RUNNING", mode = EnumSource.Mode.EXCLUDE)
	void escapeInterruptsTheManagedWorkerWhenNoRunIsRunning(RunState state) throws Exception {
		seed(state);
		this.console.activate(this.frontEnd::requestRedraw);
		submit("/run start x");
		await(this.started);

		event(KeyEvent.ofKey(KeyCode.ESCAPE));
		drainWorker();

		assertThat(this.invocations).containsExactly("run start x");
		assertThat(this.interrupted).isTrue();
		assertThat(this.completed).isFalse();
		assertThat(this.log.snapshot()).anyMatch(
				entry -> entry.role() == ChatLog.Role.INFO && entry.text().contains(CANCEL_NOTICE));
		assertThat(this.log.snapshot()).noneMatch(entry -> entry.role() == ChatLog.Role.ERROR);
		assertBusy(false);
	}

	@Test
	void escapeInterruptsAnOrdinaryTurnEvenIfAnEarlierManagedTaskLeftARunningRunInTheGraph() throws Exception {
		CountDownLatch turnStarted = new CountDownLatch(1);
		CountDownLatch turnRelease = new CountDownLatch(1);
		AtomicBoolean turnInterrupted = new AtomicBoolean();
		when(this.agent.streamQueued(eq("hello"), any(), any())).thenAnswer(invocation -> {
			turnStarted.countDown();
			try {
				turnRelease.await();
			}
			catch (InterruptedException ex) {
				turnInterrupted.set(true);
				Thread.currentThread().interrupt();
				throw new CancellationException("interrupted");
			}
			return new Agent.AgentReply("done", null);
		});
		seed(RunState.RUNNING);
		this.console.activate(this.frontEnd::requestRedraw);
		this.release.countDown();
		submit("/run start x");
		await(this.started);
		drainWorker();
		assertBusy(false);

		submit("hello");
		await(turnStarted);
		event(KeyEvent.ofKey(KeyCode.ESCAPE));
		drainWorker();

		assertThat(turnInterrupted).isTrue();
		assertThat(this.invocations).containsExactly("run start x");
		assertBusy(false);
	}

	@Test
	void escapeWithADraftOnlyClearsItAndTheNextEscapeCancelsTheRun() throws Exception {
		seed(RunState.RUNNING);
		this.console.activate(this.frontEnd::requestRedraw);
		submit("/run start x");
		await(this.started);

		event(new PasteEvent("draft"));
		event(KeyEvent.ofKey(KeyCode.ESCAPE));
		assertThat(this.invocations).containsExactly("run start x");

		event(KeyEvent.ofKey(KeyCode.ESCAPE));
		assertThat(this.invocations).containsExactly("run start x", "run cancel " + this.runId);
		this.release.countDown();
		drainWorker();
		assertThat(this.interrupted).isFalse();
	}

	/** start/resume 은 풀어 줄 때까지 작업 스레드를 붙잡고, cancel 은 호출만 기록한다. */
	private SlashCommand managedCommand(String name) {
		return SlashCommand.of(name, "managed " + name + " control", (args, context) -> {
			this.invocations.add(name + " " + args);
			if (!args.startsWith("cancel ")) {
				holdTheWorker();
			}
		});
	}

	private void holdTheWorker() {
		this.started.countDown();
		try {
			this.release.await();
			this.completed.set(true);
		}
		catch (InterruptedException ex) {
			this.interrupted.set(true);
			Thread.currentThread().interrupt();
			throw new CancellationException("interrupted");
		}
	}

	/** 그래프에 run 상태 이벤트를 하나 넣는다. null 이면 아무 run 도 보이지 않는 상태로 둔다. */
	private void seed(RunState state) {
		if (state != null) {
			this.graph.accept(new TraceEvent.RunStateChanged(this.runId, this.session.id(), "attempt-1", state,
					new ModelRef("fake", "scripted")));
		}
	}

	private void occupyWorker(CountDownLatch occupied, CountDownLatch free) {
		this.worker.submit(() -> {
			occupied.countDown();
			try {
				free.await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
	}

	private void assertBusy(boolean expected) {
		assertThat(ReflectionTestUtils.getField(this.frontEnd, "busy")).isEqualTo(expected);
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
