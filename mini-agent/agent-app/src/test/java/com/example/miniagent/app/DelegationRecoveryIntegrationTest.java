package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DelegationRecoveryIntegrationTest {
	@TempDir Path directory;

	@Test void killedScopedChildRecoversOnlyWithExactOriginalAuthorityAndUnknownResolution() throws Exception {
		Path home = Files.createDirectory(this.directory.resolve("home"));
		Path cwd = Files.createDirectory(this.directory.resolve("work")).toRealPath();
		Files.writeString(cwd.resolve("source.txt"), "before\n");
		var runs = new FileRunStore(home);
		RunRecord parent, child;
		try (var process = CrashRecoveryProcessFixture.launch(home, cwd, "delegation-crash")) {
			process.awaitReady();
			parent = CrashRecoveryProcessFixture.root(runs);
			child = CrashRecoveryProcessFixture.child(runs);
			assertThat(parent.state()).isEqualTo(RunState.RUNNING);
			assertThat(child.state()).isEqualTo(RunState.RUNNING);
			assertThat(child.spec().parentRunId()).isEqualTo(parent.spec().runId());
			assertThat(child.spec().parentConversationId()).isEqualTo(parent.spec().conversationId());
			assertThat(child.spec().conversationId()).isNotEqualTo(parent.spec().conversationId());
			assertThat(child.attempts().getFirst().bindingId()).isNotEqualTo(parent.attempts().getFirst().bindingId());
			assertThat(child.uncertainTools()).hasSize(1);
			assertThat(assertThrows(RunStoreException.class, () -> {
				try (var writer = runs.openWriter(child.spec().runId())) { writer.record(); }
			}).code())
					.isEqualTo(RunStoreException.Code.RUN_LOCKED);
			try (var paths = Files.walk(home.resolve("changes"))) {
				var sources = paths.filter(path -> path.getFileName().toString().equals("source.txt")).toList();
				assertThat(sources).hasSize(2);
				assertThat(sources.stream().map(path -> {
					try { return Files.readString(path); }
					catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
				})).containsExactlyInAnyOrder("before\n", "staged-effect");
			}
			process.kill();
		}
		CrashRecoveryProcessFixture.lost(runs.read(child.spec().runId()));
		assertThat(runs.read(parent.spec().runId()).reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
		try (var restarted = CrashRecoveryProcessFixture.launch(home, cwd, "delegation-recover")) {
			restarted.awaitSuccess();
		}
		CrashRecoveryProcessFixture.resumed(child, runs.read(child.spec().runId()));
		assertThat(Files.readString(cwd.resolve("source.txt"))).isEqualTo("before\n");
	}
}
