package com.example.miniagent.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.model.FileSessionModelStore;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.ProviderAuth;
import com.example.miniagent.core.model.ToolContextKeys;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.model.registry.ModelRegistry;
import com.example.miniagent.core.run.*;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunExecutorTest {
	private static final ModelSelection SELECTED = new ModelSelection(new ModelRef("fake", "a"),
			Map.of("temperature", "0.2"));
	private static final ModelSelection OTHER = new ModelSelection(new ModelRef("fake", "b"));
	private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
	private static final String DIGEST = "a".repeat(64);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@TempDir
	Path home;
	private FileRunStore runs;
	private FileSessionModelStore selections;
	private RunRequestStore requests;
	private RunExecutor executor;
	private Agent agent;
	private AgentSession session;
	private Path cwd;
	private final MutableClock clock = new MutableClock();
	private final AtomicBoolean available = new AtomicBoolean(true);
	private final AtomicReference<String> wire = new AtomicReference<>("fake");
	private final AtomicInteger bindings = new AtomicInteger();
	private final List<TurnContext> turns = new ArrayList<>();
	private final List<Prompt> prompts = new ArrayList<>();
	private Consumer<Prompt> onCall = prompt -> {};
	private Consumer<ToolContext> onTool = context -> {};
	private UnaryOperator<ChatResponse> transform = response -> response;

	@BeforeEach
	void setup() throws Exception {
		Path root = this.home;
		this.home = Files.createDirectory(root.resolve("state"));
		this.cwd = Files.createDirectory(root.resolve("workspace")).toRealPath();
		this.runs = new FileRunStore(this.home);
		this.selections = new FileSessionModelStore(this.home);
		this.requests = new RunRequestStore(this.home);
		this.session = new AgentSession();
		this.session.switchTo("parent");
		var callbacks = MethodToolCallbackProvider.builder().toolObjects(new ContextTool()).build();
		ModelProvider provider = new ModelProvider() {
			@Override public String providerId() { return "fake"; }
			@Override public ProviderAuth auth() { return available::get; }
			@Override public Set<String> supportedOptions() { return Set.of("temperature"); }
			@Override public List<ModelEntry> models() {
				return List.of(new ModelEntry(SELECTED.ref(), wire.get(), null),
						new ModelEntry(OTHER.ref(), wire.get(), null));
			}
			@Override public ChatModel create(ModelRef ref, Map<String, String> options) {
				bindings.incrementAndGet();
				return new ScriptedChatModel() {
					@Override public ChatResponse call(Prompt prompt) {
						turns.add(TurnContext.current().orElseThrow());
						prompts.add(prompt);
						assertThat(RunContext.current().orElseThrow().turnContext()).isEqualTo(turns.getLast());
						onCall.accept(prompt);
						return transform.apply(super.call(prompt));
					}
				};
			}
		};
		ModelRegistry registry = new ModelRegistry(List.of(provider),
				model -> ChatClient.builder(model).defaultOptions(
						ToolCallingChatOptions.builder().toolCallbacks(callbacks.getToolCallbacks())).build());
		this.agent = new Agent(registry, this.selections, SELECTED, this.session, this.cwd, "test",
				List.of(), List.of(callbacks), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
		this.executor = new RunExecutor(this.agent, registry, this.selections, this.runs,
				this.requests, this.clock, List.of());
	}

	private RunExecutor.Result start(String goal) throws IOException {
		return this.executor.start(goal, this.session.id(), SELECTED, this.cwd, RunBudget.defaults());
	}

	private RunExecutor.Result interrupt() throws IOException {
		this.onCall = prompt -> { throw new Stop(Stop.Kind.INTERRUPTED); };
		RunExecutor.Result result = start("Finish the original goal");
		this.onCall = prompt -> {};
		return result;
	}

	private Path request(RunRecord record) {
		return this.home.resolve("run-requests").resolve(record.spec().runId().value() + ".json");
	}

	private void refusal(RunExecutor.Code code, org.junit.jupiter.api.function.Executable action) {
		assertThat(assertThrows(RunExecutor.Failure.class, action).code()).isEqualTo(code);
	}

	@Test
	void startUsesDedicatedConversationAndActualOfficialToolLoop() throws Exception {
		this.onTool = tools -> {
			TurnContext turn = (TurnContext) tools.getContext().get(ToolContextKeys.TURN_CONTEXT);
			RunContext run = RunContext.current().orElseThrow();
			assertThat(tools.getContext()).containsEntry(ToolContextKeys.SESSION_ID, run.spec().conversationId())
				.containsEntry(ToolContextKeys.RUN_ID, run.spec().runId().value())
				.containsEntry(ToolContextKeys.ATTEMPT_ID, run.attemptId());
			assertThat(turn).isEqualTo(run.turnContext());
			assertThat(this.runsRead(run.spec().runId()).state()).isEqualTo(RunState.RUNNING);
		};
		RunExecutor.Result result = start("!tool inspect_context {}");
		RunRecord record = result.record();
		assertThat(result.reply().text()).contains("captured-context");
		assertThat(record.state()).isEqualTo(RunState.COMPLETED);
		assertThat(record.spec().parentConversationId()).isEqualTo("parent");
		assertThat(record.spec().conversationId()).isNotEqualTo("parent").isNotEqualTo(record.spec().runId().value());
		assertThat(this.turns).hasSize(2);
		assertThat(this.turns.get(0)).isEqualTo(this.turns.get(1));
		assertThat(this.bindings).hasValue(1);
		assertThat(this.session.id()).isEqualTo("parent");
		assertThat(record.attempts().getFirst().attemptId()).isEqualTo(this.turns.getFirst().attemptId());
		assertThat(record.usage().knownTokens()).isEqualTo(result.reply().usage().getTotalTokens().longValue());
		assertThat(record.usage().tokensUnknown()).isFalse();
		assertThat(new FileSessionModelStore(this.home).load(record.spec().conversationId())).contains(SELECTED);
		assertThat(new RunRequestStore(this.home).load(record.spec()).goal()).isEqualTo("!tool inspect_context {}");
		assertThat(Files.getPosixFilePermissions(request(record)))
			.isEqualTo(PosixFilePermissions.fromString("rw-------"));
		assertThat(this.selections.load("parent")).isEmpty();
		assertThat(RunContext.current()).isEmpty();
		assertThat(TurnContext.current()).isEmpty();
	}

	@Test
	void consoleMutationDuringDispatchCannotChangeCapturedRun() throws Exception {
		this.onTool = tools -> {
			this.session.switchTo("different-parent");
			try {
				this.selections.save("different-parent", OTHER);
			}
			catch (IOException ex) {
				throw new java.io.UncheckedIOException(ex);
			}
		};
		RunRecord record = start("!tool inspect_context {}").record();
		assertThat(this.turns).hasSize(2).allSatisfy(turn -> {
			assertThat(turn.binding().ref()).isEqualTo(SELECTED.ref());
			assertThat(turn.binding().optionsFingerprint()).isEqualTo(SELECTED.optionsFingerprint());
			assertThat(turn.sessionId()).isEqualTo(record.spec().conversationId());
		});
		assertThat(record.spec().parentConversationId()).isEqualTo("parent");
		assertThat(this.session.id()).isEqualTo("different-parent");
		assertThat(this.agent.selectedModel()).isEqualTo(OTHER);
	}

	@Test
	void explicitResumeCreatesFreshBindingAttemptAndSafeTextOnlyInput() throws Exception {
		RunRecord first = interrupt().record();
		this.clock.value = AT.plusSeconds(2);
		RunRecord resumed = this.executor.resume(first.spec().runId(), SELECTED, this.cwd).record();
		assertThat(resumed.state()).isEqualTo(RunState.COMPLETED);
		assertThat(resumed.attempts()).hasSize(2);
		assertThat(resumed.attempts().getFirst().bindingId()).isNotEqualTo(resumed.attempts().getLast().bindingId());
		assertThat(resumed.attempts().getFirst().attemptId()).isNotEqualTo(resumed.attempts().getLast().attemptId());
		assertThat(this.bindings).hasValue(2);
		assertThat(this.turns.getLast().sessionId()).isEqualTo(first.spec().conversationId());
		assertThat(this.prompts.getLast().getInstructions()).noneMatch(message ->
				message instanceof ToolResponseMessage || message instanceof AssistantMessage assistant
						&& assistant.hasToolCalls());
		assertThat(resumed.usage().tokensUnknown()).isTrue();
		assertThat(resumed.usage().knownTokens()).isPositive();
		assertThat(new FileRunStore(this.home).read(first.spec().runId())).isEqualTo(resumed);
	}

	@Test
	void resumeUnavailableModelKeepsStateAndLogAndReleasesWriter() throws Exception {
		RunRecord first = interrupt().record();
		String before = this.runs.export(first.spec().runId()).eventsLog();
		this.available.set(false);
		refusal(RunExecutor.Code.MODEL_UNAVAILABLE,
				() -> this.executor.resume(first.spec().runId(), SELECTED, this.cwd));
		assertThat(this.runs.export(first.spec().runId()).eventsLog()).isEqualTo(before);
		assertThat(this.runs.read(first.spec().runId())).isEqualTo(first);
		try (RunWriter writer = this.runs.openWriter(first.spec().runId())) {
			assertThat(writer.record()).isEqualTo(first);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "COMPLETED", "FAILED", "CANCELLED" })
	void terminalRunRejectsResume(String state) throws Exception {
		RunRecord record;
		if (state.equals("FAILED")) {
			this.onCall = prompt -> { throw new IllegalStateException("provider failed"); };
			assertThatThrownBy(() -> start("goal")).hasMessageContaining("provider failed");
			record = this.runs.read(this.runs.list().getFirst());
		}
		else if (state.equals("CANCELLED")) {
			record = interrupt().record();
			record = this.executor.cancel(record.spec().runId());
		}
		else {
			record = start("goal").record();
		}
		assertThat(record.state()).isEqualTo(RunState.valueOf(state));
		RunId id = record.spec().runId();
		String before = this.runs.export(id).eventsLog();
		refusal(RunExecutor.Code.TERMINAL, () -> this.executor.resume(id, SELECTED, this.cwd));
		assertThat(this.runs.export(id).eventsLog()).isEqualTo(before);
	}

	@Test
	void unknownMutationRefusedWithoutInventingOldAttemptToolSuccess() throws Exception {
		this.onCall = prompt -> {
			RunContext context = RunContext.current().orElseThrow();
			try {
				context.writer().append(new RunEvent.ToolStarted(context.now(), context.attemptId(),
						"uncertain", "write_file", DIGEST, RunEvent.Effect.MUTATING));
			}
			catch (RunStoreException ex) {
				throw new java.io.UncheckedIOException(ex);
			}
			throw new Stop(Stop.Kind.INTERRUPTED);
		};
		RunRecord first = start("goal").record();
		String before = this.runs.export(first.spec().runId()).eventsLog();
		RunExecutor.Failure error = assertThrows(RunExecutor.Failure.class,
				() -> this.executor.resume(first.spec().runId(), SELECTED, this.cwd));
		assertThat(error.code()).isEqualTo(RunExecutor.Code.UNKNOWN_EFFECTS);
		assertThat(error.uncertainTools()).containsExactlyElementsOf(first.uncertainTools());
		assertThat(this.runs.export(first.spec().runId()).eventsLog()).isEqualTo(before);
		assertThat(this.bindings).hasValue(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "goal", "schema", "inputDigest", "specDigest", "options", "extra", "tree", "duplicate" })
	void corruptRequestTreeAndDigestsRefuseBeforeBinding(String corruption) throws Exception {
		RunRecord first = interrupt().record();
		Path file = request(first);
		var root = JSON.readTree(Files.readString(file)).deepCopy();
		var object = (tools.jackson.databind.node.ObjectNode) root;
		switch (corruption) {
			case "goal" -> object.put("goal", "tampered");
			case "schema" -> object.put("schemaVersion", 2);
			case "inputDigest" -> object.put("inputDigest", DIGEST);
			case "specDigest" -> object.put("specDigest", DIGEST);
			case "options" -> ((tools.jackson.databind.node.ObjectNode) root.path("selection").path("options"))
					.put("apiKey", "synthetic-not-a-secret");
			case "extra" -> object.put("client", "forbidden");
			case "tree" -> object.put("selection", "not-an-object");
			case "duplicate" -> {}
			default -> throw new AssertionError();
		}
		String text = JSON.writeValueAsString(root);
		if (corruption.equals("duplicate")) {
			text = text.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1");
		}
		Files.writeString(file, text);
		refusal(RunExecutor.Code.CORRUPT_REQUEST,
				() -> this.executor.resume(first.spec().runId(), SELECTED, this.cwd));
		assertThat(this.bindings).hasValue(1);
		assertThat(Files.readString(file)).isEqualTo(text);
		assertThat(this.runs.read(first.spec().runId())).isEqualTo(first);
	}

	@ParameterizedTest
	@ValueSource(strings = { "caller-ref", "caller-options", "stored-selection", "cwd", "wire", "missing-selection" })
	void identityDriftRefusedBeforeDispatch(String drift) throws Exception {
		RunRecord first = interrupt().record();
		ModelSelection expected = SELECTED;
		Path expectedCwd = this.cwd;
		switch (drift) {
			case "caller-ref" -> expected = OTHER;
			case "caller-options" -> expected = new ModelSelection(SELECTED.ref());
			case "stored-selection" -> this.selections.save(first.spec().conversationId(), OTHER);
			case "cwd" -> expectedCwd = Files.createDirectory(this.home.resolve("other-workspace"));
			case "wire" -> this.wire.set("changed");
			case "missing-selection" -> Files.delete(this.home.resolve("model-selections")
					.resolve(first.spec().conversationId() + ".json"));
			default -> throw new AssertionError();
		}
		ModelSelection caller = expected;
		Path directory = expectedCwd;
		refusal(drift.equals("missing-selection") ? RunExecutor.Code.CORRUPT_REQUEST : RunExecutor.Code.IDENTITY_DRIFT,
				() -> this.executor.resume(first.spec().runId(), caller, directory));
		assertThat(this.turns).hasSize(1);
		assertThat(this.runs.read(first.spec().runId())).isEqualTo(first);
		try (RunWriter writer = this.runs.openWriter(first.spec().runId())) {
			assertThat(writer.record()).isEqualTo(first);
		}
	}

	@Test
	void usagePrecedesAttemptEndAndTerminalWithoutDoubleCountingRounds() throws Exception {
		this.onCall = prompt -> this.clock.value = this.clock.value.plusMillis(100);
		this.transform = response -> new ChatResponse(response.getResults(),
				org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
					.usage(new org.springframework.ai.chat.metadata.DefaultUsage(3, 4)).build());
		RunRecord record = start("!tool inspect_context {}").record();
		assertThat(record.usage()).isEqualTo(new RunUsage(14, false, 0, 200, 0));
		String log = this.runs.export(record.spec().runId()).eventsLog();
		List<String> types = log.lines().map(line -> JSON.readTree(line.split("\t", 4)[3])
				.path("type").asString()).toList();
		assertThat(types).containsExactly("StateChanged", "AttemptStarted", "UsageRecorded",
				"UsageRecorded", "UsageRecorded", "AttemptEnded", "StateChanged");
		assertThat(log).doesNotContain("loop-total");
	}

	@Test
	void userCancelIntentIsDurableBeforeInterruptAndCleanupPreservesFlag() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch neverReleased = new CountDownLatch(1);
		CompletableFuture<RunId> identity = new CompletableFuture<>();
		CompletableFuture<Boolean> observedCancel = new CompletableFuture<>();
		CompletableFuture<Boolean> flagAfterCleanup = new CompletableFuture<>();
		this.onCall = prompt -> {
			RunContext context = RunContext.current().orElseThrow();
			identity.complete(context.spec().runId());
			entered.countDown();
			try {
				assertThat(neverReleased.await(10, TimeUnit.SECONDS)).isTrue();
				throw new AssertionError("Cancellation should interrupt the dispatch");
			}
			catch (InterruptedException ex) {
				RunRecord intent = runsRead(context.spec().runId());
				observedCancel.complete(intent.state() == RunState.INTERRUPTED
						&& intent.reason() == RunEvent.Reason.USER_CANCELLED);
				Thread.currentThread().interrupt();
				throw new RuntimeException(ex);
			}
		};
		CompletableFuture<RunExecutor.Result> completed = new CompletableFuture<>();
		Thread worker = new Thread(() -> {
			try {
				completed.complete(start("goal"));
				flagAfterCleanup.complete(Thread.currentThread().isInterrupted());
			}
			catch (Throwable ex) {
				completed.completeExceptionally(ex);
				flagAfterCleanup.completeExceptionally(ex);
			}
		});
		worker.start();
		try {
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			RunId id = identity.get(10, TimeUnit.SECONDS);
			this.executor.cancel(id);
			assertThat(observedCancel.get(10, TimeUnit.SECONDS)).isTrue();
			assertThat(completed.get(10, TimeUnit.SECONDS).record().state()).isEqualTo(RunState.CANCELLED);
			assertThat(flagAfterCleanup.get(10, TimeUnit.SECONDS)).isTrue();
			assertThat(this.runs.read(id).usage().tokensUnknown()).isTrue();
			try (RunWriter writer = this.runs.openWriter(id)) {
				assertThat(writer.record().state()).isEqualTo(RunState.CANCELLED);
			}
		}
		finally {
			worker.interrupt();
			worker.join(10000);
			assertThat(worker.isAlive()).isFalse();
		}
	}

	@Test
	void deferredAndBudgetSignalsAreNotUserCancellation() throws Exception {
		this.onCall = prompt -> { throw new Stop(Stop.Kind.DEFERRED); };
		RunRecord waiting = start("goal").record();
		try {
			assertThat(this.runs.read(waiting.spec().runId()).state()).isEqualTo(RunState.WAITING_APPROVAL);
			refusal(RunExecutor.Code.NOT_RESUMABLE,
					() -> this.executor.resume(waiting.spec().runId(), SELECTED, this.cwd));
		}
		finally {
			assertThat(this.executor.cancel(waiting.spec().runId()).state()).isEqualTo(RunState.CANCELLED);
		}
		this.onCall = prompt -> { throw new Stop(Stop.Kind.BUDGET_EXHAUSTED); };
		RunRecord budget = start("goal").record();
		assertThat(budget.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(budget.reason()).isEqualTo(RunEvent.Reason.BUDGET_EXHAUSTED);
	}

	@Test
	void capturedToolLookupWorksAcrossThreadsOnlyWithExplicitScopes() throws Exception {
		this.onTool = tools -> {
			RunContext captured = RunContext.current().orElseThrow();
			assertThat(RunContext.fromToolContext(tools)).contains(captured);
			ToolContext futureTools = tools;
			CompletableFuture<Boolean> signal = new CompletableFuture<>();
			Thread callback = new Thread(() -> {
				try {
					assertThat(RunContext.current()).isEmpty();
					RunContext carried = RunContext.fromToolContext(futureTools).orElseThrow();
					try (var scope = carried.open(); var turnScope = carried.turnContext().open()) {
						assertThat(RunContext.current()).contains(captured);
						assertThat(TurnContext.current()).contains(captured.turnContext());
					}
					assertThat(RunContext.current()).isEmpty();
					signal.complete(true);
				}
				catch (Throwable ex) {
					signal.completeExceptionally(ex);
				}
			});
			callback.start();
			try {
				assertThat(signal.get(10, TimeUnit.SECONDS)).isTrue();
			}
			catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		};
		assertThat(start("!tool inspect_context {}").record().state()).isEqualTo(RunState.COMPLETED);
	}

	@Test
	void invalidInputAndUnsafeRequestPathsNeverDispatch() throws Exception {
		assertThatThrownBy(() -> start(" ")).isInstanceOf(IOException.class);
		assertThat(this.bindings).hasValue(0);
		RunRecord first = interrupt().record();
		Files.delete(request(first));
		Files.createSymbolicLink(request(first), this.home.resolve("outside.json"));
		refusal(RunExecutor.Code.CORRUPT_REQUEST,
				() -> this.executor.resume(first.spec().runId(), SELECTED, this.cwd));
		assertThat(this.bindings).hasValue(1);
	}

	@Test
	void checkedStoreFailurePropagatesAndReleasesWriterWithoutFakeTerminal() throws Exception {
		this.onCall = prompt -> {
			try {
				RunContext.current().orElseThrow().recordUsage(RunEvent.UsageKind.MODEL_ROUND,
						"invalid/call-id", RunUsage.empty());
			}
			catch (RunStoreException ex) {
				throw new java.io.UncheckedIOException(ex);
			}
		};
		RunStoreException error = assertThrows(RunStoreException.class, () -> start("goal"));
		assertThat(error.code()).isEqualTo(RunStoreException.Code.INVALID_TRANSITION);
		RunId id = this.runs.list().getFirst();
		assertThat(this.runs.read(id).state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(this.runs.read(id).reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
		try (RunWriter writer = this.runs.openWriter(id)) {
			assertThat(writer.record().state()).isEqualTo(RunState.INTERRUPTED);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void absentUsageIsUnknownAndExplicitMeasuredZeroIsKnown(boolean absent) throws Exception {
		this.transform = response -> absent ? new ChatResponse(response.getResults())
				: new ChatResponse(response.getResults(), ChatResponseMetadata.builder()
						.usage(new DefaultUsage(0, 0)).build());
		RunRecord record = start("goal").record();
		assertThat(record.usage().knownTokens()).isZero();
		assertThat(record.usage().tokensUnknown()).isEqualTo(absent);
	}

	private RunRecord runsRead(RunId id) {
		try {
			return this.runs.read(id);
		}
		catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

	public class ContextTool {
		@Tool(name = "inspect_context", description = "Return captured context from an actual tool callback")
		public String inspect(ToolContext context) {
			onTool.accept(context);
			return "captured-context";
		}
	}

	private static final class MutableClock extends Clock {
		private volatile Instant value = AT;
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return this.value; }
	}
}
