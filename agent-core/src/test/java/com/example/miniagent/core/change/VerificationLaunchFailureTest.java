package com.example.miniagent.core.change;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** A process that cannot start is a typed IO outcome, never evidence, and leaves no private clone. */
class VerificationLaunchFailureTest {
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

	// Fixture-only policy: it swaps in a command that does not exist and claims no OS sandbox enforcement.
	private static VerificationRunner.Launch missingBinary(VerificationRunner.Context context) {
		return new VerificationRunner.Launch(List.of(context.temp().resolve("missing-binary").toString()), Map.of(),
				Fingerprinter.sha256("fixture-missing-binary".getBytes()));
	}

	@Test void missingExecutableIsTypedIoWithoutEvidenceAndRemovesThePrivateClone() throws Exception {
		var request = prepare();
		var contexts = new ArrayList<VerificationRunner.Context>();
		var runner = new VerificationRunner(this.f.store, (context, argv) -> {
			assertThat(context.root().getParent()).isDirectory();
			contexts.add(context);
			return missingBinary(context);
		}, EngineFixture.CLOCK);

		var result = runner.run(request, new CompletableFuture<>());

		assertThat(result.code()).isEqualTo(VerificationRunner.Code.IO);
		assertThat(result.evidence()).isNull();
		assertThat(contexts).hasSize(1);
		assertThat(contexts.getFirst().root().getParent()).doesNotExist();
		try (var entries = Files.list(this.f.stage.getParent())) {
			assertThat(entries.map(p -> p.getFileName().toString())).noneMatch(name -> name.startsWith("verify-"));
		}
		var stored = this.f.store.read().get(this.set.changeSetId());
		assertThat(stored.evidence()).isEmpty();
		assertThat(stored.changeSet().status()).isEqualTo(ChangeStatus.PENDING_APPROVAL);
	}

	@Test void failedLaunchLeavesTheProposalVerifiableByALaterRun() throws Exception {
		var request = prepare();
		var failing = new VerificationRunner(this.f.store, (context, argv) -> missingBinary(context), EngineFixture.CLOCK);
		assertThat(failing.run(request, new CompletableFuture<>()).code()).isEqualTo(VerificationRunner.Code.IO);

		var working = new VerificationRunner(this.f.store, (context, argv) -> new VerificationRunner.Launch(argv,
				Map.of(), Fingerprinter.sha256("fixture-working".getBytes())), EngineFixture.CLOCK);
		var result = working.run(request, new CompletableFuture<>());

		assertThat(result.code()).isEqualTo(VerificationRunner.Code.SUCCEEDED);
		assertThat(this.f.store.read().get(this.set.changeSetId()).evidence()).containsExactly(result.evidence());
	}
}
