package com.example.miniagent.core.change;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** An ownership proof is bound to one change set and one store; only apply also binds it to its issuing engine. */
class OwnershipProofBindingTest {
	@TempDir Path work;
	@TempDir Path state;

	@Test void proofMintedForAnotherChangeSetCannotApply() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("source"), "before\n", 0644);
		var first = f.propose(stage -> f.file(stage.resolve("source"), "first\n", 0644), true);
		var second = f.propose(stage -> f.file(stage.resolve("source"), "second\n", 0644), true);
		var engine = f.applyEngine();
		var proofForFirst = f.proof(engine, first);
		byte[] journal = f.journal();

		EngineFixture.code(ApplyEngine.Code.EXCLUSIVE_OWNERSHIP_REQUIRED,
				() -> engine.apply(second.changeSetId(), second.owner(), second.digest(), proofForFirst));

		assertThat(f.journal()).isEqualTo(journal);
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("before\n");
		assertThat(f.store.read().get(second.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.APPROVED);
		var applied = engine.apply(second.changeSetId(), second.owner(), second.digest(), f.proof(engine, second));
		assertThat(applied.changeSet().status()).isEqualTo(ChangeStatus.APPLIED);
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("second\n");
	}

	@Test void proofMintedForAnotherChangeSetCannotUndo() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("a"), "a before\n", 0644);
		f.file(this.work.resolve("b"), "b before\n", 0644);
		var engine = f.applyEngine();
		var first = f.propose(stage -> f.file(stage.resolve("a"), "a after\n", 0644), true);
		engine.apply(first.changeSetId(), first.owner(), first.digest(), f.proof(engine, first));
		var second = f.propose(stage -> f.file(stage.resolve("b"), "b after\n", 0644), true);
		engine.apply(second.changeSetId(), second.owner(), second.digest(), f.proof(engine, second));
		var undo = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK);
		var proofForFirst = f.proof(engine, first);
		byte[] journal = f.journal();

		EngineFixture.code(ApplyEngine.Code.EXCLUSIVE_OWNERSHIP_REQUIRED,
				() -> undo.undo(second.changeSetId(), second.owner(), second.digest(), proofForFirst));

		assertThat(f.journal()).isEqualTo(journal);
		assertThat(Files.readString(this.work.resolve("b"))).isEqualTo("b after\n");
		var undone = undo.undo(second.changeSetId(), second.owner(), second.digest(), f.proof(engine, second));
		assertThat(undone.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		assertThat(Files.readString(this.work.resolve("b"))).isEqualTo("b before\n");
		assertThat(Files.readString(this.work.resolve("a"))).isEqualTo("a after\n");
		assertThat(f.store.read().get(first.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.APPLIED);
	}

	/** Same workspace and the same change set, but a second journal directory: only the store binding can refuse. */
	@Test void proofMintedOnAnotherStoreCannotUndo() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("source"), "before\n", 0644);
		var set = f.propose(stage -> f.file(stage.resolve("source"), "after\n", 0644), true);
		var otherStore = new FileChangeStore(this.state.resolve("other"), this.work);
		try (var lease = otherStore.acquire()) {
			lease.propose(set);
			lease.decide(set.changeSetId(), set.owner(), set.digest(), f.approval(set));
			lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.APPLYING);
			lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.RECOVERY_REQUIRED);
		}
		var proofFromFirstStore = f.proof(f.applyEngine(), set);
		var undo = new UndoEngine(otherStore, f.policy, EngineFixture.CLOCK);
		Path otherJournal = otherStore.directory().resolve("journal.log");
		byte[] journal = Files.readAllBytes(otherJournal);

		EngineFixture.code(ApplyEngine.Code.EXCLUSIVE_OWNERSHIP_REQUIRED,
				() -> undo.undo(set.changeSetId(), set.owner(), set.digest(), proofFromFirstStore));

		assertThat(Files.readAllBytes(otherJournal)).isEqualTo(journal);
		assertThat(otherStore.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.RECOVERY_REQUIRED);
		var proofFromOtherStore = f.proof(new ApplyEngine(otherStore, f.policy, EngineFixture.CLOCK), set);
		var undone = undo.undo(set.changeSetId(), set.owner(), set.digest(), proofFromOtherStore);
		assertThat(undone.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
	}

	/** Pins the apply/undo asymmetry: undo has no issuing engine to compare, so any engine of the store may mint its proof. */
	@Test void undoAcceptsProofMintedByAnotherApplyEngineOfTheSameStore() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("source"), "before\n", 0644);
		var set = f.propose(stage -> f.file(stage.resolve("source"), "after\n", 0644), true);
		var applier = f.applyEngine();
		applier.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(applier, set));
		var proofFromAnotherEngine = f.proof(f.applyEngine(), set);

		var undone = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), proofFromAnotherEngine);

		assertThat(undone.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		assertThat(f.printer().snapshot()).isEqualTo(set.baseline());
	}
}
