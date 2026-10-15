package com.example.miniagent.core.delegation;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.*;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.*;

/** Actual durable stores, scope/executor and the existing FIFO broker; no paid calls. */
class DelegationCancelTest {
	@TempDir Path directory;

	@Test void freshUnboundScopeCanInspectCompletedChildWithoutChangingRunOrGrantingResume() throws Exception {
		var f = new Fixture(false);
		var initial = f.child("a", Set.of());
		var child = new ChildSpec(initial.childId(), initial.task(), ChildRole.IMPLEMENT, initial.model(),
			initial.dependencies(), initial.scope(), initial.budget(), initial.maxAttempts(), initial.required());
		RunRecord completed;
		try (var scope = f.factory.create(f.parent, child, f.authority)) { completed = scope.call().record(); }
		try (var scope = f.factory.create(f.parent, child, f.authority)) {
			assertThat(scope.bound()).isEmpty();
			assertThat(scope.executor().inspectDrift(completed.spec().runId()).changed()).isFalse();
			assertThat(scope.bound()).isEmpty();
			assertThat(f.runs.read(completed.spec().runId())).isEqualTo(completed);
			assertThatThrownBy(() -> scope.resume(completed.spec().runId(), RunExecutor.ResumeOptions.ordinary()))
				.isInstanceOf(RunExecutor.Failure.class).extracting("code").isEqualTo(RunExecutor.Code.TERMINAL);
			assertThat(f.runs.read(completed.spec().runId())).isEqualTo(completed);
		}
		assertThat(f.calls).hasValue(1);
	}

	@Test void createdThenBoundPublishExactOwnerBeforeFirstModel() throws Exception {
		var f = new Fixture(false);
		var order = new ArrayList<String>();
		var created = new AtomicReference<RunRecord>();
		var bound = new AtomicReference<RunContext>();
		f.script = prompt -> {
			assertThat(order).containsExactly("created", "bound");
			assertThat(RunContext.current()).containsSame(bound.get());
			assertThat(bound.get().spec()).isEqualTo(created.get().spec());
			return f.answer();
		};
		try (var scope = f.factory.create(f.parent, f.child("a", Set.of()), f.authority,
				new ChildScopeFactory.Boundaries() {
					@Override public void created(ChildScopeFactory.Metadata metadata, RunRecord record) {
						order.add("created"); created.set(record);
						assertThat(record.spec().childId()).isEqualTo(metadata.childId());
						assertThat(record.spec().parentRunId().value()).isEqualTo(metadata.parentRunId());
					}
					@Override public void bound(ChildScopeFactory.Metadata metadata, RunContext context) {
						order.add("bound"); bound.set(context);
					}
				})) {
			assertThat(scope.call().record().state()).isEqualTo(RunState.COMPLETED);
			assertThat(scope.runContext()).isSameAs(bound.get());
		}
	}

	@Test void acknowledgedCreationCanCancelBeforeBootstrapOrFirstCapture() throws Exception {
		var f = new Fixture(false);
		var holder = new AtomicReference<ChildScopeFactory.Scope>();
		var created = new AtomicReference<RunRecord>();
		var bound = new AtomicInteger();
		try (var scope = f.factory.create(f.parent, f.child("a", Set.of()), f.authority,
				new ChildScopeFactory.Boundaries() {
					@Override public void created(ChildScopeFactory.Metadata metadata, RunRecord record) {
						created.set(record);
						assertThat(record.state()).isEqualTo(RunState.CREATED);
						assertThat(holder.get().record()).contains(record);
						try { holder.get().cancel(); }
						catch (IOException ex) { throw new UncheckedIOException(ex); }
					}
					@Override public void bound(ChildScopeFactory.Metadata metadata, RunContext context) {
						bound.incrementAndGet();
					}
				})) {
			holder.set(scope);
			RunExecutor.Result result;
			try { result = scope.call(); }
			finally { Thread.interrupted(); }
			assertThat(result.record().spec().runId()).isEqualTo(created.get().spec().runId());
			assertThat(result.record().state()).isEqualTo(RunState.CANCELLED);
			assertThat(result.record().attempts()).isEmpty();
			assertThat(f.runs.read(result.record().spec().runId()).reason()).isEqualTo(RunEvent.Reason.USER_CANCELLED);
			assertThat(f.calls).hasValue(0);
			assertThat(bound).hasValue(0);
		}
	}

	@Test void cancellationPreventsOrWakesExactStagingLeaseWaitWithoutWriterLeak() throws Exception {
		var f = new Fixture(false);
		var started = new CountDownLatch(1); // Subscribe before dispatch and before holding the lease.
		f.events = event -> { if (event instanceof RunEvent.AttemptStarted) started.countDown(); };
		var changes = new FileChangeStore(f.home, f.workspace);
		try (var lease = changes.acquire(); var scope = f.factory.create(f.parent, f.child("a", Set.of()), f.authority)) {
			var task = launch(scope::call);
			await(started);
			RunId id = scope.record().orElseThrow().spec().runId();
			scope.cancel();
			assertThat(task.get(10, TimeUnit.SECONDS).record().state()).isEqualTo(RunState.CANCELLED);
			assertThat(f.runs.read(id).state()).isEqualTo(RunState.CANCELLED);
			assertThat(f.calls).hasValue(0);
			assertThat(f.runs.read(id).attempts().getLast().endedAt()).isNotNull();
		}
		try (var lease = changes.acquireStaging()) { assertThat(lease.read().changes()).isEmpty(); }
	}

	@Test void twoApprovalsUseExistingFifoThenRemainDurablyPendingUntilPlanCancel() throws Exception {
		var f = new Fixture(true);
		var firstDisplayed = new CountDownLatch(1);
		var secondRegistered = new CountDownLatch(1);
		f.console.onDisplay = request -> firstDisplayed.countDown();
		f.afterRequest = request -> {
			if (request.childId().equals("b")) secondRegistered.countDown();
		};
		f.script = prompt -> {
			if (RunContext.current().orElseThrow().spec().childId().equals("b")) await(firstDisplayed);
			return f.ask();
		};
		try (var runtime = f.runtime()) {
			var task = launch(() -> f.execute(runtime, f.plan(f.child("a", Set.of()), f.child("b", Set.of()))));
			await(secondRegistered);
			var first = f.console.take();
			var pending = f.broker.pending();
			assertThat(pending).hasSize(2);
			var second = pending.stream().filter(request -> !request.equals(first)).findFirst().orElseThrow();
			assertThat(f.broker.respond(second, ApprovalDecision.APPROVED)).isFalse();
			assertThat(f.broker.respond(first, ApprovalDecision.DEFERRED)).isTrue();
			assertThat(f.console.take()).isEqualTo(second);
			assertThat(f.broker.respond(second, ApprovalDecision.DEFERRED)).isTrue();
			var outcome = (DefaultDelegationRuntime.Pending) task.get(10, TimeUnit.SECONDS);
			assertThat(outcome.approvals()).containsExactly(first, second);
			assertThat(runtime.pending("parent", "plan")).contains(outcome);
			for (var child : outcome.children()) {
				assertThat(child.record().state()).isEqualTo(RunState.WAITING_APPROVAL);
				assertThat(f.runs.read(child.record().spec().runId()).state()).isEqualTo(RunState.WAITING_APPROVAL);
			}
			assertThat(runtime.cancel("parent", "plan")).isTrue();
			assertThat(f.broker.pending()).isEmpty();
			for (var child : outcome.children()) {
				var record = f.runs.read(child.record().spec().runId());
				assertThat(record.state()).isEqualTo(RunState.CANCELLED);
				assertThat(record.reason()).isEqualTo(RunEvent.Reason.USER_CANCELLED);
				assertThat(record.attempts().getLast().endedAt()).isNotNull();
				assertThat(f.runs.export(record.spec().runId()).eventsLog()).contains("CANCELLED");
			}
		}
	}

	@Test void noninteractivePendingRetainsQuestionAndDoesNotTouchHistoricalOrForeignOwner() throws Exception {
		var f = new Fixture(false);
		RunRecord historical;
		try (var scope = f.factory.create(f.parent, f.child("old", Set.of()), f.authority)) {
			historical = scope.call().record();
		}
		f.script = prompt -> f.ask();
		var foreignParent = new TurnContext("foreign", UUID.randomUUID().toString(), f.workspace,
				f.parent.binding(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
		try (var foreign = f.factory.create(foreignParent, f.child("foreign-child", Set.of()), f.authority);
				var runtime = f.runtime()) {
			RunRecord foreignRecord = foreign.call().record();
			try {
				var outcome = (DefaultDelegationRuntime.Pending) f.execute(runtime,
					f.plan(f.child("a", Set.of()), f.child("dependent", Set.of("a"))));
				assertThat(outcome.queued()).containsExactly("dependent");
				assertThat(outcome.completed()).isEmpty();
				assertThat(outcome.approvals()).hasSize(1);
				var request = outcome.approvals().getFirst();
				var owner = outcome.children().getFirst().record();
				assertThat(request.runId()).isEqualTo(owner.spec().runId());
				assertThat(request.attemptId()).isEqualTo(owner.attempts().getLast().attemptId());
				assertThat(f.runs.export(request.runId()).eventsLog())
					.contains(request.requestId().toString(), request.toolCallKey(), request.argsDigest(), "DEFERRED");
				assertThat(runtime.executions()).hasSize(2);
				assertThat(runtime.cancel("foreign", "plan")).isFalse();
				assertThat(runtime.cancel("parent", "plan")).isTrue();
				assertThat(f.runs.read(owner.spec().runId()).state()).isEqualTo(RunState.CANCELLED);
				assertThat(f.runs.read(historical.spec().runId())).isEqualTo(historical);
				assertThat(f.runs.read(foreignRecord.spec().runId()).state()).isEqualTo(RunState.WAITING_APPROVAL);
				assertThat(f.broker.pending()).extracting(ApprovalRequest::runId).containsExactly(foreignRecord.spec().runId());
			}
			finally { foreign.cancel(); }
		}
	}

	@Test void parentCancelDeniesPendingApprovalAndCancelsChildren() throws Exception {
		var f = new Fixture(false);
		var waiting = new CountDownLatch(1);
		var running = new CountDownLatch(1);
		var released = new CountDownLatch(1);
		f.events = event -> {
			if (event instanceof RunEvent.StateChanged change && change.state() == RunState.WAITING_APPROVAL)
				waiting.countDown();
		};
		f.script = prompt -> {
			if (RunContext.current().orElseThrow().spec().childId().equals("approval")) return f.ask();
			running.countDown();
			await(released); // Cancellation interrupts this exact subscribed blocker.
			return f.answer();
		};
		try (var runtime = f.runtime()) {
			var task = launch(() -> f.execute(runtime, f.plan(f.child("approval", Set.of()),
				f.child("worker", Set.of()), f.child("queued", Set.of("approval")))));
			try {
				await(waiting);
				await(running);
				assertThat(runtime.executions()).extracting(DefaultDelegationRuntime.Execution::childId)
					.containsExactlyInAnyOrder("approval", "worker", "queued");
				assertThat(runtime.cancel("parent", "plan")).isTrue();
				var result = ((DefaultDelegationRuntime.Completed) task.get(10, TimeUnit.SECONDS)).result();
				assertThat(result.status()).isEqualTo(DelegationResult.Status.CANCELLED);
				assertThat(result.children()).extracting(ChildResult::status).containsOnly(ChildStatus.CANCELLED);
				assertThat(f.broker.pending()).isEmpty();
				for (var child : result.children().stream().filter(child -> !child.childId().equals("queued")).toList())
					assertThat(f.runs.read(child.runId()).state()).isEqualTo(RunState.CANCELLED);
				assertThat(f.runs.list()).hasSize(2);
			}
			finally { released.countDown(); }
		}
	}

	@Test void explicitDeferredResolutionAndResumeReuseRunConversationWithFreshBinding() throws Exception {
		var f = new Fixture(false);
		f.script = prompt -> f.ask();
		try (var runtime = f.runtime()) {
			var outcome = (DefaultDelegationRuntime.Pending) f.execute(runtime, f.plan(f.child("a", Set.of())));
			var old = outcome.children().getFirst().record();
			var request = outcome.approvals().getFirst();
			assertThat(runtime.resolveApproval("parent", "plan", request, ApprovalDecision.REJECTED).state())
				.isEqualTo(RunState.INTERRUPTED);
			assertThat(f.calls).hasValue(1);
			f.script = prompt -> f.answer();
			var result = ((DefaultDelegationRuntime.Completed) runtime.resume("parent", "plan", "a",
				RunExecutor.ResumeOptions.ordinary())).result();
			var record = f.runs.read(result.children().getFirst().runId());
			assertThat(record.spec()).isEqualTo(old.spec());
			assertThat(record.attempts()).hasSize(2);
			assertThat(record.attempts().getLast().attemptId()).isNotEqualTo(old.attempts().getFirst().attemptId());
			assertThat(record.attempts().getLast().bindingId()).isNotEqualTo(old.attempts().getFirst().bindingId());
			assertThat(record.state()).isEqualTo(RunState.COMPLETED);
			assertThat(runtime.executions()).isEmpty();
		}
	}

	@Test void abandonedChildReconstructsExactScopeAndRefusesGenericOrWidenedResumeEvenAcceptDrift() throws Exception {
		var f = new Fixture(false);
		var initial = f.child("a", Set.of());
		Path left = Files.createDirectory(f.workspace.resolve("left"));
		Path right = Files.createDirectory(f.workspace.resolve("right"));
		var child = new ChildSpec(initial.childId(), initial.task(), initial.role(), initial.model(),
			initial.dependencies(), new ToolScope(new LinkedHashSet<>(List.of("one", "two")),
				new LinkedHashSet<>(List.of(f.workspace, left, right)), Set.of(), Set.of(), false, false, false, false),
			initial.budget(), initial.maxAttempts(), initial.required());
		f.script = prompt -> { throw new AssertionError("Simulated abandoned provider"); };
		RunId id;
		try (var scope = f.factory.create(f.parent, child, f.authority)) {
			assertThatThrownBy(scope::call).isInstanceOf(AssertionError.class);
			id = scope.record().orElseThrow().spec().runId();
		}
		var lost = f.runs.read(id);
		assertThat(lost.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(lost.reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
		var generic = new RunExecutor(f.agent, f.registry, f.selections, f.runs, f.requests, f.clock, List.of());
		var genericDifference = generic.inspectDrift(id);
		var genericOptions = new RunExecutor.ResumeOptions(List.of(), new DriftGate.Acknowledgement(
			genericDifference.expectedDigest(), genericDifference.actualDigest(), "operator", "evidence"));
		assertThatThrownBy(() -> generic.resume(id, new ModelSelection(f.ref), f.workspace, genericOptions))
			.isInstanceOf(RunExecutor.Failure.class).extracting("code").isEqualTo(RunExecutor.Code.IDENTITY_DRIFT);
		try (var sibling = f.factory.create(f.parent, f.child("sibling", Set.of()), f.authority)) {
			assertThatThrownBy(() -> sibling.executor().resume(id, new ModelSelection(f.ref), f.workspace))
				.isInstanceOf(RunExecutor.Failure.class).extracting("code").isEqualTo(RunExecutor.Code.IDENTITY_DRIFT);
		}
		var widened = new ChildSpec(child.childId(), child.task(), ChildRole.IMPLEMENT, child.model(),
			child.dependencies(), child.scope(), child.budget(), child.maxAttempts(), child.required());
		try (var scope = f.factory.create(f.parent, widened, f.authority)) {
			var difference = scope.executor().inspectDrift(id);
			var acknowledgement = new DriftGate.Acknowledgement(difference.expectedDigest(), difference.actualDigest(),
				"operator", "evidence");
			assertThatThrownBy(() -> scope.resume(id, new RunExecutor.ResumeOptions(List.of(), acknowledgement)))
				.isInstanceOf(RunExecutor.Failure.class).extracting("code").isEqualTo(RunExecutor.Code.IDENTITY_DRIFT);
		}
		f.script = prompt -> f.answer();
		var reordered = new ToolScope(new LinkedHashSet<>(List.of("two", "one")),
			new LinkedHashSet<>(List.of(right, left, f.workspace)), Set.of(), Set.of(), false, false, false, false);
		var reconstructed = new ChildSpec(child.childId(), child.task(), child.role(), child.model(), child.dependencies(),
			reordered, child.budget(), child.maxAttempts(), child.required());
		try (var scope = f.factory.create(f.parent, reconstructed, f.authority)) {
			var result = scope.resume(id, RunExecutor.ResumeOptions.ordinary());
			assertThat(result.record().spec()).isEqualTo(lost.spec());
			assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
			assertThat(result.record().attempts()).hasSize(2);
			assertThat(scope.context().sessionId()).isEqualTo(lost.spec().conversationId());
		}
	}

	@Test void unresolvedMutationRefusesExplicitResumeWithoutFakeToolCompletion() throws Exception {
		var f = new Fixture(false);
		f.script = prompt -> {
			RunContext context = RunContext.current().orElseThrow();
			try {
				context.writer().append(new RunEvent.ToolStarted(context.now(), context.attemptId(), "unknown",
					"one", "0".repeat(64), RunEvent.Effect.MUTATING));
			}
			catch (IOException ex) { throw new UncheckedIOException(ex); }
			throw new Stop(Stop.Kind.INTERRUPTED);
		};
		RunId id;
		try (var scope = f.factory.create(f.parent, f.child("a", Set.of()), f.authority)) {
			var result = scope.call();
			id = result.record().spec().runId();
			assertThat(result.record().state()).isEqualTo(RunState.INTERRUPTED);
			assertThat(result.record().uncertainTools()).hasSize(1);
		}
		int calls = f.calls.get();
		try (var scope = f.factory.create(f.parent, f.child("a", Set.of()), f.authority)) {
			assertThatThrownBy(() -> scope.resume(id, RunExecutor.ResumeOptions.ordinary()))
				.isInstanceOf(RunExecutor.Failure.class).extracting("code").isEqualTo(RunExecutor.Code.UNKNOWN_EFFECTS);
		}
		assertThat(f.calls).hasValue(calls);
		assertThat(f.runs.read(id).attempts()).hasSize(1);
		assertThat(f.runs.export(id).eventsLog()).doesNotContain("ToolFinished", "ToolResolution");
	}

	@Test void cancelledStageKeepsOriginalProposalWithoutApplyingOrLeakingTempAuthority() throws Exception {
		var f = new Fixture(false);
		var proposed = new CountDownLatch(1);
		var block = new CountDownLatch(1);
		var retained = new AtomicReference<Path>();
		f.script = prompt -> {
			var stage = StagedWorkspace.current().orElseThrow();
			retained.set(stage.root());
			try { Files.writeString(stage.root().resolve("source.txt"), "proposal"); stage.record(); }
			catch (IOException ex) { throw new UncheckedIOException(ex); }
			proposed.countDown();
			await(block);
			return f.answer();
		};
		try (var scope = f.factory.create(f.parent, f.child("a", Set.of()), f.authority)) {
			var task = launch(scope::call);
			try {
				await(proposed);
				scope.cancel();
				assertThat(task.get(10, TimeUnit.SECONDS).record().state()).isEqualTo(RunState.CANCELLED);
				assertThat(Files.readString(f.workspace.resolve("source.txt"))).isEqualTo("original");
				assertThat(Files.readString(retained.get().resolve("source.txt"))).isEqualTo("proposal");
				var changes = new FileChangeStore(f.home, f.workspace).read().changes();
				assertThat(changes).hasSize(1);
				assertThat(changes.getFirst().changeSet().status()).isEqualTo(ChangeStatus.PENDING_APPROVAL);
				try (var entries = Files.list(retained.get().getParent())) {
					assertThat(entries.map(path -> path.getFileName().toString()).toList())
						.noneMatch(name -> name.startsWith("temp-"));
				}
			}
			finally { block.countDown(); }
		}
	}

	private static void await(CountDownLatch latch) {
		try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Expected lifecycle event was not delivered"); }
		catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
	}
	private static <T> FutureTask<T> launch(Callable<T> work) {
		var task = new FutureTask<>(work);
		Thread.startVirtualThread(task);
		return task;
	}

	private static final class EventConsole extends AgentConsole {
		final BlockingQueue<ApprovalRequest> displayed = new LinkedBlockingQueue<>();
		volatile Consumer<ApprovalRequest> onDisplay = request -> {};
		EventConsole(boolean interactive) {
			super(new BufferedReader(new StringReader("")), new PrintStream(OutputStream.nullOutputStream()), interactive);
		}
		@Override public void requestApproval(ApprovalRequest request, Consumer<ApprovalAnswer> response) {
			displayed.add(request);
			onDisplay.accept(request);
		}
		ApprovalRequest take() throws InterruptedException {
			var request = displayed.poll(10, TimeUnit.SECONDS);
			assertThat(request).isNotNull();
			return request;
		}
	}

	private final class Fixture {
		final Path workspace, home;
		final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
		final ModelRef ref = new ModelRef("fake", "model");
		final AtomicInteger calls = new AtomicInteger();
		final FileRunStore runs;
		final RunRequestStore requests;
		final FileSessionModelStore selections;
		final ModelRegistry registry;
		final Agent agent;
		final TurnContext parent;
		final ToolScope authority;
		final ChildScopeFactory factory;
		final EventConsole console;
		final ConsoleApprovalBroker broker;
		volatile Consumer<RunEvent> events = event -> {};
		volatile Consumer<ApprovalRequest> afterRequest = request -> {};
		volatile Function<Prompt, ChatResponse> script = prompt -> answer();
		Fixture(boolean interactive) throws Exception {
			Path base = Files.createTempDirectory(directory, "fixture-");
			workspace = Files.createDirectory(base.resolve("workspace")).toRealPath();
			home = Files.createDirectory(base.resolve("state")).toRealPath();
			Files.writeString(workspace.resolve("source.txt"), "original");
			runs = new FileRunStore(home);
			requests = new RunRequestStore(home);
			selections = new FileSessionModelStore(home);
			console = new EventConsole(interactive);
			broker = new ConsoleApprovalBroker(console);
			ModelProvider provider = new ModelProvider() {
				@Override public String providerId() { return "fake"; }
				@Override public List<ModelEntry> models() { return List.of(new ModelEntry(ref, "fake-wire", null)); }
				@Override public ProviderAuth auth() { return () -> true; }
				@Override public ChatModel create(ModelRef ref, Map<String, String> options) {
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
			agent = new Agent(registry, selections, new ModelSelection(ref), session, workspace, "system",
				callbacks, List.of(), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
			parent = new TurnContext("parent", UUID.randomUUID().toString(), workspace,
				registry.bind(new ModelSelection(ref)), UUID.randomUUID().toString(), UUID.randomUUID().toString());
			authority = new ToolScope(Set.of("one", "two"), Set.of(workspace), Set.of(workspace), Set.of(),
				true, false, true, false);
			RunStore observed = new RunStore() {
				RunWriter wrap(RunWriter writer) {
					return new RunWriter() {
						@Override public RunRecord record() { return writer.record(); }
						@Override public RunRecord append(RunEvent event) throws RunStoreException {
							var record = writer.append(event); events.accept(event); return record;
						}
						@Override public void close() throws RunStoreException { writer.close(); }
					};
				}
				@Override public RunWriter create(RunSpec spec) throws RunStoreException { return wrap(runs.create(spec)); }
				@Override public RunWriter openWriter(RunId id) throws RunStoreException { return wrap(runs.openWriter(id)); }
				@Override public RunRecord read(RunId id) throws RunStoreException { return runs.read(id); }
				@Override public List<RunId> list() { throw new AssertionError("Lifecycle must not scan historical runs"); }
				@Override public OpaqueRun export(RunId id) throws RunStoreException { return runs.export(id); }
			};
			factory = new ChildScopeFactory(agent, home, observed, requests, selections, clock, broker,
				callbacks, List.of(), List.of(new RunBudgetInterceptor(), new ToolJournalInterceptor()),
				List.of(), List.of(), List.of());
		}
		ToolCallback callback(String name) {
			return new ToolCallback() {
				@Override public ToolDefinition getToolDefinition() {
					return ToolDefinition.builder().name(name).description("local test callback").inputSchema("{\"type\":\"object\"}").build();
				}
				@Override public String call(String input) { throw new AssertionError("No callback expected"); }
			};
		}
		ChildSpec child(String id, Set<String> dependencies) {
			return new ChildSpec(id, "goal " + id, ChildRole.RESEARCH, ref, dependencies,
				new ToolScope(new LinkedHashSet<>(List.of("one", "two")), Set.of(workspace), Set.of(), Set.of(),
					false, false, false, false),
				new RunBudget(Duration.ofSeconds(30), null, 5), 1, true);
		}
		DelegationPlan plan(ChildSpec... children) { return new DelegationPlan("plan", List.of(children), 1); }
		DefaultDelegationRuntime runtime() {
			return new DefaultDelegationRuntime(factory, new DelegationProperties(2, 4, 1, 1,
				Duration.ofMinutes(5), null, 50), new SessionRunLock());
		}
		DefaultDelegationRuntime.Outcome execute(DefaultDelegationRuntime runtime, DelegationPlan plan) {
			return runtime.executeOutcome(parent, plan, authority, new RunBudget(Duration.ofMinutes(5), null, 50),
				RunUsage::empty, "revision", "configuration");
		}
		ChatResponse answer() {
			return new ChatResponse(List.of(new Generation(new AssistantMessage("narrative"))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(1, 0)).build());
		}
		ChatResponse ask() {
			var context = RunContext.current().orElseThrow();
			var request = ApprovalRequest.tool(context, "test-approval", "{}");
			var future = broker.request(request, context);
			afterRequest.accept(request);
			try {
				var decision = future.get(10, TimeUnit.SECONDS);
				if (decision == ApprovalDecision.DEFERRED) throw new Stop(Stop.Kind.DEFERRED);
				if (decision == ApprovalDecision.CANCELLED) throw new Stop(Stop.Kind.CANCELLED);
				return answer();
			}
			catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
			catch (ExecutionException | TimeoutException ex) { throw new IllegalStateException(ex); }
		}
	}
}
