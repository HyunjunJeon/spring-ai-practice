package com.example.miniagent.core.change;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import com.example.miniagent.core.approval.ApprovalDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

/** Only an eligible proposal can be verified, and eligibility is checked again before evidence is recorded. */
class VerificationRunnerEligibilityTest {
	@TempDir Path work;
	@TempDir Path state;
	private EngineFixture f;
	private ChangeSet set;

	private VerificationRunner.Request prepare() throws Exception {
		this.f = new EngineFixture(this.work, this.state);
		this.f.file(this.work.resolve("source"), "before\n", 0644);
		this.set = this.f.propose(stage -> this.f.file(stage.resolve("source"), "after\n", 0644), false);
		return new VerificationRunner.Request(this.set.changeSetId(), this.set.owner(), this.set.digest(),
				Fingerprinter.treeDigest(this.set.proposed()), List.of("/bin/sh", "-c", "exit 0"),
				Duration.ofSeconds(10), 1024);
	}

	// Fixture-only policy: these tests do not claim OS sandbox enforcement.
	private static VerificationRunner.Launch launch(List<String> argv) {
		return new VerificationRunner.Launch(argv, Map.of(), Fingerprinter.sha256("fixture-eligibility".getBytes()));
	}

	private void settle(ChangeStatus status) throws Exception {
		UUID id = this.set.changeSetId();
		var owner = this.set.owner();
		String digest = this.set.digest();
		if (status == ChangeStatus.UNDONE) {
			try (var lease = this.f.store.acquire()) { lease.decide(id, owner, digest, this.f.approval(this.set)); }
			var engine = this.f.applyEngine();
			engine.apply(id, owner, digest, this.f.proof(engine, this.set));
			new UndoEngine(this.f.store, this.f.policy, EngineFixture.CLOCK)
					.undo(id, owner, digest, this.f.proof(engine, this.set));
			return;
		}
		try (var lease = this.f.store.acquire()) {
			if (status == ChangeStatus.REJECTED) lease.decide(id, owner, digest, new Approval(UUID.randomUUID(), id,
					digest, owner, "console", ApprovalDecision.REJECTED, EngineFixture.AT));
			else lease.transition(id, owner, digest, status);
		}
	}

	@ParameterizedTest
	@EnumSource(value = ChangeStatus.class, names = { "REJECTED", "SUPERSEDED", "STALE", "UNDONE" })
	void ineligibleProposalIsStaleBeforeLaunchAndRecordsNoEvidence(ChangeStatus status) throws Exception {
		var request = prepare();
		settle(status);
		var preparations = new AtomicInteger();
		var runner = new VerificationRunner(this.f.store, (context, argv) -> {
			preparations.incrementAndGet();
			return launch(argv);
		}, EngineFixture.CLOCK);

		var result = runner.run(request, new CompletableFuture<>());

		assertThat(result.code()).isEqualTo(VerificationRunner.Code.STALE);
		assertThat(result.evidence()).isNull();
		assertThat(preparations).hasValue(0);
		var stored = this.f.store.read().get(this.set.changeSetId());
		assertThat(stored.changeSet().status()).isEqualTo(status);
		assertThat(stored.evidence()).isEmpty();
	}

	@Test void unchangedEligibleProposalIsAcceptedWithTheSameLaunch() throws Exception {
		var request = prepare();
		var configuration = new CompletableFuture<String>();
		var runner = new VerificationRunner(this.f.store, (context, argv) -> launch(argv), EngineFixture.CLOCK);

		var result = runner.run(new VerificationTarget(request.changeSetId(), request.owner(), request.digest(),
				request.sourceRevision(), false), request.argv(), request.timeout(), request.maxOutputBytes(),
				new CompletableFuture<>(), binding -> configuration.complete(binding.configuration())).result();

		assertThat(result.code()).isEqualTo(VerificationRunner.Code.SUCCEEDED);
		assertThat(result.accepted(request.sourceRevision(), configuration.join())).isTrue();
		assertThat(this.f.store.read().get(this.set.changeSetId()).evidence()).containsExactly(result.evidence());
	}

	@Test void proposalSupersededWhileTheProcessRunsIsStaleButKeepsItsHonestEvidence() throws Exception {
		var request = prepare();
		var configuration = new CompletableFuture<String>();
		var runner = new VerificationRunner(this.f.store, (context, argv) -> {
			// The runner holds no lease while the policy prepares; another operator decision lands here.
			try (var lease = this.f.store.acquire()) {
				lease.transition(this.set.changeSetId(), this.set.owner(), this.set.digest(), ChangeStatus.SUPERSEDED);
			}
			return launch(argv);
		}, EngineFixture.CLOCK);

		var result = runner.run(new VerificationTarget(request.changeSetId(), request.owner(), request.digest(),
				request.sourceRevision(), false), request.argv(), request.timeout(), request.maxOutputBytes(),
				new CompletableFuture<>(), binding -> configuration.complete(binding.configuration())).result();

		assertThat(result.code()).isEqualTo(VerificationRunner.Code.STALE);
		assertThat(result.evidence().exitCode()).isZero();
		assertThat(result.evidence().cancelled()).isFalse();
		assertThat(result.accepted(request.sourceRevision(), configuration.join())).isFalse();
		var stored = this.f.store.read().get(this.set.changeSetId());
		assertThat(stored.changeSet().status()).isEqualTo(ChangeStatus.SUPERSEDED);
		assertThat(stored.evidence()).containsExactly(result.evidence());
		assertThat(Path.of(result.evidence().cwd())).doesNotExist();
	}
}
