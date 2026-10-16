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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.ConsoleApprovalBroker;
import com.example.miniagent.core.change.FileChangeStore;
import com.example.miniagent.core.delegation.ChildFailure;
import com.example.miniagent.core.delegation.ChildResult;
import com.example.miniagent.core.delegation.ChildRole;
import com.example.miniagent.core.delegation.ChildSpec;
import com.example.miniagent.core.delegation.ChildStatus;
import com.example.miniagent.core.delegation.DelegationEvidenceCollector;
import com.example.miniagent.core.delegation.DelegationPlan;
import com.example.miniagent.core.delegation.DelegationProperties;
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

/** Retained plan state under the wired evidence collector, as Spring assembles it; private durable stores. */
class DelegationRuntimeRetentionTest {
	/** DefaultDelegationRuntime retains at most this many plans. */
	private static final int RETAINED = 128;
	@TempDir Path directory;

	@Test void finishedPlansGiveWayOldestFirstSoThe129thDistinctPlanIsAccepted() throws Exception {
		var f = new Fixture();
		try (var writer = f.runs.create(f.parentSpec()); var runtime = f.runtime()) {
			var parent = f.startParent(writer);
			try (var captured = parent.open()) {
				var children = new HashMap<Integer, RunId>();
				for (int plan = 1; plan <= RETAINED; plan++) children.put(plan, f.childRun(f.execute(runtime, id(plan))));
				var accepted = f.execute(runtime, id(RETAINED + 1));
				assertThat(accepted.result().children()).extracting(ChildResult::status).containsExactly(ChildStatus.SUCCEEDED);
				children.put(RETAINED + 1, f.childRun(accepted));
				for (int plan : List.of(2, RETAINED, RETAINED + 1))
					assertThat(f.childRun(runtime.consume(f.parent.sessionId(), id(plan)))).isEqualTo(children.get(plan));
				assertThatThrownBy(() -> runtime.consume(f.parent.sessionId(), id(1)))
					.isInstanceOf(IllegalArgumentException.class).hasMessage("No current owned plan");
				// Eviction forgets transient plan state only; the durable child run stays as it was.
				assertThat(f.runs.read(children.get(1)).state()).isEqualTo(RunState.COMPLETED);
			}
		}
	}

	@Test void messagingPlanKeepsItsIdentitySoTheNextOldestFinishedPlanGivesWay() throws Exception {
		var f = new Fixture();
		try (var writer = f.runs.create(f.parentSpec()); var runtime = f.runtime()) {
			var parent = f.startParent(writer);
			try (var captured = parent.open()) {
				var messaging = f.childRun(f.execute(runtime, id(1)));
				assertThat(runtime.messageTarget(parent, id(1), "a").sender()).isEqualTo("parent");
				// Fillers fail at provider binding: finished plans without a child run, cheap to retain.
				f.bindingFailures.set(RETAINED);
				for (int plan = 2; plan <= RETAINED + 1; plan++)
					assertThat(f.execute(runtime, id(plan)).result().children().getFirst().failure().code())
						.isEqualTo(ChildFailure.Code.MODEL_UNAVAILABLE);
				assertThat(f.childRun(runtime.consume(f.parent.sessionId(), id(1)))).isEqualTo(messaging);
				assertThatThrownBy(() -> runtime.consume(f.parent.sessionId(), id(2)))
					.isInstanceOf(IllegalArgumentException.class).hasMessage("No current owned plan");
				assertThatThrownBy(() -> f.execute(runtime, id(1)))
					.isInstanceOf(IllegalStateException.class).hasMessage("Messaging plan identity already used");
			}
		}
	}

	@Test void retainedPlanLimitStillRefusesWhenEveryRetainedPlanReservesItsMessagingIdentity() throws Exception {
		var f = new Fixture();
		try (var writer = f.runs.create(f.parentSpec()); var runtime = f.runtime()) {
			var parent = f.startParent(writer);
			try (var captured = parent.open()) {
				for (int plan = 1; plan <= RETAINED; plan++) {
					f.execute(runtime, id(plan));
					runtime.messageTarget(parent, id(plan), "a");
				}
				int runs = f.runs.list().size();
				assertThatThrownBy(() -> f.execute(runtime, id(RETAINED + 1)))
					.isInstanceOf(IllegalStateException.class).hasMessage("Retained plan limit");
				assertThat(f.runs.list()).hasSize(runs);
				for (int plan : List.of(1, RETAINED))
					assertThat(runtime.consume(f.parent.sessionId(), id(plan))).isInstanceOf(DefaultDelegationRuntime.Completed.class);
			}
		}
	}

	private static String id(int plan) { return "plan-%03d".formatted(plan); }

	private final class Fixture {
		final Path home = Files.createTempDirectory(directory, "home-").toRealPath();
		final Path workspace = Files.createTempDirectory(directory, "work-").toRealPath();
		final ModelRef ref = new ModelRef("fake", "captured");
		final AtomicInteger bindingFailures = new AtomicInteger();
		final FileRunStore runs = new FileRunStore(home);
		final RunRequestStore requests = new RunRequestStore(home);
		final ToolScope childScope = new ToolScope(Set.of("read_file"), Set.of(workspace), Set.of(), Set.of(),
				false, false, false, false);
		final ToolScope parentScope = new ToolScope(Set.of("read_file"), Set.of(workspace), Set.of(), Set.of(),
				false, false, true, false);
		final RunBudget parentBudget = new RunBudget(Duration.ofMinutes(10), null, 10);
		final TurnContext parent;
		final ChildScopeFactory factory;

		Fixture() throws Exception {
			Files.writeString(workspace.resolve("source.txt"), "original");
			ModelProvider provider = new ModelProvider() {
				@Override public String providerId() { return "fake"; }
				@Override public List<ModelEntry> models() { return List.of(new ModelEntry(ref, "fake", null)); }
				@Override public ProviderAuth auth() { return () -> true; }
				@Override public ChatModel create(ModelRef model, Map<String, String> options) {
					if (bindingFailures.getAndUpdate(left -> Math.max(0, left - 1)) > 0)
						throw new DefaultDelegationRuntime.TransientFailure("provider binding temporarily unavailable");
					return new ChatModel() {
						@Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
							return ToolCallingChatOptions.builder().build();
						}
						@Override public ChatResponse call(Prompt prompt) {
							return new ChatResponse(List.of(new Generation(new AssistantMessage("model narrative"))),
									ChatResponseMetadata.builder().usage(new DefaultUsage(1, 0)).build());
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
			// Collected evidence binds the parent conversation as a UUID, as RunExecutor allocates it.
			parent = new TurnContext(UUID.randomUUID().toString(), UUID.randomUUID().toString(), workspace,
					registry.bind(new ModelSelection(ref)), UUID.randomUUID().toString(), UUID.randomUUID().toString());
			StagingToolInterceptor.Policy policy = () -> Map.of("read_file",
					new StagingToolInterceptor.Declaration(SideEffectClass.definitionDigest(read.getToolDefinition()),
						StagingToolInterceptor.Kind.READ, List.of()));
			var console = new AgentConsole(new BufferedReader(new StringReader("")),
					new PrintStream(OutputStream.nullOutputStream()), false);
			factory = new ChildScopeFactory(agent, home, runs, requests, selections,
					Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new ConsoleApprovalBroker(console),
					List.of(read), List.of(), List.of(new StagingToolInterceptor(() -> List.of(policy)),
						new RunBudgetInterceptor(), new ToolJournalInterceptor()), List.of(policy),
					List.of(() -> Map.of("read_file", SideEffectClass.definitionDigest(read.getToolDefinition()))),
					List.of());
		}
		DefaultDelegationRuntime runtime() throws Exception {
			return new DefaultDelegationRuntime(factory, new DelegationProperties(1, 1, 1, 1, Duration.ofSeconds(1), null, 10),
					new SessionRunLock()).evidence(new DelegationEvidenceCollector(runs, requests.evidence(),
						new FileChangeStore(home, workspace)));
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
		DefaultDelegationRuntime.Completed execute(DefaultDelegationRuntime runtime, String planId) {
			var child = new ChildSpec("a", "task " + planId, ChildRole.RESEARCH, null, Set.of(), childScope,
					new RunBudget(Duration.ofMillis(100), null, 1), 1, true);
			return completed(runtime.executeOutcome(parent, new DelegationPlan(planId, List.of(child), 1), parentScope,
					parentBudget, RunUsage::empty, "0".repeat(64), "0".repeat(64)));
		}
		RunId childRun(DefaultDelegationRuntime.Outcome outcome) {
			return completed(outcome).result().children().getFirst().runId();
		}
		DefaultDelegationRuntime.Completed completed(DefaultDelegationRuntime.Outcome outcome) {
			if (!(outcome instanceof DefaultDelegationRuntime.Completed completed))
				throw new AssertionError("Expected a completed plan: " + outcome);
			return completed;
		}
	}
}
