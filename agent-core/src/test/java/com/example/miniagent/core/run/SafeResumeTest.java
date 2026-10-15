package com.example.miniagent.core.run;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.example.miniagent.core.*;
import com.example.miniagent.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import static org.assertj.core.api.Assertions.*;

class SafeResumeTest {
	@TempDir Path temp;

	static final class Fixture {
		final Path home, cwd;
		final FileRunStore runs;
		final RunRequestStore requests;
		final RunExecutor executor;
		final Agent agent;
		final DriftGate gate;
		final ModelSelection selected = new ModelSelection(new ModelRef("test", "one"));
		final ToolJournalInterceptor journal = new ToolJournalInterceptor();
		final ToolDefinition tool = ToolDefinition.builder().name("fixture_tool").description("Real filesystem fixture")
				.inputSchema("{\"type\":\"object\"}").build();
		final AtomicReference<ToolDefinition> currentTool = new AtomicReference<>(this.tool);
		final AtomicReference<Consumer<Prompt>> action = new AtomicReference<>(p -> {});
		final AtomicReference<String> wire = new AtomicReference<>("test");
		final AtomicReference<String> config = new AtomicReference<>("one");
		Fixture(Path root, boolean readOnly) throws Exception {
			this.home = Files.createDirectory(root.resolve("home"));
			this.cwd = Files.createDirectory(root.resolve("workspace")).toRealPath();
			this.runs = new FileRunStore(this.home);
			this.requests = new RunRequestStore(this.home);
			var selections = new FileSessionModelStore(this.home);
			ModelProvider provider = new ModelProvider() {
				public String providerId() { return "test"; }
				public ProviderAuth auth() { return () -> true; }
				public List<ModelEntry> models() { return List.of(new ModelEntry(selected.ref(), wire.get(), null)); }
				public ChatModel create(ModelRef ref, Map<String, String> options) {
					return prompt -> {
						action.get().accept(prompt);
						return new ChatResponse(List.of(new Generation(new AssistantMessage("model-response"))));
					};
				}
			};
			var registry = new ModelRegistry(List.of(provider), model -> ChatClient.builder(model).build());
			ToolCallback callback = new ToolCallback() {
				public ToolDefinition getToolDefinition() { return currentTool.get(); }
				public String call(String input) { throw new AssertionError("Use explicit unit chain"); }
			};
			this.agent = new Agent(registry, selections, this.selected, new AgentSession(), this.cwd,
					"fixture", List.of(callback), List.of(), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
			this.gate = new DriftGate(this.requests.evidence(), readOnly
					? List.of(() -> Map.of(this.tool.name(), SideEffectClass.definitionDigest(this.tool))) : List.of(), this.config::get);
			this.executor = new RunExecutor(this.agent, registry, selections, this.runs, this.requests,
					Clock.systemUTC(), List.of(), null, this.gate);
		}
		RunRecord start() throws Exception {
			return this.executor.start("Read or change fixture", "parent", this.selected, this.cwd, RunBudget.defaults()).record();
		}
		RunRecord interrupted() throws Exception {
			this.action.set(p -> { throw new Stop(Stop.Kind.INTERRUPTED); });
			RunRecord record = start();
			this.action.set(p -> {});
			return record;
		}
		String invoke(ToolInterceptor.Chain chain) {
			RunContext run = RunContext.current().orElseThrow();
			return this.journal.intercept(new ToolInterceptor.ToolInvocation(this.currentTool.get(), "{}",
					new ToolContext(Map.of(RunContext.TOOL_CONTEXT_KEY, run))), chain);
		}
	}

	@Test void unknownMutatingRefusedWithoutFlag() throws Exception {
		var f = new Fixture(this.temp, false);
		f.action.set(p -> f.invoke(i -> { throw new Stop(Stop.Kind.INTERRUPTED); }));
		RunRecord record = f.start();
		String before = f.runs.export(record.spec().runId()).eventsLog();
		assertThatThrownBy(() -> f.executor.resume(record.spec().runId(), f.selected, f.cwd))
				.isInstanceOfSatisfying(RunExecutor.Failure.class, e -> assertThat(e.code()).isEqualTo(RunExecutor.Code.UNKNOWN_EFFECTS));
		assertThat(f.runs.export(record.spec().runId()).eventsLog()).isEqualTo(before);
		assertThat(record.uncertainTools()).hasSize(1);
	}

	@Test void trustedReadOnlyAutomaticallyResolvesWithoutFakeFinish() throws Exception {
		var f = new Fixture(this.temp, true);
		f.action.set(p -> f.invoke(i -> { throw new Stop(Stop.Kind.INTERRUPTED); }));
		RunRecord record = f.start();
		f.action.set(p -> {});
		var result = f.executor.resume(record.spec().runId(), f.selected, f.cwd);
		assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(f.runs.export(record.spec().runId()).eventsLog()).contains("READ_RETRY", "ToolResolution").doesNotContain("ToolFinished");
	}

	@Test void explicitMutationRetryAndSkipAcknowledgeUnknownOnly() throws Exception {
		for (var decision : List.of(RunEvent.Resolution.MUTATING_RETRY, RunEvent.Resolution.SKIP)) {
			Path root = Files.createDirectory(this.temp.resolve(decision.name()));
			var f = new Fixture(root, false);
			f.action.set(p -> f.invoke(i -> { throw new IllegalStateException("failed effect"); }));
			RunRecord record = f.start();
			var unknown = record.uncertainTools().getFirst();
			f.action.set(p -> {});
			var action = new RunExecutor.ToolResolutionInput(unknown.attemptId(), unknown.toolCallKey(), decision, "operator", "inspection");
			assertThat(f.executor.resume(record.spec().runId(), f.selected, f.cwd,
					new RunExecutor.ResumeOptions(List.of(action), null)).record().state()).isEqualTo(RunState.COMPLETED);
			assertThat(f.runs.export(record.spec().runId()).eventsLog()).contains(decision.name()).doesNotContain("ToolFinished");
		}
	}

	@Test void humanEditBlocksResumeAndOnlyExactAcknowledgementWorks() throws Exception {
		var f = new Fixture(this.temp, false);
		Files.writeString(f.cwd.resolve("source.txt"), "before");
		var record = f.interrupted();
		Files.writeString(f.cwd.resolve("source.txt"), "after");
		assertThatThrownBy(() -> f.executor.resume(record.spec().runId(), f.selected, f.cwd)).isInstanceOf(RunExecutor.Failure.class);
		var diff = f.executor.inspectDrift(record.spec().runId());
		assertThat(diff.changed()).isTrue();
		var ack = new DriftGate.Acknowledgement(diff.expectedDigest(), diff.actualDigest(), "operator", "review");
		Files.writeString(f.cwd.resolve("source.txt"), "third");
		assertThatThrownBy(() -> f.executor.resume(record.spec().runId(), f.selected, f.cwd,
				new RunExecutor.ResumeOptions(List.of(), ack))).isInstanceOf(RunExecutor.Failure.class);
		var exact = f.executor.inspectDrift(record.spec().runId());
		assertThat(f.executor.resume(record.spec().runId(), f.selected, f.cwd,
				new RunExecutor.ResumeOptions(List.of(), new DriftGate.Acknowledgement(exact.expectedDigest(),
						exact.actualDigest(), "operator", "review"))).record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(f.runs.export(record.spec().runId()).eventsLog()).contains("DriftAccepted");
	}

	@Test void configDriftRequiresExactAckAndWireCwdNeverAcceptDrift() throws Exception {
		var f = new Fixture(this.temp, false);
		var record = f.interrupted();
		f.config.set("changed");
		var diff = f.executor.inspectDrift(record.spec().runId());
		var options = new RunExecutor.ResumeOptions(List.of(),
				new DriftGate.Acknowledgement(diff.expectedDigest(), diff.actualDigest(), "operator", "review"));
		assertThatThrownBy(() -> f.executor.resume(record.spec().runId(), f.selected, f.cwd)).isInstanceOf(RunExecutor.Failure.class);
		f.wire.set("other");
		assertThatThrownBy(() -> f.executor.resume(record.spec().runId(), f.selected, f.cwd, options))
				.isInstanceOfSatisfying(RunExecutor.Failure.class, e -> assertThat(e.code()).isEqualTo(RunExecutor.Code.IDENTITY_DRIFT));
		f.wire.set("test");
		assertThatThrownBy(() -> f.executor.resume(record.spec().runId(), f.selected,
				Files.createDirectory(this.temp.resolve("elsewhere")), options)).isInstanceOf(RunExecutor.Failure.class);
		assertThat(f.executor.resume(record.spec().runId(), f.selected, f.cwd, options).record().state()).isEqualTo(RunState.COMPLETED);
	}

	@Test void terminalRefusesEvenWithOperatorInputs() throws Exception {
		var f = new Fixture(this.temp, false);
		var record = f.start();
		assertThatThrownBy(() -> f.executor.resume(record.spec().runId(), f.selected, f.cwd))
				.isInstanceOfSatisfying(RunExecutor.Failure.class, e -> assertThat(e.code()).isEqualTo(RunExecutor.Code.TERMINAL));
	}

	@Test void lazyToolSchemaRemainsFrozenAndResumeRequiresExactToolDriftAck() throws Exception {
		var f = new Fixture(this.temp, true);
		f.action.set(p -> {
			f.currentTool.set(ToolDefinition.builder().name(f.tool.name()).description("Changed schema")
					.inputSchema("{\"type\":\"object\",\"required\":[\"path\"]}").build());
			f.invoke(i -> { throw new AssertionError("Changed lazy callback must not execute"); });
		});
		var record = f.start();
		assertThat(record.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(record.uncertainTools()).isEmpty();
		assertThat(f.runs.export(record.spec().runId()).eventsLog()).doesNotContain("ToolStarted");
		f.action.set(p -> {});
		assertThatThrownBy(() -> f.executor.resume(record.spec().runId(), f.selected, f.cwd)).isInstanceOf(RunExecutor.Failure.class);
		var diff = f.executor.inspectDrift(record.spec().runId());
		assertThat(f.executor.resume(record.spec().runId(), f.selected, f.cwd,
				new RunExecutor.ResumeOptions(List.of(), new DriftGate.Acknowledgement(diff.expectedDigest(),
						diff.actualDigest(), "operator", "schema-review"))).record().state()).isEqualTo(RunState.COMPLETED);
	}

	@Test void unterminatedKnownTailRecoversButCompleteCorruptionAndFutureSchemaRefuse() throws Exception {
		for (String kind : List.of("tail", "complete", "future")) {
			var f = new Fixture(Files.createDirectory(this.temp.resolve(kind)), false);
			var record = f.interrupted();
			Path log = f.home.resolve("runs").resolve(record.spec().runId().value()).resolve("events.log");
			String fragment = (kind.equals("future") ? "2" : "1") + "\t999\t00000000\t{\"type\":\"cut\"}";
			Files.writeString(log, fragment + (kind.equals("complete") ? "\n" : ""), StandardOpenOption.APPEND);
			if (kind.equals("tail")) {
				assertThat(f.executor.resume(record.spec().runId(), f.selected, f.cwd).record().state()).isEqualTo(RunState.COMPLETED);
				assertThat(Files.readString(log)).doesNotContain("cut");
			}
			else {
				String before = Files.readString(log);
				assertThatThrownBy(() -> f.executor.resume(record.spec().runId(), f.selected, f.cwd))
						.isInstanceOfSatisfying(RunStoreException.class, e -> assertThat(e.code()).isEqualTo(
								kind.equals("future") ? RunStoreException.Code.FUTURE_SCHEMA : RunStoreException.Code.CORRUPT));
				assertThat(Files.readString(log)).isEqualTo(before);
			}
		}
	}

	@Test void aliasedWorkspaceHomeDoesNotIncludePrivateSessionWrites() throws Exception {
		Path root = Files.createDirectory(this.temp.resolve("workspace"));
		Path home = Files.createDirectories(root.resolve("private-state/sessions"));
		Path alias = this.temp.resolve("alias");
		Files.createSymbolicLink(alias, root);
		var baseline = WorkspaceFingerprint.capture(alias, alias.resolve("private-state"));
		Files.writeString(home.resolve("conversation.jsonl"), "private session");
		assertThat(WorkspaceFingerprint.capture(alias, alias.resolve("private-state"))).isEqualTo(baseline);
		Files.writeString(root.resolve("source.txt"), "source");
		assertThat(WorkspaceFingerprint.capture(alias, alias.resolve("private-state")).entries())
				.containsOnlyKeys("source.txt");
	}

	@Test void secretGeneratedAndStateExclusionsPrecedeContentReadsAndEscapeRefuses() throws Exception {
		var f = new Fixture(this.temp, false);
		// Dangling secret links prove contents were never opened.
		Files.createSymbolicLink(f.cwd.resolve(".env.private"), this.temp.resolve("missing"));
		Files.createSymbolicLink(f.cwd.resolve("credential.json"), this.temp.resolve("missing"));
		Files.createDirectory(f.cwd.resolve("target"));
		Files.createSymbolicLink(f.cwd.resolve("target").resolve("generated"), this.temp.resolve("missing"));
		var baseline = WorkspaceFingerprint.capture(f.cwd, f.home);
		assertThat(baseline.entries()).isEmpty();
		Files.createSymbolicLink(f.cwd.resolve("escape"), f.home);
		assertThatThrownBy(() -> WorkspaceFingerprint.capture(f.cwd, f.home)).isInstanceOf(java.io.IOException.class);
	}
}
