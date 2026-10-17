package com.example.miniagent.core.change;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** The configuration digest follows the configured gate timeout, not the timeout enforced after clamping. */
class VerificationRunnerConfiguredTimeoutTest {
	@TempDir Path work;
	@TempDir Path state;
	private EngineFixture f;
	private VerificationRunner.Request request;
	private VerificationRunner runner;

	@BeforeEach void proposeAndPrepareRunner() throws Exception {
		this.f = new EngineFixture(this.work, this.state);
		this.f.file(this.work.resolve("source"), "before\n", 0644);
		var set = this.f.propose(stage -> this.f.file(stage.resolve("source"), "after\n", 0644), false);
		this.request = new VerificationRunner.Request(set.changeSetId(), set.owner(), set.digest(),
				Fingerprinter.treeDigest(set.proposed()), List.of("/bin/sh", "-c", "exit 0"), Duration.ofSeconds(10), 1024);
		// Fixture-only policy: these tests do not claim OS sandbox enforcement.
		this.runner = new VerificationRunner(this.f.store, (context, argv) -> new VerificationRunner.Launch(argv, Map.of(),
				Fingerprinter.sha256("fixture-configured-timeout".getBytes())), EngineFixture.CLOCK);
	}

	private String configurationFor(Duration enforced, Duration configured) throws Exception {
		var configuration = new CompletableFuture<String>();
		var target = new VerificationTarget(this.request.changeSetId(), this.request.owner(), this.request.digest(),
				this.request.sourceRevision(), false);
		this.runner.run(target, this.request.argv(), enforced, configured, this.request.maxOutputBytes(),
				new CompletableFuture<>(), binding -> configuration.complete(binding.configuration()));
		return configuration.join();
	}

	@Test void clampingTheEnforcedTimeoutLeavesTheConfigurationUnchanged() throws Exception {
		var configured = Duration.ofSeconds(30);

		assertThat(configurationFor(Duration.ofSeconds(2), configured))
				.isEqualTo(configurationFor(Duration.ofSeconds(5), configured));
	}

	@Test void aDifferentConfiguredTimeoutChangesTheConfiguration() throws Exception {
		var enforced = Duration.ofSeconds(5);

		assertThat(configurationFor(enforced, Duration.ofSeconds(30)))
				.isNotEqualTo(configurationFor(enforced, Duration.ofSeconds(31)));
	}
}
