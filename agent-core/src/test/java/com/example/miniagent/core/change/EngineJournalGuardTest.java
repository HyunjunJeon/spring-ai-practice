package com.example.miniagent.core.change;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import com.example.miniagent.core.AgentConsole;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

/** A damaged journal is never repaired or extended: confirm, apply and undo refuse with its typed code before any write. */
class EngineJournalGuardTest {
	@TempDir Path work;
	@TempDir Path state;

	enum Damage {
		TORN_TAIL("1\ttruncated", ChangeStore.Code.TRUNCATED_JOURNAL),
		BAD_CRC_ROW("1\tbroken\tcrc\t{}\n", ChangeStore.Code.CORRUPT),
		FUTURE_SCHEMA_ROW("2\tfuture\n", ChangeStore.Code.FUTURE_SCHEMA);

		private final String tail;
		private final ChangeStore.Code code;
		Damage(String tail, ChangeStore.Code code) { this.tail = tail; this.code = code; }

		void inflictOn(EngineFixture f) throws IOException {
			Files.write(f.store.directory().resolve("journal.log"), this.tail.getBytes(UTF_8), StandardOpenOption.APPEND);
		}

		void assertRefused(ThrowingCallable action) {
			assertThatThrownBy(action).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
				assertThat(ex.code()).isEqualTo(this.code);
				assertThat(ex).hasMessage("Journal must be inspected without repair");
			});
		}
	}

	@ParameterizedTest @EnumSource(Damage.class)
	void confirmRefusesBeforeAskingTheOperator(Damage damage) throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("source"), "before\n", 0644);
		var set = f.propose(stage -> f.file(stage.resolve("source"), "after\n", 0644), true);
		damage.inflictOn(f);
		byte[] journal = f.journal();
		var prompts = new ByteArrayOutputStream();
		var console = new AgentConsole(new BufferedReader(new StringReader("exclusive " + set.changeSetId() + " " + set.digest() + "\n")),
				new PrintStream(prompts), true);

		damage.assertRefused(() -> f.applyEngine().confirmExclusiveOwnership(console, set.changeSetId(), set.owner(), set.digest()));

		assertThat(prompts.size()).isZero();
		assertThat(f.journal()).isEqualTo(journal);
		assertThat(f.printer().snapshot()).isEqualTo(set.baseline());
	}

	@ParameterizedTest @EnumSource(Damage.class)
	void applyRefusesAValidProofWithoutWritingSourceOrJournal(Damage damage) throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("source"), "before\n", 0644);
		var set = f.propose(stage -> f.file(stage.resolve("source"), "after\n", 0644), true);
		var engine = f.applyEngine();
		var proof = f.proof(engine, set);
		damage.inflictOn(f);
		byte[] journal = f.journal();

		damage.assertRefused(() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof));

		assertThat(f.journal()).isEqualTo(journal);
		assertThat(f.printer().snapshot()).isEqualTo(set.baseline());
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("before\n");
	}

	/** The change is already RECOVERY_REQUIRED, so only the guard keeps undo from restoring files behind the journal's back. */
	@ParameterizedTest @EnumSource(Damage.class)
	void undoRefusesAValidProofWithoutRestoringSourceOrJournal(Damage damage) throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> {
			f.file(stage.resolve("a"), "new a\n", 0644);
			f.file(stage.resolve("b"), "new b\n", 0644);
		}, true);
		var engine = f.applyEngine((boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.MUTATED) throw new IOException("forced boundary");
		});
		var proof = f.proof(engine, set);
		assertThatThrownBy(() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof))
				.isInstanceOf(IOException.class).hasMessage("forced boundary");
		assertThat(f.store.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.RECOVERY_REQUIRED);
		var undoProof = f.proof(f.applyEngine(), set);
		var livePartial = f.printer().snapshot();
		damage.inflictOn(f);
		byte[] journal = f.journal();

		damage.assertRefused(() -> new UndoEngine(f.store, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), undoProof));

		assertThat(f.journal()).isEqualTo(journal);
		assertThat(f.printer().snapshot()).isEqualTo(livePartial);
		assertThat(Files.readString(this.work.resolve("a"))).isEqualTo("new a\n");
	}
}
