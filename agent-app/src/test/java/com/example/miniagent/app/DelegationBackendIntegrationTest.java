package com.example.miniagent.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.*;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.change.command.ApplyCommand;
import com.example.miniagent.core.delegation.*;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.run.*;
import com.example.miniagent.plugin.bash.BashSandbox;
import com.example.miniagent.plugin.fs.FileTools;
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
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Actual advisor, file callbacks, durable stores, sandbox/process and shared apply. */
class DelegationBackendIntegrationTest {
	@TempDir Path directory;

	@Test void mixedWorkflowCompletesWithDifferentOwnedVerificationAndParentOnlyApply() throws Exception {
		try (var f = new Fixture()) {
			var operation = f.execute();
			var result = completed(operation.value());
			assertThat(result.status()).isEqualTo(DelegationResult.Status.COMPLETE);
			assertThat(operation.record().spec().childId()).isNull();
			assertThat(operation.record().spec().parentConversationId()).isEqualTo(f.session.id());
			assertThat(f.controller.list(f.session.id())).hasSize(1);
			assertThat(f.controller.list("foreign")).isEmpty();
			assertThat(result.children()).extracting(ChildResult::status).containsOnly(ChildStatus.SUCCEEDED);
			var implementation = result.children().get(1);
			var verification = result.children().get(2);
			var implementSet = f.proposal(implementation.runId());
			var verifySet = f.proposal(verification.runId());
			assertThat(verifySet.owner()).isNotEqualTo(implementSet.owner());
			assertThat(verifySet.proposed()).isEqualTo(implementSet.proposed());
			var process = f.changes.read().get(verifySet.changeSetId()).evidence().getFirst();
			assertThat(process.owner()).isEqualTo(verifySet.owner());
			assertThat(process.exitCode()).isZero();
			assertThat(process.cwd()).isNotEqualTo(process.sourceCwd());
			assertThat(Path.of(process.cwd())).doesNotExist();
			assertThat(verifySet.bindingIds()).contains(f.runs.read(verification.runId()).attempts().getLast().bindingId());
			assertThat(Files.readString(f.workspace.resolve("source.txt"))).isEqualTo("before");
			f.screen.setInteractive(true);
			f.screen.lines.add("exclusive " + implementSet.changeSetId() + " " + implementSet.digest());
			try (var worker = Executors.newSingleThreadExecutor()) {
				// Queue is subscribed before the exact shared ApplyCommand request is triggered.
				var task = worker.submit(() -> f.controller.apply(f.view().parent(), "plan", "implement",
					implementSet.changeSetId(), f.console()));
				var question = f.screen.questions.poll(10, TimeUnit.SECONDS);
				assertThat(question).isNotNull();
				assertThat(question.runId()).isEqualTo(implementation.runId());
				assertThat(f.broker.respond(question, ApprovalDecision.APPROVED)).isTrue();
				assertThat(task.get(15, TimeUnit.SECONDS)).isTrue();
			}
			assertThat(Files.readString(f.workspace.resolve("source.txt"))).isEqualTo("after");
			assertThat(f.changes.read().get(implementSet.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.APPLIED);
			assertThat(f.traces).anyMatch(e -> e instanceof TraceEvent.DelegationStateChanged d
				&& d.conversationId().equals(f.session.id()) && d.resultStatus() == DelegationResult.Status.COMPLETE);
		}
	}
	@Test void narrativeOnlyCannotCompleteOrStartImplementDependency() throws Exception {
		try (var f = new Fixture()) {
			f.narrative = true;
			var result = completed(f.execute().value());
			assertThat(result.status()).isEqualTo(DelegationResult.Status.FAILED);
			assertThat(result.children().getFirst().evidence()).anyMatch(ChildEvidence.Rejected.class::isInstance);
			assertThat(result.children().get(1).status()).isEqualTo(ChildStatus.SKIPPED);
			assertThat(f.changes.read().changes()).isEmpty();
		}
	}
	@Test void arbitraryRootControlDeclarationIsDeniedBeforeChildBudgetAndDispatch() throws Exception {
		try (var f = new Fixture()) {
			f.controlChild = true;
			var result = completed(f.execute().value());
			assertThat(result.children().getFirst().failure().code()).isEqualTo(ChildFailure.Code.SCOPE_DENIED);
			assertThat(result.children().getFirst().usage().toolCalls()).isZero();
			assertThat(f.changes.read().changes()).isEmpty();
		}
	}
	@Test void deniedVerifyWritesKeepPlanPartialBeforeToolBudget() throws Exception {
		try (var f = new Fixture()) {
			f.deniedWrite = true;
			var result = completed(f.execute().value());
			assertThat(result.status()).isEqualTo(DelegationResult.Status.PARTIAL);
			var verify = result.children().get(2);
			assertThat(verify.failure().code()).isEqualTo(ChildFailure.Code.SCOPE_DENIED);
			assertThat(verify.usage().toolCalls()).isZero();
			assertThat(Files.readString(f.workspace.resolve("source.txt"))).isEqualTo("before");
		}
	}
	@Test void foreignVerifyTargetNeverLaunchesOrCreatesVerifyProposal() throws Exception {
		try (var f = new Fixture()) {
			f.target = "research";
			var result = completed(f.execute().value());
			assertThat(result.status()).isEqualTo(DelegationResult.Status.PARTIAL);
			assertThat(f.launches).hasValue(0);
			assertThat(f.changes.read().changes()).hasSize(1);
			assertThat(result.children().get(2).usage().toolCalls()).isZero();
		}
	}
	@Test void actualNonzeroProcessEvidenceCannotBecomeComplete() throws Exception {
		try (var f = new Fixture()) {
			f.argv = List.of("/usr/bin/false");
			var result = completed(f.execute().value());
			assertThat(result.status()).isEqualTo(DelegationResult.Status.PARTIAL);
			var set = f.proposal(result.children().get(2).runId());
			assertThat(f.changes.read().get(set.changeSetId()).evidence().getFirst().exitCode()).isNotZero();
			assertThat(result.children().get(2).evidence()).anyMatch(ChildEvidence.Rejected.class::isInstance);
		}
	}
	@Test void realSandboxDeniesSourceWritesRatherThanInferringArgvSafety() throws Exception {
		try (var f = new Fixture()) {
			f.argv = List.of("/bin/sh", "-c", "printf forbidden > source.txt");
			var result = completed(f.execute().value());
			assertThat(result.status()).isEqualTo(DelegationResult.Status.PARTIAL);
			assertThat(Files.readString(f.workspace.resolve("source.txt"))).isEqualTo("before");
			var set = f.proposal(result.children().get(2).runId());
			assertThat(f.changes.read().get(set.changeSetId()).evidence().getFirst().exitCode()).isNotZero();
		}
	}
	@Test void exitZeroFollowedBySourceDriftIsRecollectedAndRefused() throws Exception {
		try (var f = new Fixture()) {
			f.afterVerify = () -> {
				try { Files.writeString(f.workspace.resolve("source.txt"), "human"); }
				catch (IOException ex) { throw new UncheckedIOException(ex); }
			};
			var result = completed(f.execute().value());
			assertThat(result.status()).isNotEqualTo(DelegationResult.Status.COMPLETE);
			var verifySet = f.proposal(result.children().get(2).runId());
			assertThat(f.changes.read().get(verifySet.changeSetId()).evidence().getFirst().exitCode()).isZero();
			assertThat(Files.readString(f.workspace.resolve("source.txt"))).isEqualTo("human");
		}
	}
	@Test void sourceChangedAfterCompleteIsRecollectedAtParentApply() throws Exception {
		try (var f = new Fixture()) {
			var result = completed(f.execute().value());
			assertThat(result.status()).isEqualTo(DelegationResult.Status.COMPLETE);
			var set = f.proposal(result.children().get(1).runId());
			Files.writeString(f.workspace.resolve("source.txt"), "human");
			assertThatThrownBy(() -> f.controller.apply(f.view().parent(), "plan", "implement",
				set.changeSetId(), f.console())).isInstanceOf(IOException.class);
			assertThat(f.broker.pending()).isEmpty();
			assertThat(Files.readString(f.workspace.resolve("source.txt"))).isEqualTo("human");
		}
	}
	@Test void actualRunnerStaleExitZeroRetainsObservedCodeInsteadOfRelabellingSuccess() throws Exception {
		try (var f = new Fixture()) {
			f.driftDuringVerify = true;
			f.verificationMillis = 15000;
			f.argv = List.of("/bin/sh", "-c",
				"printf ready > \"$TMPDIR/ready\"; /bin/cat \"$TMPDIR/gate\" > /dev/null; exit 0");
			var result = completed(f.execute().value());
			f.driftCompleted.get(10, TimeUnit.SECONDS);
			assertThat(f.observedVerification.code()).isEqualTo(VerificationRunner.Code.STALE);
			assertThat(f.observedVerification.evidence().exitCode()).isZero();
			assertThat(result.status()).isNotEqualTo(DelegationResult.Status.COMPLETE);
			assertThat(Files.readString(f.workspace.resolve("source.txt"))).isEqualTo("human");
		}
	}
	@Test void pendingRemainsPendingAndExactCancellationDoesNotTouchForeignPlan() throws Exception {
		try (var f = new Fixture()) {
			f.pending = true;
			var outcome = f.execute().value();
			assertThat(outcome).isInstanceOf(DefaultDelegationRuntime.Pending.class);
			var pending = (DefaultDelegationRuntime.Pending) outcome;
			assertThat(pending.children().getFirst().record().state()).isEqualTo(RunState.WAITING_APPROVAL);
			assertThat(f.controller.cancel("foreign", "plan")).isFalse();
			assertThat(f.controller.cancel(f.session.id(), "plan")).isTrue();
			assertThat(f.runs.read(pending.children().getFirst().record().spec().runId()).state()).isEqualTo(RunState.CANCELLED);
			assertThat(completed(f.view().outcome()).status()).isEqualTo(DelegationResult.Status.CANCELLED);
			assertThat(f.broker.pending()).isEmpty();
		}
	}
	@Test void normalShutdownOfUnansweredApprovalIsProcessLossNotUserCancellation() throws Exception {
		try (var f = new Fixture()) {
			f.pending = true;
			var pending = (DefaultDelegationRuntime.Pending) f.execute().value();
			var id = pending.children().getFirst().record().spec().runId();
			f.runtime.close();
			var record = f.runs.read(id);
			assertThat(record.state()).isEqualTo(RunState.INTERRUPTED);
			assertThat(record.reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
			assertThat(FileRunStore.events(f.runs, id)).noneMatch(e -> e instanceof RunEvent.StateChanged s
				&& s.reason() == RunEvent.Reason.USER_CANCELLED);
			assertThat(f.broker.pending()).isEmpty();
		}
	}
	@Test void explicitRestartRecoveryUsesExactPersistedChildWithoutAllocatingParentOrRun() throws Exception {
		try (var f = new Fixture()) {
			f.pending = true;
			var pending = (DefaultDelegationRuntime.Pending) f.execute().value();
			var child = pending.children().getFirst().record();
			var exact = f.plan().children().getFirst();
			f.runtime.close();
			f.pending = false;
			int count = f.runs.list().size();
			try (var runtime = new DefaultDelegationRuntime(f.factory, new DelegationProperties(1, 4, 1, 1,
					Duration.ofMinutes(3), null, 30), new SessionRunLock());
				 var controller = new DelegationController(runtime, f.runs, f.service, event -> {})) {
				runtime.evidence(new DelegationEvidenceCollector(f.runs, f.requests.evidence(), f.changes));
				var before = f.runs.read(child.spec().runId());
				assertThatThrownBy(() -> controller.recover(child.spec().runId(), "recover", exact, "foreign",
					f.authority, RunExecutor.ResumeOptions.ordinary())).isInstanceOf(IOException.class);
				assertThat(f.runs.read(child.spec().runId())).isEqualTo(before);
				var wrongRole = new ChildSpec(exact.childId(), exact.task(), ChildRole.VERIFY, exact.model(),
					exact.dependencies(), exact.scope(), exact.budget(), exact.maxAttempts(), exact.required());
				assertThatThrownBy(() -> controller.recover(child.spec().runId(), "recover", wrongRole, f.session.id(),
					f.authority, RunExecutor.ResumeOptions.ordinary())).isInstanceOf(IOException.class);
				assertThat(f.runs.read(child.spec().runId())).isEqualTo(before);
				var result = controller.recover(child.spec().runId(), "recover", exact, f.session.id(),
					f.authority, RunExecutor.ResumeOptions.ordinary());
				assertThat(result.record().spec()).isEqualTo(child.spec());
				assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
				assertThat(result.record().attempts()).hasSize(child.attempts().size() + 1);
				assertThat(f.runs.list()).hasSize(count);
				assertThat(controller.show(f.session.id(), "recover").parent()).isNull();
			}
		}
	}
	@Test void applyInsideRealParentAttemptDoesNotUndoOrPoisonApprovedChildAtStageClose() throws Exception {
		try (var f = new Fixture(); var worker = Executors.newSingleThreadExecutor()) {
			f.screen.setInteractive(true);
			var task = worker.submit(() -> f.executor.startOperation("mixed and apply", f.session.id(),
				new ModelSelection(f.model), f.workspace, f.budget, context -> {
					var result = completed(f.controller.execute(context.turnContext(), f.plan(),
						f.authority, f.budget, context::usage));
					try {
						var proposal = f.proposal(result.children().get(1).runId());
						f.screen.lines.add("exclusive " + proposal.changeSetId() + " " + proposal.digest());
						assertThat(StagedWorkspace.current().orElseThrow().owner().runId()).isEqualTo(context.spec().runId());
						assertThat(f.controller.apply(context.turnContext(), "plan", "implement", proposal.changeSetId(),
							f.console())).isTrue();
						return proposal;
					}
					catch (IOException ex) { throw new UncheckedIOException(ex); }
				}));
			var question = f.screen.questions.poll(10, TimeUnit.SECONDS);
			assertThat(question).isNotNull();
			assertThat(f.broker.respond(question, ApprovalDecision.APPROVED)).isTrue();
			var result = task.get(15, TimeUnit.SECONDS);
			assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
			assertThat(Files.readString(f.workspace.resolve("source.txt"))).isEqualTo("after");
			assertThat(f.changes.read().get(result.value().changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.APPLIED);
			assertThat(f.changes.read().changes()).noneMatch(s -> s.changeSet().owner().runId().equals(result.record().spec().runId()));
		}
	}
	private static DelegationResult completed(DefaultDelegationRuntime.Outcome outcome) {
		assertThat(outcome).isInstanceOf(DefaultDelegationRuntime.Completed.class);
		return ((DefaultDelegationRuntime.Completed) outcome).result();
	}
	private final class Fixture implements AutoCloseable {
		final Path home = Files.createTempDirectory(directory, "home-").toRealPath();
		final Path workspace = Files.createTempDirectory(directory, "work-").toRealPath();
		final Clock clock = Clock.systemUTC();
		final FileRunStore runs = new FileRunStore(home);
		final RunRequestStore requests = new RunRequestStore(home);
		final FileChangeStore changes = new FileChangeStore(home, workspace);
		final AgentSession session = new AgentSession();
		final ChangeCommandsE2ETest.Screen screen = new ChangeCommandsE2ETest.Screen();
		final ConsoleApprovalBroker broker = new ConsoleApprovalBroker(screen);
		final AtomicInteger launches = new AtomicInteger();
		final List<TraceEvent> traces = java.util.Collections.synchronizedList(new ArrayList<>());
		final Map<String, AtomicInteger> rounds = new ConcurrentHashMap<>();
		final ModelRef model = new ModelRef("fake", "backend");
		final RunBudget budget = new RunBudget(Duration.ofMinutes(3), null, 30);
		final Agent agent;
		final RunExecutor executor;
		final ChildScopeFactory factory;
		final StagedApplyService service;
		final DefaultDelegationRuntime runtime;
		final DelegationController controller;
		final ToolScope authority;
		boolean narrative, deniedWrite, pending, driftDuringVerify, controlChild;
		final CompletableFuture<Void> driftCompleted = new CompletableFuture<>();
		VerificationRunner.Result observedVerification;
		String target = "implement";
		List<String> argv = List.of("/usr/bin/true");
		long verificationMillis = 5000;
		Runnable afterVerify = () -> {};
		Fixture() throws Exception {
			Files.writeString(workspace.resolve("source.txt"), "before");
			var verifier = new DelegationVerificationTool(changes, (context, command) -> {
				launches.incrementAndGet();
				var launch = BashSandbox.verificationPolicy(List.of()).prepare(context, command);
				if (driftDuringVerify) subscribeToProcessReady(context);
				return launch;
			}, clock);
			var fileProvider = MethodToolCallbackProvider.builder()
				.toolObjects(new FileTools(List.of(), 1024 * 1024, null)).build();
			var files = List.of(fileProvider.getToolCallbacks()).stream().filter(t ->
				Set.of("read_file", "write_file").contains(t.getToolDefinition().name())).toList();
			var callbacks = new ArrayList<ToolCallback>(files);
			callbacks.add(verifier.callback());
			ToolDefinition control = ToolDefinition.builder().name("root_control").description("Parent-only exact control")
				.inputSchema("{\"type\":\"object\"}").build();
			callbacks.add(new ToolCallback() {
				@Override public ToolDefinition getToolDefinition() { return control; }
				@Override public String call(String input) { throw new AssertionError("Child control must never dispatch"); }
			});
			StagingToolInterceptor.Policy filePolicy = () -> {
				var map = new java.util.HashMap<String, StagingToolInterceptor.Declaration>();
				for (var callback : files) map.put(callback.getToolDefinition().name(),
					new StagingToolInterceptor.Declaration(SideEffectClass.definitionDigest(callback.getToolDefinition()),
						callback.getToolDefinition().name().equals("write_file") ? StagingToolInterceptor.Kind.FILE_WRITE
							: StagingToolInterceptor.Kind.READ, List.of()));
				return map;
			};
			StagingToolInterceptor.Policy controlPolicy = () -> Map.of("root_control",
				new StagingToolInterceptor.Declaration(SideEffectClass.definitionDigest(control),
					StagingToolInterceptor.Kind.CONTROL, List.of()));
			var policies = List.of(filePolicy, verifier.stagingPolicy(), controlPolicy);
			var reads = List.<SideEffectClass.Policy>of(() -> Map.of("read_file",
				SideEffectClass.definitionDigest(files.stream().filter(t ->
					t.getToolDefinition().name().equals("read_file")).findFirst().orElseThrow().getToolDefinition())),
				verifier.readPolicy());
			var staging = new StagingToolInterceptor(() -> policies);
			var selections = new FileSessionModelStore(home);
			ModelProvider provider = new ModelProvider() {
				@Override public String providerId() { return "fake"; }
				@Override public ProviderAuth auth() { return () -> true; }
				@Override public List<ModelEntry> models() { return List.of(new ModelEntry(model, "fake", null)); }
				@Override public ChatModel create(ModelRef ref, Map<String, String> options) {
					return new ChatModel() {
						@Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
						@Override public ChatResponse call(Prompt prompt) { return script(prompt); }
					};
				}
			};
			var registry = new ModelRegistry(List.of(provider), m -> ChatClient.builder(m).build());
			agent = new Agent(registry, selections, new ModelSelection(model), session, workspace,
				"system", callbacks, List.of(), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
			factory = new ChildScopeFactory(agent, home, runs, requests, selections, clock, broker, callbacks,
				List.of(), List.of(staging, new RunBudgetInterceptor(), new ToolJournalInterceptor()), policies, reads, List.of());
			runtime = new DefaultDelegationRuntime(factory, new DelegationProperties(1, 4, 1, 1,
				Duration.ofMinutes(3), null, 30), new SessionRunLock());
			runtime.evidence(new DelegationEvidenceCollector(runs, requests.evidence(), changes));
			var livePolicy = new PathPolicy(workspace, home, List.of());
			var apply = new ApplyCommand(() -> changes,
				() -> new ApplyEngine(changes, livePolicy, clock),
				broker, clock, event -> {});
			service = new StagedApplyService(runs, changes, factory, apply);
			controller = new DelegationController(runtime, runs, service, event -> {});
			controller.observed(traces::add);
			executor = new RunExecutor(agent, registry, selections, runs, requests, clock, List.of(), broker)
				.staging(staging, () -> changes);
			authority = new ToolScope(Set.of("read_file", "write_file", "verify_staged", "root_control"), Set.of(workspace),
				Set.of(workspace), Set.of(workspace.resolve("target")), true, false, true, true);
		}
		DelegationPlan plan() {
			var read = new ToolScope(controlChild ? Set.of("read_file", "root_control") : Set.of("read_file"),
				Set.of(workspace), Set.of(), Set.of(), false, false, false, false);
			var write = new ToolScope(Set.of("write_file"), Set.of(workspace), Set.of(workspace), Set.of(), true, false, false, false);
			var verify = new ToolScope(Set.of("verify_staged", "write_file"), Set.of(workspace), Set.of(),
				Set.of(workspace.resolve("target")), true, false, false, false);
			var childBudget = new RunBudget(Duration.ofSeconds(30), null, 5);
			return new DelegationPlan("plan", List.of(
				new ChildSpec("research", "research", ChildRole.RESEARCH, model, Set.of(), read, childBudget, 1, true),
				new ChildSpec("implement", "implement", ChildRole.IMPLEMENT, model, Set.of("research"), write, childBudget, 1, true),
				new ChildSpec("verify", "verify", ChildRole.VERIFY, model, Set.of("implement"), verify, childBudget, 1, true)), 1);
		}
		RunExecutor.OperationResult<DefaultDelegationRuntime.Outcome> execute() throws IOException {
			return controller.executeOperator(executor, session.id(), new ModelSelection(model), workspace, plan(), authority, budget);
		}
		DelegationController.View view() { return controller.show(session.id(), "plan"); }
		SlashCommand.CommandContext console() { return new SlashCommand.CommandContext(agent, session, screen); }
		ChangeSet proposal(RunId run) throws IOException {
			return changes.read().changes().stream().map(ChangeStore.Snapshot::changeSet)
				.filter(s -> s.owner().runId().equals(run) && s.status() != ChangeStatus.SUPERSEDED).findFirst().orElseThrow();
		}
		ChatResponse script(Prompt prompt) {
			var context = RunContext.current().orElseThrow();
			String child = context.spec().childId();
			int round = rounds.computeIfAbsent(context.attemptId(), id -> new AtomicInteger()).getAndIncrement();
			if (pending && child.equals("research")) {
				var request = ApprovalRequest.tool(context, "test", "{}");
				var answer = broker.request(request, context);
				assertThat(answer.join()).isEqualTo(ApprovalDecision.DEFERRED);
				throw new Stop(Stop.Kind.DEFERRED);
			}
			if (round > 0) {
				if (child.equals("verify")) {
					var scope = (ChildScopeFactory.Scope) ((ToolCallingChatOptions) prompt.getOptions())
						.getToolContext().get(ToolContextKeys.CHILD_SCOPE);
					try { observedVerification = scope.evidenceRequest(plan().children().get(2)).verification(); }
					catch (IOException ex) { throw new UncheckedIOException(ex); }
					afterVerify.run();
				}
				return answer();
			}
			return switch (child) {
				case "research" -> controlChild ? tool("root_control", "{\"planId\":\"foreign\"}")
					: narrative ? answer() : tool("read_file", "{\"path\":\"source.txt\"}");
				case "implement" -> tool("write_file", "{\"path\":\"source.txt\",\"content\":\"after\"}");
				case "verify" -> deniedWrite ? tool("write_file", "{\"path\":\"source.txt\",\"content\":\"forbidden\"}")
					: tool("verify_staged", JsonMapper.builder().build().writeValueAsString(Map.of("childId", target,
						"argv", argv, "timeoutMillis", verificationMillis, "maxOutputBytes", 1024)));
				default -> throw new AssertionError("Operator must not dispatch a model");
			};
		}
		ChatResponse answer() {
			return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build());
		}
		ChatResponse tool(String name, String arguments) {
			return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", name, arguments))).build())),
				ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build());
		}
		void subscribeToProcessReady(VerificationRunner.Context context) throws IOException {
			Path gate = context.temp().resolve("gate");
			Path ready = context.temp().resolve("ready");
			Process mkfifo = new ProcessBuilder("/usr/bin/mkfifo", gate.toString(), ready.toString()).start();
			try {
				assertThat(mkfifo.onExit().get(5, TimeUnit.SECONDS).exitValue()).isZero();
			}
			catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
			catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) { throw new IOException(ex); }
			finally { if (mkfifo.isAlive()) mkfifo.destroyForcibly(); }
			Process reader = new ProcessBuilder("/bin/cat", ready.toString()).start();
			var readySignal = reader.onExit(); // Armed before the sandboxed printf opens the FIFO.
			Thread.ofVirtual().start(() -> {
				Process release = null;
				try {
					assertThat(readySignal.get(10, TimeUnit.SECONDS).exitValue()).isZero();
					assertThat(new String(reader.getInputStream().readAllBytes(),
						java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("ready");
					Files.writeString(workspace.resolve("source.txt"), "human");
					release = new ProcessBuilder("/bin/sh", "-c", "printf ok > \"$1\"", "release", gate.toString()).start();
					assertThat(release.onExit().get(5, TimeUnit.SECONDS).exitValue()).isZero();
					driftCompleted.complete(null);
				}
				catch (Throwable ex) { driftCompleted.completeExceptionally(ex); }
				finally {
					if (reader.isAlive()) reader.destroyForcibly();
					if (release != null && release.isAlive()) release.destroyForcibly();
				}
			});
		}
		@Override public void close() { controller.close(); runtime.close(); }
	}
}
