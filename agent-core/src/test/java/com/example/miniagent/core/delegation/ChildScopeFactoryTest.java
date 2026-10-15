package com.example.miniagent.core.delegation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.ConsoleApprovalBroker;
import com.example.miniagent.core.change.StagedWorkspace;
import com.example.miniagent.core.change.StagingToolInterceptor;
import com.example.miniagent.core.model.*;
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
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ChildScopeFactoryTest {
	@TempDir Path directory;

	@Test void parentModelSelectionAndJsonlAreNotCopiedOrAltered() throws Exception {
		var f = new Fixture();
		byte[] parentBytes = "parent-private-history".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Path history = f.home.resolve("sessions/parent.jsonl");
		Files.createDirectories(history.getParent());
		Files.write(history, parentBytes);
		var captured = f.parent;
		f.agent.selectModel(new ModelSelection(f.b));
		f.script = prompt -> {
			assertThat(prompt.getInstructions()).noneMatch(message -> String.valueOf(message.getText()).contains("parent-private"));
			assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
				.extracting(callback -> callback.getToolDefinition().name()).containsExactly("read_file");
			return f.answer("child");
		};
		try (var scope = f.factory.create(captured, f.spec(ChildRole.RESEARCH, null, f.readScope()), f.readScope())) {
			var result = scope.call();
			assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
			assertThat(result.reply().text()).isEqualTo("child");
			assertThat(scope.context().binding().ref()).isEqualTo(f.a);
			assertThat(scope.context().binding().bindingId()).isNotEqualTo(captured.binding().bindingId());
			assertThat(scope.context().binding().client()).isNotSameAs(captured.binding().client());
			assertThat(scope.context().sessionId()).isNotEqualTo(captured.sessionId());
			assertThat(Set.of(scope.context().sessionId(), scope.context().runId(), scope.context().attemptId())).hasSize(3);
			UUID.fromString(scope.context().sessionId());
			assertThat(scope.metadata().parentRunId()).isEqualTo(captured.runId());
		}
		assertThat(f.agent.selectedModel().ref()).isEqualTo(f.b);
		assertThat(Files.readAllBytes(history)).isEqualTo(parentBytes);
		assertThat(f.session.id()).isEqualTo("parent");
	}

	@Test void mcpToolAddedAfterBindIsDenied() throws Exception {
		var f = new Fixture();
		f.script = prompt -> {
			f.dynamic.add(f.callback("new_mcp", false));
			return f.tool("new_mcp", "{\"path\":\"source.txt\"}");
		};
		try (var scope = f.factory.create(f.parent, f.spec(ChildRole.RESEARCH, null, f.readScope()), f.readScope())) {
			assertThatThrownBy(scope::call).hasStackTraceContaining("TOOL_NOT_ALLOWED");
			assertThat(scope.runContext().writer().record().usage().toolCalls()).isZero();
			assertThat(f.effects).hasValue(0);
		}
	}

	@Test void mcpRemovalAndSchemaDriftRevokeCapturedCallback() throws Exception {
		for (boolean removal : List.of(true, false)) {
			var f = new Fixture();
			f.script = prompt -> {
				f.dynamic.clear();
				if (!removal) f.dynamic.add(f.callback("read_file", true));
				return f.tool("read_file", "{\"path\":\"source.txt\"}");
			};
			try (var scope = f.factory.create(f.parent, f.spec(ChildRole.RESEARCH, null, f.readScope()), f.readScope())) {
				assertThatThrownBy(scope::call).hasStackTraceContaining("Tool definition drift");
				assertThat(f.effects).hasValue(0);
				assertThat(scope.runContext().writer().record().usage().toolCalls()).isZero();
			}
		}
	}

	@Test void mcpCallbackReplacedWithSameSchemaIsDenied() throws Exception {
		var f = new Fixture();
		ToolCallback original = f.dynamic.getFirst();
		ToolCallback replacement = f.callback("read_file", false);
		assertThat(replacement).isNotSameAs(original);
		assertThat(SideEffectClass.definitionDigest(replacement.getToolDefinition()))
			.isEqualTo(SideEffectClass.definitionDigest(original.getToolDefinition()));
		f.script = prompt -> {
			f.dynamic.set(0, replacement);
			return f.tool("read_file", "{\"path\":\"source.txt\"}");
		};
		try (var scope = f.factory.create(f.parent, f.spec(ChildRole.RESEARCH, null, f.readScope()), f.readScope())) {
			assertThatThrownBy(scope::call).hasStackTraceContaining("TOOL_NOT_ALLOWED");
			assertThat(scope.runContext().writer().record().usage().toolCalls()).isZero();
			assertThat(f.permission).hasValue(0);
			assertThat(f.effects).hasValue(0);
		}
	}

	@Test void implementWritesOnlyOwnerStageAndExactContextCannotBeBypassed() throws Exception {
		var f = new Fixture();
		f.script = prompt -> f.rounds.getAndIncrement() == 0
			? f.tool("write_file", "{\"path\":\"source.txt\",\"content\":\"child\"}") : f.answer("done");
		var authority = new ToolScope(Set.of("write_file"), Set.of(f.workspace), Set.of(f.workspace), Set.of(),
			true, false, false, false);
		try (var scope = f.factory.create(f.parent, f.spec(ChildRole.IMPLEMENT, f.b, authority), authority)) {
			assertThat(scope.call().record().state()).isEqualTo(RunState.COMPLETED);
			assertThat(scope.context().binding().ref()).isEqualTo(f.b);
			assertThat(Files.readString(f.workspace.resolve("source.txt"))).isEqualTo("original");
			assertThat(Files.readString(f.written)).isEqualTo("child");
			var callbacks = f.seenCallbacks;
			assertThatThrownBy(() -> callbacks.getFirst().call("{}")).isInstanceOf(RolePolicy.Denied.class);
			assertThatThrownBy(() -> callbacks.getFirst().call("{}", new ToolContext(Map.of())))
				.isInstanceOf(RolePolicy.Denied.class);
			assertThatThrownBy(() -> scope.context().binding().client().prompt("bypass").call().content())
				.hasStackTraceContaining("captured child context");
			assertThatThrownBy(() -> scope.context().binding().chatModel().call("bypass"))
				.hasStackTraceContaining("active captured child owner");
		}
		assertThat(f.effects).hasValue(1);
	}

	@Test void unclassifiedExternalToolAndShellAreDeniedBeforeBudget() throws Exception {
		for (String name : List.of("mcp_external", "bash")) {
			var f = new Fixture();
			f.dynamic.add(f.callback(name, false));
			var authority = new ToolScope(Set.of(name), Set.of(f.workspace), Set.of(), Set.of(),
				false, false, false, false);
			f.script = prompt -> f.tool(name, "{}");
			try (var scope = f.factory.create(f.parent, f.spec(ChildRole.RESEARCH, null, authority), authority)) {
				assertThatThrownBy(scope::call).hasStackTraceContaining("UNKNOWN_EFFECT");
				assertThat(scope.runContext().writer().record().usage().toolCalls()).isZero();
				assertThat(f.permission).hasValue(0);
				assertThat(f.effects).hasValue(0);
			}
		}
	}

	@Test void researchMutationAndVerifySourceMutationRefuseBeforeBudgetAndPermission() throws Exception {
		for (ChildRole role : List.of(ChildRole.RESEARCH, ChildRole.VERIFY)) {
			var f = new Fixture();
			var authority = new ToolScope(Set.of("write_file"), Set.of(f.workspace), Set.of(),
				role == ChildRole.VERIFY ? Set.of(f.workspace.resolve("generated")) : Set.of(),
				role == ChildRole.VERIFY, false, false, false);
			f.script = prompt -> f.tool("write_file", "{\"path\":\"source.txt\",\"content\":\"child\"}");
			try (var scope = f.factory.create(f.parent, f.spec(role, null, authority), authority)) {
				assertThatThrownBy(scope::call).hasStackTraceContaining(role == ChildRole.RESEARCH ? "ROLE_MUTATION" : "PATH_DENIED");
				assertThat(scope.runContext().writer().record().usage().toolCalls()).isZero();
				assertThat(f.permission).hasValue(0);
				assertThat(f.effects).hasValue(0);
			}
		}
	}

	@Test void verifyMayWriteOnlyExplicitGeneratedOutput() throws Exception {
		var f = new Fixture();
		var authority = new ToolScope(Set.of("write_file"), Set.of(f.workspace), Set.of(),
			Set.of(f.workspace.resolve("generated")), true, false, false, false);
		f.script = prompt -> f.rounds.getAndIncrement() == 0
			? f.tool("write_file", "{\"path\":\"generated/result.txt\",\"content\":\"output\"}") : f.answer("done");
		try (var scope = f.factory.create(f.parent, f.spec(ChildRole.VERIFY, null, authority), authority)) {
			assertThat(scope.call().record().state()).isEqualTo(RunState.COMPLETED);
			assertThat(Files.readString(f.written)).isEqualTo("output");
			assertThat(Files.exists(f.workspace.resolve("generated/result.txt"))).isFalse();
		}
	}

	@Test void exclusionsAndEscapingSymlinkRemainDeniedBeforePermission() throws Exception {
		for (String path : List.of(".env", "auth/token", "link/escape.txt", "../outside")) {
			var f = new Fixture();
			Path outside = Files.createTempDirectory(directory, "outside-");
			boolean escapingLink = path.equals("link/escape.txt");
			if (escapingLink) Files.createSymbolicLink(f.workspace.resolve("link"), outside);
			f.script = prompt -> f.tool("read_file", "{\"path\":\"" + path + "\"}");
			try (var scope = f.factory.create(f.parent, f.spec(ChildRole.RESEARCH, null, f.readScope()), f.readScope())) {
				if (escapingLink) {
					// Workspace fingerprint refuses an escaping link before an attempt is bound.
					assertThatThrownBy(scope::call).isInstanceOf(IOException.class);
					assertThat(scope.bound()).isEmpty();
				}
				else {
					assertThatThrownBy(scope::call).isInstanceOf(RuntimeException.class);
					assertThat(scope.runContext().writer().record().usage().toolCalls()).isZero();
				}
				assertThat(f.permission).hasValue(0);
				assertThat(f.effects).hasValue(0);
			}
		}
	}

	@Test void sharedParentBoundariesObserveExistingMeterAndCanStopBeforeEffects() throws Exception {
		var f = new Fixture();
		var modelChecks = new AtomicInteger();
		var observations = new ArrayList<RunUsage>();
		var deltas = new ArrayList<RunEvent.UsageRecorded>();
		var boundaries = new ChildScopeFactory.Boundaries() {
			@Override public void beforeModel(ChildScopeFactory.Metadata metadata, RunContext context) {
				modelChecks.incrementAndGet();
				assertThat(context.turnContext().sessionId()).isNotEqualTo(metadata.parentConversationId());
			}
			@Override public void beforeTool(ChildScopeFactory.Metadata metadata, RunContext context) {
				throw new RunExecutor.Stop(RunExecutor.Stop.Kind.BUDGET_EXHAUSTED);
			}
			@Override public void observed(ChildScopeFactory.Metadata metadata, RunContext context) {
				observations.add(context.writer().record().usage());
			}
			@Override public void usage(ChildScopeFactory.Metadata metadata, RunContext context, RunEvent.UsageRecorded delta) {
				assertThat(delta.attemptId()).isEqualTo(context.attemptId());
				deltas.add(delta);
			}
		};
		f.script = prompt -> f.tool("read_file", "{\"path\":\"source.txt\"}");
		try (var scope = f.factory.create(f.parent, f.spec(ChildRole.RESEARCH, null, f.readScope()),
				f.readScope(), boundaries)) {
			assertThat(scope.call().record().state()).isEqualTo(RunState.INTERRUPTED);
			assertThat(scope.runContext().writer().record().usage().toolCalls()).isZero();
			assertThat(modelChecks).hasValue(1);
			assertThat(observations).isNotEmpty();
			assertThat(deltas).extracting(RunEvent.UsageRecorded::callId).doesNotHaveDuplicates();
			assertThat(deltas.stream().map(RunEvent.UsageRecorded::usage).reduce(RunUsage.empty(), RunUsage::plus))
				.isEqualTo(scope.runContext().writer().record().usage());
			assertThat(f.effects).hasValue(0);
		}
	}

	@Test void childCannotAcquireParentOnlyToolsOrProviderDefaultCallbacks() throws Exception {
		var f = new Fixture();
		var parentAuthority = new ToolScope(Set.of("delegate"), Set.of(f.workspace), Set.of(), Set.of(),
			false, false, false, false);
		assertThatThrownBy(() -> f.factory.create(f.parent,
			f.spec(ChildRole.RESEARCH, null, parentAuthority), parentAuthority))
			.isInstanceOf(RolePolicy.Denied.class).hasMessageContaining("PARENT_ONLY");
		ChatModel model = new ChatModel() {
			@Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
				return ToolCallingChatOptions.builder().toolCallbacks(f.dynamic).build();
			}
			@Override public ChatResponse call(Prompt prompt) { throw new AssertionError("Must not call"); }
		};
		assertThatThrownBy(() -> new AgentChatClientFactory.ScopedAssembly("system", List.of(), List.of(),
			ToolCallingAdvisor.DEFAULT_ORDER).create(model)).hasMessageContaining("default tools");
	}

	@Test void messagesReuseConversationAndOptionsButNotRunsAndKeepDurableParentTotals() throws Exception {
		var histories = new java.util.concurrent.ConcurrentHashMap<String, List<org.springframework.ai.chat.messages.Message>>();
		org.springframework.ai.chat.memory.ChatMemory memory = new org.springframework.ai.chat.memory.ChatMemory() {
			@Override public void add(String id, List<org.springframework.ai.chat.messages.Message> messages) {
				histories.compute(id, (key, previous) -> {
					var all = new ArrayList<>(previous == null ? List.<org.springframework.ai.chat.messages.Message>of() : previous);
					all.addAll(messages); return List.copyOf(all);
				});
			}
			@Override public List<org.springframework.ai.chat.messages.Message> get(String id) {
				return histories.getOrDefault(id, List.of());
			}
			@Override public void clear(String id) { histories.remove(id); }
		};
		var f = new Fixture(List.of((model, authority) -> List.of(
			org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.builder(memory).build())));
		var modelCalls = new AtomicInteger();
		f.script = prompt -> {
			int call = modelCalls.incrementAndGet();
			assertThat(prompt.getInstructions().stream().filter(m ->
				m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER).count()).isEqualTo(call);
			return f.answer("response");
		};
		var selections = new FileSessionModelStore(f.home);
		var runs = new FileRunStore(f.home);
		var selected = new ModelSelection(f.a, Map.of("effort", "high"));
		var parentExecutor = new RunExecutor(f.agent, f.agent.modelRegistry(), selections, runs,
			new RunRequestStore(f.home), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), List.of());
		var budget = new RunBudget(java.time.Duration.ofMinutes(2), 6L, 10);
		var child = new ChildSpec("child", "initial request", ChildRole.RESEARCH, null, Set.of(), f.readScope(),
			new RunBudget(java.time.Duration.ofSeconds(20), 6L, 4), 1, true);
		var authority = new ToolScope(f.readScope().toolNames(), f.readScope().readRoots(), Set.of(), Set.of(),
			false, false, true, false);
		f.modelOptions.clear();
		try (var runtime = new DefaultDelegationRuntime(f.factory, DelegationProperties.defaults(), new SessionRunLock())) {
			var service = new com.example.miniagent.core.messaging.MessageService(
				new com.example.miniagent.core.messaging.MessageStore(f.home), runtime);
			var ids = new ArrayList<RunId>();
			var result = parentExecutor.startOperation("scoped conversation", "operator", selected, f.workspace, budget, owner -> {
				try {
					owner.recordUsage(RunEvent.UsageKind.MODEL_ROUND, "no-parent-model", RunUsage.empty());
					var initial = runtime.execute(owner.turnContext(), new DelegationPlan("thread", List.of(child), 1),
						authority, budget, owner::usage, "a".repeat(64), "b".repeat(64)).children().getFirst();
					ids.add(initial.runId());
					String conversation = initial.conversationId().toString();
					assertThatThrownBy(() -> runtime.messageOperator("foreign", "thread"))
						.isInstanceOf(IllegalArgumentException.class);
					assertThatThrownBy(() -> service.read(owner, "foreign", "child", 0))
						.isInstanceOf(IllegalArgumentException.class);
					var one = service.send(owner, "thread", "child", "message-one", 0, "follow-up one");
					assertThat(one.state()).isEqualTo(com.example.miniagent.core.messaging.MessageStore.State.DELIVERED);
					ids.add(new RunId(one.executionRun()));
					assertThat(one.originalRun()).isEqualTo(initial.runId().value());
					assertThat(runs.read(ids.getLast()).spec().conversationId()).isEqualTo(conversation);
					var two = service.send(owner, "thread", "child", "message-two", 2, "follow-up two");
					ids.add(new RunId(two.executionRun()));
					assertThat(two.state()).isEqualTo(com.example.miniagent.core.messaging.MessageStore.State.DELIVERED);
					assertThat(two.originalRun()).isEqualTo(one.executionRun());
					assertThat(runs.read(ids.getLast()).spec().conversationId()).isEqualTo(conversation);
					assertThat(ids).doesNotHaveDuplicates();
					int bindings = f.modelOptions.size();
					assertThat(service.send(owner, "thread", "child", "message-one", 0, "follow-up one")).isEqualTo(one);
					assertThatThrownBy(() -> service.send(owner, "thread", "child", "message-one", 4, "different"))
						.hasMessageContaining("Idempotency ID reused");
					var blocked = service.send(owner, "thread", "child", "message-three", 4, "must not bind");
					assertThat(blocked.state()).isEqualTo(com.example.miniagent.core.messaging.MessageStore.State.BLOCKED);
					assertThat(f.modelOptions).hasSize(bindings);
					assertThat(f.modelOptions).allMatch(options -> options.equals(Map.of("effort", "high")));
					assertThat(owner.usage().knownTokens()).isEqualTo(6);
					assertThat(runs.read(owner.spec().runId()).usage().knownTokens()).isEqualTo(6);
					owner.reconcileChildUsage(runs);
					assertThat(owner.usage().knownTokens()).isEqualTo(6);
					assertThatThrownBy(owner::beforeModel).isInstanceOf(RunExecutor.Stop.class);
					assertThatThrownBy(() -> runtime.execute(owner.turnContext(),
						new DelegationPlan("another", List.of(child), 1), authority, budget, owner::usage,
						"a".repeat(64), "b".repeat(64))).isInstanceOf(IllegalArgumentException.class);
					throw new RunExecutor.Stop(RunExecutor.Stop.Kind.BUDGET_EXHAUSTED);
				}
				catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
			});
			assertThat(result.record().state()).isEqualTo(RunState.INTERRUPTED);
			assertThat(result.record().usage().knownTokens()).isEqualTo(6);
			var resumed = parentExecutor.resumeOperation(result.record().spec().runId(), selected, f.workspace,
				RunExecutor.ResumeOptions.ordinary(), owner -> {
					assertThat(owner.usage().knownTokens()).isEqualTo(6);
					assertThat(runtime.messageTarget(owner, "thread", "child").sender()).isEqualTo("parent");
					assertThatThrownBy(() -> runtime.followUp(owner, "thread", "child", "no budget"))
						.isInstanceOf(RunExecutor.Stop.class);
					assertThat(owner.usage().knownTokens()).isEqualTo(6);
					return true;
				});
			assertThat(resumed.record().usage().knownTokens()).isEqualTo(6);
			assertThat(new FileRunStore(f.home).read(result.record().spec().runId()).usage().knownTokens()).isEqualTo(6);
			assertThatThrownBy(() -> runtime.messageOperator("operator", "thread"))
				.isInstanceOf(IllegalStateException.class);
		}
	}

	@Test void implementationContinuationRefusesBeforeAnyProviderBinding() throws Exception {
		var f = new Fixture();
		var authority = new ToolScope(Set.of("write_file"), Set.of(f.workspace), Set.of(f.workspace), Set.of(),
			true, false, false, false);
		var child = f.spec(ChildRole.IMPLEMENT, null, authority);
		try (var scope = f.factory.create(f.parent, child, authority)) {
			scope.call();
			int bindings = f.modelOptions.size();
			assertThatThrownBy(() -> scope.continuation(child, new ChildScopeFactory.Boundaries() {}))
				.hasMessageContaining("trusted staged proposal restore");
			assertThat(f.modelOptions).hasSize(bindings);
		}
	}

	private final class Fixture {
		final Path home = Files.createTempDirectory(directory, "home-").toRealPath();
		final Path workspace = Files.createTempDirectory(directory, "work-").toRealPath();
		final ModelRef a = new ModelRef("fake", "a"), b = new ModelRef("fake", "b");
		final AgentSession session = new AgentSession();
		final List<ToolCallback> dynamic = new ArrayList<>();
		final List<Map<String, String>> modelOptions = new ArrayList<>();
		final AtomicInteger effects = new AtomicInteger(), permission = new AtomicInteger(), rounds = new AtomicInteger();
		Function<Prompt, ChatResponse> script = prompt -> answer("done");
		List<ToolCallback> seenCallbacks = List.of();
		Path written;
		final Agent agent;
		final TurnContext parent;
		final ChildScopeFactory factory;
		Fixture() throws Exception { this(List.of()); }
		Fixture(List<ChildScopeFactory.AdvisorContribution> contributions) throws Exception {
			Files.writeString(workspace.resolve("source.txt"), "original");
			session.switchTo("parent");
			dynamic.add(callback("read_file", false));
			dynamic.add(callback("write_file", false));
			ToolCallbackProvider tools = () -> dynamic.toArray(ToolCallback[]::new);
			ModelProvider provider = new ModelProvider() {
				@Override public String providerId() { return "fake"; }
				@Override public List<ModelEntry> models() { return List.of(new ModelEntry(a, "fake", null), new ModelEntry(b, "fake", null)); }
				@Override public ProviderAuth auth() { return () -> true; }
				@Override public Set<String> supportedOptions() { return Set.of("effort"); }
				@Override public ChatModel create(ModelRef ref, Map<String, String> options) {
					modelOptions.add(Map.copyOf(options));
					return new ChatModel() {
						@Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
						@Override public ChatResponse call(Prompt prompt) {
							seenCallbacks = ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks();
							return script.apply(prompt);
						}
					};
				}
			};
			var registry = new ModelRegistry(List.of(provider), model -> ChatClient.builder(model).defaultTools(tools).build());
			var selections = new FileSessionModelStore(home);
			agent = new Agent(registry, selections, new ModelSelection(a), session, workspace,
				"system", List.of(), List.of(tools), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
			parent = new TurnContext("parent", UUID.randomUUID().toString(), workspace, registry.bind(new ModelSelection(a)),
				UUID.randomUUID().toString(), UUID.randomUUID().toString());
			var console = new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(java.io.OutputStream.nullOutputStream()), false);
			StagingToolInterceptor.Policy stagePolicy = () -> {
				var map = new java.util.HashMap<String, StagingToolInterceptor.Declaration>();
				dynamic.stream().filter(callback -> Set.of("read_file", "write_file")
					.contains(callback.getToolDefinition().name())).forEach(callback -> map.put(callback.getToolDefinition().name(),
					new StagingToolInterceptor.Declaration(SideEffectClass.definitionDigest(callback.getToolDefinition()),
						callback.getToolDefinition().name().equals("write_file") ? StagingToolInterceptor.Kind.FILE_WRITE
							: StagingToolInterceptor.Kind.READ, List.of())));
				return Map.copyOf(map);
			};
			var staging = new StagingToolInterceptor(() -> List.of(stagePolicy));
			ToolInterceptor permissions = (invocation, chain) -> {
				permission.incrementAndGet();
				return chain.proceed(invocation);
			};
			factory = new ChildScopeFactory(agent, home, new FileRunStore(home), new RunRequestStore(home),
				selections, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new ConsoleApprovalBroker(console),
				List.of(), List.of(tools), List.of(staging, new RunBudgetInterceptor(), permissions, new ToolJournalInterceptor()),
				List.of(stagePolicy), List.of(() -> Map.of("read_file",
					SideEffectClass.definitionDigest(dynamic.getFirst().getToolDefinition()))), contributions);
		}
		ToolScope readScope() { return new ToolScope(Set.of("read_file"), Set.of(workspace), Set.of(), Set.of(), false, false, false, false); }
		ChildSpec spec(ChildRole role, ModelRef model, ToolScope scope) {
			return new ChildSpec("child", "perform child task", role, model, Set.of(), scope, RunBudget.defaults(), 1, true);
		}
		ChatResponse answer(String text) {
			return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build());
		}
		ChatResponse tool(String name, String args) {
			return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", name, args))).build())),
				ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build());
		}
		ToolCallback callback(String name, boolean drift) {
			return new ToolCallback() {
				final ToolDefinition definition = ToolDefinition.builder().name(name).description(drift ? "changed" : "local file tool")
					.inputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}}}").build();
				@Override public ToolDefinition getToolDefinition() { return definition; }
				@Override public String call(String input) { throw new AssertionError("Context required"); }
				@Override public String call(String input, ToolContext tools) {
					try {
						effects.incrementAndGet();
						var args = JsonMapper.builder().build().readTree(input);
						boolean write = name.equals("write_file");
						Path target = StagedWorkspace.from(tools).orElseThrow().resolve(args.get("path").asString(), write, List.of());
						if (write) {
							written = target;
							Files.createDirectories(target.getParent());
							Files.writeString(target, args.get("content").asString());
							return "written";
						}
						return Files.readString(target);
					}
					catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
				}
			};
		}
	}
}
