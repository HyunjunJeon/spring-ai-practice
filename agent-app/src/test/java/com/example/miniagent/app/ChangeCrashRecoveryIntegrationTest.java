package com.example.miniagent.app;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.run.RunBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChangeCrashRecoveryIntegrationTest {
	@TempDir Path directory;

	@Test void forcedJvmLossAfterPreparedPreservesBackupsAndBeforeHashes() throws Exception {
		crashAndRecover("change-prepared", "change-before");
	}
	@Test void forcedJvmLossBetweenFilesPreservesActualPartialApply() throws Exception {
		crashAndRecover("change-partial", "change-after");
	}
	@Test void humanDriftAfterCrashIsNotClobberedOnFreshJvmScan() throws Exception {
		crashAndRecover("change-prepared", "change-drift");
	}
	@Test void partialChangeJournalIsPreservedAndBlocksApplyAcrossRestart() throws Exception {
		crashAndRecover("change-prepared", "change-tail");
	}
	@Test void completeBadCrcChangeJournalIsPreservedAndBlocksApplyAcrossRestart() throws Exception {
		crashAndRecover("change-prepared", "change-corrupt");
	}

	private void crashAndRecover(String crashMode, String recoveryMode) throws Exception {
		Path home = Files.createDirectory(this.directory.resolve("home"));
		Path cwd = Files.createDirectory(this.directory.resolve("work")).toRealPath();
		Files.writeString(cwd.resolve("source.txt"), "before\n");
		Files.writeString(cwd.resolve("z-second.txt"), "second-before\n");
		ChangeStore.Snapshot acknowledged;
		try (var process = CrashRecoveryProcessFixture.launch(home, cwd, crashMode)) {
			process.awaitReady();
			// Independent OS lease acquisition must fail while PREPARED's engine owns it.
			assertThat(assertThrows(ChangeStore.Failure.class, () -> {
				try (var lease = new FileChangeStore(home, cwd).acquire()) { lease.read(); }
			}).code())
					.isEqualTo(ChangeStore.Code.LOCKED);
			assertThat(Files.readString(cwd.resolve("source.txt")))
					.isEqualTo(crashMode.equals("change-partial") ? "after\n" : "before\n");
			assertThat(Files.readString(cwd.resolve("z-second.txt"))).isEqualTo("second-before\n");
			process.kill();
		}
		// Reopening after forceful JVM loss must acquire the released shared lease.
		var store = new FileChangeStore(home, cwd);
		acknowledged = store.read().changes().getFirst();
		assertThat(acknowledged.changeSet().status()).isEqualTo(ChangeStatus.APPLYING);
		assertThat(acknowledged.checkpoints().getLast().state()).isEqualTo(Checkpoint.State.PREPARED);
		Path journal = store.directory().resolve("journal.log");
		byte[] original = Files.readAllBytes(journal);
		if (recoveryMode.equals("change-drift")) Files.writeString(cwd.resolve("source.txt"), "human edit\n");
		if (recoveryMode.equals("change-tail"))
			RunCrashRecoveryIntegrationTest.appendForced(journal, "1\t" + (acknowledged.sequence() + 1) + "\t00000000\t{");
		if (recoveryMode.equals("change-corrupt"))
			RunCrashRecoveryIntegrationTest.appendForced(journal, "1\t" + (acknowledged.sequence() + 1) + "\t00000000\t{}\n");
		byte[] damaged = Files.readAllBytes(journal);
		String source = Files.readString(cwd.resolve("source.txt"));
		String second = Files.readString(cwd.resolve("z-second.txt"));
		try (var restarted = CrashRecoveryProcessFixture.launch(home, cwd, recoveryMode)) {
			restarted.awaitSuccess();
		}
		assertThat(Files.readAllBytes(journal)).isEqualTo(damaged);
		assertThat(Files.readString(cwd.resolve("source.txt"))).isEqualTo(source);
		assertThat(Files.readString(cwd.resolve("z-second.txt"))).isEqualTo(second);
		if (!recoveryMode.equals("change-tail") && !recoveryMode.equals("change-corrupt"))
			assertThat(damaged).isEqualTo(original);
	}

	@Test void approvalRequiresExactOriginalDigestAndBaselineDriftMakesItStale() throws Exception {
		Path home = Files.createDirectory(this.directory.resolve("home"));
		Path cwd = Files.createDirectory(this.directory.resolve("work")).toRealPath();
		Files.writeString(cwd.resolve("source.txt"), "before\n");
		var runtime = new CrashRecoveryProcessFixture.RuntimeFixture(home, cwd, false);
		runtime.executor.startOperation("stage approved edit", CrashRecoveryProcessFixture.ORIGINAL,
				CrashRecoveryProcessFixture.SELECTED, cwd, RunBudget.defaults(), context -> {
					try { Files.writeString(StagedWorkspace.current().orElseThrow().root().resolve("source.txt"), "after\n"); }
					catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
					return null;
				});
		var set = runtime.changes.read().changes().getFirst().changeSet();
		byte[] undecided = Files.readAllBytes(runtime.changes.directory().resolve("journal.log"));
		try (var lease = runtime.changes.acquire()) {
			var approval = new Approval(UUID.randomUUID(), set.changeSetId(), set.digest(), set.owner(),
					"operator", ApprovalDecision.APPROVED, CrashRecoveryProcessFixture.CLOCK.instant());
			assertThat(assertThrows(ChangeStore.Failure.class,
					() -> lease.decide(set.changeSetId(), set.owner(), "0".repeat(64), approval)).code())
					.isEqualTo(ChangeStore.Code.IDENTITY_MISMATCH);
		}
		assertThat(Files.readAllBytes(runtime.changes.directory().resolve("journal.log"))).isEqualTo(undecided);
		try (var lease = runtime.changes.acquire()) {
			lease.decide(set.changeSetId(), set.owner(), set.digest(),
					new Approval(UUID.randomUUID(), set.changeSetId(), set.digest(), set.owner(),
							"operator", ApprovalDecision.APPROVED, CrashRecoveryProcessFixture.CLOCK.instant()));
		}
		var engine = new ApplyEngine(runtime.changes, new PathPolicy(cwd, home, List.of()), CrashRecoveryProcessFixture.CLOCK);
		var console = new AgentConsole(new BufferedReader(new StringReader(
				"exclusive " + set.changeSetId() + " " + set.digest() + "\n")), new PrintStream(OutputStream.nullOutputStream()), true);
		var proof = engine.confirmExclusiveOwnership(console, set.changeSetId(), set.owner(), set.digest());
		Files.writeString(cwd.resolve("source.txt"), "human edit\n");
		assertThat(assertThrows(ApplyEngine.Failure.class,
				() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof)).code()).isEqualTo(ApplyEngine.Code.STALE);
		assertThat(runtime.changes.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.STALE);
		assertThat(runtime.changes.read().get(set.changeSetId()).checkpoints()).isEmpty();
		assertThat(Files.readString(cwd.resolve("source.txt"))).isEqualTo("human edit\n");
	}
}
