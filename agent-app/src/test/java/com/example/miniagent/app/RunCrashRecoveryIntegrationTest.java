package com.example.miniagent.app;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunCrashRecoveryIntegrationTest {
	@TempDir Path directory;

	@Test void forcedJvmLossRequiresExplicitUnknownResolutionAndFreshAttempt() throws Exception {
		crashAndRecover("run-recover", false, false);
	}

	@Test void knownPartialTailLoadsReadOnlyThenRecoversOnFreshJvmWriterAppend() throws Exception {
		crashAndRecover("run-tail", true, false);
	}

	@Test void completeCrcCorruptionIsRejectedAndPreservedAcrossFreshJvm() throws Exception {
		crashAndRecover("run-corrupt", false, true);
	}

	private void crashAndRecover(String mode, boolean tail, boolean corrupt) throws Exception {
		Path home = Files.createDirectory(this.directory.resolve("home"));
		Path cwd = Files.createDirectory(this.directory.resolve("work")).toRealPath();
		Files.writeString(cwd.resolve("source.txt"), "before\n");
		var store = new FileRunStore(home);
		RunRecord active;
		try (var process = CrashRecoveryProcessFixture.launch(home, cwd, "run-crash")) {
			process.awaitReady();
			active = CrashRecoveryProcessFixture.root(store);
			assertThat(active.state()).isEqualTo(RunState.RUNNING);
			assertThat(active.uncertainTools()).hasSize(1);
			assertThat(active.uncertainTools().getFirst().snapshotDigest()).isNotNull();
			assertThat(assertThrows(RunStoreException.class, () -> {
				try (var writer = store.openWriter(active.spec().runId())) { writer.record(); }
			}).code())
					.isEqualTo(RunStoreException.Code.RUN_LOCKED);
			// The callback has actually changed its stage, not just appended fake metadata.
			try (var paths = Files.walk(home.resolve("changes"))) {
				var staged = paths.filter(p -> p.getFileName().toString().equals("source.txt")).toList();
				assertThat(staged).hasSize(1);
				assertThat(Files.readString(staged.getFirst())).isEqualTo("staged-effect");
			}
			process.kill();
		}
		CrashRecoveryProcessFixture.lost(store.read(active.spec().runId()));
		Path log = home.resolve("runs").resolve(active.spec().runId().value()).resolve("events.log");
		if (tail) appendForced(log, "1\t" + (active.sequence() + 1) + "\t00000000\t{");
		if (corrupt) {
			String original = Files.readString(log);
			String changed = original.replaceFirst("RUNNING", "FAILED");
			assertThat(changed).isNotEqualTo(original);
			try (var channel = FileChannel.open(log, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				writeForced(channel, changed);
			}
		}
		try (var restarted = CrashRecoveryProcessFixture.launch(home, cwd, mode)) {
			restarted.awaitSuccess();
		}
		assertThat(Files.readString(cwd.resolve("source.txt"))).isEqualTo("before\n");
	}

	static void appendForced(Path file, String text) throws Exception {
		try (var channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
			writeForced(channel, text);
		}
	}
	private static void writeForced(FileChannel channel, String text) throws Exception {
		var buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
		while (buffer.hasRemaining()) channel.write(buffer);
		channel.force(true);
	}
}
