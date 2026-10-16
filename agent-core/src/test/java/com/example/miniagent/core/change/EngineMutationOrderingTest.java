package com.example.miniagent.core.change;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** Reviewed directory modes without owner write must not strand apply or undo of their children. */
class EngineMutationOrderingTest {
	@TempDir Path work;
	@TempDir Path state;

	@Test void newDirectoryWithoutOwnerWriteAppliesWithItsChildAndUndoesCompletely() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> {
			f.file(stage.resolve("x/f"), "child\n", 0644);
			Files.setPosixFilePermissions(stage.resolve("x"), EngineMutation.permissions(0500));
		}, true);
		var engine = f.applyEngine();
		var applied = engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		assertThat(applied.changeSet().status()).isEqualTo(ChangeStatus.APPLIED);
		assertThat(f.printer().snapshot()).isEqualTo(set.proposed());
		assertThat(Files.readString(this.work.resolve("x/f"))).isEqualTo("child\n");
		assertThat(Files.getPosixFilePermissions(this.work.resolve("x")))
				.isEqualTo(PosixFilePermissions.fromString("r-x------"));

		var undone = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		assertThat(undone.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		assertThat(undone.checkpoints()).allMatch(c -> c.state() == Checkpoint.State.RESTORED);
		assertThat(f.printer().snapshot()).isEqualTo(set.baseline());
		assertThat(this.work.resolve("x")).doesNotExist();
	}

	@Test void directoryMadeReadOnlyGetsItsOriginalChildrenBackOnUndo() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("d/old"), "old\n", 0644);
		Files.setPosixFilePermissions(this.work.resolve("d"), EngineMutation.permissions(0755));
		var set = f.propose(stage -> {
			Files.delete(stage.resolve("d/old"));
			f.file(stage.resolve("d/new"), "new\n", 0644);
			Files.setPosixFilePermissions(stage.resolve("d"), EngineMutation.permissions(0500));
		}, true);
		var engine = f.applyEngine();
		var applied = engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		assertThat(applied.changeSet().status()).isEqualTo(ChangeStatus.APPLIED);
		assertThat(Files.getPosixFilePermissions(this.work.resolve("d")))
				.isEqualTo(PosixFilePermissions.fromString("r-x------"));

		var undone = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		assertThat(undone.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		assertThat(f.printer().snapshot()).isEqualTo(set.baseline());
		assertThat(Files.readString(this.work.resolve("d/old"))).isEqualTo("old\n");
		assertThat(this.work.resolve("d/new")).doesNotExist();
		assertThat(Files.getPosixFilePermissions(this.work.resolve("d")))
				.isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
	}

	@Test void orderRunsDeletesDeepestFirstThenNewDirectoriesThenFilesThenModesAndMirrorsForUndo() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("a/b/c"), "gone\n", 0644);
		f.file(this.work.resolve("s"), "before\n", 0644);
		Files.createDirectory(this.work.resolve("d"));
		Files.setPosixFilePermissions(this.work.resolve("d"), EngineMutation.permissions(0755));
		var set = f.propose(stage -> {
			Files.delete(stage.resolve("a/b/c"));
			Files.delete(stage.resolve("a/b"));
			Files.delete(stage.resolve("a"));
			f.file(stage.resolve("n/m/f"), "new\n", 0644);
			f.file(stage.resolve("s"), "after\n", 0644);
			Files.setPosixFilePermissions(stage.resolve("d"), EngineMutation.permissions(0700));
		}, false);

		assertThat(EngineMutation.ordered(set.diffs(), false)).extracting(Diff::relativePath)
				.containsExactly("a/b/c", "a/b", "a", "n", "n/m", "s", "n/m/f", "d");
		assertThat(EngineMutation.ordered(set.diffs(), true)).extracting(Diff::relativePath)
				.containsExactly("n/m/f", "n/m", "n", "a", "a/b", "s", "a/b/c", "d");
	}
}
