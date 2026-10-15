package com.example.miniagent.core.delegation;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.ConsoleApprovalBroker;
import com.example.miniagent.core.execution.StagingToolInterceptor;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.model.registry.ModelRegistry;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.*;

/** Real scope/model assembly and private durable stores; no paid application calls. */
class DelegationRuntimeTest {
	@TempDir Path directory;

	@Test void storedParentWithoutExactCaptureCannotFallBackToLegacyAccounting() throws Exception {
		var f = new Fixture(1, 1);
		try (var writer = f.runs.create(f.parentSpec()); var runtime = f.runtime()) {
			f.startParent(writer);
			assertThatThrownBy(() -> f.execute(runtime, f.plan(f.child("a", Set.of()))))
					.isInstanceOf(IllegalArgumentException.class);
			assertThat(f.calls).hasValue(0);
			assertThat(f.runs.list()).containsExactly(writer.record().spec().runId());
		}
	}

	@Test void pendingOperatorAndRestartedChildUseSameResumableParentAndDurableAggregate() throws Exception {
		var f = new Fixture(1, 1);
		f.parentBudget = new RunBudget(Duration.ofSeconds(10), null, 10);
		var child = new ChildSpec("pending", "pending task", ChildRole.RESEARCH, f.ref, Set.of(),
				f.childScope, new RunBudget(Duration.ofMillis(100), null, 1), 1, true);
		f.script = prompt -> { throw new Stop(Stop.Kind.DEFERRED); };
		var executor = new RunExecutor(f.agent, f.agent.modelRegistry(), new FileSessionModelStore(f.home),
				f.runs, new RunRequestStore(f.home), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), List.of());
		RunRecord parent;
		RunRecord pendingChild;
		try (var runtime = new DefaultDelegationRuntime(f.factory,
				new DelegationProperties(1, 1, 1, 2, Duration.ofSeconds(1), null, 10), f.sessions)) {
			var operation = executor.startOperation("operator pending", "original", new ModelSelection(f.ref),
					f.workspace, f.parentBudget, context -> runtime.executeOutcome(context.turnContext(),
							new DelegationPlan("pending-plan", List.of(child), 1), f.parentScope,
							f.parentBudget, context::usage, "revision", "config"));
			assertThat(operation.value()).isInstanceOf(DefaultDelegationRuntime.Pending.class);
			parent = operation.record();
			pendingChild = ((DefaultDelegationRuntime.Pending) operation.value()).children().getFirst().record();
			assertThat(parent.state()).isEqualTo(RunState.INTERRUPTED);
			assertThat(parent.usage()).isEqualTo(pendingChild.usage());
		}
		int count = f.runs.list().size();
		f.script = prompt -> f.answer(2);
		var recovered = f.factory.recoverParent(pendingChild.spec().runId(), child, "original", f.parentScope, () -> {
			try (var scope = f.factory.reconstruct(pendingChild.spec().runId(), child, "original", f.parentScope)) {
				return scope.resume(pendingChild.spec().runId(), RunExecutor.ResumeOptions.ordinary());
			}
		});
		assertThat(recovered.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(recovered.record().spec()).isEqualTo(pendingChild.spec());
		assertThat(recovered.record().attempts()).hasSize(pendingChild.attempts().size() + 1);
		var finalParent = f.runs.read(parent.spec().runId());
		assertThat(finalParent.state()).isEqualTo(RunState.COMPLETED);
		assertThat(finalParent.spec()).isEqualTo(parent.spec());
		assertThat(finalParent.attempts()).hasSize(parent.attempts().size() + 1);
		assertThat(finalParent.usage()).isEqualTo(recovered.record().usage());
		assertThat(finalParent.usage().knownTokens()).isEqualTo(2);
		assertThat(finalParent.usage().tokensUnknown()).isTrue();
		assertThat(f.runs.list()).hasSize(count);
	}

	@Test void capturedParentRetainsChildrenAcrossTwoPlansWithoutLedgerDoubleAddition() throws Exception {
		var f = new Fixture(1, 1);
		try (var writer = f.runs.create(f.parentSpec()); var runtime = f.runtime()) {
			var context = f.startParent(writer);
			try (var captured = context.open()) {
				var first = f.execute(runtime, f.plan(f.child("a", Set.of())));
				assertThat(first.usage()).isEqualTo(new RunUsage(2, false, 0, 0, 0));
				var second = f.execute(runtime, f.plan(f.child("b", Set.of())));
				assertThat(second.usage()).isEqualTo(new RunUsage(2, false, 0, 0, 0));
				context.recordModel(new DefaultUsage(3, 0));
				assertThat(context.usage()).isEqualTo(new RunUsage(7, false, 0, 0, 0));
				assertThat(writer.record().usage()).isEqualTo(context.usage());
				assertThat(f.calls).hasValue(2);
			}
		}
	}

	@Test void capturedParentFailureRetainsAcknowledgedUsageWhenChildToolIsDenied() throws Exception {
		var f = new Fixture(1, 1);
		f.script = prompt -> new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("read-call", "function", "read_file",
					"{\"path\":\"source.txt\"}"))).build())),
				ChatResponseMetadata.builder().usage(new DefaultUsage(10, 0)).build());
		try (var writer = f.runs.create(f.parentSpec()); var runtime = f.runtime()) {
			var context = f.startParent(writer);
			try (var captured = context.open()) {
				var result = f.execute(runtime, f.plan(f.child("a", Set.of())));
				assertThat(result.children().getFirst().failure().code()).isEqualTo(ChildFailure.Code.BUDGET_EXHAUSTED);
				assertThat(f.effects).hasValue(0);
				assertThat(context.usage()).isEqualTo(result.usage()).isEqualTo(writer.record().usage());
				assertThat(context.usage().knownTokens()).isEqualTo(10);
			}
		}
	}

	@Test void capturedParentActiveTimeExcludesDelegationWaitAndResumesAfterwards() throws Exception {
		var f = new Fixture(1, 1);
		var time = new AtomicReference<>(Instant.EPOCH);
		Clock clock = org.mockito.Mockito.mock(Clock.class);
		org.mockito.Mockito.when(clock.instant()).thenAnswer(invocation -> time.get());
		var binding = f.parent.binding();
		var spec = new RunSpec(RunSpec.SCHEMA_VERSION, new RunId(f.parent.runId()), f.parent.sessionId(),
				"outer", null, null, binding.ref(), binding.wireFamily(), f.parent.cwd().toString(),
				f.parentBudget, binding.optionsFingerprint(), "0".repeat(64), Instant.EPOCH);
		try (var writer = f.runs.create(spec); var runtime = f.runtime()) {
			writer.append(new RunEvent.StateChanged(Instant.EPOCH, RunState.RUNNING, RunEvent.Reason.NONE));
			writer.append(new RunEvent.AttemptStarted(Instant.EPOCH, f.parent.attemptId(), binding.bindingId()));
			var context = new RunContext(spec, f.parent.attemptId(), writer, f.parent, clock);
			time.set(Instant.EPOCH.plusSeconds(3));
			f.script = prompt -> {
				time.set(time.get().plusSeconds(20));
				return f.answer(2);
			};
			try (var run = context.open(); var turn = f.parent.open()) {
				var result = f.execute(runtime, f.plan(f.child("a", Set.of())));
				assertThat(result.children().getFirst().status()).isEqualTo(ChildStatus.SUCCEEDED);
				assertThat(context.usage().activeMillis()).isEqualTo(3000);
				time.set(time.get().plusSeconds(2));
				assertThat(context.usage().activeMillis()).isEqualTo(5000);
				context.finishActiveTime();
				assertThat(writer.record().usage().activeMillis()).isEqualTo(5000);
			}
		}
	}

	@Test void structuralCycleRejectsBeforeModelBindingOrDurableWrites() throws Exception {
		var f = new Fixture(2, 2);
		try (var runtime = f.runtime()) {
			var plan = f.plan(f.child("a", Set.of("b")), f.child("b", Set.of("a")));
			assertThatThrownBy(() -> f.execute(runtime, plan))
				.isInstanceOf(DelegationPlanValidator.Rejection.class).hasMessageContaining("CYCLE");
			assertThat(f.calls).hasValue(0);
			assertThat(f.runs.list()).isEmpty();
			assertThat(runtime.executions()).isEmpty();
		}
	}

	@Test void wholePlanReservationsRejectWithoutStartingPrefix() throws Exception {
		var f = new Fixture(1, 2);
		f.parentBudget = new RunBudget(Duration.ofMillis(199), 100L, 10);
		try (var runtime = f.runtime()) {
			assertThatThrownBy(() -> f.execute(runtime, f.plan(f.child("a", Set.of()), f.child("b", Set.of()))))
				.isInstanceOf(DelegationPlanValidator.Rejection.class).hasMessageContaining("RESERVATION_EXCEEDED");
			assertThat(f.runs.list()).isEmpty();
			assertThat(f.calls).hasValue(0);
		}
	}

	@Test void actualScopesHaveIndependentDurableIdentitiesAndCapturedParentModel() throws Exception {
		var f = new Fixture(1, 1);
		Path history = f.home.resolve("parent-history.jsonl");
		Files.writeString(history, "parent-only");
		f.agent.selectModel(new ModelSelection(f.other));
		try (var runtime = f.runtime()) {
			var result = f.execute(runtime, f.plan(f.child("a", Set.of()), f.child("b", Set.of("a"))));
			assertThat(result.children()).extracting(ChildResult::status)
				.containsExactly(ChildStatus.SUCCEEDED, ChildStatus.SUCCEEDED);
			assertThat(result.children()).extracting(ChildResult::model).containsOnly(f.ref);
			assertThat(result.children()).extracting(ChildResult::conversationId).doesNotHaveDuplicates();
			assertThat(result.children()).extracting(ChildResult::runId).doesNotHaveDuplicates();
			assertThat(result.children()).extracting(ChildResult::attemptId).doesNotHaveDuplicates();
			for (var child : result.children()) {
				var stored = f.runs.read(child.runId());
				assertThat(stored.spec().parentConversationId()).isEqualTo(f.parent.sessionId());
				assertThat(stored.spec().parentRunId()).isEqualTo(new RunId(f.parent.runId()));
				assertThat(stored.spec().childId()).isEqualTo(child.childId());
				assertThat(stored.spec().conversationId()).isEqualTo(child.conversationId().toString());
				assertThat(stored.attempts().getLast().attemptId()).isEqualTo(child.attemptId().toString());
				assertThat(stored.usage()).isEqualTo(child.usage());
				assertThat(child.evidence()).allMatch(e -> e instanceof ChildEvidence.ModelNarrative);
				assertThat(child.verifiedSuccess("revision", "config")).isFalse();
			}
			assertThat(result.status()).isEqualTo(DelegationResult.Status.FAILED);
			assertThat(result.usage().knownTokens()).isEqualTo(4);
			assertThat(runtime.executions()).isEmpty();
		}
		assertThat(Files.readString(history)).isEqualTo("parent-only");
		assertThat(f.agent.selectedModel().ref()).isEqualTo(f.other);
	}

	@Test void failedDependencySkipsDependentsPlanPartial() throws Exception {
		var f = new Fixture(1, 2);
		f.script = prompt -> {
			if (prompt.getContents().contains("task a"))
				return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
						.toolCalls(List.of(new AssistantMessage.ToolCall("denied-read", "function", "read_file",
								"{\"path\":\"../outside\"}"))).build())),
						ChatResponseMetadata.builder().usage(new DefaultUsage(2, 0)).build());
			return f.answer(2);
		};
		try (var runtime = f.runtime()) {
			var result = f.execute(runtime, f.plan(f.child("a", Set.of()), f.child("b", Set.of("a")),
					f.child("independent", Set.of())));
			assertThat(result.children()).extracting(ChildResult::status)
				.containsExactly(ChildStatus.FAILED, ChildStatus.SKIPPED, ChildStatus.SUCCEEDED);
			assertThat(result.children().get(1).failure().code()).isEqualTo(ChildFailure.Code.DEPENDENCY_FAILED);
			assertThat(f.calls).hasValue(2);
			// The plan is execution-partial, but no child has task22 verification evidence.
			assertThat(result.status()).isEqualTo(DelegationResult.Status.FAILED);
			assertThat(f.runs.list()).hasSize(2);
		}
	}

	@Test void zeroQueueRejectsOverflowAndSkipsItsDependents() throws Exception {
		var f = new Fixture(1, 0);
		try (var runtime = f.runtime()) {
			var result = f.execute(runtime, f.plan(f.child("a", Set.of()), f.child("b", Set.of()),
					f.child("dependent", Set.of("b"))));
			assertThat(result.children().get(0).status()).isEqualTo(ChildStatus.SUCCEEDED);
			assertThat(result.children().get(1).failure().code()).isEqualTo(ChildFailure.Code.QUEUE_REJECTED);
			assertThat(result.children().get(2).failure().code()).isEqualTo(ChildFailure.Code.QUEUE_REJECTED);
			assertThat(f.runs.list()).hasSize(1);
		}
	}

	@Test void parentRemainingCapIsCheckedAgainBeforeChildModel() throws Exception {
		var f = new Fixture(1, 1);
		var samples = new AtomicInteger();
		try (var runtime = f.runtime()) {
			var result = runtime.execute(f.parent, f.plan(f.child("a", Set.of())), f.parentScope,
					f.parentBudget, () -> samples.incrementAndGet() < 3 ? RunUsage.empty()
						: new RunUsage(100, false, 0, 0, 0), "revision", "config");
			assertThat(result.children().getFirst().failure().code()).isEqualTo(ChildFailure.Code.BUDGET_EXHAUSTED);
			assertThat(f.calls).hasValue(0);
		}
	}

	@Test void unknownRoundStopsToolsAndIsNeverAutomaticallyRetried() throws Exception {
		var f = new Fixture(1, 1);
		f.script = prompt -> new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("read-call", "function", "read_file",
					"{\"path\":\"source.txt\"}"))).build())));
		try (var runtime = f.runtime()) {
			var result = f.execute(runtime, f.plan(f.child("a", Set.of())));
			assertThat(result.children().getFirst().failure().code()).isEqualTo(ChildFailure.Code.BUDGET_EXHAUSTED);
			assertThat(result.usage().tokensUnknown()).isTrue();
			assertThat(f.effects).hasValue(0);
			assertThat(f.calls).hasValue(1);
			assertThat(f.runs.list()).hasSize(1);
		}
	}

	@Test void sharedParentUnknownAfterModelRefusesBeforeToolEffect() throws Exception {
		var f = new Fixture(1, 1);
		f.script = prompt -> {
			f.parentUsage.set(new RunUsage(0, true, 0, 0, 0));
			return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall("read-call", "function", "read_file",
						"{\"path\":\"source.txt\"}"))).build())),
					ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build());
		};
		try (var runtime = f.runtime()) {
			var result = f.execute(runtime, f.plan(f.child("a", Set.of())));
			assertThat(result.children().getFirst().failure().code()).isEqualTo(ChildFailure.Code.BUDGET_EXHAUSTED);
			assertThat(f.effects).hasValue(0);
			assertThat(result.usage().knownTokens()).isEqualTo(2);
			assertThat(result.usage().tokensUnknown()).isFalse(); // child-only, parent unknown is not invented child usage
		}
	}

	@Test void durableRoundToolAndFinalDeltasAreCountedOnceNotCumulativeSnapshots() throws Exception {
		var f = new Fixture(1, 1);
		var rounds = new AtomicInteger();
		f.script = prompt -> rounds.getAndIncrement() == 0
				? new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
					.toolCalls(List.of(new AssistantMessage.ToolCall("read-call", "function", "read_file",
						"{\"path\":\"source.txt\"}"))).build())),
					ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build()) : f.answer(2);
		try (var runtime = f.runtime()) {
			var result = f.execute(runtime, f.plan(f.child("a", Set.of())));
			var child = result.children().getFirst();
			assertThat(child.status()).isEqualTo(ChildStatus.SUCCEEDED);
			assertThat(child.usage()).isEqualTo(f.runs.read(child.runId()).usage());
			assertThat(result.usage()).isEqualTo(new RunUsage(4, false, 1, 0, 0));
			assertThat(f.effects).hasValue(1);
			assertThat(f.calls).hasValue(2);
		}
	}

	@Test void directScopedSummaryModelSharesLedgerAndStopsFollowingLoopCall() throws Exception {
		var f = new Fixture(1, 1, true);
		f.script = prompt -> {
			assertThat(prompt.getContents()).contains("summary");
			return f.answer(10);
		};
		try (var runtime = f.runtime()) {
			var result = f.execute(runtime, f.plan(f.child("a", Set.of())));
			assertThat(result.children().getFirst().failure().code()).isEqualTo(ChildFailure.Code.BUDGET_EXHAUSTED);
			assertThat(result.usage().knownTokens()).isEqualTo(10);
			assertThat(f.calls).hasValue(1);
			assertThat(f.effects).hasValue(0);
		}
	}

	@Test void overshootRemainsMeasuredAndPreventsDependentModelCall() throws Exception {
		var f = new Fixture(1, 1);
		f.parentBudget = new RunBudget(Duration.ofSeconds(10), 6L, 10);
		f.script = prompt -> f.answer(10);
		var a = f.child("a", Set.of(), 4L);
		var b = f.child("b", Set.of("a"), 2L);
		try (var runtime = f.runtime()) {
			var result = f.execute(runtime, f.plan(a, b));
			assertThat(result.children().get(0).usage().knownTokens()).isEqualTo(10);
			assertThat(result.children().get(0).usage().overshootTokens()).isEqualTo(6);
			assertThat(result.children().get(1).failure().code()).isEqualTo(ChildFailure.Code.BUDGET_EXHAUSTED);
			assertThat(f.calls).hasValue(1);
			assertThat(result.usage().knownTokens()).isEqualTo(10);
		}
	}

	@Test void evenExplicitTransientModelFailureCannotRetryUnknownUsage() throws Exception {
		var f = new Fixture(1, 1);
		f.script = prompt -> { throw new DefaultDelegationRuntime.TransientFailure("pre-effect rejection"); };
		try (var runtime = f.runtime()) {
			var result = f.execute(runtime, f.plan(f.child("a", Set.of())));
			assertThat(result.children().getFirst().status()).isEqualTo(ChildStatus.FAILED);
			assertThat(result.usage().tokensUnknown()).isTrue();
			assertThat(f.calls).hasValue(1);
			assertThat(f.runs.list()).hasSize(1);
		}
	}

	@Test void transientBindingRetriesKeepOriginalReservationAndAttemptLimit() throws Exception {
		var f = new Fixture(1, 1);
		f.bindingFailures.set(1);
		try (var runtime = f.runtime()) {
			var spec = f.child("a", Set.of());
			var result = f.execute(runtime, f.plan(spec));
			assertThat(result.children().getFirst().status()).isEqualTo(ChildStatus.SUCCEEDED);
			assertThat(f.runs.list()).hasSize(1); // pre-binding refusal allocates no durable run
			assertThat(f.runs.read(result.children().getFirst().runId()).spec().budget()).isEqualTo(spec.budget());
			assertThat(f.calls).hasValue(1);
		}
		var exhausted = new Fixture(1, 1);
		exhausted.bindingFailures.set(3);
		try (var runtime = exhausted.runtime()) {
			var result = exhausted.execute(runtime, exhausted.plan(exhausted.child("a", Set.of())));
			assertThat(result.children().getFirst().failure().code()).isEqualTo(ChildFailure.Code.MODEL_UNAVAILABLE);
			assertThat(exhausted.bindingFailures).hasValue(1); // exactly maxAttempts=2, no third binding
			assertThat(exhausted.runs.list()).isEmpty();
			assertThat(exhausted.calls).hasValue(0);
			assertThat(result.usage()).isEqualTo(RunUsage.empty());
		}
	}

	@Test void concurrencyUsesEventBarriersAndQueuedChildrenRemainDiscoverable() throws Exception {
		var f = new Fixture(2, 1);
		var entered = new CountDownLatch(2);
		var release = new CountDownLatch(1);
		f.script = prompt -> {
			entered.countDown();
			await(release);
			return f.answer(2);
		};
		try (var runtime = f.runtime()) {
			var task = new FutureTask<>(() -> f.execute(runtime, f.plan(f.child("a", Set.of()),
					f.child("b", Set.of()), f.child("c", Set.of()))));
			Thread coordinator = new Thread(task, "test-delegation-coordinator");
			coordinator.start();
			try {
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				assertThat(runtime.executions()).filteredOn(e -> e.status() == ChildStatus.RUNNING).hasSize(2);
				assertThat(runtime.executions()).filteredOn(e -> e.status() == ChildStatus.QUEUED).hasSize(1);
				assertThat(runtime.executions()).filteredOn(e -> e.status() == ChildStatus.RUNNING)
					.allMatch(e -> e.attempts().size() == 1);
			}
			finally { release.countDown(); }
			assertThat(task.get(5, TimeUnit.SECONDS).children()).extracting(ChildResult::status)
				.containsOnly(ChildStatus.SUCCEEDED);
			coordinator.join(5000);
			assertThat(coordinator.isAlive()).isFalse();
			assertThat(runtime.executions()).isEmpty();
		}
	}

	@Test void cancellationTouchesOnlyOwnedActiveAndQueuedChildrenAndReleasesSession() throws Exception {
		var f = new Fixture(1, 1);
		RunId historical;
		try (var scope = f.factory.create(f.parent, f.child("history", Set.of()), f.parentScope)) {
			historical = scope.call().record().spec().runId();
		}
		var entered = new CountDownLatch(1);
		var interrupted = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		f.script = prompt -> {
			entered.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("No cancellation signal");
				return f.answer(2);
			}
			catch (InterruptedException ex) {
				interrupted.countDown();
				Thread.currentThread().interrupt();
				throw new Stop(Stop.Kind.CANCELLED);
			}
		};
		try (var runtime = f.runtime()) {
			var task = new FutureTask<>(() -> f.execute(runtime, f.plan(f.child("a", Set.of()), f.child("b", Set.of()))));
			Thread coordinator = new Thread(task, "test-cancel-coordinator");
			coordinator.start();
			try {
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				assertThat(runtime.cancel("foreign", "plan")).isFalse();
				assertThat(runtime.cancel(f.parent.sessionId(), "plan")).isTrue();
				assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
				var result = task.get(5, TimeUnit.SECONDS);
				assertThat(result.status()).isEqualTo(DelegationResult.Status.CANCELLED);
				assertThat(result.children()).extracting(ChildResult::status).containsOnly(ChildStatus.CANCELLED);
				assertThat(f.runs.read(result.children().getFirst().runId()).state()).isEqualTo(RunState.CANCELLED);
				assertThat(f.runs.read(historical).state()).isEqualTo(RunState.COMPLETED);
				assertThat(f.runs.list()).hasSize(2);
				assertThat(runtime.executions()).isEmpty();
				assertThat(runtime.cancel(f.parent.sessionId(), "plan")).isFalse();
				try (var lease = f.sessions.acquire(f.parent.sessionId(), RunId.random())) {
					assertThat(lease.conversationId()).isEqualTo(f.parent.sessionId());
				}
			}
			finally {
				release.countDown();
				coordinator.join(5000);
			}
			assertThat(coordinator.isAlive()).isFalse();
		}
	}

	@Test void abnormalWorkerFailureCancelsAndDrainsSiblingBeforeReturning() throws Exception {
		var f = new Fixture(2, 0);
		var entered = new CountDownLatch(2);
		var release = new CountDownLatch(1);
		var siblingStopped = new CountDownLatch(1);
		f.script = prompt -> {
			entered.countDown();
			if (prompt.getContents().contains("task a")) {
				await(entered);
				throw new AssertionError("abnormal provider failure");
			}
			try {
				if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Sibling was not cancelled");
				return f.answer(2);
			}
			catch (InterruptedException ex) {
				siblingStopped.countDown();
				Thread.currentThread().interrupt();
				throw new Stop(Stop.Kind.CANCELLED);
			}
		};
		try (var runtime = f.runtime()) {
			var task = new FutureTask<>(() -> f.execute(runtime, f.plan(f.child("a", Set.of()), f.child("b", Set.of()))));
			var coordinator = new Thread(task, "test-abnormal-coordinator");
			coordinator.start();
			try {
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				var result = task.get(5, TimeUnit.SECONDS);
				assertThat(siblingStopped.getCount()).isZero();
				assertThat(result.children().get(0).failure().code()).isEqualTo(ChildFailure.Code.UNKNOWN_EFFECT);
				assertThat(result.children().get(1).status()).isEqualTo(ChildStatus.CANCELLED);
				assertThat(runtime.executions()).isEmpty();
				try (var lease = f.sessions.acquire(f.parent.sessionId(), RunId.random())) {
					assertThat(lease.conversationId()).isEqualTo(f.parent.sessionId());
				}
			}
			finally {
				release.countDown();
				coordinator.join(5000);
			}
			assertThat(coordinator.isAlive()).isFalse();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Missing event signal");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new Stop(Stop.Kind.CANCELLED);
		}
	}

	private final class Fixture {
		final Path home = Files.createTempDirectory(directory, "home-").toRealPath();
		final Path workspace = Files.createTempDirectory(directory, "work-").toRealPath();
		final ModelRef ref = new ModelRef("fake", "captured"), other = new ModelRef("fake", "other");
		final AtomicInteger calls = new AtomicInteger(), effects = new AtomicInteger();
		final AtomicInteger bindingFailures = new AtomicInteger();
		final AtomicReference<RunUsage> parentUsage = new AtomicReference<>(RunUsage.empty());
		final FileRunStore runs = new FileRunStore(home);
		final SessionRunLock sessions = new SessionRunLock();
		final ToolScope childScope = new ToolScope(Set.of("read_file"), Set.of(workspace), Set.of(), Set.of(),
				false, false, false, false);
		final ToolScope parentScope = new ToolScope(Set.of("read_file"), Set.of(workspace), Set.of(), Set.of(),
				false, false, true, false);
		final DelegationProperties properties;
		RunBudget parentBudget = new RunBudget(Duration.ofSeconds(10), 100L, 10);
		volatile Function<Prompt, ChatResponse> script = prompt -> answer(2);
		final Agent agent;
		final TurnContext parent;
		final ChildScopeFactory factory;

		Fixture(int concurrency, int queue) throws Exception { this(concurrency, queue, false); }
		Fixture(int concurrency, int queue, boolean summary) throws Exception {
			Files.writeString(workspace.resolve("source.txt"), "original");
			properties = new DelegationProperties(concurrency, queue, 1, 2, Duration.ofSeconds(1), 100L, 10);
			ModelProvider provider = new ModelProvider() {
				@Override public String providerId() { return "fake"; }
				@Override public List<ModelEntry> models() { return List.of(new ModelEntry(ref, "fake", null),
						new ModelEntry(other, "fake", null)); }
				@Override public ProviderAuth auth() { return () -> true; }
				@Override public ChatModel create(ModelRef model, Map<String, String> options) {
					if (bindingFailures.get() > 0) {
						bindingFailures.decrementAndGet();
						throw new DefaultDelegationRuntime.TransientFailure("provider binding temporarily unavailable");
					}
					return new ChatModel() {
						@Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
							return ToolCallingChatOptions.builder().build();
						}
						@Override public ChatResponse call(Prompt prompt) {
							calls.incrementAndGet();
							return script.apply(prompt);
						}
					};
				}
			};
			ToolCallback read = new ToolCallback() {
				final ToolDefinition definition = ToolDefinition.builder().name("read_file").description("read bounded path")
						.inputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}").build();
				@Override public ToolDefinition getToolDefinition() { return definition; }
				@Override public String call(String input) { throw new AssertionError("Explicit context required"); }
				@Override public String call(String input, ToolContext context) { effects.incrementAndGet(); return "original"; }
			};
			var registry = new ModelRegistry(List.of(provider), model -> ChatClient.builder(model).build());
			var selections = new FileSessionModelStore(home);
			var session = new AgentSession();
			session.switchTo("parent");
			agent = new Agent(registry, selections, new ModelSelection(ref), session, workspace,
					"system", List.of(read), List.of(), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
			parent = new TurnContext("parent", UUID.randomUUID().toString(), workspace,
					registry.bind(new ModelSelection(ref)), UUID.randomUUID().toString(), UUID.randomUUID().toString());
			StagingToolInterceptor.Policy policy = () -> Map.of("read_file",
					new StagingToolInterceptor.Declaration(SideEffectClass.definitionDigest(read.getToolDefinition()),
						StagingToolInterceptor.Kind.READ, List.of()));
			var console = new AgentConsole(new BufferedReader(new StringReader("")),
					new PrintStream(OutputStream.nullOutputStream()), false);
			factory = new ChildScopeFactory(agent, home, runs, new RunRequestStore(home), selections,
					Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new ConsoleApprovalBroker(console),
					List.of(read), List.of(), List.of(new StagingToolInterceptor(() -> List.of(policy)),
						new RunBudgetInterceptor(), new ToolJournalInterceptor()), List.of(policy),
					List.of(() -> Map.of("read_file", SideEffectClass.definitionDigest(read.getToolDefinition()))),
					summary ? List.of((model, authority) -> List.of(new CallAdvisor() {
						@Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
							assertThat(authority.get().runId()).isEqualTo(RunContext.current().orElseThrow().spec().runId().value());
							model.call(new Prompt("summary"));
							return chain.nextCall(request);
						}
						@Override public int getOrder() { return org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 200; }
						@Override public String getName() { return "ScopedSummaryBoundaryTest"; }
					})) : List.of());
		}
		DefaultDelegationRuntime runtime() { return new DefaultDelegationRuntime(factory, properties, sessions); }
		RunSpec parentSpec() {
			var binding = parent.binding();
			return new RunSpec(1, new RunId(parent.runId()), parent.sessionId(), "outer", null, null,
					binding.ref(), binding.wireFamily(), workspace.toString(), parentBudget,
					binding.optionsFingerprint(), "0".repeat(64), Instant.EPOCH);
		}
		RunContext startParent(RunWriter writer) throws Exception {
			writer.append(new RunEvent.StateChanged(Instant.EPOCH, RunState.RUNNING, RunEvent.Reason.NONE));
			writer.append(new RunEvent.AttemptStarted(Instant.EPOCH, parent.attemptId(), parent.binding().bindingId()));
			return new RunContext(writer.record().spec(), parent.attemptId(), writer, parent,
					Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
		}
		ChildSpec child(String id, Set<String> dependencies) { return child(id, dependencies, 10L); }
		ChildSpec child(String id, Set<String> dependencies, long tokens) {
			return new ChildSpec(id, "task " + id, ChildRole.RESEARCH, null, dependencies, childScope,
					new RunBudget(Duration.ofMillis(100), tokens, 1), 2, true);
		}
		DelegationPlan plan(ChildSpec... children) { return new DelegationPlan("plan", List.of(children), 1); }
		DelegationResult execute(DefaultDelegationRuntime runtime, DelegationPlan plan) {
			return runtime.execute(parent, plan, parentScope, parentBudget, parentUsage::get, "revision", "config");
		}
		ChatResponse answer(int tokens) {
			return new ChatResponse(List.of(new Generation(new AssistantMessage("model narrative"))),
					ChatResponseMetadata.builder().usage(new DefaultUsage(tokens, 0)).build());
		}
	}
}
