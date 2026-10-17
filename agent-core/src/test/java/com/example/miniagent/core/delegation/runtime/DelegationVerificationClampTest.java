package com.example.miniagent.core.delegation.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongUnaryOperator;

import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.ConsoleApprovalBroker;
import com.example.miniagent.core.change.FileChangeStore;
import com.example.miniagent.core.change.Fingerprinter;
import com.example.miniagent.core.change.StagedWorkspace;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.delegation.ChildEvidence;
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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

/**
 * verify_staged clamps the requested timeout to the VERIFY child's remaining wall. The clamped request is both
 * enforced and hashed into the evidence configuration, and every consumer compares against that same request,
 * so a clamped delegated verification must still be collected as verified success.
 */
class DelegationVerificationClampTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Duration SPENT_BEFORE_VERIFY = Duration.ofSeconds(20);
	private static final long WITHIN_WALL_MILLIS = 5000;
	/** Requests the VERIFY child's whole wall, which preflight still admits as within the reservation. */
	private static final LongUnaryOperator WHOLE_WALL = LongUnaryOperator.identity();
	@TempDir Path directory;

	@Test void verifyWithinTheRemainingWallIsCollectedAsVerifiedSuccess() throws Exception {
		var f = new Fixture();
		f.verifications.add(new Verification(Duration.ZERO, wall -> WITHIN_WALL_MILLIS));

		var result = f.execute();

		assertThat(f.remainingAtRequest).singleElement().satisfies(remaining ->
			assertThat(remaining).isGreaterThan(WITHIN_WALL_MILLIS));
		assertVerifiedSuccess(result);
	}

	@Test void verifyClampedToTheRemainingWallIsStillCollectedAsVerifiedSuccess() throws Exception {
		var f = new Fixture();
		f.verifications.add(new Verification(SPENT_BEFORE_VERIFY, WHOLE_WALL));

		var result = f.execute();

		// The request asked for the whole child wall after time was spent, so the tool must clamp it.
		assertThat(f.requested).singleElement().satisfies(requested ->
			assertThat(f.remainingAtRequest.getFirst()).isLessThan(requested));
		assertVerifiedSuccess(result);
	}

	@Test void repeatedVerifyWithADifferentClampIsStillCollectedAsVerifiedSuccess() throws Exception {
		var f = new Fixture();
		f.verifications.add(new Verification(Duration.ofSeconds(10), WHOLE_WALL));
		f.verifications.add(new Verification(Duration.ofSeconds(10), WHOLE_WALL));

		var result = f.execute();

		// Each repeat of the same configured request runs with less wall left, so the two clamps differ.
		assertThat(f.requested).hasSize(2);
		assertThat(f.remainingAtRequest.get(0)).isLessThan(f.requested.get(0));
		assertThat(f.remainingAtRequest.get(1)).isLessThan(f.remainingAtRequest.get(0));
		assertVerifiedSuccess(result);
	}

	private static void assertVerifiedSuccess(DelegationResult result) {
		var verify = result.children().stream().filter(c -> c.childId().equals("verify")).findFirst().orElseThrow();
		assertThat(verify.evidence()).noneMatch(ChildEvidence.Rejected.class::isInstance);
		assertThat(verify.status()).isEqualTo(ChildStatus.SUCCEEDED);
		assertThat(verify.evidence()).anyMatch(e -> e instanceof ChildEvidence.Verification v
			&& v.outcome() == VerificationRunner.Code.SUCCEEDED);
		assertThat(result.status()).isEqualTo(DelegationResult.Status.COMPLETE);
	}

	/** Model-scripted verify_staged call: active time spent first, then the timeout it requests from the child's wall. */
	record Verification(Duration spend, LongUnaryOperator timeoutMillis) {}

	/** Monotonic system time plus an offset the scripted model advances, instead of waiting in real time. */
	static final class OffsetClock extends Clock {
		final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return Clock.systemUTC().instant().plus(this.offset.get()); }
		void advance(Duration duration) { this.offset.accumulateAndGet(duration, Duration::plus); }
	}

	private final class Fixture {
		final Path home = Files.createTempDirectory(directory, "home-").toRealPath();
		final Path workspace = Files.createTempDirectory(directory, "work-").toRealPath();
		final OffsetClock clock = new OffsetClock();
		final FileRunStore runs = new FileRunStore(home);
		final RunRequestStore requests = new RunRequestStore(home);
		final FileChangeStore changes = new FileChangeStore(home, workspace);
		final ModelRef model = new ModelRef("fake", "clamp");
		final RunBudget budget = new RunBudget(Duration.ofMinutes(3), null, 30);
		final Map<String, AtomicInteger> rounds = new ConcurrentHashMap<>();
		final List<Verification> verifications = new CopyOnWriteArrayList<>();
		final List<Long> requested = new CopyOnWriteArrayList<>();
		final List<Long> remainingAtRequest = new CopyOnWriteArrayList<>();
		final RunExecutor executor;
		final DefaultDelegationRuntime runtime;
		final ToolScope authority;

		Fixture() throws Exception {
			Files.writeString(workspace.resolve("source.txt"), "before");
			// Fixture-only policy: runs argv directly; this test does not claim OS sandbox enforcement.
			var verifier = new DelegationVerificationTool(changes, (context, argv) -> new VerificationRunner.Launch(argv,
					Map.of(), Fingerprinter.sha256("fixture-delegated-clamp".getBytes(StandardCharsets.UTF_8))), clock);
			ToolDefinition definition = ToolDefinition.builder().name("write_file").description("fixture write_file")
					.inputSchema("{\"type\":\"object\"}").build();
			ToolCallback write = new ToolCallback() {
				@Override public ToolDefinition getToolDefinition() { return definition; }
				@Override public String call(String input) { throw new AssertionError("Explicit context required"); }
				@Override public String call(String input, ToolContext tools) {
					var args = JSON.readTree(input);
					try {
						Files.writeString(StagedWorkspace.from(tools).orElseThrow()
								.resolve(args.get("path").asString(), true, List.of()), args.get("content").asString());
					}
					catch (IOException ex) { throw new UncheckedIOException(ex); }
					return "written";
				}
			};
			List<ToolCallback> callbacks = List.of(write, verifier.callback());
			StagingToolInterceptor.Policy files = () -> Map.of("write_file", new StagingToolInterceptor.Declaration(
					SideEffectClass.definitionDigest(definition), StagingToolInterceptor.Kind.FILE_WRITE, List.of()));
			var policies = List.of(files, verifier.stagingPolicy());
			var reads = List.<SideEffectClass.Policy>of(verifier.readPolicy());
			var staging = new StagingToolInterceptor(() -> policies);
			ModelProvider provider = new ModelProvider() {
				@Override public String providerId() { return "fake"; }
				@Override public ProviderAuth auth() { return () -> true; }
				@Override public List<ModelEntry> models() { return List.of(new ModelEntry(model, "fake", null)); }
				@Override public ChatModel create(ModelRef ref, Map<String, String> options) {
					return new ChatModel() {
						@Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
						@Override public ChatResponse call(Prompt prompt) { return script(); }
					};
				}
			};
			var registry = new ModelRegistry(List.of(provider), m -> ChatClient.builder(m).build());
			var selections = new FileSessionModelStore(home);
			var console = new AgentConsole(new BufferedReader(new StringReader("")),
					new PrintStream(OutputStream.nullOutputStream()), false);
			var broker = new ConsoleApprovalBroker(console);
			var agent = new Agent(registry, selections, new ModelSelection(model), new AgentSession(), workspace,
					"system", callbacks, List.of(), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
			var factory = new ChildScopeFactory(agent, home, runs, requests, selections, clock, broker, callbacks,
					List.of(), List.of(staging, new RunBudgetInterceptor(), new ToolJournalInterceptor()), policies, reads,
					List.of());
			runtime = new DefaultDelegationRuntime(factory, new DelegationProperties(1, 4, 1, 1,
					Duration.ofMinutes(3), null, 30), new SessionRunLock());
			runtime.evidence(new DelegationEvidenceCollector(runs, requests.evidence(), changes));
			executor = new RunExecutor(agent, registry, selections, runs, requests, clock, List.of(), broker);
			authority = new ToolScope(Set.of("write_file", "verify_staged"), Set.of(workspace),
					Set.of(workspace), Set.of(), true, false, true, false);
		}

		DelegationPlan plan() {
			var childBudget = new RunBudget(Duration.ofSeconds(30), null, 5);
			var implement = new ToolScope(Set.of("write_file"), Set.of(workspace), Set.of(workspace), Set.of(),
					true, false, false, false);
			var verify = new ToolScope(Set.of("verify_staged"), Set.of(workspace), Set.of(), Set.of(),
					false, false, false, false);
			return new DelegationPlan("plan", List.of(
				new ChildSpec("implement", "implement", ChildRole.IMPLEMENT, model, Set.of(), implement, childBudget, 1, true),
				new ChildSpec("verify", "verify", ChildRole.VERIFY, model, Set.of("implement"), verify,
					childBudget, 1, true)), 1);
		}

		DelegationResult execute() throws IOException {
			var outcome = executor.startOperation("Delegate clamp plan", "original", new ModelSelection(model),
					workspace, budget, context -> runtime.executeOutcome(context.turnContext(), plan(), authority, budget,
							context::usage, "0".repeat(64), "0".repeat(64))).value();
			if (outcome instanceof DefaultDelegationRuntime.Completed completed) return completed.result();
			throw new AssertionError("Plan did not complete: " + outcome);
		}

		ChatResponse script() {
			var context = RunContext.current().orElseThrow();
			int round = rounds.computeIfAbsent(context.attemptId(), id -> new AtomicInteger()).getAndIncrement();
			if (context.spec().childId().equals("implement"))
				return round == 0 ? tool("write_file", "{\"path\":\"source.txt\",\"content\":\"after\"}") : answer();
			if (!context.spec().childId().equals("verify")) throw new AssertionError("Operator must not dispatch a model");
			if (round >= verifications.size()) return answer();
			var verification = verifications.get(round);
			// Only the VERIFY child's own model call spends this time, after the IMPLEMENT proof is collected.
			clock.advance(verification.spend());
			long wall = context.spec().budget().wall().toMillis();
			long timeout = verification.timeoutMillis().applyAsLong(wall);
			requested.add(timeout);
			remainingAtRequest.add(wall - context.usage().activeMillis());
			return tool("verify_staged", JSON.writeValueAsString(Map.of("childId", "implement",
					"argv", List.of("/usr/bin/true"), "timeoutMillis", timeout, "maxOutputBytes", 1024)));
		}

		ChatResponse tool(String name, String arguments) {
			return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", name, arguments))).build())),
				ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build());
		}

		ChatResponse answer() {
			return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build());
		}
	}
}
