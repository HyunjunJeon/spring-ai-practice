package com.example.miniagent.core.delegation.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.approval.ConsoleApprovalBroker;
import com.example.miniagent.core.change.ApplyEngine;
import com.example.miniagent.core.change.FileChangeStore;
import com.example.miniagent.core.change.PathPolicy;
import com.example.miniagent.core.change.command.ApplyCommand;
import com.example.miniagent.core.delegation.ChildResult;
import com.example.miniagent.core.delegation.ChildRole;
import com.example.miniagent.core.delegation.ChildSpec;
import com.example.miniagent.core.delegation.ChildStatus;
import com.example.miniagent.core.delegation.DelegationEvidenceCollector;
import com.example.miniagent.core.delegation.DelegationPlan;
import com.example.miniagent.core.delegation.DelegationProperties;
import com.example.miniagent.core.delegation.DelegationResult;
import com.example.miniagent.core.delegation.SessionRunLock;
import com.example.miniagent.core.delegation.ToolScope;
import com.example.miniagent.core.execution.RunBudgetInterceptor;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.execution.ToolJournalInterceptor;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.model.registry.ModelRegistry;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.*;

/** Operator control of an explicitly recovered child after restart; actual durable stores and the FIFO broker. */
class DelegationControllerRecoveryTest {
	private static final String ORIGINAL = "original";
	@TempDir Path directory;

	@Test void recoveredChildThatDefersAgainStaysPendingAndResumesOnlyFromAnExplicitResolution() throws Exception {
		var f = new Fixture();
		var lost = f.interruptedChild();
		try (var runtime = f.runtime(); var controller = f.controller(runtime)) {
			var deferred = controller.recover(lost.spec().runId(), "plan", f.child, ORIGINAL, f.authority,
				RunExecutor.ResumeOptions.ordinary());
			assertThat(deferred.record().state()).isEqualTo(RunState.WAITING_APPROVAL);
			var view = controller.show(ORIGINAL, "plan");
			assertThat(view.parent()).isNull();
			assertThat(view.busy()).isFalse();
			var pending = pending(view.outcome());
			assertThat(pending.children()).extracting(DefaultDelegationRuntime.PendingChild::childId).containsExactly("a");
			assertThat(pending.children().getFirst().record().spec()).isEqualTo(lost.spec());
			var request = pending.approvals().getFirst();
			assertThat(f.broker.pending()).containsExactly(request);

			assertThatThrownBy(() -> controller.resume(ORIGINAL, "plan", "other", RunExecutor.ResumeOptions.ordinary()))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Foreign child");
			// The parent refuses to reopen while its recovered child still waits, so no recovery callback runs.
			assertThatThrownBy(() -> controller.resume(ORIGINAL, "plan", "a", RunExecutor.ResumeOptions.ordinary()))
				.isInstanceOf(UncheckedIOException.class).cause().hasMessage("Parent stopped before child recovery");
			assertThat(f.runs.read(lost.spec().runId()).state()).isEqualTo(RunState.WAITING_APPROVAL);
			assertThat(pending(controller.show(ORIGINAL, "plan").outcome()).approvals()).containsExactly(request);

			assertThat(controller.resolve(ORIGINAL, "plan", request, ApprovalDecision.REJECTED).state())
				.isEqualTo(RunState.INTERRUPTED);
			var resolved = pending(controller.show(ORIGINAL, "plan").outcome());
			assertThat(resolved.children().getFirst().record().state()).isEqualTo(RunState.INTERRUPTED);
			assertThat(resolved.approvals()).isEmpty();
			assertThat(f.broker.pending()).isEmpty();

			f.script = prompt -> f.answer();
			var resumed = completed(controller.resume(ORIGINAL, "plan", "a", RunExecutor.ResumeOptions.ordinary()));
			assertThat(resumed.children()).extracting(ChildResult::status).containsExactly(ChildStatus.SUCCEEDED);
			var record = f.runs.read(lost.spec().runId());
			assertThat(record.state()).isEqualTo(RunState.COMPLETED);
			assertThat(record.spec()).isEqualTo(lost.spec());
			assertThat(record.attempts()).hasSize(lost.attempts().size() + 2);
			assertThat(controller.cancel(ORIGINAL, "plan")).isFalse();
			assertThatThrownBy(() -> controller.resolve(ORIGINAL, "plan", request, ApprovalDecision.APPROVED))
				.isInstanceOf(IllegalStateException.class).hasMessage("Recovered child is not pending");
			assertThat(f.events).extracting(DelegationController.Event::operation)
				.containsExactly("recover", "resolve", "recover");
		}
	}

	@Test void resolvingARecoveredApprovalAsCancelledCompletesThePlanCancelled() throws Exception {
		var f = new Fixture();
		var lost = f.interruptedChild();
		try (var runtime = f.runtime(); var controller = f.controller(runtime)) {
			controller.recover(lost.spec().runId(), "plan", f.child, ORIGINAL, f.authority, RunExecutor.ResumeOptions.ordinary());
			var request = pending(controller.show(ORIGINAL, "plan").outcome()).approvals().getFirst();
			assertThat(controller.resolve(ORIGINAL, "plan", request, ApprovalDecision.CANCELLED).state())
				.isEqualTo(RunState.CANCELLED);
			var result = completed(controller.show(ORIGINAL, "plan").outcome());
			assertThat(result.status()).isEqualTo(DelegationResult.Status.CANCELLED);
			assertThat(result.children()).extracting(ChildResult::status).containsExactly(ChildStatus.CANCELLED);
			assertThat(f.runs.read(lost.spec().runId()).reason()).isEqualTo(RunEvent.Reason.USER_CANCELLED);
			assertThat(f.broker.pending()).isEmpty();
			assertThat(controller.cancel(ORIGINAL, "plan")).isFalse();
		}
	}

	@Test void cancellingARecoveredPendingChildCollectsItCancelledAndRefusesLaterRecovery() throws Exception {
		var f = new Fixture();
		var lost = f.interruptedChild();
		try (var runtime = f.runtime(); var controller = f.controller(runtime)) {
			controller.recover(lost.spec().runId(), "plan", f.child, ORIGINAL, f.authority, RunExecutor.ResumeOptions.ordinary());
			assertThat(controller.cancel("foreign", "plan")).isFalse();
			assertThat(controller.cancel(ORIGINAL, "plan")).isTrue();
			var record = f.runs.read(lost.spec().runId());
			assertThat(record.state()).isEqualTo(RunState.CANCELLED);
			assertThat(record.reason()).isEqualTo(RunEvent.Reason.USER_CANCELLED);
			var result = completed(controller.show(ORIGINAL, "plan").outcome());
			assertThat(result.status()).isEqualTo(DelegationResult.Status.CANCELLED);
			assertThat(result.children()).extracting(ChildResult::status).containsExactly(ChildStatus.CANCELLED);
			assertThat(f.broker.pending()).isEmpty();
			int calls = f.calls.get();
			assertThatThrownBy(() -> controller.resume(ORIGINAL, "plan", "a", RunExecutor.ResumeOptions.ordinary()))
				.isInstanceOf(UncheckedIOException.class).cause().hasMessage("Only explicitly interrupted child can recover");
			assertThat(f.runs.read(lost.spec().runId())).isEqualTo(record);
			assertThat(f.calls).hasValue(calls);
		}
	}

	@Test void closingTheControllerAbandonsARetainedRecoveryAsProcessLossNotCancellation() throws Exception {
		var f = new Fixture();
		var lost = f.interruptedChild();
		try (var runtime = f.runtime()) {
			var controller = f.controller(runtime);
			controller.recover(lost.spec().runId(), "plan", f.child, ORIGINAL, f.authority, RunExecutor.ResumeOptions.ordinary());
			assertThat(f.broker.pending()).hasSize(1);
			controller.close();
			var record = f.runs.read(lost.spec().runId());
			assertThat(record.state()).isEqualTo(RunState.INTERRUPTED);
			assertThat(record.reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
			assertThat(FileRunStore.events(f.runs, lost.spec().runId())).noneMatch(event -> event instanceof RunEvent.StateChanged change
				&& change.reason() == RunEvent.Reason.USER_CANCELLED);
			assertThat(f.broker.pending()).isEmpty();
		}
	}

	@Test void busyRecoveryRefusesResolutionButAcceptsCancellationOfTheRunningChild() throws Exception {
		cancelBusyRecovery(false);
	}

	@Test void busyRecoveryCancellationSurvivesAProviderThatRestoresTheInterruptFlag() throws Exception {
		cancelBusyRecovery(true);
	}

	private void cancelBusyRecovery(boolean providerRestoresInterrupt) throws Exception {
		var f = new Fixture();
		var lost = f.interruptedChild();
		var original = f.firstRequest;
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		f.script = prompt -> {
			entered.countDown();
			try {
				if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Recovered child was not cancelled");
				return f.answer();
			}
			catch (InterruptedException ex) {
				if (providerRestoresInterrupt) Thread.currentThread().interrupt();
				throw new Stop(Stop.Kind.CANCELLED);
			}
		};
		try (var runtime = f.runtime(); var controller = f.controller(runtime)) {
			var task = new FutureTask<>(() -> controller.recover(lost.spec().runId(), "plan", f.child, ORIGINAL,
				f.authority, RunExecutor.ResumeOptions.ordinary()));
			var worker = new Thread(task, "test-recovery-worker");
			worker.start();
			try {
				await(entered);
				assertThat(controller.show(ORIGINAL, "plan").busy()).isTrue();
				assertThatThrownBy(() -> controller.resolve(ORIGINAL, "plan", original, ApprovalDecision.APPROVED))
					.isInstanceOf(IllegalStateException.class).hasMessage("Current plan busy");
				assertThat(controller.cancel(ORIGINAL, "plan")).isTrue();
				assertThat(task.get(10, TimeUnit.SECONDS).record().state()).isEqualTo(RunState.CANCELLED);
			}
			finally {
				release.countDown();
				worker.join(10_000);
			}
			assertThat(worker.isAlive()).isFalse();
			var view = controller.show(ORIGINAL, "plan");
			assertThat(view.busy()).isFalse();
			assertThat(completed(view.outcome()).children()).extracting(ChildResult::status)
				.containsExactly(ChildStatus.CANCELLED);
			assertThat(f.runs.read(lost.spec().runId()).state()).isEqualTo(RunState.CANCELLED);
		}
	}

	@Test void executeRequiresTheActiveCapturedParentAndAWiredCollector() throws Exception {
		var f = new Fixture();
		var loose = new TurnContext(ORIGINAL, UUID.randomUUID().toString(), f.workspace,
			f.registry.bind(new ModelSelection(f.ref)), UUID.randomUUID().toString(), UUID.randomUUID().toString());
		try (var runtime = f.runtime(); var controller = f.controller(runtime);
				var unwired = new DefaultDelegationRuntime(f.factory, f.properties, new SessionRunLock());
				var bare = f.controller(unwired)) {
			assertThatThrownBy(() -> controller.execute(loose, f.plan(), f.authority, f.budget, RunUsage::empty))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Bound parent RunContext required");
			var operation = f.executor.startOperation("guarded delegate", ORIGINAL, new ModelSelection(f.ref),
				f.workspace, f.budget, context -> {
					var other = new RunBudget(Duration.ofMinutes(1), null, 30);
					assertThatThrownBy(() -> controller.execute(context.turnContext(), f.plan(), f.authority, other,
						context::usage)).isInstanceOf(IllegalArgumentException.class).hasMessage("Actual active parent required");
					assertThatThrownBy(() -> bare.execute(context.turnContext(), f.plan(), f.authority, f.budget,
						context::usage)).isInstanceOf(IllegalStateException.class).hasMessage("Evidence collector not wired");
					return controller.list(ORIGINAL);
				});
			assertThat(operation.value()).isEmpty();
			assertThat(operation.record().state()).isEqualTo(RunState.COMPLETED);
			assertThat(f.calls).hasValue(0);
		}
	}

	@Test void pendingCurrentPlanRefusesReexecutionAndStaysPending() throws Exception {
		var f = new Fixture();
		f.script = prompt -> f.ask();
		try (var runtime = f.runtime(); var controller = f.controller(runtime)) {
			var first = pending(f.executeOperator(controller).value());
			var child = first.children().getFirst().record();
			int runs = f.runs.list().size();
			assertThatThrownBy(() -> f.executeOperator(controller))
				.isInstanceOf(IllegalStateException.class).hasMessage("Current plan busy or pending");
			assertThat(f.runs.list()).hasSize(runs + 1); // only the refused operator parent run
			assertThat(f.runs.read(child.spec().runId()).state()).isEqualTo(RunState.WAITING_APPROVAL);
			assertThat(controller.show(ORIGINAL, "plan").outcome()).isEqualTo(first);
			assertThat(f.broker.pending()).containsExactlyElementsOf(first.approvals());
			assertThat(controller.cancel(ORIGINAL, "plan")).isTrue();
		}
	}

	private static DefaultDelegationRuntime.Pending pending(DefaultDelegationRuntime.Outcome outcome) {
		if (!(outcome instanceof DefaultDelegationRuntime.Pending pending))
			throw new AssertionError("Expected a pending plan: " + outcome);
		return pending;
	}
	private static DelegationResult completed(DefaultDelegationRuntime.Outcome outcome) {
		if (!(outcome instanceof DefaultDelegationRuntime.Completed completed))
			throw new AssertionError("Expected a completed plan: " + outcome);
		return completed.result();
	}
	private static void await(CountDownLatch latch) throws InterruptedException {
		if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Expected lifecycle event was not delivered");
	}

	private final class Fixture {
		final Path workspace, home;
		final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
		final ModelRef ref = new ModelRef("fake", "model");
		final AtomicInteger calls = new AtomicInteger();
		final FileRunStore runs;
		final RunRequestStore requests;
		final FileChangeStore changes;
		final ModelRegistry registry;
		final ConsoleApprovalBroker broker;
		final ChildScopeFactory factory;
		final StagedApplyService service;
		// RunExecutor.forAgent keeps only a weak reference; recovery needs this parent assembly alive.
		final RunExecutor executor;
		final ToolScope authority;
		final ChildSpec child;
		final DelegationProperties properties = new DelegationProperties(1, 4, 1, 1, Duration.ofMinutes(3), null, 30);
		final RunBudget budget = new RunBudget(Duration.ofMinutes(3), null, 30);
		final List<DelegationController.Event> events = new CopyOnWriteArrayList<>();
		volatile Function<Prompt, ChatResponse> script = prompt -> answer();
		volatile ApprovalRequest firstRequest;

		Fixture() throws Exception {
			Path base = Files.createTempDirectory(directory, "fixture-");
			workspace = Files.createDirectory(base.resolve("workspace")).toRealPath();
			home = Files.createDirectory(base.resolve("state")).toRealPath();
			Files.writeString(workspace.resolve("source.txt"), "original");
			runs = new FileRunStore(home);
			requests = new RunRequestStore(home);
			changes = new FileChangeStore(home, workspace);
			var selections = new FileSessionModelStore(home);
			broker = new ConsoleApprovalBroker(new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(OutputStream.nullOutputStream()), false));
			ModelProvider provider = new ModelProvider() {
				@Override public String providerId() { return "fake"; }
				@Override public List<ModelEntry> models() { return List.of(new ModelEntry(ref, "fake-wire", null)); }
				@Override public ProviderAuth auth() { return () -> true; }
				@Override public ChatModel create(ModelRef model, Map<String, String> options) {
					return new ChatModel() {
						@Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
						@Override public ChatResponse call(Prompt prompt) { calls.incrementAndGet(); return script.apply(prompt); }
					};
				}
			};
			registry = new ModelRegistry(List.of(provider), model -> ChatClient.builder(model).build());
			var callbacks = List.of(callback("one"), callback("two"));
			var session = new AgentSession();
			session.switchTo("parent");
			var agent = new Agent(registry, selections, new ModelSelection(ref), session, workspace, "system",
				callbacks, List.of(), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
			factory = new ChildScopeFactory(agent, home, runs, requests, selections, clock, broker,
				callbacks, List.of(), List.of(new RunBudgetInterceptor(), new ToolJournalInterceptor()),
				List.of(), List.of(), List.of());
			var livePolicy = new PathPolicy(workspace, home, List.of());
			service = new StagedApplyService(runs, changes, factory, new ApplyCommand(() -> changes,
				() -> new ApplyEngine(changes, livePolicy, clock), broker, clock, event -> {}));
			executor = new RunExecutor(agent, registry, selections, runs, requests, clock, List.of(), broker);
			authority = new ToolScope(Set.of("one", "two"), Set.of(workspace), Set.of(workspace), Set.of(),
				true, false, true, false);
			child = new ChildSpec("a", "goal a", ChildRole.RESEARCH, ref, Set.of(),
				new ToolScope(new LinkedHashSet<>(List.of("one", "two")), Set.of(workspace), Set.of(), Set.of(),
					false, false, false, false),
				new RunBudget(Duration.ofSeconds(30), null, 5), 1, true);
		}
		ToolCallback callback(String name) {
			return new ToolCallback() {
				@Override public ToolDefinition getToolDefinition() {
					return ToolDefinition.builder().name(name).description("local test callback").inputSchema("{\"type\":\"object\"}").build();
				}
				@Override public String call(String input) { throw new AssertionError("No callback expected"); }
			};
		}
		DelegationPlan plan() { return new DelegationPlan("plan", List.of(this.child), 1); }
		DefaultDelegationRuntime runtime() {
			return new DefaultDelegationRuntime(factory, properties, new SessionRunLock())
				.evidence(new DelegationEvidenceCollector(runs, requests.evidence(), changes));
		}
		DelegationController controller(DefaultDelegationRuntime runtime) {
			return new DelegationController(runtime, runs, service, events::add);
		}
		RunExecutor.OperationResult<DefaultDelegationRuntime.Outcome> executeOperator(DelegationController controller)
				throws IOException {
			return controller.executeOperator(executor, ORIGINAL, new ModelSelection(ref), workspace, plan(), authority, budget);
		}
		/** A deferred child whose runtime then shut down: the explicit restart-recovery input. */
		RunRecord interruptedChild() throws Exception {
			script = prompt -> ask();
			RunRecord child;
			try (var runtime = runtime(); var controller = controller(runtime)) {
				var pending = pending(executeOperator(controller).value());
				child = pending.children().getFirst().record();
				firstRequest = pending.approvals().getFirst();
			}
			var lost = runs.read(child.spec().runId());
			assertThat(lost.state()).isEqualTo(RunState.INTERRUPTED);
			assertThat(lost.reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
			events.clear();
			return lost;
		}
		ChatResponse answer() {
			return new ChatResponse(List.of(new Generation(new AssistantMessage("narrative"))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(1, 0)).build());
		}
		/** Non-interactive console: the broker answers DEFERRED and keeps the exact request pending. */
		ChatResponse ask() {
			var context = RunContext.current().orElseThrow();
			var request = ApprovalRequest.tool(context, "test-approval", "{}");
			try {
				var decision = broker.request(request, context).get(10, TimeUnit.SECONDS);
				if (decision == ApprovalDecision.DEFERRED) throw new Stop(Stop.Kind.DEFERRED);
				if (decision == ApprovalDecision.CANCELLED) throw new Stop(Stop.Kind.CANCELLED);
				return answer();
			}
			catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
			catch (ExecutionException | TimeoutException ex) { throw new IllegalStateException(ex); }
		}
	}
}
