package com.example.miniagent.core.change;

import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Core preflight refusal tests; real sandbox/process tests live in plugin-bash. */
class VerificationRunnerTest {
	@TempDir Path work;
	@TempDir Path state;
	private FileChangeStore store;
	private ChangeSet set;
	private Path stage;

	private VerificationRunner.Request prepare() throws Exception {
		Files.writeString(this.work.resolve("source"), "before");
		this.store = new FileChangeStore(this.state, this.work);
		var owner = new ChangeOwner("session", new RunId("run"), "attempt", "turn");
		var live = new PathPolicy(this.work, this.state, List.of());
		var baseline = new Fingerprinter(live).snapshot();
		try (var lease = this.store.acquire()) { this.stage = lease.stagingDirectory(owner); }
		Files.copy(this.work.resolve("source"), this.stage.resolve("source"), StandardCopyOption.COPY_ATTRIBUTES);
		Files.writeString(this.stage.resolve("source"), "after");
		var staged = PathPolicy.stagedView(this.stage, live);
		var proposed = new Fingerprinter(staged).snapshot();
		var diff = new DiffRenderer(live).render(baseline.get("source"), "before".getBytes(),
				proposed.get("source"), "after".getBytes());
		this.set = ChangeSet.propose(UUID.randomUUID(), owner, this.store.workspace().toString(),
				new ModelRef("fake", "test"), List.of(UUID.randomUUID()), baseline, proposed, List.of(diff), Clock.systemUTC().instant());
		try (var lease = this.store.acquire()) { lease.propose(this.set); }
		return new VerificationRunner.Request(this.set.changeSetId(), owner, this.set.digest(),
				Fingerprinter.treeDigest(proposed), List.of("/bin/sh", "-c", "exit 0"), Duration.ofSeconds(5), 100);
	}

	@Test void unsupportedSandboxIsTypedBeforeRequestedCommandLaunchAndCloneIsCleaned() throws Exception {
		var request = prepare();
		var seen = new CompletableFuture<VerificationRunner.Context>();
		var runner = new VerificationRunner(this.store, (context, argv) -> {
			seen.complete(context);
			throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, null);
		}, Clock.systemUTC());
		var result = runner.run(request, new CompletableFuture<>());
		assertThat(result.code()).isEqualTo(VerificationRunner.Code.UNSUPPORTED_SANDBOX);
		assertThat(result.evidence()).isNull();
		assertThat(seen.join().root().getParent()).doesNotExist();
		assertThat(this.store.read().get(this.set.changeSetId()).evidence()).isEmpty();
	}

	@Test void changedStageOrLiveRevisionRefusesBeforeSandboxPreparation() throws Exception {
		var request = prepare();
		var preparations = new AtomicInteger();
		var runner = new VerificationRunner(this.store, (context, argv) -> {
			preparations.incrementAndGet();
			throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, null);
		}, Clock.systemUTC());
		Files.writeString(this.stage.resolve("source"), "not reviewed");
		assertThat(runner.run(request, new CompletableFuture<>()).code()).isEqualTo(VerificationRunner.Code.STALE);
		Files.writeString(this.stage.resolve("source"), "after");
		Files.writeString(this.work.resolve("source"), "external");
		assertThat(runner.run(request, new CompletableFuture<>()).code()).isEqualTo(VerificationRunner.Code.STALE);
		assertThat(preparations).hasValue(0);
	}

	@Test void wrongOwnerAndDigestNeverReachLaunchPolicy() throws Exception {
		var request = prepare();
		var runner = new VerificationRunner(this.store, (context, argv) -> {
			throw new AssertionError("Unexpected policy invocation");
		}, Clock.systemUTC());
		var other = new ChangeOwner("other", request.owner().runId(), "attempt", "turn");
		var wrong = new VerificationRunner.Request(request.changeSetId(), other, request.digest(),
				request.sourceRevision(), request.argv(), request.timeout(), request.maxOutputBytes());
		assertThatThrownBy(() -> runner.run(wrong, new CompletableFuture<>()))
				.isInstanceOfSatisfying(ChangeStore.Failure.class,
						ex -> assertThat(ex.code()).isEqualTo(ChangeStore.Code.IDENTITY_MISMATCH));
		var badDigest = new VerificationRunner.Request(request.changeSetId(), request.owner(), "b".repeat(64),
				request.sourceRevision(), request.argv(), request.timeout(), request.maxOutputBytes());
		assertThatThrownBy(() -> runner.run(badDigest, new CompletableFuture<>()))
				.isInstanceOfSatisfying(ChangeStore.Failure.class,
						ex -> assertThat(ex.code()).isEqualTo(ChangeStore.Code.IDENTITY_MISMATCH));
	}

	@Test void preexistingCancellationAndInterruptNeverLaunchAndPreserveInterrupt() throws Exception {
		var request = prepare();
		var runner = new VerificationRunner(this.store, (context, argv) -> {
			throw new AssertionError("Unexpected policy invocation");
		}, Clock.systemUTC());
		var cancellation = CompletableFuture.<Void>completedFuture(null);
		assertThat(runner.run(request, cancellation).code()).isEqualTo(VerificationRunner.Code.CANCELLED);
		Thread.currentThread().interrupt();
		try {
			assertThat(runner.run(request, new CompletableFuture<>()).code()).isEqualTo(VerificationRunner.Code.CANCELLED);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally { Thread.interrupted(); }
	}
}
