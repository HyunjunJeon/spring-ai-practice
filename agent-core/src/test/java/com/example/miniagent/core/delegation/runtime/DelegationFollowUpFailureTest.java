package com.example.miniagent.core.delegation.runtime;

import java.io.BufferedReader;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.ConsoleApprovalBroker;
import com.example.miniagent.core.delegation.ChildResult;
import com.example.miniagent.core.delegation.ChildRole;
import com.example.miniagent.core.delegation.ChildSpec;
import com.example.miniagent.core.delegation.ChildStatus;
import com.example.miniagent.core.delegation.DelegationPlan;
import com.example.miniagent.core.delegation.DelegationProperties;
import com.example.miniagent.core.delegation.DelegationResult;
import com.example.miniagent.core.delegation.SessionRunLock;
import com.example.miniagent.core.delegation.ToolScope;
import com.example.miniagent.core.execution.RunBudgetInterceptor;
import com.example.miniagent.core.execution.StagingToolInterceptor;
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
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.*;

/** A parent follow-up that throws must not strand a healthy child; real scopes and private durable stores. */
class DelegationFollowUpFailureTest {
	@TempDir Path directory;

	@Test void followUpThatThrowsBeforeTheChildRunLeavesTheChildAbleToContinue() throws Exception {
		var f = new Fixture();
		try (var writer = f.runs.create(f.parentSpec()); var runtime = f.runtime()) {
			var parent = f.startParent(writer);
			try (var captured = parent.open()) {
				ChildResult initial = f.execute(runtime).children().getFirst();
				assertThat(initial.status()).isEqualTo(ChildStatus.SUCCEEDED);
				int runs = f.runs.list().size();
				f.bindingFailures.set(1);
				assertThatThrownBy(() -> runtime.followUp(parent, "plan", "a", "first follow-up"))
					.isInstanceOf(DefaultDelegationRuntime.TransientFailure.class)
					.hasMessage("provider binding temporarily unavailable");
				assertThat(f.runs.list()).hasSize(runs);
				var next = runtime.followUp(parent, "plan", "a", "second follow-up");
				assertThat(next.record().state()).isEqualTo(RunState.COMPLETED);
				assertThat(next.record().spec().conversationId()).isEqualTo(initial.conversationId().toString());
				assertThat(next.record().spec().runId()).isNotEqualTo(initial.runId());
				assertThat(next.reply().text()).isEqualTo("model narrative");
				assertThat(f.calls).hasValue(2);
			}
		}
	}

	@Test void followUpWhoseModelCallThrowsKeepsItsFailedRunAndLaterRefusalComesFromUnknownUsage() throws Exception {
		var f = new Fixture();
		try (var writer = f.runs.create(f.parentSpec()); var runtime = f.runtime()) {
			var parent = f.startParent(writer);
			try (var captured = parent.open()) {
				ChildResult initial = f.execute(runtime).children().getFirst();
				f.script = prompt -> { throw new IllegalStateException("provider failed during follow-up"); };
				assertThatThrownBy(() -> runtime.followUp(parent, "plan", "a", "first follow-up"))
					.isInstanceOf(IllegalStateException.class).hasMessage("provider failed during follow-up");
				var conversation = f.runs.list().stream().map(id -> {
						try { return f.runs.read(id); }
						catch (RunStoreException ex) { throw new UncheckedIOException(ex); }
					}).filter(record -> record.spec().conversationId().equals(initial.conversationId().toString())).toList();
				assertThat(conversation).extracting(RunRecord::state)
					.containsExactlyInAnyOrder(RunState.COMPLETED, RunState.FAILED);
				assertThat(parent.usage().tokensUnknown()).isTrue();
				f.script = prompt -> f.answer(2);
				int calls = f.calls.get();
				// The parent meter refuses after the child-status guard, so the child is no longer stranded RUNNING.
				assertThatThrownBy(() -> runtime.followUp(parent, "plan", "a", "second follow-up"))
					.isInstanceOfSatisfying(Stop.class, stop -> assertThat(stop.kind()).isEqualTo(Stop.Kind.BUDGET_EXHAUSTED))
					.hasMessage("Token usage is unknown");
				assertThat(f.calls).hasValue(calls);
				assertThat(runtime.executions()).isEmpty();
			}
		}
	}

	private final class Fixture {
		final Path home = Files.createTempDirectory(directory, "home-").toRealPath();
		final Path workspace = Files.createTempDirectory(directory, "work-").toRealPath();
		final ModelRef ref = new ModelRef("fake", "captured");
		final AtomicInteger calls = new AtomicInteger(), bindingFailures = new AtomicInteger();
		final FileRunStore runs = new FileRunStore(home);
		final ToolScope childScope = new ToolScope(Set.of("read_file"), Set.of(workspace), Set.of(), Set.of(),
				false, false, false, false);
		final ToolScope parentScope = new ToolScope(Set.of("read_file"), Set.of(workspace), Set.of(), Set.of(),
				false, false, true, false);
		final RunBudget parentBudget = new RunBudget(Duration.ofSeconds(10), 100L, 10);
		volatile Function<Prompt, ChatResponse> script = prompt -> answer(2);
		final TurnContext parent;
		final ChildScopeFactory factory;

		Fixture() throws Exception {
			Files.writeString(workspace.resolve("source.txt"), "original");
			ModelProvider provider = new ModelProvider() {
				@Override public String providerId() { return "fake"; }
				@Override public List<ModelEntry> models() { return List.of(new ModelEntry(ref, "fake", null)); }
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
				@Override public String call(String input, ToolContext context) { return "original"; }
			};
			var registry = new ModelRegistry(List.of(provider), model -> ChatClient.builder(model).build());
			var selections = new FileSessionModelStore(home);
			var session = new AgentSession();
			session.switchTo("parent");
			var agent = new Agent(registry, selections, new ModelSelection(ref), session, workspace,
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
					List.of());
		}
		DefaultDelegationRuntime runtime() {
			return new DefaultDelegationRuntime(factory, new DelegationProperties(1, 1, 1, 2, Duration.ofSeconds(1), 100L, 10),
					new SessionRunLock());
		}
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
		DelegationResult execute(DefaultDelegationRuntime runtime) {
			var child = new ChildSpec("a", "task a", ChildRole.RESEARCH, null, Set.of(), childScope,
					new RunBudget(Duration.ofMillis(100), 10L, 1), 1, true);
			return runtime.execute(parent, new DelegationPlan("plan", List.of(child), 1), parentScope,
					parentBudget, RunUsage::empty, "revision", "config");
		}
		ChatResponse answer(int tokens) {
			return new ChatResponse(List.of(new Generation(new AssistantMessage("model narrative"))),
					ChatResponseMetadata.builder().usage(new DefaultUsage(tokens, 0)).build());
		}
	}
}
