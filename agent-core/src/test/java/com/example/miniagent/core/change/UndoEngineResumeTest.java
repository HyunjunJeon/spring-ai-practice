package com.example.miniagent.core.change;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** Operator recovery path: a conflicted undo is resumed with a fresh proof once the human has resolved the path. */
class UndoEngineResumeTest {
	@TempDir Path work;
	@TempDir Path state;

	private static Map<String, Checkpoint.State> states(ChangeStore.Snapshot snapshot) {
		return snapshot.checkpoints().stream().collect(Collectors.toMap(c -> c.before().relativePath(), Checkpoint::state));
	}

	/** Records the boundaries an undo crosses, so a test can tell which paths it wrote. */
	private static final class Crossings implements ApplyEngine.BoundaryHook {
		final List<String> seen = new ArrayList<>();
		@Override public void reached(ApplyEngine.Boundary boundary, Checkpoint checkpoint) {
			this.seen.add(boundary + ":" + checkpoint.before().relativePath());
		}
	}

	@Test void resumeAfterHumanRestoredAppliedBytesRevertsOnlyTheConflictedPath() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("a"), "a before\n", 0644);
		f.file(this.work.resolve("b"), "b before\n", 0644);
		var set = f.propose(stage -> {
			f.file(stage.resolve("a"), "a after\n", 0644);
			f.file(stage.resolve("b"), "b after\n", 0644);
		}, true);
		var engine = f.applyEngine();
		engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		f.file(this.work.resolve("b"), "b human\n", 0644);
		var first = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		assertThat(first.changeSet().status()).isEqualTo(ChangeStatus.PARTIALLY_UNDONE);
		assertThat(states(first)).containsOnly(Map.entry("a", Checkpoint.State.RESTORED), Map.entry("b", Checkpoint.State.CONFLICT));
		assertThat(Files.readString(this.work.resolve("b"))).isEqualTo("b human\n");

		f.file(this.work.resolve("b"), "b after\n", 0644);
		var crossings = new Crossings();
		var resumed = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK, crossings)
				.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));

		assertThat(resumed.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		assertThat(states(resumed)).containsOnly(Map.entry("a", Checkpoint.State.RESTORED), Map.entry("b", Checkpoint.State.RESTORED));
		assertThat(crossings.seen).containsExactly("PREPARED:b", "MUTATED:b", "COMPLETED:b");
		assertThat(f.printer().snapshot()).isEqualTo(set.baseline());
		assertThat(new FileChangeStore(this.state, this.work).read().get(set.changeSetId())).isEqualTo(resumed);
	}

	@Test void resumeAfterHumanRestoredBaselineBytesRecordsRestoredWithoutWriting() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("source"), "before\n", 0644);
		var set = f.propose(stage -> f.file(stage.resolve("source"), "after\n", 0644), true);
		var engine = f.applyEngine();
		engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		f.file(this.work.resolve("source"), "human\n", 0644);
		var first = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		assertThat(states(first)).containsOnly(Map.entry("source", Checkpoint.State.CONFLICT));

		f.file(this.work.resolve("source"), "before\n", 0644);
		var crossings = new Crossings();
		var resumed = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK, crossings)
				.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));

		assertThat(resumed.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		assertThat(states(resumed)).containsOnly(Map.entry("source", Checkpoint.State.RESTORED));
		assertThat(crossings.seen).isEmpty();
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("before\n");
	}

	/** A human-reverted APPLIED checkpoint is not recognised as restored: it needs a conflict round and a second undo. */
	@Test void manuallyRevertedAppliedCheckpointNeedsASecondUndoToSettle() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("source"), "before\n", 0644);
		var set = f.propose(stage -> f.file(stage.resolve("source"), "after\n", 0644), true);
		var engine = f.applyEngine();
		engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		f.file(this.work.resolve("source"), "before\n", 0644);
		var crossings = new Crossings();
		var undo = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK, crossings);

		var first = undo.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));

		assertThat(first.changeSet().status()).isEqualTo(ChangeStatus.PARTIALLY_UNDONE);
		assertThat(states(first)).containsOnly(Map.entry("source", Checkpoint.State.CONFLICT));
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("before\n");

		var second = undo.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));

		assertThat(second.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		assertThat(states(second)).containsOnly(Map.entry("source", Checkpoint.State.RESTORED));
		assertThat(crossings.seen).isEmpty();
		assertThat(f.printer().snapshot()).isEqualTo(set.baseline());
	}

	@Test void resumeWhileTheConflictIsUnresolvedKeepsHumanBytesAndStaysPartiallyUndone() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("source"), "before\n", 0644);
		var set = f.propose(stage -> f.file(stage.resolve("source"), "after\n", 0644), true);
		var engine = f.applyEngine();
		engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		f.file(this.work.resolve("source"), "human\n", 0644);
		var undo = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK);
		var first = undo.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));

		var again = undo.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));

		assertThat(again.changeSet().status()).isEqualTo(ChangeStatus.PARTIALLY_UNDONE);
		assertThat(states(again)).isEqualTo(states(first)).containsOnly(Map.entry("source", Checkpoint.State.CONFLICT));
		assertThat(again.checkpoints()).isEqualTo(first.checkpoints());
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("human\n");
		assertThat(f.store.read().recoveryRequired()).isTrue();
	}
}
