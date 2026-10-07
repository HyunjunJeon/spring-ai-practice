package com.example.miniagent.core.change;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

class UndoEngineTest {
	@TempDir Path work;
	@TempDir Path state;

	@Test void humanEditAfterApplyIsNotClobbered() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("source"), "before\n", 0644);
		var set = f.propose(stage -> f.file(stage.resolve("source"), "after\n", 0644), true);
		var engine = f.applyEngine();
		engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		f.file(this.work.resolve("source"), "human\n", 0644);
		var result = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("human\n");
		assertThat(result.changeSet().status()).isEqualTo(ChangeStatus.PARTIALLY_UNDONE);
		assertThat(result.checkpoints().getFirst().state()).isEqualTo(Checkpoint.State.CONFLICT);
		assertThat(new FileChangeStore(this.state, this.work).read().get(set.changeSetId())).isEqualTo(result);
	}

	@Test void restoresBytesModesDirectoryAddsAndDeletesAcrossNewInstances() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("old/nested/file"), "old\n", 0750);
		f.file(this.work.resolve("source"), "before\n", 0644);
		var set = f.propose(stage -> {
			Files.delete(stage.resolve("old/nested/file"));
			Files.delete(stage.resolve("old/nested"));
			Files.delete(stage.resolve("old"));
			f.file(stage.resolve("new/nested/file"), "new\n", 0640);
			f.file(stage.resolve("source"), "after\n", 0700);
		}, true);
		var engine = f.applyEngine();
		engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		var reopened = new FileChangeStore(this.state, this.work);
		var result = new UndoEngine(reopened, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		assertThat(result.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		assertThat(result.checkpoints()).allMatch(c -> c.state() == Checkpoint.State.RESTORED);
		assertThat(f.printer().snapshot()).isEqualTo(set.baseline());
		assertThat(reopened.read().recoveryRequired()).isFalse();
	}

	@Test void restoresMatchingFilesButRetainsHumanModeAndByteChanges() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		for (String path : java.util.List.of("a", "b")) f.file(this.work.resolve(path), "before\n", 0644);
		var set = f.propose(stage -> {
			f.file(stage.resolve("a"), "after\n", 0644);
			f.file(stage.resolve("b"), "after\n", 0644);
		}, true);
		var engine = f.applyEngine();
		engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		Files.setPosixFilePermissions(this.work.resolve("b"), EngineMutation.permissions(0750));
		var result = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		assertThat(Files.readString(this.work.resolve("a"))).isEqualTo("before\n");
		assertThat(Files.readString(this.work.resolve("b"))).isEqualTo("after\n");
		assertThat(f.printer().fingerprint("b").posixMode()).isEqualTo(0750);
		assertThat(result.changeSet().status()).isEqualTo(ChangeStatus.PARTIALLY_UNDONE);
		assertThat(result.checkpoints()).extracting(Checkpoint::state)
				.containsExactly(Checkpoint.State.RESTORED, Checkpoint.State.CONFLICT);
	}

	@Test void humanAddedChildPreventsDirectoryRemovalWithoutClobberingIt() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("new/file"), "new\n", 0644), true);
		var engine = f.applyEngine();
		engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		f.file(this.work.resolve("new/human"), "keep\n", 0644);
		var result = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		assertThat(this.work.resolve("new/file")).doesNotExist();
		assertThat(Files.readString(this.work.resolve("new/human"))).isEqualTo("keep\n");
		assertThat(result.changeSet().status()).isEqualTo(ChangeStatus.PARTIALLY_UNDONE);
		assertThat(result.checkpoints()).anyMatch(c -> c.before().relativePath().equals("new") && c.state() == Checkpoint.State.CONFLICT);
	}

	@Test void undoRecoversPreparedAfterAndUnpreparedBeforeFromPartialApply() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> {
			f.file(stage.resolve("a"), "new a\n", 0644);
			f.file(stage.resolve("b"), "new b\n", 0644);
		}, true);
		var engine = f.applyEngine((boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.MUTATED) throw new IOException("forced boundary");
		});
		var proof = f.proof(engine, set);
		assertThatThrownBy(() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof)).isInstanceOf(IOException.class);
		var reopened = new FileChangeStore(this.state, this.work);
		var issuer = new ApplyEngine(reopened, f.policy, EngineFixture.CLOCK);
		var result = new UndoEngine(reopened, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(issuer, set));
		assertThat(result.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		assertThat(f.printer().snapshot()).isEqualTo(set.baseline());
		assertThat(result.checkpoints()).hasSize(1).allMatch(c -> c.state() == Checkpoint.State.RESTORED);
	}

	@Test void preparedBeforeCanBeRecordedRestoredWithoutSourceMutation() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("a"), "new\n", 0644), true);
		var engine = f.applyEngine((boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.PREPARED) throw new IOException("forced boundary");
		});
		var proof = f.proof(engine, set);
		assertThatThrownBy(() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof)).isInstanceOf(IOException.class);
		var result = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		assertThat(this.work.resolve("a")).doesNotExist();
		assertThat(result.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		assertThat(result.checkpoints().getFirst().state()).isEqualTo(Checkpoint.State.RESTORED);
	}

	@Test void undoRequiresFreshExclusiveProofWithoutChangingJournalOrSource() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("a"), "new\n", 0644), true);
		var engine = f.applyEngine();
		engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		byte[] journal = f.journal();
		EngineFixture.code(ApplyEngine.Code.EXCLUSIVE_OWNERSHIP_REQUIRED,
				() -> new UndoEngine(f.store, f.policy, EngineFixture.CLOCK).undo(set.changeSetId(), set.owner(), set.digest(), null));
		assertThat(f.journal()).isEqualTo(journal);
		assertThat(Files.readString(this.work.resolve("a"))).isEqualTo("new\n");
	}

	@Test void interruptedUndoRetainsRecoveryWithoutFabricatingRestoration() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("a"), "new\n", 0644), true);
		var engine = f.applyEngine();
		engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		var undo = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK, (boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.MUTATED) Thread.currentThread().interrupt();
		});
		var proof = f.proof(engine, set);
		try {
			assertThatThrownBy(() -> undo.undo(set.changeSetId(), set.owner(), set.digest(), proof)).isInstanceOf(InterruptedIOException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally { Thread.interrupted(); }
		var reopened = new FileChangeStore(this.state, this.work).read().get(set.changeSetId());
		assertThat(reopened.changeSet().status()).isEqualTo(ChangeStatus.RECOVERY_REQUIRED);
		assertThat(reopened.checkpoints().getFirst().state()).isEqualTo(Checkpoint.State.APPLIED);
		assertThat(this.work.resolve("a")).doesNotExist();
	}
}
