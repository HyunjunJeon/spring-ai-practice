package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.miniagent.core.change.ChangeStatus;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.change.Checkpoint;
import com.example.miniagent.core.change.FileChangeStore;
import com.example.miniagent.core.change.Fingerprinter;
import com.example.miniagent.core.change.PathPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A change left APPLYING by a killed JVM is undone by a different, freshly started JVM. */
class ChangeCrashUndoIntegrationTest {
	@TempDir Path directory;
	private Path home;
	private Path cwd;
	private Path user;

	@BeforeEach
	void workspace() throws Exception {
		this.home = Files.createDirectory(this.directory.resolve("home"));
		this.cwd = Files.createDirectory(this.directory.resolve("work")).toRealPath();
		this.user = Files.createDirectory(this.directory.resolve("user"));
		Files.writeString(this.cwd.resolve("source.txt"), "before\n");
		Files.writeString(this.cwd.resolve("z-second.txt"), "second-before\n");
	}

	@Test void freshJvmUndoAfterLossAtPreparedRestoresBaseline() throws Exception {
		crash("change-prepared");
		assertThat(Files.readString(this.cwd.resolve("source.txt"))).isEqualTo("before\n");
		undoInFreshJvm();
		assertUndoneAtBaseline();
	}

	@Test void freshJvmUndoAfterLossBetweenFilesRestoresBaseline() throws Exception {
		crash("change-partial");
		assertThat(Files.readString(this.cwd.resolve("source.txt"))).isEqualTo("after\n");
		undoInFreshJvm();
		assertUndoneAtBaseline();
	}

	@Test void undoLostBetweenFilesFinishesOnTheNextJvm() throws Exception {
		crash("change-partial");
		try (var undo = ChangeCrashProcessFixture.launch(this.home, this.cwd, this.user, "undo-crash")) {
			undo.awaitReady();
			// The undo engine still owns the lease when the kill lands.
			assertThat(assertThrows(ChangeStore.Failure.class, () -> {
				try (var lease = new FileChangeStore(this.home, this.cwd).acquire()) { lease.read(); }
			}).code()).isEqualTo(ChangeStore.Code.LOCKED);
			undo.kill();
		}
		var interrupted = new FileChangeStore(this.home, this.cwd).read().changes().getFirst();
		assertThat(interrupted.changeSet().status()).isEqualTo(ChangeStatus.RECOVERY_REQUIRED);
		assertThat(interrupted.checkpoints()).extracting(c -> c.before().relativePath(), Checkpoint::state)
				.containsExactly(tuple("source.txt", Checkpoint.State.RESTORED),
						tuple("z-second.txt", Checkpoint.State.PREPARED));
		assertThat(Files.readString(this.cwd.resolve("source.txt"))).isEqualTo("before\n");
		undoInFreshJvm();
		assertUndoneAtBaseline();
	}

	private void crash(String mode) throws Exception {
		try (var process = CrashRecoveryProcessFixture.launch(this.home, this.cwd, mode)) {
			process.awaitReady();
			process.kill();
		}
		var lost = new FileChangeStore(this.home, this.cwd).read().changes().getFirst();
		assertThat(lost.changeSet().status()).isEqualTo(ChangeStatus.APPLYING);
		assertThat(lost.checkpoints().getLast().state()).isEqualTo(Checkpoint.State.PREPARED);
		assertThat(Files.readString(this.cwd.resolve("z-second.txt"))).isEqualTo("second-before\n");
	}

	private void undoInFreshJvm() throws Exception {
		try (var undo = ChangeCrashProcessFixture.launch(this.home, this.cwd, this.user, "undo")) {
			undo.awaitSuccess();
			assertThat(undo.output.toString()).contains("STATUS UNDONE");
		}
	}

	private void assertUndoneAtBaseline() throws Exception {
		var view = new FileChangeStore(this.home, this.cwd).read();
		var snapshot = view.changes().getFirst();
		assertThat(snapshot.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		assertThat(snapshot.checkpoints()).isNotEmpty().allMatch(c -> c.state() == Checkpoint.State.RESTORED);
		assertThat(view.recoveryRequired()).isFalse();
		assertThat(Files.readString(this.cwd.resolve("source.txt"))).isEqualTo("before\n");
		assertThat(Files.readString(this.cwd.resolve("z-second.txt"))).isEqualTo("second-before\n");
		assertThat(new Fingerprinter(new PathPolicy(this.cwd, this.home, List.of())).snapshot())
				.isEqualTo(snapshot.changeSet().baseline());
	}
}
