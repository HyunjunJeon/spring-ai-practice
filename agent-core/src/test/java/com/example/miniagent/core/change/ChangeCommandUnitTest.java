package com.example.miniagent.core.change;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.approval.ApprovalAnswer;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.approval.ConsoleApprovalBroker;
import com.example.miniagent.core.autoconfigure.ChangeCommandAutoConfiguration;
import com.example.miniagent.core.change.command.ApplyCommand;
import com.example.miniagent.core.change.command.ChangesCommand;
import com.example.miniagent.core.change.command.UndoCommand;
import com.example.miniagent.core.change.command.VerifyCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

/** Actual storage, engines and broker; fixture-only process policy is not sandbox QA. */
class ChangeCommandUnitTest {
	@TempDir Path work;
	@TempDir Path home;

	private EngineFixture fixture() throws Exception {
		var f = new EngineFixture(this.work, this.home);
		f.file(this.work.resolve("source"), "before\n", 0644);
		return f;
	}
	private ChangeSet proposal(EngineFixture f) throws Exception {
		return f.propose(stage -> f.file(stage.resolve("source"), "after\n", 0644), false);
	}
	private static SlashCommand.CommandContext context(AgentConsole console) {
		var session = new AgentSession();
		session.switchTo("different-console-conversation");
		return new SlashCommand.CommandContext(null, session, console);
	}
	private static ApplyCommand apply(EngineFixture f, ConsoleApprovalBroker broker) {
		var engine = f.applyEngine();
		return new ApplyCommand(() -> f.store, () -> engine, broker, EngineFixture.CLOCK, ignored -> {});
	}
	private static UndoCommand undo(EngineFixture f) {
		return new UndoCommand(() -> f.store, f::applyEngine,
				() -> new UndoEngine(f.store, f.policy, EngineFixture.CLOCK),
				() -> new RecoveryScanner(f.store, f.policy), () -> null, ignored -> {});
	}

	@Test void approvalIsDurableBeforeExclusiveQuestionAndHistoricalOwnerSurvivesSessionChange() throws Exception {
		var f = fixture();
		var set = proposal(f);
		var console = new OperatorConsole(set, ApprovalDecision.APPROVED) {
			@Override public String readLine(String prompt) {
				try {
					var saved = f.store.read(); // Engine holds lease while asking.
					throw new AssertionError("Lease should exclude separate reader: " + saved);
				}
				catch (ChangeStore.Failure ex) {
					assertThat(ex.code()).isEqualTo(ChangeStore.Code.LOCKED);
				}
				assertThat(Files.exists(f.store.directory().resolve("manifests").resolve(set.changeSetId() + ".json"))).isTrue();
				try {
					var manifest = JsonMapper.builder().build().readTree(Files.readString(
							f.store.directory().resolve("manifests").resolve(set.changeSetId() + ".json")));
					assertThat(manifest.path("snapshot").path("changeSet").path("status").asString()).isEqualTo("APPROVED");
					assertThat(manifest.path("snapshot").path("changeSet").path("approval").path("decision").asString())
							.isEqualTo("APPROVED");
				}
				catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
				return super.readLine(prompt);
			}
		};
		var broker = new ConsoleApprovalBroker(console);
		assertThat(apply(f, broker).executeWithStatus(set.changeSetId().toString(), context(console))).isTrue();
		var saved = new FileChangeStore(this.home, this.work).read().get(set.changeSetId());
		assertThat(saved.changeSet().status()).isEqualTo(ChangeStatus.APPLIED);
		assertThat(saved.changeSet().owner()).isEqualTo(set.owner());
		assertThat(saved.changeSet().approval().owner()).isEqualTo(set.owner());
		assertThat(saved.changeSet().approval().changeSetDigest()).isEqualTo(set.digest());
		assertThat(console.questions).hasValue(1);
		assertThat(console.proofs).hasValue(1);
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("after\n");
		assertThat(broker.pending()).isEmpty();
	}

	@Test void unattendedDefersDurablyWithoutProofOrWorkspaceWrites() throws Exception {
		var f = fixture();
		var set = proposal(f);
		var console = new OperatorConsole(set, ApprovalDecision.APPROVED);
		console.setInteractive(false);
		var broker = new ConsoleApprovalBroker(console);
		assertThat(apply(f, broker).executeWithStatus(set.changeSetId().toString(), context(console))).isFalse();
		var saved = f.store.read().get(set.changeSetId());
		assertThat(saved.changeSet().status()).isEqualTo(ChangeStatus.PENDING_APPROVAL);
		assertThat(saved.changeSet().approval().decision()).isEqualTo(ApprovalDecision.DEFERRED);
		assertThat(saved.checkpoints()).isEmpty();
		assertThat(console.proofs).hasValue(0);
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("before\n");
		assertThat(broker.pending()).hasSize(1);
	}

	@Test void rejectedAndCancelledDecisionsRejectWithoutIssuingProof() throws Exception {
		for (var decision : List.of(ApprovalDecision.REJECTED, ApprovalDecision.CANCELLED)) {
			Path workspace = Files.createDirectory(this.work.resolve(decision.name()));
			Path state = Files.createDirectory(this.home.resolve(decision.name()));
			var f = new EngineFixture(workspace, state);
			var set = f.propose(stage -> f.file(stage.resolve("new"), "after\n", 0644), false);
			var console = new OperatorConsole(set, decision);
			assertThat(apply(f, new ConsoleApprovalBroker(console))
					.executeWithStatus(set.changeSetId().toString(), context(console))).isFalse();
			assertThat(f.store.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.REJECTED);
			assertThat(console.proofs).hasValue(0);
			assertThat(workspace.resolve("new")).doesNotExist();
		}
	}

	@Test void missingExclusiveProofPreservesApprovedProposalWithoutApplying() throws Exception {
		var f = fixture();
		var set = proposal(f);
		var console = new OperatorConsole(set, ApprovalDecision.APPROVED) {
			@Override public String readLine(String prompt) { return "true"; }
		};
		assertThat(apply(f, new ConsoleApprovalBroker(console))
				.executeWithStatus(set.changeSetId().toString(), context(console))).isFalse();
		assertThat(f.store.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.APPROVED);
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("before\n");
	}

	@Test void interruptionCancelsOnlyExactOutstandingRequestAndRestoresFlagAfterDurability() throws Exception {
		var f = fixture();
		var set = proposal(f);
		var shown = new CompletableFuture<ApprovalRequest>();
		var console = new OperatorConsole(set, ApprovalDecision.APPROVED) {
			@Override public void requestApproval(ApprovalRequest request, Consumer<ApprovalAnswer> response) {
				shown.complete(request); // Subscribe before starting the command.
			}
		};
		var broker = new ConsoleApprovalBroker(console);
		var done = new CompletableFuture<Boolean>();
		var interrupted = new CompletableFuture<Boolean>();
		Thread worker = Thread.ofVirtual().start(() -> {
			try {
				done.complete(apply(f, broker).executeWithStatus(set.changeSetId().toString(), context(console)));
				interrupted.complete(Thread.currentThread().isInterrupted());
			}
			catch (Throwable ex) { done.completeExceptionally(ex); interrupted.completeExceptionally(ex); }
		});
		var request = shown.get(5, TimeUnit.SECONDS);
		var unrelated = new ApprovalRequest(java.util.UUID.randomUUID(), request.runId(), request.attemptId(),
				null, null, "other", request.argsDigest(), request.diffDigest());
		var otherDecisions = new AtomicInteger();
		broker.requestChange(unrelated, ignored -> otherDecisions.incrementAndGet());
		worker.interrupt();
		assertThat(done.get(5, TimeUnit.SECONDS)).isFalse();
		assertThat(interrupted.get(5, TimeUnit.SECONDS)).isTrue();
		var saved = f.store.read().get(set.changeSetId());
		assertThat(saved.changeSet().status()).isEqualTo(ChangeStatus.REJECTED);
		assertThat(saved.changeSet().approval().decision()).isEqualTo(ApprovalDecision.CANCELLED);
		assertThat(broker.pending()).containsExactly(unrelated);
		assertThat(otherDecisions).hasValue(0);
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("before\n");
		broker.cancel(unrelated);
	}

	@Test void liveDriftAndSupersededProposalCannotApply() throws Exception {
		var f = fixture();
		var set = proposal(f);
		Files.writeString(this.work.resolve("source"), "human\n");
		var console = new OperatorConsole(set, ApprovalDecision.APPROVED);
		assertThat(apply(f, new ConsoleApprovalBroker(console))
				.executeWithStatus(set.changeSetId().toString(), context(console))).isFalse();
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("human\n");
		try (var lease = f.store.acquire()) {
			lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.SUPERSEDED);
		}
		assertThat(apply(f, new ConsoleApprovalBroker(console))
				.executeWithStatus(set.changeSetId().toString(), context(console))).isFalse();
		assertThat(console.questions).hasValue(1);
	}

	@Test void managedUndoRestoresBytesAndHumanConflictReturnsFalseWithoutClobber() throws Exception {
		var f = fixture();
		var set = proposal(f);
		var console = new OperatorConsole(set, ApprovalDecision.APPROVED);
		assertThat(apply(f, new ConsoleApprovalBroker(console))
				.executeWithStatus(set.changeSetId().toString(), context(console))).isTrue();
		assertThat(undo(f).executeWithStatus(set.changeSetId().toString(), context(console))).isTrue();
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("before\n");
		assertThat(f.store.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
	}

	@Test void managedUndoReportsPartialConflictAndPreservesHumanBytes() throws Exception {
		var f = fixture();
		var set = proposal(f);
		var console = new OperatorConsole(set, ApprovalDecision.APPROVED);
		assertThat(apply(f, new ConsoleApprovalBroker(console))
				.executeWithStatus(set.changeSetId().toString(), context(console))).isTrue();
		Files.writeString(this.work.resolve("source"), "human\n");
		assertThat(undo(f).executeWithStatus(set.changeSetId().toString(), context(console))).isFalse();
		assertThat(f.store.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.PARTIALLY_UNDONE);
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("human\n");
		var report = new RecoveryScanner(f.store, f.policy).scan(set.changeSetId(), set.owner(), set.digest());
		assertThat(report.hasConflicts()).isTrue();
		assertThat(report.entries()).allMatch(e -> e.code() == ConflictReport.Code.HUMAN_EDIT);
	}

	@Test void changesReportsFullDiffAndRecoveryWithoutRepairingPartialState() throws Exception {
		var f = fixture();
		var set = proposal(f);
		var console = new OperatorConsole(set, ApprovalDecision.APPROVED);
		var engine = f.applyEngine((boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.MUTATED) throw new java.io.IOException("boundary failure");
		});
		var observed = new java.util.ArrayList<com.example.miniagent.core.TraceEvent.ChangeStateChanged>();
		var command = new ApplyCommand(() -> f.store, () -> engine, new ConsoleApprovalBroker(console),
				EngineFixture.CLOCK, event -> observed.add((com.example.miniagent.core.TraceEvent.ChangeStateChanged) event));
		assertThat(command.executeWithStatus(set.changeSetId().toString(), context(console))).isFalse();
		assertThat(observed).extracting(com.example.miniagent.core.TraceEvent.ChangeStateChanged::status)
				.containsExactly(ChangeStatus.PENDING_APPROVAL, ChangeStatus.APPROVED, ChangeStatus.RECOVERY_REQUIRED);
		byte[] journal = f.journal();
		var changes = new ChangesCommand(() -> f.store, () -> new RecoveryScanner(f.store, f.policy), ignored -> {});
		assertThat(changes.executeWithStatus(set.changeSetId().toString(), context(console))).isFalse();
		assertThat(f.journal()).isEqualTo(journal);
		var report = new RecoveryScanner(f.store, f.policy).scan(set.changeSetId(), set.owner(), set.digest());
		assertThat(report.entries()).allMatch(e -> e.position() == ConflictReport.Position.AFTER);
		assertThat(f.store.read().get(set.changeSetId()).checkpoints())
				.allMatch(checkpoint -> checkpoint.state() == Checkpoint.State.PREPARED);
		assertThat(console.output.toString()).contains(set.diffs().getFirst().patch());
	}

	@Test void legacyUndoAndCommandDiscoveryNeverResolveManagedStore() {
		var calls = new AtomicInteger();
		try (var beans = new AnnotationConfigApplicationContext()) {
			beans.registerBean(ChangeStore.class, () -> { throw new AssertionError("Managed store resolved"); },
					definition -> definition.setLazyInit(true));
			beans.registerBean(ApplyEngine.class, () -> { throw new AssertionError("Apply resolved"); },
					definition -> definition.setLazyInit(true));
			beans.registerBean(UndoEngine.class, () -> { throw new AssertionError("Undo resolved"); },
					definition -> definition.setLazyInit(true));
			beans.registerBean(RecoveryScanner.class, () -> { throw new AssertionError("Recovery resolved"); },
					definition -> definition.setLazyInit(true));
			beans.registerBean(VerificationRunner.class, () -> { throw new AssertionError("Runner resolved"); },
					definition -> definition.setLazyInit(true));
			var console = EngineFixture.console("", false);
			beans.registerBean(ApprovalBroker.class, () -> new ConsoleApprovalBroker(console));
			beans.registerBean(UndoCommand.LegacyUndo.class, () -> ctx -> { calls.incrementAndGet(); return false; });
			beans.register(ChangeCommandAutoConfiguration.class);
			beans.refresh();
			assertThat(beans.getBeansOfType(SlashCommand.class)).hasSize(4);
			assertThat(beans.getBean(UndoCommand.class).executeWithStatus("", context(console))).isFalse();
			assertThat(calls).hasValue(1);
		}
	}

	@Test void exactJsonArgvReachesRealRunnerCloneAndActualExitDeterminesResult() throws Exception {
		var f = fixture();
		var set = proposal(f);
		var console = new OperatorConsole(set, ApprovalDecision.APPROVED);
		for (String mode : List.of("success", "nonzero")) {
			String cp = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
					.map(entry -> Path.of(entry).toAbsolutePath().toString())
					.collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
			var argv = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", cp,
					ArgvFixture.class.getName(), mode, "space arg", "", "quote\"arg", "back\\slash");
			var prepared = new CompletableFuture<VerificationRunner.Context>();
			var runner = new VerificationRunner(f.store, (scope, actual) -> {
				assertThat(actual).isEqualTo(argv);
				assertThat(scope.root()).isNotEqualTo(f.store.workspace());
				assertThat(Files.readString(scope.root().resolve("source"))).isEqualTo("after\n");
				prepared.complete(scope);
				// Fixture-only policy: this test does not claim OS sandbox enforcement.
				return new VerificationRunner.Launch(actual, Map.of(), Fingerprinter.sha256("fixture".getBytes()));
			}, EngineFixture.CLOCK);
			var events = new java.util.ArrayList<com.example.miniagent.core.TraceEvent.ChangeStateChanged>();
			var command = new VerifyCommand(() -> f.store, () -> runner, Duration.ofSeconds(10), 1024,
					event -> events.add((com.example.miniagent.core.TraceEvent.ChangeStateChanged) event));
			boolean result = command.executeWithStatus(set.changeSetId() + " "
					+ JsonMapper.builder().build().writeValueAsString(argv), context(console));
			assertThat(result).isEqualTo(mode.equals("success"));
			var evidence = f.store.read().get(set.changeSetId()).evidence().getLast();
			assertThat(events.getLast().evidenceId()).isEqualTo(evidence.evidenceId());
			assertThat(events.getLast().verification()).isEqualTo(mode.equals("success")
					? VerificationRunner.Code.SUCCEEDED : VerificationRunner.Code.NON_ZERO_EXIT);
			assertThat(evidence.argv()).isEqualTo(argv);
			assertThat(evidence.exitCode()).isEqualTo(mode.equals("success") ? 0 : 7);
			assertThat(evidence.sourceCwd()).isEqualTo(f.store.workspace().toString());
			assertThat(evidence.cwd()).isEqualTo(prepared.get(5, TimeUnit.SECONDS).root().toString());
			assertThat(Path.of(evidence.cwd())).doesNotExist();
			assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("before\n");
		}
	}

	@Test void invalidJsonNeverResolvesStoreAndUnsupportedPolicyNeverClaimsSuccess() throws Exception {
		var console = EngineFixture.console("", false);
		var invalid = new VerifyCommand(() -> { throw new AssertionError("Store resolved"); },
				() -> { throw new AssertionError("Runner resolved"); }, Duration.ofMinutes(30), 1048576, ignored -> {});
		for (String json : List.of("[]", "[1]", "[null]", "[\"echo\"] true", "\"echo\"", "[\"echo\",]"))
			assertThat(invalid.executeWithStatus(java.util.UUID.randomUUID() + " " + json, context(console))).isFalse();
		var f = fixture();
		var set = proposal(f);
		var runner = new VerificationRunner(f.store, (scope, argv) -> {
			throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, null);
		}, EngineFixture.CLOCK);
		assertThat(new VerifyCommand(() -> f.store, () -> runner, Duration.ofMinutes(30), 1048576, ignored -> {})
				.executeWithStatus(set.changeSetId() + " [\"echo\",\"space arg\"]", context(console))).isFalse();
		assertThat(f.store.read().get(set.changeSetId()).evidence()).isEmpty();
	}

	@Test void naturalZeroExitWithSourceDriftIsNotAcceptedAndKeepsHonestEvidence() throws Exception {
		var f = fixture();
		var set = proposal(f);
		String cp = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
				.map(entry -> Path.of(entry).toAbsolutePath().toString())
				.collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
		var argv = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", cp,
				SourceDriftFixture.class.getName());
		var runner = new VerificationRunner(f.store, (scope, actual) -> new VerificationRunner.Launch(
				actual, Map.of(), Fingerprinter.sha256("fixture-only-source-drift".getBytes())), EngineFixture.CLOCK);
		var events = new java.util.ArrayList<com.example.miniagent.core.TraceEvent.ChangeStateChanged>();
		var command = new VerifyCommand(() -> f.store, () -> runner, Duration.ofSeconds(10), 1024,
				event -> events.add((com.example.miniagent.core.TraceEvent.ChangeStateChanged) event));
		assertThat(command.executeWithStatus(set.changeSetId() + " "
				+ JsonMapper.builder().build().writeValueAsString(argv), context(EngineFixture.console("", false)))).isFalse();
		var evidence = f.store.read().get(set.changeSetId()).evidence().getLast();
		assertThat(evidence.exitCode()).isZero();
		assertThat(evidence.successful()).isTrue(); // Exit zero alone is insufficient.
		assertThat(events.getLast().verification()).isEqualTo(VerificationRunner.Code.SOURCE_CHANGED);
		assertThat(events.getLast().evidenceId()).isEqualTo(evidence.evidenceId());
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("before\n");
	}

	public static final class SourceDriftFixture {
		public static void main(String[] args) throws Exception {
			// Only the private clone is modified; a real sandbox policy would deny this.
			Files.writeString(Path.of("source"), "generated over source\n");
		}
	}

	public static final class ArgvFixture {
		public static void main(String[] args) {
			if (!Arrays.equals(Arrays.copyOfRange(args, 1, args.length),
					new String[] { "space arg", "", "quote\"arg", "back\\slash" })) System.exit(9);
			System.out.print("observed");
			if (args[0].equals("nonzero")) System.exit(7);
		}
	}

	private static class OperatorConsole extends AgentConsole {
		final ByteArrayOutputStream output;
		final ChangeSet set;
		final ApprovalDecision decision;
		final AtomicInteger questions = new AtomicInteger();
		final AtomicInteger proofs = new AtomicInteger();
		OperatorConsole(ChangeSet set, ApprovalDecision decision) {
			this(set, decision, new ByteArrayOutputStream());
		}
		private OperatorConsole(ChangeSet set, ApprovalDecision decision, ByteArrayOutputStream output) {
			super(new BufferedReader(new StringReader("")), new PrintStream(output), true);
			this.set = set; this.decision = decision; this.output = output;
		}
		@Override public void requestApproval(ApprovalRequest request, Consumer<ApprovalAnswer> response) {
			this.questions.incrementAndGet();
			assertThat(request.runId()).isEqualTo(this.set.owner().runId());
			assertThat(request.attemptId()).isEqualTo(this.set.owner().attemptId());
			assertThat(request.diffDigest()).isEqualTo(this.set.digest());
			response.accept(new ApprovalAnswer(request.requestId(), this.decision, false));
		}
		@Override public String readLine(String prompt) {
			this.proofs.incrementAndGet();
			return "exclusive " + this.set.changeSetId() + " " + this.set.digest();
		}
	}
}
