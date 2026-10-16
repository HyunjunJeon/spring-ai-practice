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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.ConsoleApprovalBroker;
import com.example.miniagent.core.change.FileChangeStore;
import com.example.miniagent.core.change.StagedWorkspace;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.delegation.ChildFailure;
import com.example.miniagent.core.delegation.ChildRole;
import com.example.miniagent.core.delegation.ChildSpec;
import com.example.miniagent.core.delegation.ChildStatus;
import com.example.miniagent.core.delegation.DelegationEvidenceCollector;
import com.example.miniagent.core.delegation.DelegationPlan;
import com.example.miniagent.core.delegation.DelegationProperties;
import com.example.miniagent.core.delegation.DelegationResult;
import com.example.miniagent.core.delegation.RolePolicy;
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
 * verify_staged refusals inside actual child scopes of a managed parent. A probe tool hands the real
 * child ToolContext to the verify_staged callback; the counting launch policy never starts a process.
 */
class DelegationVerificationToolRefusalTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	@TempDir Path directory;

	@Test void nonVerifyChildIsDeniedAsRoleMutationWithoutLaunch() throws Exception {
		var f = new Fixture();
		f.steps.put("implement", List.of(f.write(), f.probe("source.txt")));
		f.probes.put("implement", tools -> f.attempt(tools, f.valid("implement")));

		f.execute();

		var denied = f.attempts.getFirst();
		assertThat(denied.error()).isInstanceOfSatisfying(RolePolicy.Denied.class,
				ex -> assertThat(ex.code()).isEqualTo(RolePolicy.Code.ROLE_MUTATION))
			.hasMessage("ROLE_MUTATION: verify_staged");
		assertThat(denied.launches()).isZero();
	}

	@Test void dependencyThatIsNotAProvenImplementChildNeverLaunches() throws Exception {
		var f = new Fixture();
		f.verifyProbe(tools -> {
			f.attempt(tools, f.valid("research"));
			f.attempt(tools, f.valid("implement"));
		});

		f.execute();

		var unproven = f.attempts.get(0);
		assertThat(unproven.error()).isInstanceOfSatisfying(RolePolicy.Denied.class,
				ex -> assertThat(ex.code()).isEqualTo(RolePolicy.Code.PATH_DENIED))
			.hasMessage("PATH_DENIED: Unproven IMPLEMENT dependency");
		assertThat(unproven.launches()).isZero();
		// The same scope reaches the launch policy once the dependency is the proven IMPLEMENT child.
		assertThat(f.attempts.get(1).output()).isEqualTo("UNSUPPORTED_SANDBOX");
		assertThat(f.attempts.get(1).launches()).isEqualTo(1);
	}

	@Test void invalidRunnerBoundsAndATimeoutAboveTheReservationAreRefusedBeforeStageEffects() throws Exception {
		var f = new Fixture();
		f.verifyProbe(tools -> {
			f.attempt(tools, f.args("implement", List.of(), 1000, 1024));
			f.attempt(tools, f.args("implement", List.of("/usr/bin/true"), 0, 1024));
			f.attempt(tools, f.args("implement", List.of("/usr/bin/true"), 1000, 0));
			f.attempt(tools, f.args("implement", List.of("/usr/bin/true"), Duration.ofDays(1).toMillis(), 1024));
			f.attempt(tools, f.valid("implement"));
		});

		f.execute();

		for (var bounds : f.attempts.subList(0, 3)) {
			assertThat(bounds.error()).isExactlyInstanceOf(IllegalArgumentException.class)
				.hasMessage("Invalid verification request");
		}
		assertThat(f.attempts.get(3).error()).isInstanceOfSatisfying(RolePolicy.Denied.class,
				ex -> assertThat(ex.code()).isEqualTo(RolePolicy.Code.PATH_DENIED))
			.hasMessage("PATH_DENIED: Process exceeds child reservation");
		// Only the implement proposal exists until a request passes preflight and imports into the VERIFY stage.
		assertThat(f.attempts.subList(0, 4)).allSatisfy(refused -> {
			assertThat(refused.launches()).isZero();
			assertThat(refused.proposals()).isEqualTo(1);
		});
		assertThat(f.attempts.get(4).launches()).isEqualTo(1);
		assertThat(f.attempts.get(4).proposals()).isEqualTo(2);
	}

	@Test void driftedLiveSourceOrDependencyStageIsRefusedUntilItIsRestored() throws Exception {
		var f = new Fixture();
		Files.createSymbolicLink(f.workspace.resolve("link"), Path.of("source.txt"));
		f.verifyProbe(tools -> {
			Path source = f.workspace.resolve("source.txt");
			Files.writeString(source, "human");
			f.attempt(tools, f.valid("implement"));
			Files.writeString(source, "before");

			Path staged = f.implementStage(tools).resolve("source.txt");
			Files.writeString(staged, "tampered");
			f.attempt(tools, f.valid("implement"));
			Files.writeString(staged, "after");

			Path relinked = f.implementStage(tools).resolve("link");
			Files.writeString(relinked, "not a link");
			f.attempt(tools, f.valid("implement"));
			Files.delete(relinked);

			f.attempt(tools, f.valid("implement"));
		});

		f.execute();

		assertRefusedIo(f.attempts.get(0), "Stale dependency proposal");
		assertRefusedIo(f.attempts.get(1), "Stale dependency stage");
		assertRefusedIo(f.attempts.get(2), "Dependency link replaced");
		assertThat(f.attempts.get(3).output()).isEqualTo("UNSUPPORTED_SANDBOX");
		assertThat(f.attempts.get(3).launches()).isEqualTo(1);
	}

	@Test void proposalPathOutsideTheVerifyReadRootsIsDenied() throws Exception {
		var f = new Fixture();
		f.steps.put("narrow", List.of(f.probe("docs/readme.txt")));
		f.probes.put("narrow", tools -> f.attempt(tools, f.valid("implement")));

		f.execute();

		var denied = f.attempts.getFirst();
		assertThat(denied.error()).isInstanceOfSatisfying(RolePolicy.Denied.class,
				ex -> assertThat(ex.code()).isEqualTo(RolePolicy.Code.PATH_DENIED))
			.hasMessage("PATH_DENIED: source.txt");
		assertThat(denied.launches()).isZero();
		assertThat(denied.proposals()).isEqualTo(1);
	}

	@Test void scopedPreflightFailsTheVerifyChildBeforeToolBudgetAndLaunch() throws Exception {
		var f = new Fixture();
		f.steps.put("verify", List.of(f.tool("verify_staged",
				f.args("implement", List.of("/usr/bin/true"), Duration.ofDays(1).toMillis(), 1024))));

		var result = f.execute();

		var verify = result.children().stream().filter(c -> c.childId().equals("verify")).findFirst().orElseThrow();
		assertThat(verify.status()).isEqualTo(ChildStatus.FAILED);
		assertThat(verify.failure().code()).isEqualTo(ChildFailure.Code.SCOPE_DENIED);
		assertThat(verify.usage().toolCalls()).isZero();
		assertThat(f.launches).hasValue(0);
		assertThat(f.changes.read().changes()).hasSize(1);
	}

	private static void assertRefusedIo(Attempt attempt, String message) {
		assertThat(attempt.error()).isInstanceOf(UncheckedIOException.class).cause()
			.isExactlyInstanceOf(IOException.class).hasMessage(message);
		assertThat(attempt.launches()).isZero();
	}

	/** One verify_staged call from a probe, with the counters observed right after it. */
	record Attempt(String output, RuntimeException error, int launches, int proposals) {}

	@FunctionalInterface interface Probe { void run(ToolContext tools) throws IOException; }

	private final class Fixture {
		final Path home = Files.createTempDirectory(directory, "home-").toRealPath();
		final Path workspace = Files.createTempDirectory(directory, "work-").toRealPath();
		final Clock clock = Clock.systemUTC();
		final FileRunStore runs = new FileRunStore(home);
		final RunRequestStore requests = new RunRequestStore(home);
		final FileChangeStore changes = new FileChangeStore(home, workspace);
		final ModelRef model = new ModelRef("fake", "refusal");
		final RunBudget budget = new RunBudget(Duration.ofMinutes(3), null, 30);
		final AtomicInteger launches = new AtomicInteger();
		final Map<String, AtomicInteger> rounds = new ConcurrentHashMap<>();
		final Map<String, List<ChatResponse>> steps = new ConcurrentHashMap<>();
		final Map<String, Probe> probes = new ConcurrentHashMap<>();
		final List<Attempt> attempts = Collections.synchronizedList(new ArrayList<>());
		final DelegationVerificationTool verifier;
		final RunExecutor executor;
		final DefaultDelegationRuntime runtime;
		final ToolScope authority;

		Fixture() throws Exception {
			Files.writeString(workspace.resolve("source.txt"), "before");
			Files.createDirectory(workspace.resolve("docs"));
			Files.writeString(workspace.resolve("docs/readme.txt"), "docs");
			steps.put("research", List.of(tool("read_file", "{\"path\":\"source.txt\"}")));
			steps.put("implement", List.of(write()));
			// Fixture-only policy: counts each process the runner would start and refuses to start it.
			verifier = new DelegationVerificationTool(changes, (context, argv) -> {
				launches.incrementAndGet();
				throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, null);
			}, clock);
			var read = callback("read_file", (input, tools) ->
					Files.readString(stage(tools).resolve(JSON.readTree(input).get("path").asString(), false, List.of())));
			var write = callback("write_file", (input, tools) -> {
				var args = JSON.readTree(input);
				Files.writeString(stage(tools).resolve(args.get("path").asString(), true, List.of()),
						args.get("content").asString());
				return "written";
			});
			var probe = callback("probe", (input, tools) -> {
				probes.get(RunContext.current().orElseThrow().spec().childId()).run(tools);
				return "probed";
			});
			List<ToolCallback> callbacks = List.of(read, write, probe, verifier.callback());
			StagingToolInterceptor.Policy files = () -> Map.of(
					"read_file", declaration(read, StagingToolInterceptor.Kind.READ),
					"write_file", declaration(write, StagingToolInterceptor.Kind.FILE_WRITE),
					"probe", declaration(probe, StagingToolInterceptor.Kind.READ));
			var policies = List.of(files, verifier.stagingPolicy());
			var reads = List.<SideEffectClass.Policy>of(() -> Map.of(
					"read_file", SideEffectClass.definitionDigest(read.getToolDefinition()),
					"probe", SideEffectClass.definitionDigest(probe.getToolDefinition())), verifier.readPolicy());
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
			authority = new ToolScope(Set.of("read_file", "write_file", "probe", "verify_staged"), Set.of(workspace),
					Set.of(workspace), Set.of(), true, false, true, false);
		}

		DelegationPlan plan() {
			var budget = new RunBudget(Duration.ofSeconds(30), null, 5);
			var research = new ToolScope(Set.of("read_file"), Set.of(workspace), Set.of(), Set.of(),
					false, false, false, false);
			var implement = new ToolScope(Set.of("write_file", "probe"), Set.of(workspace), Set.of(workspace), Set.of(),
					true, false, false, false);
			var verify = new ToolScope(Set.of("verify_staged", "probe"), Set.of(workspace), Set.of(), Set.of(),
					false, false, false, false);
			var narrow = new ToolScope(Set.of("verify_staged", "probe"), Set.of(workspace.resolve("docs")), Set.of(),
					Set.of(), false, false, false, false);
			return new DelegationPlan("plan", List.of(
				new ChildSpec("research", "research", ChildRole.RESEARCH, model, Set.of(), research, budget, 1, true),
				new ChildSpec("implement", "implement", ChildRole.IMPLEMENT, model, Set.of(), implement, budget, 1, true),
				new ChildSpec("verify", "verify", ChildRole.VERIFY, model, Set.of("research", "implement"), verify,
					budget, 1, false),
				new ChildSpec("narrow", "narrow", ChildRole.VERIFY, model, Set.of("implement"), narrow,
					budget, 1, false)), 1);
		}

		DelegationResult execute() throws IOException {
			var outcome = executor.startOperation("Delegate refusal plan", "original", new ModelSelection(model),
					workspace, budget, context -> runtime.executeOutcome(context.turnContext(), plan(), authority, budget,
							context::usage, "0".repeat(64), "0".repeat(64))).value();
			if (outcome instanceof DefaultDelegationRuntime.Completed completed) return completed.result();
			throw new AssertionError("Plan did not complete: " + outcome);
		}

		void verifyProbe(Probe probe) {
			steps.put("verify", List.of(probe("source.txt")));
			probes.put("verify", probe);
		}

		void attempt(ToolContext tools, String input) {
			String output = null;
			RuntimeException error = null;
			try { output = verifier.callback().call(input, tools); }
			catch (RuntimeException ex) { error = ex; }
			int proposals;
			try { proposals = changes.read().changes().size(); }
			catch (IOException ex) { throw new UncheckedIOException(ex); }
			attempts.add(new Attempt(output, error, launches.get(), proposals));
		}

		String valid(String childId) { return args(childId, List.of("/usr/bin/true"), 1000, 1024); }

		String args(String childId, List<String> argv, long timeoutMillis, int maxOutputBytes) {
			return JSON.writeValueAsString(Map.of("childId", childId, "argv", argv,
					"timeoutMillis", timeoutMillis, "maxOutputBytes", maxOutputBytes));
		}

		Path implementStage(ToolContext tools) {
			if (!(tools.getContext().get(ToolContextKeys.CHILD_SCOPE) instanceof ChildScopeFactory.Scope scope))
				throw new IllegalStateException("Child scope required");
			return scope.dependencies().stream().filter(d -> d.spec().childId().equals("implement")).findFirst()
					.orElseThrow().evidence().stagePolicy().root();
		}

		ChatResponse script() {
			var context = RunContext.current().orElseThrow();
			int round = rounds.computeIfAbsent(context.attemptId(), id -> new AtomicInteger()).getAndIncrement();
			var scripted = steps.getOrDefault(context.spec().childId(), List.of());
			return round < scripted.size() ? scripted.get(round) : answer();
		}

		ChatResponse write() { return tool("write_file", "{\"path\":\"source.txt\",\"content\":\"after\"}"); }

		ChatResponse probe(String path) { return tool("probe", "{\"path\":\"" + path + "\"}"); }

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

	@FunctionalInterface interface Body { String call(String input, ToolContext tools) throws IOException; }

	private static ToolCallback callback(String name, Body body) {
		ToolDefinition definition = ToolDefinition.builder().name(name).description("fixture " + name)
				.inputSchema("{\"type\":\"object\"}").build();
		return new ToolCallback() {
			@Override public ToolDefinition getToolDefinition() { return definition; }
			@Override public String call(String input) { throw new AssertionError("Explicit context required"); }
			@Override public String call(String input, ToolContext tools) {
				try { return body.call(input, tools); }
				catch (IOException ex) { throw new UncheckedIOException(ex); }
			}
		};
	}

	private static StagingToolInterceptor.Declaration declaration(ToolCallback callback, StagingToolInterceptor.Kind kind) {
		return new StagingToolInterceptor.Declaration(SideEffectClass.definitionDigest(callback.getToolDefinition()),
				kind, List.of());
	}

	private static StagedWorkspace stage(ToolContext tools) {
		return StagedWorkspace.from(tools).orElseThrow();
	}
}
