package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.model.FileSessionModelStore;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.ProviderAuth;
import com.example.miniagent.core.model.registry.ModelRegistry;
import com.example.miniagent.core.run.*;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** agent.runs.wall caps active time across attempts, including an attempt whose process was lost. */
class RunWallBudgetTest {
	private static final ModelSelection SELECTED = new ModelSelection(new ModelRef("fake", "wall"));
	private static final Instant START = Instant.parse("2026-10-06T00:00:00Z");
	private static final Duration WALL = Duration.ofMinutes(10);
	private static final Duration WORK = Duration.ofMinutes(15);
	/** Above WORK but below twice WORK, so a lost attempt charged twice exhausts it. */
	private static final Duration LONG_WALL = Duration.ofMinutes(20);
	/** Paused time inside the lost attempt: longer than LONG_WALL on its own. */
	private static final Duration PAUSED = Duration.ofMinutes(30);
	private static final Duration CHILD = Duration.ofMinutes(1);
	private static final String DIGEST = "a".repeat(64);
	private static final String GOAL = "!tool checkpoint {}";

	@TempDir Path directory;
	private final MutableClock clock = new MutableClock();
	private final AtomicInteger modelCalls = new AtomicInteger();
	private volatile boolean loseProcess;
	private volatile Journaled beforeLoss;
	private FileRunStore runs;
	private RunExecutor executor;
	private Path cwd;

	@BeforeEach
	void setup() throws Exception {
		Path home = Files.createDirectory(this.directory.resolve("state"));
		this.cwd = Files.createDirectory(this.directory.resolve("workspace")).toRealPath();
		this.runs = new FileRunStore(home);
		var selections = new FileSessionModelStore(home);
		var callbacks = MethodToolCallbackProvider.builder().toolObjects(new CheckpointTool()).build();
		ModelProvider provider = new ModelProvider() {
			@Override public String providerId() { return "fake"; }
			@Override public ProviderAuth auth() { return () -> true; }
			@Override public List<ModelEntry> models() { return List.of(new ModelEntry(SELECTED.ref(), "fake", null)); }
			@Override public ChatModel create(ModelRef ref, Map<String, String> options) {
				return new ScriptedChatModel() {
					@Override public ChatResponse call(Prompt prompt) {
						// The first round spends WORK of active time before its usage row is journaled.
						if (modelCalls.incrementAndGet() == 1) clock.advance(WORK);
						return super.call(prompt);
					}
				};
			}
		};
		var registry = new ModelRegistry(List.of(provider), model -> ChatClient.builder(model).defaultOptions(
				ToolCallingChatOptions.builder().toolCallbacks(callbacks.getToolCallbacks())).build());
		var agent = new Agent(registry, selections, SELECTED, new AgentSession(), this.cwd, "test",
				List.of(), List.of(callbacks), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
		this.executor = new RunExecutor(agent, registry, selections, this.runs, new RunRequestStore(home),
				this.clock, List.of());
	}

	@Test void settledAttemptKeepsItsActiveTimeSoResumeStaysAtTheWall() throws Exception {
		RunRecord first = start().record();
		assertThat(first).extracting(RunRecord::state, RunRecord::reason)
				.containsExactly(RunState.INTERRUPTED, RunEvent.Reason.BUDGET_EXHAUSTED);
		assertThat(first.usage().activeMillis()).isEqualTo(WORK.toMillis());
		RunRecord resumed = this.executor.resume(first.spec().runId(), SELECTED, this.cwd).record();
		assertThat(resumed).extracting(RunRecord::state, RunRecord::reason)
				.containsExactly(RunState.INTERRUPTED, RunEvent.Reason.BUDGET_EXHAUSTED);
		assertThat(resumed.attempts()).hasSize(2);
		assertThat(this.modelCalls).hasValue(1);
	}

	@Test void lostAttemptIsProcessLostWithItsFirstRoundStillJournaled() throws Exception {
		RunId id = loseProcessAfterFirstRound();
		RunRecord lost = this.runs.read(id);
		assertThat(lost).extracting(RunRecord::state, RunRecord::reason)
				.containsExactly(RunState.INTERRUPTED, RunEvent.Reason.PROCESS_LOST);
		assertThat(lost.attempts()).singleElement().extracting(Attempt::endedAt).isNull();
		// The durable journal itself shows WORK of activity before the loss.
		assertThat(FileRunStore.events(this.runs, id)).filteredOn(RunEvent.UsageRecorded.class::isInstance)
				.singleElement().extracting(RunEvent::at).isEqualTo(START.plus(WORK));
	}

	@Test void lostAttemptStillCountsTowardTheWallOnResume() throws Exception {
		RunId id = loseProcessAfterFirstRound();
		RunRecord resumed = this.executor.resume(id, SELECTED, this.cwd).record();
		assertThat(resumed).extracting(RunRecord::state, RunRecord::reason)
				.containsExactly(RunState.INTERRUPTED, RunEvent.Reason.BUDGET_EXHAUSTED);
		assertThat(resumed.usage().activeMillis()).isGreaterThanOrEqualTo(WORK.toMillis());
		assertThat(this.modelCalls).hasValue(1);
	}

	@Test void activeTimeTheLostAttemptJournaledIsNotChargedTwice() throws Exception {
		// A deferred approval journals ACTIVE_TIME, then keeps the writer until its process exits.
		RunId id = loseProcessAfterFirstRound(LONG_WALL, RunContext::finishActiveTime);
		RunRecord resumed = this.executor.resume(id, SELECTED, this.cwd).record();
		assertThat(resumed.state()).isEqualTo(RunState.COMPLETED);
		assertThat(resumed.usage().activeMillis()).isEqualTo(WORK.toMillis());
	}

	@Test void approvalWaitBeforeTheLossDoesNotCountTowardTheWall() throws Exception {
		RunId id = loseProcessAfterFirstRound(LONG_WALL, context -> {
			context.writer().append(new RunEvent.ApprovalRequested(context.now(), context.attemptId(), "approval",
					"checkpoint-call", DIGEST, null));
			this.clock.advance(PAUSED);
			context.writer().append(new RunEvent.ApprovalResolved(context.now(), context.attemptId(), "approval",
					RunEvent.Decision.APPROVED));
		});
		RunRecord resumed = this.executor.resume(id, SELECTED, this.cwd).record();
		assertThat(resumed.state()).isEqualTo(RunState.COMPLETED);
		assertThat(resumed.usage().activeMillis()).isEqualTo(WORK.toMillis());
	}

	@Test void childRunBeforeTheLossCountsOnlyThroughItsImportedRow() throws Exception {
		// The parent pauses while a child runs, then imports the child's own acknowledged time.
		RunId id = loseProcessAfterFirstRound(LONG_WALL, context -> {
			this.clock.advance(PAUSED);
			context.writer().append(new RunEvent.UsageRecorded(context.now(), context.attemptId(),
					RunEvent.UsageKind.COMPACTION, "child-" + DIGEST, new RunUsage(0, false, 0, CHILD.toMillis(), 0)));
		});
		RunRecord resumed = this.executor.resume(id, SELECTED, this.cwd).record();
		assertThat(resumed.state()).isEqualTo(RunState.COMPLETED);
		assertThat(resumed.usage().activeMillis()).isEqualTo(WORK.plus(CHILD).toMillis());
	}

	private RunExecutor.Result start() throws Exception {
		return start(WALL);
	}

	private RunExecutor.Result start(Duration wall) throws Exception {
		return this.executor.start(GOAL, "parent", SELECTED, this.cwd, new RunBudget(wall, null, 50));
	}

	/** The tool releases the run writer the way a killed JVM releases its lock: nothing later is journaled. */
	private RunId loseProcessAfterFirstRound() throws Exception {
		return loseProcessAfterFirstRound(WALL, context -> {});
	}

	/** As above, after the lost attempt journals {@code rows} through its own writer and the mutable clock. */
	private RunId loseProcessAfterFirstRound(Duration wall, Journaled rows) throws Exception {
		this.loseProcess = true;
		this.beforeLoss = rows;
		assertThat(assertThrows(RunStoreException.class, () -> start(wall)).code())
				.isEqualTo(RunStoreException.Code.CLOSED);
		this.loseProcess = false;
		return this.runs.list().getFirst();
	}

	private interface Journaled {
		void append(RunContext context) throws RunStoreException;
	}

	public class CheckpointTool {
		@Tool(name = "checkpoint", description = "Reached after the first model round")
		public String reach() throws RunStoreException {
			if (loseProcess) {
				RunContext context = RunContext.current().orElseThrow();
				beforeLoss.append(context);
				context.writer().close();
			}
			return "reached";
		}
	}

	private static final class MutableClock extends Clock {
		private volatile Instant value = START;
		void advance(Duration duration) { this.value = this.value.plus(duration); }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return this.value; }
	}
}
