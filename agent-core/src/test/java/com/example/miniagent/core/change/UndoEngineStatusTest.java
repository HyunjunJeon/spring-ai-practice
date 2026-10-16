package com.example.miniagent.core.change;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.approval.ApprovalDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

/** Undo only acts on changes with live effects; every other status is refused before the proof or the source is touched. */
class UndoEngineStatusTest {
	@TempDir Path work;
	@TempDir Path state;

	/** A change in the requested status, plus a still-unspent undo proof whenever the status allowed minting one. */
	private record Reached(ChangeSet set, ApplyEngine.OwnershipProof unspentProof) {}

	private Reached reach(EngineFixture f, ChangeStatus status) throws Exception {
		f.file(this.work.resolve("source"), "before\n", 0644);
		boolean approved = status != ChangeStatus.PENDING_APPROVAL && status != ChangeStatus.REJECTED;
		var set = f.propose(stage -> f.file(stage.resolve("source"), "after\n", 0644), approved);
		var engine = f.applyEngine();
		return switch (status) {
			case PENDING_APPROVAL -> new Reached(set, null);
			case APPROVED -> new Reached(set, f.proof(engine, set));
			case REJECTED -> {
				try (var lease = f.store.acquire()) {
					lease.decide(set.changeSetId(), set.owner(), set.digest(), new Approval(UUID.randomUUID(),
							set.changeSetId(), set.digest(), set.owner(), "console", ApprovalDecision.REJECTED, EngineFixture.AT));
				}
				yield new Reached(set, null);
			}
			case SUPERSEDED -> {
				var proof = f.proof(engine, set);
				try (var lease = f.store.acquire()) {
					lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.SUPERSEDED);
				}
				yield new Reached(set, proof);
			}
			case STALE -> {
				var applyProof = f.proof(engine, set);
				var unspent = f.proof(engine, set);
				f.file(this.work.resolve("source"), "human\n", 0644);
				EngineFixture.code(ApplyEngine.Code.STALE,
						() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), applyProof));
				yield new Reached(set, unspent);
			}
			default -> throw new IllegalArgumentException("Not an inert status: " + status);
		};
	}

	@ParameterizedTest
	@EnumSource(value = ChangeStatus.class, names = { "PENDING_APPROVAL", "APPROVED", "REJECTED", "SUPERSEDED", "STALE" })
	void undoRefusesChangesWithoutLiveEffects(ChangeStatus status) throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var reached = reach(f, status);
		var set = reached.set();
		byte[] journal = f.journal();
		var live = f.printer().snapshot();

		assertThatThrownBy(() -> new UndoEngine(f.store, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), reached.unspentProof()))
				.isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
					assertThat(ex.code()).isEqualTo(ChangeStore.Code.INVALID_TRANSITION);
					assertThat(ex).hasMessage("Change has no undoable effects");
				});

		assertThat(f.journal()).isEqualTo(journal);
		assertThat(f.printer().snapshot()).isEqualTo(live);
		assertThat(f.store.read().get(set.changeSetId()).changeSet().status()).isEqualTo(status);
	}

	@ParameterizedTest
	@EnumSource(value = ChangeStatus.class, names = { "PENDING_APPROVAL", "REJECTED", "SUPERSEDED", "STALE" })
	void confirmingOwnershipOfAnUnapplicableChangeIsRefusedWithoutPrompting(ChangeStatus status) throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = reach(f, status).set();
		byte[] journal = f.journal();
		var prompts = new ByteArrayOutputStream();
		var console = new AgentConsole(new BufferedReader(new StringReader("exclusive " + set.changeSetId() + " " + set.digest() + "\n")),
				new PrintStream(prompts), true);

		EngineFixture.code(ApplyEngine.Code.APPROVAL_REQUIRED,
				() -> f.applyEngine().confirmExclusiveOwnership(console, set.changeSetId(), set.owner(), set.digest()));

		assertThat(prompts.size()).isZero();
		assertThat(f.journal()).isEqualTo(journal);
	}

	@Test void secondUndoOfAnUndoneChangeIsRefusedAndLeavesTheBaselineAlone() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("source"), "before\n", 0644);
		var set = f.propose(stage -> f.file(stage.resolve("source"), "after\n", 0644), true);
		var engine = f.applyEngine();
		engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		var secondUndoProof = f.proof(engine, set);
		var undo = new UndoEngine(f.store, f.policy, EngineFixture.CLOCK);
		var undone = undo.undo(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		assertThat(undone.changeSet().status()).isEqualTo(ChangeStatus.UNDONE);
		byte[] journal = f.journal();

		assertThatThrownBy(() -> undo.undo(set.changeSetId(), set.owner(), set.digest(), secondUndoProof))
				.isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
					assertThat(ex.code()).isEqualTo(ChangeStore.Code.INVALID_TRANSITION);
					assertThat(ex).hasMessage("Change has no undoable effects");
				});
		EngineFixture.code(ApplyEngine.Code.APPROVAL_REQUIRED, () -> engine.confirmExclusiveOwnership(
				EngineFixture.console("exclusive " + set.changeSetId() + " " + set.digest() + "\n", true),
				set.changeSetId(), set.owner(), set.digest()));

		assertThat(f.journal()).isEqualTo(journal);
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("before\n");
		assertThat(f.store.read().get(set.changeSetId())).isEqualTo(undone);
	}
}
