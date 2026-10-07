package com.example.miniagent.core.run;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.delegation.BudgetLedger;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.TurnContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real journals and contexts, not a ledger-only proof. All time and concurrency are explicit. */
class SharedParentBudgetTest {
	private static final Instant AT = Instant.EPOCH;
	private static final String DIGEST = "0".repeat(64);
	@TempDir Path home;

	private RunSpec spec(String id, RunSpec parent, RunBudget budget) {
		return new RunSpec(1, new RunId(id), "conversation-" + id,
				parent == null ? "original" : parent.conversationId(),
				parent == null ? null : parent.runId(), parent == null ? null : "worker",
				new ModelRef("fake", "test"), "fake", this.home.toString(), budget, DIGEST, DIGEST, AT);
	}

	private RunContext start(RunWriter writer, String attempt, Clock clock) throws Exception {
		RunSpec spec = writer.record().spec();
		var binding = new ModelBinding(UUID.randomUUID(), spec.model(), DIGEST, "fake",
				mock(ChatModel.class), mock(ChatClient.class));
		writer.append(new RunEvent.StateChanged(clock.instant(), RunState.RUNNING, RunEvent.Reason.NONE));
		writer.append(new RunEvent.AttemptStarted(clock.instant(), attempt, binding.bindingId()));
		var turn = new TurnContext(spec.conversationId(), UUID.randomUUID().toString(),
				Path.of(spec.cwd()), binding, spec.runId().value(), attempt);
		return new RunContext(spec, attempt, writer, turn, clock);
	}

	private RunEvent.UsageRecorded usage(RunWriter child, RunEvent.UsageKind kind, String call, RunUsage usage)
			throws Exception {
		var delta = new RunEvent.UsageRecorded(AT, "child-attempt", kind, call, usage);
		child.append(delta);
		return delta;
	}

	private Clock clock() { return Clock.fixed(AT, ZoneOffset.UTC); }

	@ParameterizedTest
	@EnumSource(value = RunState.class, names = {"COMPLETED", "FAILED", "CANCELLED", "WAITING_APPROVAL"})
	void everyOutcomeChargesLiveAndDurableOnceBeforeLaterParentWork(RunState outcome) throws Exception {
		var runs = new FileRunStore(home);
		var parent = spec("parent", null, new RunBudget(Duration.ofSeconds(10), 10L, 2));
		var child = spec("child", parent, RunBudget.defaults());
		try (var writer = runs.create(parent); var childWriter = runs.create(child)) {
			var context = start(writer, "parent-attempt", clock());
			context.toolAttempt(); // The parent's delegate attempt.
			start(childWriter, "child-attempt", clock());
			var delta = usage(childWriter, RunEvent.UsageKind.MODEL_ROUND, "round",
					new RunUsage(10, false, 1, 70, 0));
			if (outcome != RunState.WAITING_APPROVAL)
				childWriter.append(new RunEvent.AttemptEnded(AT, "child-attempt"));
			childWriter.append(new RunEvent.StateChanged(AT, outcome, RunEvent.Reason.NONE));
			context.chargeChildUsage(runs, child, delta);
			long sequence = writer.record().sequence();
			context.chargeChildUsage(runs, child, delta);
			assertThat(writer.record().sequence()).isEqualTo(sequence);
			assertThat(context.usage()).isEqualTo(new RunUsage(10, false, 2, 70, 0));
			assertThat(writer.record().usage()).isEqualTo(context.usage());
			assertThatThrownBy(context::beforeModel).isInstanceOf(RunExecutor.Stop.class);
			assertThatThrownBy(context::toolAttempt).isInstanceOf(RunExecutor.Stop.class);
			var secondPlan = new BudgetLedger(parent.budget(), context::usage, true);
			assertThatThrownBy(() -> secondPlan.reserve(java.util.Map.of("next",
					new RunBudget(Duration.ofMillis(1), 1L, 1)))).isInstanceOf(BudgetLedger.Refusal.class);
			var shared = new BudgetLedger(parent.budget(), context::usage, true);
			assertThat(shared.usage()).isEqualTo(context.usage());
		}
	}

	@Test void unknownRemainsStickyAndAlreadyStartedOvershootUsesParentCap() throws Exception {
		var runs = new FileRunStore(home);
		var parent = spec("parent", null, new RunBudget(Duration.ofSeconds(10), 10L, 5));
		var child = spec("child", parent, new RunBudget(Duration.ofSeconds(10), 100L, 5));
		try (var writer = runs.create(parent); var childWriter = runs.create(child)) {
			var context = start(writer, "parent-attempt", clock());
			start(childWriter, "child-attempt", clock());
			context.recordModel(new DefaultUsage(4, 0));
			var missing = usage(childWriter, RunEvent.UsageKind.MODEL_ROUND, "unknown",
					new RunUsage(0, true, 0, 0, 0));
			context.chargeChildUsage(runs, child, missing);
			// A concurrent call admitted before the unknown observation still returns honestly.
			var overshoot = usage(childWriter, RunEvent.UsageKind.MODEL_ROUND, "started-earlier",
					new RunUsage(9, false, 0, 0, 0));
			context.chargeChildUsage(runs, child, overshoot);
			assertThat(context.usage()).isEqualTo(new RunUsage(13, true, 0, 0, 3));
			assertThat(writer.record().usage()).isEqualTo(context.usage());
			assertThatThrownBy(context::beforeModel).isInstanceOf(RunExecutor.Stop.class);
			assertThatThrownBy(context::toolAttempt).isInstanceOf(RunExecutor.Stop.class);
		}
	}

	@Test void childActiveTimeIsImportedWithoutChargingParentWaitTwice() throws Exception {
		var time = new AtomicReference<>(AT);
		var clock = mock(Clock.class);
		when(clock.instant()).thenAnswer(invocation -> time.get());
		var runs = new FileRunStore(home);
		var parent = spec("parent", null, RunBudget.defaults());
		var child = spec("child", parent, RunBudget.defaults());
		try (var writer = runs.create(parent); var childWriter = runs.create(child)) {
			var context = start(writer, "parent-attempt", clock);
			start(childWriter, "child-attempt", clock());
			time.set(AT.plusMillis(3));
			context.pauseBudget();
			time.set(AT.plusMillis(1003));
			var delta = usage(childWriter, RunEvent.UsageKind.ACTIVE_TIME, "active", new RunUsage(0, false, 0, 7, 0));
			context.chargeChildUsage(runs, child, delta);
			context.resumeBudget();
			time.set(AT.plusMillis(1005));
			context.finishActiveTime();
			context.finishActiveTime();
			assertThat(context.usage().activeMillis()).isEqualTo(12);
			assertThat(writer.record().usage().activeMillis()).isEqualTo(12);
		}
	}

	@Test void restartRepairsAcknowledgedGapAndDeduplicatesAcrossParentAttemptsAndUsageKinds() throws Exception {
		var runs = new FileRunStore(home);
		var parent = spec("parent", null, RunBudget.defaults());
		var child = spec("child", parent, RunBudget.defaults());
		RunEvent.UsageRecorded first;
		try (var writer = runs.create(parent); var childWriter = runs.create(child)) {
			var context = start(writer, "parent-first", clock());
			start(childWriter, "child-attempt", clock());
			first = usage(childWriter, RunEvent.UsageKind.MODEL_ROUND, "same-call", new RunUsage(8, false, 0, 0, 0));
			context.chargeChildUsage(runs, child, first);
			// Child acknowledgement is durable; process loss occurs before the parent's import.
			usage(childWriter, RunEvent.UsageKind.TOOLS, "same-call", new RunUsage(0, false, 1, 0, 0));
			childWriter.append(new RunEvent.AttemptEnded(AT, "child-attempt"));
			childWriter.append(new RunEvent.StateChanged(AT, RunState.COMPLETED, RunEvent.Reason.NONE));
			writer.append(new RunEvent.AttemptEnded(AT, "parent-first"));
			writer.append(new RunEvent.StateChanged(AT, RunState.INTERRUPTED, RunEvent.Reason.PROCESS_LOST));
		}
		var restarted = new FileRunStore(home);
		try (var writer = restarted.openWriter(parent.runId())) {
			var context = start(writer, "parent-second", clock());
			context.reconcileChildUsage(restarted);
			context.reconcileChildUsage(restarted);
			context.chargeChildUsage(restarted, child, first);
			assertThat(context.usage()).isEqualTo(new RunUsage(8, false, 1, 0, 0));
			assertThat(writer.record().usage()).isEqualTo(context.usage());
			var charged = FileRunStore.events(restarted, parent.runId()).stream()
					.filter(RunEvent.UsageRecorded.class::isInstance).map(RunEvent.UsageRecorded.class::cast)
					.filter(event -> event.callId().startsWith("child-")).findFirst().orElseThrow();
			assertThat(org.junit.jupiter.api.Assertions.assertThrows(RunStoreException.class,
					() -> writer.append(new RunEvent.UsageRecorded(AT, "parent-second",
							RunEvent.UsageKind.COMPACTION, charged.callId(), charged.usage()))).code())
					.isEqualTo(RunStoreException.Code.INVALID_TRANSITION);
			context.finishActiveTime();
			writer.append(new RunEvent.AttemptEnded(AT, "parent-second"));
			writer.append(new RunEvent.StateChanged(AT, RunState.COMPLETED, RunEvent.Reason.NONE));
		}
		assertThat(new FileRunStore(home).read(parent.runId()).usage()).isEqualTo(new RunUsage(8, false, 1, 0, 0));
	}

	@Test void concurrentCallbacksWithDuplicateIdentityChargeOnlyOnce() throws Exception {
		var runs = new FileRunStore(home);
		var parent = spec("parent", null, RunBudget.defaults());
		var child = spec("child", parent, RunBudget.defaults());
		try (var writer = runs.create(parent); var childWriter = runs.create(child)) {
			var context = start(writer, "parent-attempt", clock());
			start(childWriter, "child-attempt", clock());
			var first = usage(childWriter, RunEvent.UsageKind.MODEL_ROUND, "one", new RunUsage(2, false, 0, 0, 0));
			var second = usage(childWriter, RunEvent.UsageKind.MODEL_ROUND, "two", new RunUsage(3, false, 0, 0, 0));
			var ready = new CountDownLatch(2);
			var go = new CountDownLatch(1);
			var left = importAsync(context, runs, child, first, ready, go);
			var right = importAsync(context, runs, child, second, ready, go);
			try {
				assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			}
			finally { go.countDown(); }
			CompletableFuture.allOf(left, right).get(5, TimeUnit.SECONDS);
			assertThat(context.usage().knownTokens()).isEqualTo(5);
			assertThat(writer.record().usage().knownTokens()).isEqualTo(5);
		}
	}

	private CompletableFuture<Void> importAsync(RunContext context, RunStore runs, RunSpec child,
			RunEvent.UsageRecorded delta, CountDownLatch ready, CountDownLatch go) {
		return CompletableFuture.runAsync(() -> {
			ready.countDown();
			try {
				if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("Missing release event");
				context.chargeChildUsage(runs, child, delta);
				context.chargeChildUsage(runs, child, delta);
			}
			catch (Exception ex) { throw new java.util.concurrent.CompletionException(ex); }
		});
	}

	@Test void unacknowledgedForeignAndTerminalChargesAreRefusedWithoutMutation() throws Exception {
		var runs = new FileRunStore(home);
		var parent = spec("parent", null, RunBudget.defaults());
		var foreign = spec("foreign", spec("other-parent", null, RunBudget.defaults()), RunBudget.defaults());
		var child = spec("child", parent, RunBudget.defaults());
		try (var writer = runs.create(parent); var childWriter = runs.create(child); var foreignWriter = runs.create(foreign)) {
			var context = start(writer, "parent-attempt", clock());
			start(childWriter, "child-attempt", clock());
			start(foreignWriter, "child-attempt", clock());
			var delta = usage(childWriter, RunEvent.UsageKind.MODEL_ROUND, "one", new RunUsage(3, false, 0, 0, 0));
			var foreignDelta = usage(foreignWriter, RunEvent.UsageKind.MODEL_ROUND, "one", delta.usage());
			assertThatThrownBy(() -> context.chargeChildUsage(runs, foreign, foreignDelta)).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> context.chargeChildUsage(runs, child,
					new RunEvent.UsageRecorded(AT, "child-attempt", delta.kind(), delta.callId(), new RunUsage(4, false, 0, 0, 0))))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> context.reconcileChildUsage(runs)).isInstanceOf(RunExecutor.Stop.class);
			assertThat(context.usage()).isEqualTo(RunUsage.empty());
			context.finishActiveTime();
			writer.append(new RunEvent.AttemptEnded(AT, "parent-attempt"));
			writer.append(new RunEvent.StateChanged(AT, RunState.COMPLETED, RunEvent.Reason.NONE));
			long sequence = writer.record().sequence();
			assertThatThrownBy(() -> context.chargeChildUsage(runs, child, delta)).isInstanceOf(IllegalStateException.class);
			assertThat(writer.record().sequence()).isEqualTo(sequence);
		}
	}

	@Test void failedParentAppendStopsContinuationAndFreshContextReconcilesWithoutInventingCharge() throws Exception {
		var runs = new FileRunStore(home);
		var parent = spec("parent", null, RunBudget.defaults());
		var child = spec("child", parent, RunBudget.defaults());
		var fail = new AtomicBoolean(true);
		try (var writer = runs.create(parent); var childWriter = runs.create(child)) {
			start(writer, "parent-attempt", clock());
			start(childWriter, "child-attempt", clock());
			RunWriter rejecting = new RunWriter() {
				public RunRecord record() { return writer.record(); }
				public RunRecord append(RunEvent event) throws RunStoreException {
					if (event instanceof RunEvent.UsageRecorded delta && delta.callId().startsWith("child-") && fail.get())
						throw new RunStoreException(RunStoreException.Code.IO, "Injected failure before append");
					return writer.append(event);
				}
				public void close() {}
			};
			var binding = new ModelBinding(UUID.randomUUID(), parent.model(), DIGEST, "fake", mock(ChatModel.class), mock(ChatClient.class));
			var turn = new TurnContext(parent.conversationId(), "turn", home, binding, parent.runId().value(), "parent-attempt");
			var context = new RunContext(parent, "parent-attempt", rejecting, turn, clock());
			var delta = usage(childWriter, RunEvent.UsageKind.MODEL_ROUND, "one", new RunUsage(3, false, 0, 0, 0));
			assertThatThrownBy(() -> context.chargeChildUsage(runs, child, delta)).isInstanceOf(RunStoreException.class);
			assertThat(context.usage()).isEqualTo(RunUsage.empty());
			assertThatThrownBy(context::beforeModel).isInstanceOf(java.io.UncheckedIOException.class);
			assertThatThrownBy(context::finishActiveTime).isInstanceOf(RunStoreException.class);
			fail.set(false);
			var fresh = new RunContext(parent, "parent-attempt", rejecting, turn, clock());
			fresh.chargeChildUsage(runs, child, delta);
			assertThat(fresh.usage()).isEqualTo(writer.record().usage()).isEqualTo(delta.usage());
		}
	}
}
