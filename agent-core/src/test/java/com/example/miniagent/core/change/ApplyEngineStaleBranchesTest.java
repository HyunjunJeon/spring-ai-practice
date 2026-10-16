package com.example.miniagent.core.change;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * STALE detected while the lease is held and publication has started. Unlike the pre-lease STALE branches these never
 * record ChangeStatus.STALE: the status left behind depends on whether a checkpoint is still PREPARED.
 */
class ApplyEngineStaleBranchesTest {
	@TempDir Path work;
	@TempDir Path state;

	private ChangeSet twoFileProposal(EngineFixture f) throws Exception {
		f.file(this.work.resolve("a"), "a before\n", 0644);
		f.file(this.work.resolve("b"), "b before\n", 0644);
		return f.propose(stage -> {
			f.file(stage.resolve("a"), "a after\n", 0644);
			f.file(stage.resolve("b"), "b after\n", 0644);
		}, true);
	}

	private static String path(Checkpoint checkpoint) { return checkpoint.before().relativePath(); }
	private static String pathAndState(Checkpoint checkpoint) { return path(checkpoint) + ":" + checkpoint.state(); }

	@Test void driftOfALaterPathAfterAnEarlierOneCompletedLeavesPartiallyAppliedWithoutOverwriting() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = twoFileProposal(f);
		var engine = f.applyEngine((boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.COMPLETED && path(checkpoint).equals("a"))
				Files.writeString(this.work.resolve("b"), "b human\n");
		});
		var proof = f.proof(engine, set);

		EngineFixture.code(ApplyEngine.Code.STALE, () -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof));

		var left = f.store.read().get(set.changeSetId());
		assertThat(left.changeSet().status()).isEqualTo(ChangeStatus.PARTIALLY_APPLIED);
		assertThat(left.checkpoints()).extracting(ApplyEngineStaleBranchesTest::pathAndState).containsExactly("a:APPLIED");
		assertThat(Files.readString(this.work.resolve("a"))).isEqualTo("a after\n");
		assertThat(Files.readString(this.work.resolve("b"))).isEqualTo("b human\n");
		assertThat(f.store.read().recoveryRequired()).isTrue();
	}

	@Test void changeToThePublishedTargetBeforeItsCompletionLeavesRecoveryRequiredWithPreparedCheckpoint() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = twoFileProposal(f);
		var engine = f.applyEngine((boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.MUTATED) Files.writeString(this.work.resolve("a"), "a human\n");
		});
		var proof = f.proof(engine, set);

		EngineFixture.code(ApplyEngine.Code.STALE, () -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof));

		var left = f.store.read().get(set.changeSetId());
		assertThat(left.changeSet().status()).isEqualTo(ChangeStatus.RECOVERY_REQUIRED);
		assertThat(left.checkpoints()).extracting(ApplyEngineStaleBranchesTest::pathAndState).containsExactly("a:PREPARED");
		assertThat(Files.readString(this.work.resolve("a"))).isEqualTo("a human\n");
		assertThat(Files.readString(this.work.resolve("b"))).isEqualTo("b before\n");
		assertThat(f.store.read().recoveryRequired()).isTrue();
	}

	@Test void unrelatedFileAddedBeforeTheFinalSnapshotLeavesPartiallyAppliedAndUndoRevertsOnlyTheProposal() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = twoFileProposal(f);
		var engine = f.applyEngine((boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.COMPLETED && path(checkpoint).equals("b"))
				Files.writeString(this.work.resolve("unrelated"), "human\n");
		});
		var proof = f.proof(engine, set);

		EngineFixture.code(ApplyEngine.Code.STALE, () -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof));

		var left = f.store.read().get(set.changeSetId());
		assertThat(left.changeSet().status()).isEqualTo(ChangeStatus.PARTIALLY_APPLIED);
		assertThat(left.checkpoints()).extracting(ApplyEngineStaleBranchesTest::pathAndState).containsExactly("a:APPLIED", "b:APPLIED");
		assertThat(Files.readString(this.work.resolve("a"))).isEqualTo("a after\n");
		assertThat(Files.readString(this.work.resolve("b"))).isEqualTo("b after\n");
		assertThat(Files.readString(this.work.resolve("unrelated"))).isEqualTo("human\n");

		var undone = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(f.applyEngine(), set));

		assertThat(undone.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		assertThat(Files.readString(this.work.resolve("a"))).isEqualTo("a before\n");
		assertThat(Files.readString(this.work.resolve("b"))).isEqualTo("b before\n");
		assertThat(Files.readString(this.work.resolve("unrelated"))).isEqualTo("human\n");
	}
}
