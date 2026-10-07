package com.example.miniagent.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.*;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.change.command.ApplyCommand;
import com.example.miniagent.core.delegation.*;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.run.*;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Test-only entry point: each invocation is a distinct Java 21 OS process. */
public final class CrashRecoveryProcessFixture {
	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);
	static final ModelSelection SELECTED = new ModelSelection(new ModelRef("fake", "crash-boundary"));
	static final String GOAL = "!tool write_file {\"path\":\"source.txt\",\"content\":\"staged-effect\"}";
	static final String ORIGINAL = "operator-conversation";
	private static final Duration LIMIT = Duration.ofSeconds(30);

	private CrashRecoveryProcessFixture() {}

	/**
	 * Register stdout/exit futures before sending GO. No child can execute its
	 * engine before this subscription exists; READY is an exact protocol token.
	 */
	static Child launch(Path home, Path cwd, String mode) throws Exception {
		String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		// The subprocess cwd differs from Surefire's cwd; resolve relative entries first.
		classpath = java.util.Arrays.stream(classpath.split(java.io.File.pathSeparator))
				.map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
				.collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
		ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", classpath, CrashRecoveryProcessFixture.class.getName(), mode, home.toString(), cwd.toString());
		builder.directory(cwd.toFile()).redirectErrorStream(true);
		builder.environment().clear();
		Child child = new Child(builder.start());
		try {
			child.process.getOutputStream().write("GO\n".getBytes(StandardCharsets.UTF_8));
			child.process.getOutputStream().flush();
			return child;
		}
		catch (Throwable ex) { child.close(); throw ex; }
	}

	static final class Child implements AutoCloseable {
		final Process process;
		final CompletableFuture<Void> ready = new CompletableFuture<>();
		final CompletableFuture<Void> done = new CompletableFuture<>();
		final CompletableFuture<Process> exit;
		final CompletableFuture<Void> drained = new CompletableFuture<>();
		final StringBuffer output = new StringBuffer();
		Child(Process process) {
			this.process = process;
			this.exit = process.onExit();
			Thread.ofVirtual().start(() -> {
				try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					for (String line; (line = reader.readLine()) != null;) {
						this.output.append(line).append('\n');
						if (line.equals("READY")) this.ready.complete(null);
						if (line.equals("DONE")) this.done.complete(null);
					}
					this.ready.completeExceptionally(new IOException("No READY: " + this.output));
					this.done.completeExceptionally(new IOException("No DONE: " + this.output));
					this.drained.complete(null);
				}
				catch (Throwable ex) {
					this.ready.completeExceptionally(ex); this.done.completeExceptionally(ex);
					this.drained.completeExceptionally(ex);
				}
			});
		}
		void awaitReady() throws Exception { this.ready.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS); }
		void awaitSuccess() throws Exception {
			this.exit.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
			this.drained.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
			assertThat(this.process.exitValue()).as(this.output.toString()).isZero();
			this.done.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
		}
		void kill() throws Exception {
			// Subscribe all exits before issuing any forceful destruction.
			List<ProcessHandle> descendants = this.process.descendants().toList();
			List<CompletableFuture<ProcessHandle>> exits = descendants.stream().map(ProcessHandle::onExit).toList();
			descendants.forEach(ProcessHandle::destroyForcibly);
			this.process.destroyForcibly();
			this.exit.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
			CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new))
					.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
			this.drained.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
			assertThat(this.process.isAlive()).isFalse();
			assertThat(descendants).noneMatch(ProcessHandle::isAlive);
		}
		@Override public void close() throws Exception { kill(); }
	}

	public static void main(String[] args) throws Exception {
		assertThat(Runtime.version().feature()).isEqualTo(21);
		var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		if (!"GO".equals(input.readLine())) throw new IOException("Missing subscribed parent");
		String mode = args[0];
		Path home = Path.of(args[1]), cwd = Path.of(args[2]).toRealPath();
		switch (mode) {
			case "run-crash", "delegation-crash" -> {
				var runtime = new RuntimeFixture(home, cwd, true);
				if (mode.equals("run-crash"))
					runtime.executor.start(GOAL, ORIGINAL, SELECTED, cwd, RunBudget.defaults());
				else runtime.executor.startOperation("delegate exact child", ORIGINAL, SELECTED, cwd,
						RunBudget.defaults(), context -> {
							try (var scope = runtime.scopes.create(context.turnContext(), runtime.child(), runtime.authority())) {
								try { return scope.call(); }
								catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
							}
						});
				throw new AssertionError("Crash fixture escaped boundary");
			}
			case "run-recover", "run-tail", "run-corrupt" -> recoverRun(home, cwd, mode);
			case "delegation-recover" -> recoverDelegation(home, cwd);
			case "change-prepared", "change-partial" -> crashChange(home, cwd, mode);
			case "change-before", "change-after", "change-drift", "change-tail", "change-corrupt" ->
				recoverChange(home, cwd, mode);
			default -> throw new IllegalArgumentException(mode);
		}
		System.out.println("DONE"); System.out.flush();
	}

	/** Blocking on stdin, not time: only the parent OS kill can pass this boundary. */
	static void readyAndBlock() throws IOException {
		System.out.println("READY"); System.out.flush();
		if (System.in.read() == -1) throw new IOException("Parent disappeared before kill");
		throw new IOException("Unexpected parent input after READY");
	}

	static RunRecord root(FileRunStore store) throws Exception {
		return store.list().stream().map(id -> {
			try { return store.read(id); }
			catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
		}).filter(record -> record.spec().childId() == null).findFirst().orElseThrow();
	}
	static RunRecord child(FileRunStore store) throws Exception {
		return store.list().stream().map(id -> {
			try { return store.read(id); }
			catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
		}).filter(record -> record.spec().childId() != null).findFirst().orElseThrow();
	}

	static void lost(RunRecord record) {
		assertThat(record.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(record.reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
		assertThat(record.attempts()).hasSize(1);
		assertThat(record.attempts().getFirst().endedAt()).isNull();
		assertThat(record.uncertainTools()).hasSize(1).allMatch(t -> t.effect() == RunEvent.Effect.MUTATING);
	}
	static RunExecutor.ResumeOptions resolve(RunRecord record) {
		var tool = record.uncertainTools().getFirst();
		return new RunExecutor.ResumeOptions(List.of(new RunExecutor.ToolResolutionInput(tool.attemptId(),
				tool.toolCallKey(), RunEvent.Resolution.SKIP, "operator", "explicit-unknown-resolution")), null);
	}
	static void resumed(RunRecord before, RunRecord after) {
		assertThat(after.spec()).isEqualTo(before.spec());
		assertThat(after.state()).isEqualTo(RunState.COMPLETED);
		assertThat(after.attempts()).hasSize(2);
		assertThat(after.attempts().getFirst().endedAt()).isNotNull();
		assertThat(after.attempts().getLast().attemptId()).isNotEqualTo(before.attempts().getFirst().attemptId());
		assertThat(after.attempts().getLast().bindingId()).isNotEqualTo(before.attempts().getFirst().bindingId());
		assertThat(after.uncertainTools()).isEmpty();
	}

	private static void recoverRun(Path home, Path cwd, String mode) throws Exception {
		var runtime = new RuntimeFixture(home, cwd, false);
		RunId id = runtime.runs.list().getFirst();
		Path journal = home.resolve("runs").resolve(id.value()).resolve("events.log");
		byte[] before = Files.readAllBytes(journal);
		if (mode.equals("run-corrupt")) {
			assertThat(assertThrows(RunStoreException.class, () -> runtime.runs.read(id)).code())
					.isEqualTo(RunStoreException.Code.CORRUPT);
			assertThat(assertThrows(RunStoreException.class, () -> {
				try (var writer = runtime.runs.openWriter(id)) { writer.record(); }
			}).code())
					.isEqualTo(RunStoreException.Code.CORRUPT);
			assertThat(Files.readAllBytes(journal)).isEqualTo(before);
			return;
		}
		var record = runtime.runs.read(id);
		lost(record);
		assertThat(Files.readAllBytes(journal)).isEqualTo(before);
		assertThat(assertThrows(RunExecutor.Failure.class,
				() -> runtime.executor.resume(id, SELECTED, cwd)).code()).isEqualTo(RunExecutor.Code.UNKNOWN_EFFECTS);
		assertThat(Files.readAllBytes(journal)).isEqualTo(before);
		// Actual writer lock acquisition and normal append happen in the fresh JVM.
		var result = runtime.executor.resume(id, SELECTED, cwd, resolve(record));
		resumed(record, result.record());
		assertThat(FileRunStore.events(runtime.runs, id)).filteredOn(RunEvent.ToolStarted.class::isInstance).hasSize(1);
		assertThat(Files.readString(cwd.resolve("source.txt"))).isEqualTo("before\n");
		assertThat(runtime.changes.read().changes()).isEmpty();
	}

	private static void recoverDelegation(Path home, Path cwd) throws Exception {
		var runtime = new RuntimeFixture(home, cwd, false);
		var parent = root(runtime.runs);
		var record = child(runtime.runs);
		lost(record);
		assertThat(parent.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(parent.reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
		assertThat(record.spec().parentRunId()).isEqualTo(parent.spec().runId());
		assertThat(record.spec().parentConversationId()).isEqualTo(parent.spec().conversationId());
		assertThat(assertThrows(RunExecutor.Failure.class,
				() -> runtime.executor.resume(record.spec().runId(), SELECTED, cwd, resolve(record))).code())
				.isEqualTo(RunExecutor.Code.IDENTITY_DRIFT);
		var properties = DelegationProperties.defaults();
		try (var delegation = new DefaultDelegationRuntime(runtime.scopes, properties, new SessionRunLock())) {
			delegation.evidence(new DelegationEvidenceCollector(runtime.runs, runtime.requests.evidence(), runtime.changes));
			var apply = new ApplyCommand(() -> runtime.changes,
					() -> new ApplyEngine(runtime.changes, runtime.policy, CLOCK), runtime.broker, CLOCK, event -> {});
			try (var controller = new DelegationController(delegation, runtime.runs,
					new StagedApplyService(runtime.runs, runtime.changes, runtime.scopes, apply), event -> {})) {
				byte[] journal = Files.readAllBytes(home.resolve("runs").resolve(record.spec().runId().value()).resolve("events.log"));
				assertThrows(IOException.class, () -> controller.recover(record.spec().runId(), "recovery",
						runtime.child(), "foreign-conversation", runtime.authority(), resolve(record)));
				var wrong = new ChildSpec("implement", "different goal", ChildRole.IMPLEMENT, SELECTED.ref(),
						Set.of(), runtime.authority(), RunBudget.defaults(), 1, true);
				assertThrows(IOException.class, () -> controller.recover(record.spec().runId(), "recovery",
						wrong, ORIGINAL, runtime.authority(), resolve(record)));
				var narrowed = new ToolScope(Set.of("write_file"), Set.of(cwd), Set.of(cwd.resolve("restricted")),
						Set.of(), true, false, false, false);
				var changedScope = new ChildSpec("implement", GOAL, ChildRole.IMPLEMENT, SELECTED.ref(),
						Set.of(), narrowed, RunBudget.defaults(), 1, true);
				assertThat(assertThrows(RunExecutor.Failure.class, () -> controller.recover(record.spec().runId(),
						"recovery", changedScope, ORIGINAL, runtime.authority(), resolve(record))).code())
						.isEqualTo(RunExecutor.Code.IDENTITY_DRIFT);
				assertThat(assertThrows(RunExecutor.Failure.class, () -> controller.recover(record.spec().runId(),
						"recovery", runtime.child(), ORIGINAL, runtime.authority(), RunExecutor.ResumeOptions.ordinary())).code())
						.isEqualTo(RunExecutor.Code.UNKNOWN_EFFECTS);
				assertThat(Files.readAllBytes(home.resolve("runs").resolve(record.spec().runId().value()).resolve("events.log")))
						.isEqualTo(journal);
				var result = controller.recover(record.spec().runId(), "recovery", runtime.child(),
						ORIGINAL, runtime.authority(), resolve(record));
				resumed(record, result.record());
				// Narrative-only recovery is not fabricated verification or an apply grant.
				assertThat(controller.show(ORIGINAL, "recovery").parent()).isNull();
				assertThat(((DefaultDelegationRuntime.Completed) controller.show(ORIGINAL, "recovery").outcome())
						.result().status()).isNotEqualTo(DelegationResult.Status.COMPLETE);
				assertThat(FileRunStore.events(runtime.runs, record.spec().runId()))
						.filteredOn(RunEvent.ToolStarted.class::isInstance).hasSize(1);
			}
		}
		assertThat(Files.readString(cwd.resolve("source.txt"))).isEqualTo("before\n");
		assertThat(runtime.changes.read().changes()).isEmpty();
	}

	private static void crashChange(Path home, Path cwd, String mode) throws Exception {
		var store = new FileChangeStore(home, cwd);
		var policy = new PathPolicy(cwd, home, List.of());
		var fixture = new RuntimeFixture(home, cwd, false);
		// Obtain the stage and owner through the common executor, not invented IDs.
		var result = fixture.executor.startOperation("prepare two changes", ORIGINAL, SELECTED, cwd,
				RunBudget.defaults(), context -> {
					try {
						var stage = StagedWorkspace.current().orElseThrow();
						Files.writeString(stage.root().resolve("source.txt"), "after\n");
						Files.writeString(stage.root().resolve("z-second.txt"), "second-after\n");
						stage.record();
						return stage.owner();
					}
					catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
				});
		var set = store.read().changes().getFirst().changeSet();
		assertThat(set.owner()).isEqualTo(result.value());
		try (var lease = store.acquire()) {
			lease.decide(set.changeSetId(), set.owner(), set.digest(),
					new Approval(UUID.randomUUID(), set.changeSetId(), set.digest(), set.owner(),
							"operator", ApprovalDecision.APPROVED, CLOCK.instant()));
		}
		var engine = new ApplyEngine(store, policy, CLOCK, (boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.PREPARED
					&& (mode.equals("change-prepared") || checkpoint.before().relativePath().equals("z-second.txt"))) {
				assertThat(checkpoint.state()).isEqualTo(Checkpoint.State.PREPARED);
				readyAndBlock();
			}
		});
		var console = new AgentConsole(new BufferedReader(new StringReader(
				"exclusive " + set.changeSetId() + " " + set.digest() + "\n")),
				new PrintStream(OutputStream.nullOutputStream()), true);
		var proof = engine.confirmExclusiveOwnership(console, set.changeSetId(), set.owner(), set.digest());
		engine.apply(set.changeSetId(), set.owner(), set.digest(), proof);
		throw new AssertionError("Apply escaped kill boundary");
	}

	private static void recoverChange(Path home, Path cwd, String mode) throws Exception {
		var store = new FileChangeStore(home, cwd);
		var policy = new PathPolicy(cwd, home, List.of());
		Path journal = store.directory().resolve("journal.log");
		byte[] before = Files.readAllBytes(journal);
		var view = store.read();
		var snapshot = view.changes().getFirst();
		var set = snapshot.changeSet();
		assertThat(view.recoveryRequired()).isTrue();
		assertThat(set.status()).isEqualTo(ChangeStatus.APPLYING);
		try (var lease = store.acquire()) {
			for (var checkpoint : snapshot.checkpoints()) {
				for (var blob : List.of(checkpoint.beforeBlob(), checkpoint.afterBlob()))
					assertThat(Fingerprinter.sha256(lease.readBlob(blob))).isEqualTo(blob.sha256());
			}
		}
		var report = new RecoveryScanner(store, policy).scan(set.changeSetId(), set.owner(), set.digest());
		assertThat(Files.readAllBytes(journal)).isEqualTo(before);
		switch (mode) {
			case "change-before" -> {
				assertThat(snapshot.checkpoints()).hasSize(1);
				assertThat(report.entries()).allMatch(e -> e.position() == ConflictReport.Position.BEFORE);
			}
			case "change-after" -> {
				assertThat(snapshot.checkpoints()).hasSize(2);
				assertThat(snapshot.checkpoints().getFirst().state()).isEqualTo(Checkpoint.State.APPLIED);
				assertThat(snapshot.checkpoints().getLast().state()).isEqualTo(Checkpoint.State.PREPARED);
				assertThat(report.entries().getFirst().position()).isEqualTo(ConflictReport.Position.AFTER);
				assertThat(report.entries().getLast().position()).isEqualTo(ConflictReport.Position.BEFORE);
			}
			case "change-drift" -> {
				assertThat(report.entries().getFirst().position()).isEqualTo(ConflictReport.Position.OTHER);
				assertThat(report.entries().getFirst().code()).isEqualTo(ConflictReport.Code.HUMAN_EDIT);
				assertThat(Files.readString(cwd.resolve("source.txt"))).isEqualTo("human edit\n");
			}
			case "change-tail" -> assertThat(view.journalState()).isEqualTo(ChangeJournal.State.TRUNCATED);
			case "change-corrupt" -> assertThat(view.journalState()).isEqualTo(ChangeJournal.State.CORRUPT);
			default -> throw new IllegalArgumentException(mode);
		}
		var engine = new ApplyEngine(store, policy, CLOCK);
		assertThrows(IOException.class, () -> engine.apply(set.changeSetId(), set.owner(), set.digest(), null));
		assertThat(Files.readAllBytes(journal)).isEqualTo(before);
	}

	static final class RuntimeFixture {
		final FileRunStore runs;
		final RunRequestStore requests;
		final FileChangeStore changes;
		final PathPolicy policy;
		final RunExecutor executor;
		final ChildScopeFactory scopes;
		final ConsoleApprovalBroker broker;
		final Path cwd;
		RuntimeFixture(Path home, Path cwd, boolean crash) throws Exception {
			this.cwd = cwd;
			this.runs = new FileRunStore(home);
			this.requests = new RunRequestStore(home);
			this.changes = new FileChangeStore(home, cwd);
			this.policy = new PathPolicy(cwd, home, List.of());
			var selections = new FileSessionModelStore(home);
			this.broker = new ConsoleApprovalBroker(new AgentConsole(new BufferedReader(new StringReader("")),
					new PrintStream(OutputStream.nullOutputStream()), false));
			ToolCallback raw = new ToolCallback() {
				final ToolDefinition definition = ToolDefinition.builder().name("write_file").description("staged file write")
						.inputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}}}")
						.build();
				@Override public ToolDefinition getToolDefinition() { return this.definition; }
				@Override public String call(String input) { throw new AssertionError("Captured context required"); }
				@Override public String call(String input, ToolContext tools) {
					try {
						var context = RunContext.fromToolContext(tools).orElseThrow();
						assertThat(context.writer().record().uncertainTools()).hasSize(1);
						var stage = StagedWorkspace.from(tools).orElseThrow();
						Files.writeString(stage.resolve("source.txt", true, List.of()), "staged-effect");
						// ToolJournalInterceptor has acknowledged STARTED and forced it before entry.
						if (crash) readyAndBlock();
						throw new AssertionError("UNKNOWN mutation was automatically retried");
					}
					catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
				}
			};
			StagingToolInterceptor.Policy declaration = () -> Map.of("write_file",
					new StagingToolInterceptor.Declaration(SideEffectClass.definitionDigest(raw.getToolDefinition()),
							StagingToolInterceptor.Kind.FILE_WRITE, List.of()));
			var staging = new StagingToolInterceptor(() -> List.of(declaration));
			List<ToolInterceptor> interceptors = List.of(staging, new RunBudgetInterceptor(), new ToolJournalInterceptor());
			var wrapped = InterceptingToolCallback.wrap(raw, interceptors);
			ModelProvider provider = new ModelProvider() {
				@Override public String providerId() { return "fake"; }
				@Override public ProviderAuth auth() { return () -> true; }
				@Override public List<ModelEntry> models() { return List.of(new ModelEntry(SELECTED.ref(), "fake", null)); }
				@Override public ChatModel create(ModelRef ref, Map<String, String> options) {
					return new ChatModel() {
						@Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
							return ToolCallingChatOptions.builder().build();
						}
						@Override public ChatResponse call(Prompt prompt) {
							boolean resume = prompt.getInstructions().stream().filter(UserMessage.class::isInstance)
									.anyMatch(message -> message.getText().startsWith("Explicitly resume"));
							AssistantMessage answer = resume ? new AssistantMessage("No UNKNOWN mutation retried or applied")
									: AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
											"call", "function", "write_file", "{\"path\":\"source.txt\",\"content\":\"staged-effect\"}"))).build();
							return new ChatResponse(List.of(new Generation(answer)),
									ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build());
						}
					};
				}
			};
			var registry = new ModelRegistry(List.of(provider), model ->
					new AgentChatClientFactory.ScopedAssembly("test", List.of(wrapped), List.of(),
							ToolCallingAdvisor.DEFAULT_ORDER).create(model));
			var agent = new Agent(registry, selections, SELECTED, new AgentSession(), cwd, "test",
					List.of(wrapped), List.of(), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
			this.executor = new RunExecutor(agent, registry, selections, this.runs, this.requests, CLOCK,
					List.of(), this.broker).staging(staging, () -> this.changes);
			this.scopes = new ChildScopeFactory(agent, home, this.runs, this.requests, selections, CLOCK,
					this.broker, List.of(raw), List.of(), interceptors, List.of(declaration), List.of(), List.of());
		}
		ToolScope authority() {
			return new ToolScope(Set.of("write_file"), Set.of(this.cwd), Set.of(this.cwd), Set.of(),
					true, false, false, false);
		}
		ChildSpec child() {
			return new ChildSpec("implement", GOAL, ChildRole.IMPLEMENT, SELECTED.ref(), Set.of(),
					authority(), RunBudget.defaults(), 1, true);
		}
	}
}
