package com.example.miniagent.core.change;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

class RecoveryScannerTest {
	@TempDir Path work;
	@TempDir Path state;

	@Test void restartClassifiesPartialAppliedPreparedAndUnpreparedWithoutWrites() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> {
			f.file(stage.resolve("a"), "new a\n", 0644);
			f.file(stage.resolve("b"), "new b\n", 0644);
			f.file(stage.resolve("c"), "new c\n", 0644);
		}, true);
		var engine = f.applyEngine((boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.PREPARED && checkpoint.before().relativePath().equals("b"))
				throw new IOException("forced boundary");
		});
		var proof = f.proof(engine, set);
		assertThatThrownBy(() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof)).isInstanceOf(IOException.class);
		byte[] journal = f.journal();
		var reopened = new FileChangeStore(this.state, this.work);
		var report = new RecoveryScanner(reopened, f.policy).scan(set.changeSetId(), set.owner(), set.digest());
		assertThat(report.entries()).extracting(ConflictReport.Entry::position)
				.containsExactly(ConflictReport.Position.AFTER, ConflictReport.Position.BEFORE, ConflictReport.Position.BEFORE);
		assertThat(report.entries()).extracting(ConflictReport.Entry::checkpointState)
				.containsExactly(Checkpoint.State.APPLIED, Checkpoint.State.PREPARED, null);
		assertThat(report.hasConflicts()).isFalse();
		assertThat(f.journal()).isEqualTo(journal);
		assertThat(Files.readString(this.work.resolve("a"))).isEqualTo("new a\n");
		assertThat(this.work.resolve("b")).doesNotExist();
	}

	@Test void mutationBeforeCompletionIsAfterPreparedNotInventedApplied() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("a"), "new\n", 0644), true);
		var engine = f.applyEngine((boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.MUTATED) throw new IOException("forced boundary");
		});
		var proof = f.proof(engine, set);
		assertThatThrownBy(() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof)).isInstanceOf(IOException.class);
		var report = new RecoveryScanner(new FileChangeStore(this.state, this.work), f.policy)
				.scan(set.changeSetId(), set.owner(), set.digest());
		assertThat(report.entries().getFirst().position()).isEqualTo(ConflictReport.Position.AFTER);
		assertThat(report.entries().getFirst().checkpointState()).isEqualTo(Checkpoint.State.PREPARED);
	}

	@Test void humanBytesAndUnsafeLinksAreTypedConflictsWithoutDereferencing() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> {
			f.file(stage.resolve("a"), "new a\n", 0644);
			f.file(stage.resolve("b"), "new b\n", 0644);
		}, true);
		var engine = f.applyEngine();
		engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		f.file(this.work.resolve("a"), "human\n", 0644);
		Files.delete(this.work.resolve("b"));
		Path outside = Files.writeString(this.state.resolve("outside"), "keep\n");
		Files.createSymbolicLink(this.work.resolve("b"), outside);
		byte[] journal = f.journal();
		var report = new RecoveryScanner(f.store, f.policy).scan(set.changeSetId(), set.owner(), set.digest());
		assertThat(report.entries()).extracting(ConflictReport.Entry::code)
				.containsExactly(ConflictReport.Code.HUMAN_EDIT, ConflictReport.Code.UNSAFE_PATH);
		assertThat(report.entries().getLast().current()).isNull();
		assertThat(Files.readString(outside)).isEqualTo("keep\n");
		assertThat(f.journal()).isEqualTo(journal);
	}

	@Test void unpreparedAfterIsUnjournaledEffectNotRecoveryPermissionToWrite() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("a"), "new\n", 0644), true);
		f.file(this.work.resolve("a"), "new\n", 0644);
		var report = new RecoveryScanner(f.store, f.policy).scan(set.changeSetId(), set.owner(), set.digest());
		assertThat(report.entries().getFirst().position()).isEqualTo(ConflictReport.Position.AFTER);
		assertThat(report.entries().getFirst().code()).isEqualTo(ConflictReport.Code.UNJOURNALED_EFFECT);
		assertThat(report.entries().getFirst().checkpointId()).isNull();
	}

	@Test void damagedFutureAndTruncatedJournalsRemainUntouched() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("a"), "new\n", 0644), true);
		var engine = f.applyEngine();
		engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		Path journal = f.store.directory().resolve("journal.log");
		byte[] clean = f.journal();
		Files.writeString(journal, "2\tfuture\n", StandardOpenOption.APPEND);
		assertJournal(f, set, ChangeJournal.State.FUTURE_SCHEMA);
		Files.write(journal, clean);
		Files.writeString(journal, "1\ttruncated", StandardOpenOption.APPEND);
		assertJournal(f, set, ChangeJournal.State.TRUNCATED);
		Files.write(journal, clean);
		Files.writeString(journal, "1\tbroken\tcrc\t{}\n", StandardOpenOption.APPEND);
		assertJournal(f, set, ChangeJournal.State.CORRUPT);
	}

	@Test void cutPreparedJournalExposesOnlyValidatedPrefixAcrossRestart() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("a"), "new\n", 0644), true);
		try (var lease = f.store.acquire()) {
			lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.APPLYING);
			lease.prepare(set.changeSetId(), set.owner(), set.digest(), java.util.UUID.randomUUID(),
					"a", new byte[0], "new\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), EngineFixture.AT);
		}
		byte[] bytes = f.journal();
		Files.write(f.store.directory().resolve("journal.log"), Arrays.copyOf(bytes, bytes.length - 8));
		var report = new RecoveryScanner(new FileChangeStore(this.state, this.work), f.policy)
				.scan(set.changeSetId(), set.owner(), set.digest());
		assertThat(report.journalState()).isEqualTo(ChangeJournal.State.TRUNCATED);
		assertThat(report.entries().getFirst().checkpointId()).isNull();
		assertThat(report.entries().getFirst().position()).isEqualTo(ConflictReport.Position.BEFORE);
		assertThat(this.work.resolve("a")).doesNotExist();
	}

	private void assertJournal(EngineFixture f, ChangeSet set, ChangeJournal.State expected) throws Exception {
		byte[] journal = f.journal();
		var report = new RecoveryScanner(new FileChangeStore(this.state, this.work), f.policy)
				.scan(set.changeSetId(), set.owner(), set.digest());
		assertThat(report.journalState()).isEqualTo(expected);
		assertThat(f.journal()).isEqualTo(journal);
		assertThat(Files.readString(this.work.resolve("a"))).isEqualTo("new\n");
	}
}
