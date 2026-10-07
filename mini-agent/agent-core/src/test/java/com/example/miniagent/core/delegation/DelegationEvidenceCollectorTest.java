package com.example.miniagent.core.delegation;

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

import com.example.miniagent.core.change.*;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

/** Real store acknowledgements and runner processes, not a runtime verification adapter. */
class DelegationEvidenceCollectorTest {
	@TempDir Path work;
	@TempDir Path home;
	private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
	private final ModelRef model = new ModelRef("fake", "test");
	private final String config = "a".repeat(64);
	private final UUID parentConversation = UUID.randomUUID();
	private final RunId parentRun = RunId.random();
	private FileRunStore runs;
	private ToolEvidenceStore tools;
	private FileChangeStore changes;
	private DelegationEvidenceCollector collector;

	private void prepare() throws Exception {
		Files.writeString(this.work.resolve("source.txt"), "before\n");
		this.runs = new FileRunStore(this.home);
		this.tools = new ToolEvidenceStore(this.home);
		this.changes = new FileChangeStore(this.home, this.work);
		this.collector = new DelegationEvidenceCollector(this.runs, this.tools, this.changes);
	}

	private final class Fixture implements AutoCloseable {
		final ChildSpec child;
		final UUID conversation = UUID.randomUUID();
		final UUID attempt = UUID.randomUUID();
		final UUID binding = UUID.randomUUID();
		final RunId run = RunId.random();
		final ChangeOwner owner;
		final RunSpec spec;
		final RunWriter writer;
		StagedWorkspace stage;
		String resultDigest;
		final Map<UUID, VerificationRunner.Request> verificationRequests = new java.util.HashMap<>();

		Fixture(String id, ChildRole role) throws Exception {
			this.owner = new ChangeOwner(this.conversation.toString(), this.run, this.attempt.toString(),
					UUID.randomUUID().toString());
			this.child = new ChildSpec(id, "Collect source evidence", role, model, Set.of(),
					new ToolScope(Set.of("read_file", "write_file"), Set.of(work.toRealPath()),
							role == ChildRole.IMPLEMENT ? Set.of(work.toRealPath()) : Set.of(),
							Set.of(), role == ChildRole.IMPLEMENT, false, false, false),
					RunBudget.defaults(), 1, true);
			this.spec = new RunSpec(1, this.run, this.conversation.toString(), parentConversation.toString(),
					parentRun, id, model, "fake", work.toRealPath().toString(), RunBudget.defaults(),
					config, config, Instant.EPOCH);
			this.writer = runs.create(this.spec);
			this.writer.append(new RunEvent.StateChanged(Instant.EPOCH, RunState.RUNNING, RunEvent.Reason.NONE));
			this.writer.append(new RunEvent.AttemptStarted(Instant.EPOCH, this.attempt.toString(), this.binding));
		}

		void stage() throws Exception {
			stage(this.binding);
		}

		void stage(UUID stageBinding) throws Exception {
			ChatModel fake = prompt -> new ChatResponse(List.of());
			var bound = new ModelBinding(stageBinding, model, config, "fake", fake, ChatClient.builder(fake).build());
			var turn = new TurnContext(this.owner.sessionId(), this.owner.turnId(), work.toRealPath(),
					bound, this.run.value(), this.attempt.toString());
			this.stage = StagedWorkspace.open(changes, turn, clock);
		}

		void acknowledge(String name, String arguments, boolean mutation, java.util.concurrent.Callable<String> action) throws Exception {
			var workspace = WorkspaceFingerprint.capture(work, home);
			var snapshot = new DriftGate.Snapshot(1,
					Fingerprinter.sha256(JsonMapper.builder().build().writeValueAsBytes(this.spec)), workspace,
					Map.of(name, new DriftGate.Definition(config,
							mutation ? SideEffectClass.MUTATING : SideEffectClass.READ_ONLY)), config);
			String digest = tools.put(snapshot);
			String argsDigest = Fingerprinter.sha256(arguments.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			this.writer.append(new RunEvent.ToolStarted(Instant.EPOCH, this.attempt.toString(), "call", name,
					argsDigest, mutation ? RunEvent.Effect.MUTATING : RunEvent.Effect.READ_ONLY, digest));
			String output = action.call();
			this.resultDigest = tools.put(new ToolEvidenceStore.ResultEvidence(1, this.run.value(),
					this.attempt.toString(), "call", name, argsDigest, output, snapshot));
			this.writer.append(new RunEvent.ToolFinished(Instant.EPOCH, this.attempt.toString(), "call", this.resultDigest));
		}

		ChildResult finish() throws Exception {
			this.writer.append(new RunEvent.UsageRecorded(Instant.EPOCH, this.attempt.toString(),
					RunEvent.UsageKind.MODEL_ROUND, "round", new RunUsage(7, true, 1, 2, 3)));
			this.writer.append(new RunEvent.AttemptEnded(Instant.EPOCH, this.attempt.toString()));
			this.writer.append(new RunEvent.StateChanged(Instant.EPOCH, RunState.COMPLETED, RunEvent.Reason.NONE));
			return result(ChildStatus.SUCCEEDED, List.of(new ChildEvidence.ModelNarrative("Done")));
		}

		ChildResult result(ChildStatus status, List<ChildEvidence> evidence) {
			return new ChildResult(this.child.childId(), this.conversation, this.run, this.attempt, model,
					status, null, evidence, RunUsage.empty());
		}

		DelegationEvidenceCollector.Request request(List<DelegationEvidenceCollector.ReadInput> reads,
				VerificationRunner.Result verification) throws Exception {
			String revision = this.stage == null
					? Fingerprinter.treeDigest(new Fingerprinter(new PathPolicy(work, home, List.of())).snapshot())
					: Fingerprinter.treeDigest(changes.read().changes().stream()
							.filter(s -> s.changeSet().owner().equals(this.owner)).findFirst().orElseThrow().changeSet().proposed());
			String configuration = verification == null ? config : verification.evidence().configurationDigest();
			return new DelegationEvidenceCollector.Request(this.child, this.owner, parentRun, parentConversation,
					revision, configuration, reads, this.stage == null ? null : this.stage.policy(),
					verification == null ? null : this.verificationRequests.get(verification.evidence().evidenceId()), verification);
		}

		@Override public void close() throws Exception {
			if (this.stage != null) this.stage.close();
			this.writer.close();
		}
	}

	private String mutate(Fixture fixture) {
		try {
			Files.writeString(fixture.stage.resolve("source.txt", true, List.of()), "after\n");
			fixture.stage.record();
			return Files.readString(fixture.stage.resolve("source.txt", false, List.of()));
		}
		catch (Exception ex) { throw new RuntimeException(ex); }
	}

	private VerificationRunner.Result verify(Fixture fixture, String command) throws Exception {
		var set = this.changes.read().changes().stream().filter(s -> s.changeSet().owner().equals(fixture.owner))
				.findFirst().orElseThrow().changeSet();
		var runner = new VerificationRunner(this.changes, (context, argv) ->
				new VerificationRunner.Launch(argv, Map.of(), this.config), this.clock);
		var request = new VerificationRunner.Request(set.changeSetId(), fixture.owner, set.digest(),
				Fingerprinter.treeDigest(set.proposed()), List.of(command), Duration.ofSeconds(5), 100);
		var result = runner.run(request, new CompletableFuture<>());
		fixture.verificationRequests.put(result.evidence().evidenceId(), request);
		return result;
	}

	@Test void mixedRolesCompleteFromOwnedStoresAndActualVerificationWithoutResearchProcess() throws Exception {
		prepare();
		try (var research = new Fixture("research", ChildRole.RESEARCH);
				var implementation = new Fixture("implementation", ChildRole.IMPLEMENT);
				var verification = new Fixture("verification", ChildRole.VERIFY)) {
			String args = "{\"path\":\"source.txt\"}";
			research.acknowledge("read_file", args, false, () -> {
				String read = Files.readString(this.work.resolve("source.txt"));
				assertThat(read).isEqualTo("before\n");
				return read;
			});
			var r = research.finish();
			var rq = research.request(List.of(new DelegationEvidenceCollector.ReadInput("call", args)), null);
			implementation.stage();
			implementation.acknowledge("write_file", "{\"path\":\"source.txt\",\"content\":\"after\\n\"}",
					true, () -> mutate(implementation));
			var i = implementation.finish();
			var iq = implementation.request(List.of(), null);
			verification.stage();
			mutate(verification);
			var observed = verify(verification, "/usr/bin/true");
			assertThat(observed.code()).isEqualTo(VerificationRunner.Code.SUCCEEDED);
			var v = verification.finish();
			var vq = verification.request(List.of(), observed);
			var plan = new DelegationPlan("plan", List.of(research.child, implementation.child, verification.child), 1);
			var aggregate = this.collector.aggregate(plan, List.of(r, i, v), false,
					Map.of("research", rq, "implementation", iq, "verification", vq));
			assertThat(aggregate.status()).isEqualTo(DelegationResult.Status.COMPLETE);
			assertThat(aggregate.usage()).isEqualTo(new RunUsage(21, true, 3, 6, 9));
			assertThat(aggregate.children().getFirst().evidence()).noneMatch(ChildEvidence.Verification.class::isInstance);
			assertThat(Files.readString(this.work.resolve("source.txt"))).isEqualTo("before\n");
			assertThat(this.collector.aggregate(plan, List.of(r, i, v), true,
					Map.of("research", rq, "implementation", iq, "verification", vq)).status())
					.isEqualTo(DelegationResult.Status.CANCELLED);
		}
	}

	@Test void narrativeAndForgedReadArgumentsCannotCompleteAndCurrentSourceDriftRejects() throws Exception {
		prepare();
		try (var f = new Fixture("research", ChildRole.RESEARCH)) {
			String args = "{\"path\":\"source.txt\"}";
			f.acknowledge("read_file", args, false, () -> Files.readString(this.work.resolve("source.txt")));
			var result = f.finish();
			var request = f.request(List.of(new DelegationEvidenceCollector.ReadInput("call", args)), null);
			var plan = new DelegationPlan("plan", List.of(f.child), 1);
			assertThat(DelegationResult.aggregate(plan, List.of(result), false, request.revision(), this.config).status())
					.isEqualTo(DelegationResult.Status.FAILED);
			var forged = f.request(List.of(new DelegationEvidenceCollector.ReadInput("call", "{\"path\":\"other\"}")), null);
			assertThat(this.collector.aggregate(plan, List.of(result), false, Map.of("research", forged)).status())
					.isEqualTo(DelegationResult.Status.FAILED);
			Files.writeString(this.work.resolve("source.txt"), "drift\n");
			assertThatThrownBy(() -> this.collector.collect(request, result))
					.isInstanceOfSatisfying(DelegationEvidenceCollector.Rejection.class,
							ex -> assertThat(ex.code()).isEqualTo(DelegationEvidenceCollector.Code.STALE));
		}
	}

	@Test void foreignProposalBindingAndForgedPrivateResultMetadataReject() throws Exception {
		prepare();
		try (var f = new Fixture("implementation", ChildRole.IMPLEMENT)) {
			f.stage(UUID.randomUUID());
			f.acknowledge("write_file", "{}", true, () -> mutate(f));
			var result = f.finish();
			assertThatThrownBy(() -> this.collector.collect(f.request(List.of(), null), result))
					.isInstanceOfSatisfying(DelegationEvidenceCollector.Rejection.class,
							ex -> assertThat(ex.code()).isEqualTo(DelegationEvidenceCollector.Code.FOREIGN));
		}
		try (var f = new Fixture("research", ChildRole.RESEARCH)) {
			var snapshot = new DriftGate.Snapshot(1,
					Fingerprinter.sha256(JsonMapper.builder().build().writeValueAsBytes(f.spec)),
					WorkspaceFingerprint.capture(this.work, this.home),
					Map.of("read_file", new DriftGate.Definition(this.config, SideEffectClass.READ_ONLY)), this.config);
			String args = "{\"path\":\"source.txt\"}";
			String argsDigest = Fingerprinter.sha256(args.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			f.writer.append(new RunEvent.ToolStarted(Instant.EPOCH, f.attempt.toString(), "call", "read_file",
					argsDigest, RunEvent.Effect.READ_ONLY, this.tools.put(snapshot)));
			String forged = this.tools.put(new ToolEvidenceStore.ResultEvidence(1, "foreign-run", f.attempt.toString(),
					"call", "read_file", argsDigest, Files.readString(this.work.resolve("source.txt")), snapshot));
			f.writer.append(new RunEvent.ToolFinished(Instant.EPOCH, f.attempt.toString(), "call", forged));
			var result = f.finish();
			assertThatThrownBy(() -> this.collector.collect(f.request(
					List.of(new DelegationEvidenceCollector.ReadInput("call", args)), null), result))
					.isInstanceOfSatisfying(DelegationEvidenceCollector.Rejection.class,
							ex -> assertThat(ex.code()).isEqualTo(DelegationEvidenceCollector.Code.FOREIGN));
		}
	}

	@Test void unfinishedToolAndUnstartedFailureCannotCompleteOrLosePartialResults() throws Exception {
		prepare();
		try (var f = new Fixture("research", ChildRole.RESEARCH)) {
			String args = "{\"path\":\"source.txt\"}";
			f.acknowledge("read_file", args, false, () -> Files.readString(this.work.resolve("source.txt")));
			var result = f.finish();
			var request = f.request(List.of(new DelegationEvidenceCollector.ReadInput("call", args)), null);
			var failedSpec = new ChildSpec("unstarted", "Unavailable model", ChildRole.RESEARCH, this.model,
					Set.of(), f.child.scope(), RunBudget.defaults(), 1, true);
			var failedOwner = new ChangeOwner(UUID.randomUUID().toString(), RunId.random(),
					UUID.randomUUID().toString(), UUID.randomUUID().toString());
			var failure = new ChildFailure(ChildFailure.Code.MODEL_UNAVAILABLE, "Unavailable", false);
			var failed = new ChildResult("unstarted", UUID.fromString(failedOwner.sessionId()), failedOwner.runId(),
					UUID.fromString(failedOwner.attemptId()), this.model, ChildStatus.FAILED, failure, List.of(), RunUsage.empty());
			var failedRequest = new DelegationEvidenceCollector.Request(failedSpec, failedOwner, this.parentRun,
					this.parentConversation, request.revision(), this.config, List.of(), null, null, null);
			var plan = new DelegationPlan("plan", List.of(f.child, failedSpec), 1);
			assertThat(this.collector.aggregate(plan, List.of(result, failed), false,
					Map.of(f.child.childId(), request, "unstarted", failedRequest)).status())
					.isEqualTo(DelegationResult.Status.PARTIAL);
		}
		try (var f = new Fixture("unknown", ChildRole.RESEARCH)) {
			f.writer.append(new RunEvent.ToolStarted(Instant.EPOCH, f.attempt.toString(), "unknown-call",
					"read_file", this.config, RunEvent.Effect.READ_ONLY));
			f.writer.append(new RunEvent.UsageRecorded(Instant.EPOCH, f.attempt.toString(),
					RunEvent.UsageKind.TOOLS, "usage", RunUsage.empty()));
			f.writer.append(new RunEvent.AttemptEnded(Instant.EPOCH, f.attempt.toString()));
			f.writer.append(new RunEvent.StateChanged(Instant.EPOCH, RunState.FAILED, RunEvent.Reason.ERROR));
			var request = f.request(List.of(), null);
			assertThat(this.collector.aggregate(new DelegationPlan("plan", List.of(f.child), 1),
					List.of(f.result(ChildStatus.SUCCEEDED, List.of())), false,
					Map.of(f.child.childId(), request)).status()).isEqualTo(DelegationResult.Status.FAILED);
		}
	}

	@Test void proposalDigestBindingAndRetainedStageMustStillMatch() throws Exception {
		prepare();
		try (var f = new Fixture("implementation", ChildRole.IMPLEMENT)) {
			f.stage();
			f.acknowledge("write_file", "{}", true, () -> mutate(f));
			var result = f.finish();
			var request = f.request(List.of(), null);
			assertThat(this.collector.collect(request, result).verifiedSuccess(ChildRole.IMPLEMENT,
					request.revision(), this.config)).isTrue();
			Files.writeString(f.stage.resolve("source.txt", true, List.of()), "tampered");
			assertThatThrownBy(() -> this.collector.collect(request, result))
					.isInstanceOfSatisfying(DelegationEvidenceCollector.Rejection.class,
							ex -> assertThat(ex.code()).isEqualTo(DelegationEvidenceCollector.Code.STALE));
		}
	}

	@Test void actualNonzeroAndStaleZeroExitAndForgedLogCannotProveVerification() throws Exception {
		prepare();
		try (var f = new Fixture("verification", ChildRole.VERIFY)) {
			f.stage();
			mutate(f);
			var failed = verify(f, "/usr/bin/false");
			var succeeded = verify(f, "/usr/bin/true");
			var result = f.finish();
			var stale = new VerificationRunner.Result(VerificationRunner.Code.STALE, succeeded.evidence());
			for (var observed : List.of(failed, stale)) {
				assertThatThrownBy(() -> this.collector.collect(f.request(List.of(), observed), result))
						.isInstanceOfSatisfying(DelegationEvidenceCollector.Rejection.class,
								ex -> assertThat(ex.code()).isEqualTo(DelegationEvidenceCollector.Code.FAILED));
			}
			var e = succeeded.evidence();
			var forged = new VerificationEvidence(e.evidenceId(), e.changeSetId(), e.owner(), e.argv(), e.cwd(),
					e.sourceCwd(), e.sourceRevision(), e.configurationDigest(), "b".repeat(64), e.exitCode(),
					e.cancelled(), e.startedAt(), e.finishedAt());
			assertThatThrownBy(() -> this.collector.collect(f.request(List.of(),
					new VerificationRunner.Result(VerificationRunner.Code.SUCCEEDED, forged)), result))
					.isInstanceOfSatisfying(DelegationEvidenceCollector.Rejection.class,
							ex -> assertThat(ex.code()).isEqualTo(DelegationEvidenceCollector.Code.FOREIGN));
			Files.writeString(this.work.resolve("source.txt"), "changed after exit0");
			assertThatThrownBy(() -> this.collector.collect(f.request(List.of(), succeeded), result))
					.isInstanceOfSatisfying(DelegationEvidenceCollector.Rejection.class,
							ex -> assertThat(ex.code()).isEqualTo(DelegationEvidenceCollector.Code.STALE));
		}
	}

	@Test void foreignVerificationOwnerMissingAndNonterminalChildrenStayRejected() throws Exception {
		prepare();
		try (var f = new Fixture("verification", ChildRole.VERIFY)) {
			f.stage();
			mutate(f);
			var observed = verify(f, "/usr/bin/true");
			assertThatThrownBy(() -> new ChildResult(f.child.childId(), UUID.randomUUID(), f.run, f.attempt,
					this.model, ChildStatus.SUCCEEDED, null,
					List.of(new ChildEvidence.Verification(observed.code(), observed.evidence())), RunUsage.empty()))
					.isInstanceOf(IllegalArgumentException.class);
			var request = f.request(List.of(), observed);
			assertThatThrownBy(() -> this.collector.collect(request, f.result(ChildStatus.RUNNING, List.of())))
					.isInstanceOfSatisfying(DelegationEvidenceCollector.Rejection.class,
							ex -> assertThat(ex.code()).isEqualTo(DelegationEvidenceCollector.Code.NONTERMINAL));
			var plan = new DelegationPlan("plan", List.of(f.child), 1);
			assertThatThrownBy(() -> this.collector.aggregate(plan, List.of(), false, Map.of(f.child.childId(), request)))
					.isInstanceOf(IllegalArgumentException.class);
			f.finish();
		}
	}
}
