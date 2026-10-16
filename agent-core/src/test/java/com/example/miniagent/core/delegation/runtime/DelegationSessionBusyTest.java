package com.example.miniagent.core.delegation.runtime;

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

/** One plan per parent conversation: the SessionRunLock lease taken by a running plan; event barriers only. */
class DelegationSessionBusyTest {
	@TempDir Path directory;

	@Test void secondPlanOnABusyConversationIsRefusedSessionBusyWhileTheFirstRunsOn() throws Exception {
		var f = new Fixture();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		f.script = prompt -> {
			if (prompt.getContents().contains("task first")) {
				entered.countDown();
				await(release);
			}
			return f.answer();
		};
		try (var runtime = f.runtime()) {
			var task = new FutureTask<>(() -> f.execute(runtime, f.parent, "first"));
			var coordinator = new Thread(task, "test-busy-coordinator");
			coordinator.start();
			try {
				await(entered);
				assertThatThrownBy(() -> f.execute(runtime, f.parent, "second"))
					.isInstanceOfSatisfying(SessionRunLock.Busy.class, busy -> {
						assertThat(busy.conversationId()).isEqualTo(f.parent.sessionId());
						assertThat(busy.runId()).isEqualTo(new RunId(f.parent.runId()));
					})
					.hasMessage("SESSION_BUSY: parent run=" + f.parent.runId());
				// The conversation lease precedes plan identity, so the running id is refused the same way.
				assertThatThrownBy(() -> f.execute(runtime, f.parent, "first")).isInstanceOf(SessionRunLock.Busy.class);
				assertThat(runtime.executions()).extracting(DefaultDelegationRuntime.Execution::planId,
					DefaultDelegationRuntime.Execution::status).containsExactly(tuple("first", ChildStatus.RUNNING));
				assertThat(runtime.pending(f.parent.sessionId(), "second")).isEmpty();
				assertThat(runtime.cancel(f.parent.sessionId(), "second")).isFalse();
				assertThat(f.calls).hasValue(1);
				assertThat(f.runs.list()).hasSize(1);
			}
			finally { release.countDown(); }
			var first = task.get(5, TimeUnit.SECONDS);
			coordinator.join(5000);
			assertThat(coordinator.isAlive()).isFalse();
			assertThat(first.children()).extracting(ChildResult::status).containsExactly(ChildStatus.SUCCEEDED);
			var second = f.execute(runtime, f.parent, "second");
			assertThat(second.children()).extracting(ChildResult::status).containsExactly(ChildStatus.SUCCEEDED);
			assertThat(f.runs.list()).hasSize(2);
			assertThat(f.calls).hasValue(2);
		}
	}

	@Test void busyLeaseIsPerConversationSoAnotherParentConversationDelegatesMeanwhile() throws Exception {
		var f = new Fixture();
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		f.script = prompt -> {
			if (prompt.getContents().contains("task first")) {
				entered.countDown();
				await(release);
			}
			return f.answer();
		};
		var other = new TurnContext("other", UUID.randomUUID().toString(), f.workspace, f.parent.binding(),
				UUID.randomUUID().toString(), UUID.randomUUID().toString());
		try (var runtime = f.runtime()) {
			var task = new FutureTask<>(() -> f.execute(runtime, f.parent, "first"));
			var coordinator = new Thread(task, "test-busy-coordinator");
			coordinator.start();
			try {
				await(entered);
				var independent = f.execute(runtime, other, "independent");
				assertThat(independent.children()).extracting(ChildResult::status).containsExactly(ChildStatus.SUCCEEDED);
				assertThat(f.runs.read(independent.children().getFirst().runId()).spec().parentConversationId())
					.isEqualTo("other");
				assertThat(runtime.executions()).extracting(DefaultDelegationRuntime.Execution::conversationId)
					.containsExactly(f.parent.sessionId());
			}
			finally { release.countDown(); }
			assertThat(task.get(5, TimeUnit.SECONDS).children()).extracting(ChildResult::status)
				.containsExactly(ChildStatus.SUCCEEDED);
			coordinator.join(5000);
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
		final ModelRef ref = new ModelRef("fake", "captured");
		final AtomicInteger calls = new AtomicInteger();
		final FileRunStore runs = new FileRunStore(home);
		final ToolScope childScope = new ToolScope(Set.of("read_file"), Set.of(workspace), Set.of(), Set.of(),
				false, false, false, false);
		final ToolScope parentScope = new ToolScope(Set.of("read_file"), Set.of(workspace), Set.of(), Set.of(),
				false, false, true, false);
		final RunBudget parentBudget = new RunBudget(Duration.ofSeconds(10), 100L, 10);
		volatile Function<Prompt, ChatResponse> script = prompt -> answer();
		final TurnContext parent;
		final ChildScopeFactory factory;

		Fixture() throws Exception {
			Files.writeString(workspace.resolve("source.txt"), "original");
			ModelProvider provider = new ModelProvider() {
				@Override public String providerId() { return "fake"; }
				@Override public List<ModelEntry> models() { return List.of(new ModelEntry(ref, "fake", null)); }
				@Override public ProviderAuth auth() { return () -> true; }
				@Override public ChatModel create(ModelRef model, Map<String, String> options) {
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
			return new DefaultDelegationRuntime(factory, new DelegationProperties(2, 2, 1, 1, Duration.ofSeconds(1), 100L, 10),
					new SessionRunLock());
		}
		DelegationResult execute(DefaultDelegationRuntime runtime, TurnContext owner, String planId) {
			var child = new ChildSpec("a", "task " + planId, ChildRole.RESEARCH, null, Set.of(), childScope,
					new RunBudget(Duration.ofMillis(100), 10L, 1), 1, true);
			return runtime.execute(owner, new DelegationPlan(planId, List.of(child), 1), parentScope,
					parentBudget, RunUsage::empty, "revision", "config");
		}
		ChatResponse answer() {
			return new ChatResponse(List.of(new Generation(new AssistantMessage("model narrative"))),
					ChatResponseMetadata.builder().usage(new DefaultUsage(2, 0)).build());
		}
	}
}
