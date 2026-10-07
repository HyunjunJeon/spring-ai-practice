package com.example.miniagent.core.change;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

class ApplyEngineTest {
	@TempDir Path work;
	@TempDir Path state;

	@Test void appliesActualBytesModesAddsAndDeletesThroughDurableBoundaries() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("old/nested/remove"), "delete\n", 0644);
		f.file(this.work.resolve("source"), "before\n", 0644);
		ChangeSet set = f.propose(stage -> {
			Files.delete(stage.resolve("old/nested/remove"));
			Files.delete(stage.resolve("old/nested"));
			Files.delete(stage.resolve("old"));
			f.file(stage.resolve("new/nested/add"), "add\r\n", 0750);
			f.file(stage.resolve("source"), "after\n", 0640);
		}, true);
		var boundaries = new ArrayList<ApplyEngine.Boundary>();
		var engine = f.applyEngine((boundary, checkpoint) -> {
			boundaries.add(boundary);
			assertThatThrownBy(f.store::acquire).isInstanceOfSatisfying(ChangeStore.Failure.class,
					ex -> assertThat(ex.code()).isEqualTo(ChangeStore.Code.LOCKED));
			if (boundary == ApplyEngine.Boundary.PREPARED) {
				for (var blob : new Checkpoint.BlobRef[] { checkpoint.beforeBlob(), checkpoint.afterBlob() }) {
					if (blob != null) {
						Path path = f.store.directory().resolve("blobs").resolve(blob.sha256());
						assertThat(path).isRegularFile();
						assertThat(Fingerprinter.sha256(AtomicFiles.read(path, 16 * 1024 * 1024))).isEqualTo(blob.sha256());
					}
				}
			}
		});
		var result = engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set));
		assertThat(result.changeSet().status()).isEqualTo(ChangeStatus.APPLIED);
		assertThat(result.checkpoints()).hasSize(set.diffs().size()).allMatch(c -> c.state() == Checkpoint.State.APPLIED);
		assertThat(f.printer().snapshot()).isEqualTo(set.proposed());
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("after\n");
		assertThat(Files.getPosixFilePermissions(this.work.resolve("source")))
				.isEqualTo(PosixFilePermissions.fromString("rw-r-----"));
		assertThat(boundaries).hasSize(set.diffs().size() * 3);
		assertThat(new FileChangeStore(this.state, this.work).read().get(set.changeSetId())).isEqualTo(result);
	}

	@Test void exactOwnerDigestApprovalAndOwnershipAreRequiredWithoutWrites() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("source"), "before\n", 0644);
		var set = f.propose(stage -> f.file(stage.resolve("source"), "after\n", 0644), false);
		var engine = f.applyEngine();
		byte[] journal = f.journal();
		EngineFixture.code(ApplyEngine.Code.APPROVAL_REQUIRED,
				() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), null));
		try (var lease = f.store.acquire()) {
			lease.decide(set.changeSetId(), set.owner(), set.digest(), f.approval(set));
		}
		journal = f.journal();
		EngineFixture.code(ApplyEngine.Code.IDENTITY_MISMATCH, () -> engine.apply(set.changeSetId(),
				new ChangeOwner("other", set.owner().runId(), "attempt", "turn"), set.digest(), null));
		EngineFixture.code(ApplyEngine.Code.IDENTITY_MISMATCH,
				() -> engine.apply(set.changeSetId(), set.owner(), "0".repeat(64), null));
		EngineFixture.code(ApplyEngine.Code.EXCLUSIVE_OWNERSHIP_REQUIRED,
				() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), null));
		for (var console : List.of(EngineFixture.console("true\n", true), EngineFixture.console("", false)))
			EngineFixture.code(ApplyEngine.Code.EXCLUSIVE_OWNERSHIP_REQUIRED,
					() -> engine.confirmExclusiveOwnership(console, set.changeSetId(), set.owner(), set.digest()));
		assertThat(f.journal()).isEqualTo(journal);
		assertThat(f.printer().snapshot()).isEqualTo(set.baseline());
		f.file(this.work.resolve("source"), "human\n", 0644);
		EngineFixture.code(ApplyEngine.Code.EXCLUSIVE_OWNERSHIP_REQUIRED,
				() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), null));
		assertThat(f.journal()).isEqualTo(journal);
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("human\n");
	}

	@Test void foreignEngineCannotUseConsoleProof() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("new"), "new\n", 0644), true);
		var issuer = f.applyEngine();
		var proof = f.proof(issuer, set);
		EngineFixture.code(ApplyEngine.Code.EXCLUSIVE_OWNERSHIP_REQUIRED,
				() -> f.applyEngine().apply(set.changeSetId(), set.owner(), set.digest(), proof));
		assertThat(this.work.resolve("new")).doesNotExist();
		issuer.apply(set.changeSetId(), set.owner(), set.digest(), proof);
		assertThatThrownBy(() -> new UndoEngine(f.store, f.policy, EngineFixture.CLOCK)
				.undo(set.changeSetId(), set.owner(), set.digest(), proof))
				.isInstanceOfSatisfying(ApplyEngine.Failure.class,
						ex -> assertThat(ex.code()).isEqualTo(ApplyEngine.Code.EXCLUSIVE_OWNERSHIP_REQUIRED));
	}

	@Test void wholeBaselineDriftAfterConfirmationRejectsAllPaths() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("a"), "a\n", 0644);
		f.file(this.work.resolve("untouched"), "old\n", 0644);
		var set = f.propose(stage -> f.file(stage.resolve("a"), "proposal\n", 0644), true);
		var engine = f.applyEngine();
		var proof = f.proof(engine, set);
		f.file(this.work.resolve("untouched"), "human\n", 0644);
		EngineFixture.code(ApplyEngine.Code.STALE, () -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof));
		assertThat(Files.readString(this.work.resolve("a"))).isEqualTo("a\n");
		assertThat(f.store.read().get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.STALE);
		assertThat(f.store.read().get(set.changeSetId()).checkpoints()).isEmpty();
	}

	@Test void liveDriftDuringConsoleConfirmationRejectsWholeProposal() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("a"), "a\n", 0644);
		var set = f.propose(stage -> f.file(stage.resolve("a"), "proposal\n", 0644), true);
		var console = new AgentConsole(new BufferedReader(new StringReader("")), new PrintStream(new ByteArrayOutputStream()), true) {
			@Override public String readLine(String prompt) {
				try { Files.writeString(work.resolve("a"), "human\n"); }
				catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
				return "exclusive " + set.changeSetId() + " " + set.digest();
			}
		};
		EngineFixture.code(ApplyEngine.Code.STALE,
				() -> f.applyEngine().confirmExclusiveOwnership(console, set.changeSetId(), set.owner(), set.digest()));
		assertThat(Files.readString(this.work.resolve("a"))).isEqualTo("human\n");
	}

	@Test void changedStageBytesOrModesRejectBeforePreparingAnyPath() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("new"), "approved\n", 0644), true);
		var engine = f.applyEngine();
		var proof = f.proof(engine, set);
		f.file(f.stage.resolve("new"), "unapproved\n", 0755);
		EngineFixture.code(ApplyEngine.Code.INVALID_PROPOSAL,
				() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof));
		assertThat(this.work.resolve("new")).doesNotExist();
		assertThat(f.store.read().get(set.changeSetId()).checkpoints()).isEmpty();
	}

	@Test void excludedAndSymlinkStageEffectsNeverReadTargetsOrWriteLive() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("new"), "approved\n", 0644), true);
		Files.delete(f.stage.resolve("new"));
		Path outside = Files.writeString(this.state.resolve("outside"), "human\n");
		Files.createSymbolicLink(f.stage.resolve("new"), outside);
		var engine = f.applyEngine();
		EngineFixture.code(ApplyEngine.Code.INVALID_PROPOSAL,
				() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), f.proof(engine, set)));
		assertThat(Files.readString(outside)).isEqualTo("human\n");
		assertThat(this.work.resolve("new")).doesNotExist();
	}

	@Test void oversizedStageEndpointIsBoundedBeforeLivePublication() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("new"), "approved\n", 0644), true);
		var engine = f.applyEngine();
		var proof = f.proof(engine, set);
		Files.write(f.stage.resolve("new"), new byte[16 * 1024 * 1024 + 1]);
		assertThatThrownBy(() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof))
				.isInstanceOfSatisfying(PathPolicy.Violation.class, ex -> assertThat(ex.code()).isEqualTo(PathPolicy.Code.LIMIT));
		assertThat(this.work.resolve("new")).doesNotExist();
		assertThat(f.store.read().get(set.changeSetId()).checkpoints()).isEmpty();
	}

	@Test void completedFirstFileInterruptionRetainsTruthfulPartialState() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> {
			f.file(stage.resolve("a"), "a\n", 0644);
			f.file(stage.resolve("b"), "b\n", 0644);
		}, true);
		var engine = f.applyEngine((boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.COMPLETED) Thread.currentThread().interrupt();
		});
		var proof = f.proof(engine, set);
		try {
			assertThatThrownBy(() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof))
					.isInstanceOf(InterruptedIOException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally { Thread.interrupted(); }
		var snapshot = new FileChangeStore(this.state, this.work).read().get(set.changeSetId());
		assertThat(snapshot.changeSet().status()).isEqualTo(ChangeStatus.PARTIALLY_APPLIED);
		assertThat(snapshot.checkpoints()).hasSize(1).allMatch(c -> c.state() == Checkpoint.State.APPLIED);
		assertThat(Files.readString(this.work.resolve("a"))).isEqualTo("a\n");
		assertThat(this.work.resolve("b")).doesNotExist();
	}

	@Test void interruptionAtPreparedLeavesBeforeAndRecoveryRequiredAcrossRestart() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("new"), "approved\n", 0644), true);
		var engine = f.applyEngine((boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.PREPARED) Thread.currentThread().interrupt();
		});
		var proof = f.proof(engine, set);
		try {
			assertThatThrownBy(() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof))
					.isInstanceOf(InterruptedIOException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally { Thread.interrupted(); }
		var reopened = new FileChangeStore(this.state, this.work).read().get(set.changeSetId());
		assertThat(reopened.changeSet().status()).isEqualTo(ChangeStatus.RECOVERY_REQUIRED);
		assertThat(reopened.checkpoints().getFirst().state()).isEqualTo(Checkpoint.State.PREPARED);
		assertThat(this.work.resolve("new")).doesNotExist();
	}

	@Test void cooperatingDriftAtPreparedBoundaryIsNeverOverwritten() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		f.file(this.work.resolve("source"), "before\n", 0644);
		var set = f.propose(stage -> f.file(stage.resolve("source"), "after\n", 0644), true);
		var engine = f.applyEngine((boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.PREPARED) Files.writeString(work.resolve("source"), "human\n");
		});
		var proof = f.proof(engine, set);
		assertThatThrownBy(() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof))
				.isInstanceOfSatisfying(PathPolicy.Violation.class, ex -> assertThat(ex.code()).isEqualTo(PathPolicy.Code.CHANGED));
		assertThat(Files.readString(this.work.resolve("source"))).isEqualTo("human\n");
		var report = new RecoveryScanner(new FileChangeStore(this.state, this.work), f.policy)
				.scan(set.changeSetId(), set.owner(), set.digest());
		assertThat(report.status()).isEqualTo(ChangeStatus.RECOVERY_REQUIRED);
		assertThat(report.entries().getFirst().code()).isEqualTo(ConflictReport.Code.HUMAN_EDIT);
		assertThat(report.entries().getFirst().checkpointState()).isEqualTo(Checkpoint.State.PREPARED);
	}

	@Test void interruptionAtMutatedLeavesAfterWithoutFabricatingCompletion() throws Exception {
		var f = new EngineFixture(this.work, this.state);
		var set = f.propose(stage -> f.file(stage.resolve("new"), "approved\n", 0644), true);
		var engine = f.applyEngine((boundary, checkpoint) -> {
			if (boundary == ApplyEngine.Boundary.MUTATED) Thread.currentThread().interrupt();
		});
		var proof = f.proof(engine, set);
		try {
			assertThatThrownBy(() -> engine.apply(set.changeSetId(), set.owner(), set.digest(), proof))
					.isInstanceOf(InterruptedIOException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally { Thread.interrupted(); }
		var reopened = new FileChangeStore(this.state, this.work).read().get(set.changeSetId());
		assertThat(reopened.changeSet().status()).isEqualTo(ChangeStatus.RECOVERY_REQUIRED);
		assertThat(reopened.checkpoints().getFirst().state()).isEqualTo(Checkpoint.State.PREPARED);
		assertThat(Files.readString(this.work.resolve("new"))).isEqualTo("approved\n");
		try (var paths = Files.list(this.work)) {
			assertThat(paths.map(p -> p.getFileName().toString()).toList()).containsExactly("new");
		}
	}
}

/** Real storage/stage fixture shared only by these three engine test classes. */
final class EngineFixture {
	static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
	static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);
	final FileChangeStore store;
	final PathPolicy policy;
	final Path work;
	Path stage;
	EngineFixture(Path work, Path state) throws Exception {
		this.work = work;
		this.store = new FileChangeStore(state, work);
		this.policy = new PathPolicy(work, state, List.of());
	}
	@FunctionalInterface interface Edit { void edit(Path stage) throws Exception; }
	void file(Path path, String text, int mode) throws Exception {
		Files.createDirectories(path.getParent());
		Files.writeString(path, text);
		Files.setPosixFilePermissions(path, EngineMutation.permissions(mode));
	}
	Fingerprinter printer() { return new Fingerprinter(this.policy); }
	ChangeSet propose(Edit edit, boolean approve) throws Exception {
		Map<String, Fingerprint> baseline = printer().snapshot();
		var originals = new TreeMap<String, byte[]>();
		for (String path : baseline.keySet()) originals.put(path, printer().read(path).bytes());
		var owner = new ChangeOwner("session", new RunId("run"), "attempt", "turn");
		try (var lease = this.store.acquire()) { this.stage = lease.stagingDirectory(owner); }
		for (var fp : baseline.values()) {
			Path target = this.stage.resolve(fp.relativePath());
			if (fp.kind() == EntryKind.DIRECTORY) Files.createDirectories(target);
			else file(target, new String(originals.get(fp.relativePath()), java.nio.charset.StandardCharsets.UTF_8), fp.posixMode());
		}
		for (var fp : baseline.values())
			Files.setPosixFilePermissions(this.stage.resolve(fp.relativePath()), EngineMutation.permissions(fp.posixMode()));
		edit.edit(this.stage);
		var stagePrinter = new Fingerprinter(PathPolicy.stagedView(this.stage, this.policy));
		var proposed = stagePrinter.snapshot();
		var paths = new TreeSet<>(baseline.keySet());
		paths.addAll(proposed.keySet());
		var diffs = new ArrayList<Diff>();
		for (String path : paths) {
			var before = baseline.getOrDefault(path, Fingerprint.missing(path));
			var after = proposed.getOrDefault(path, Fingerprint.missing(path));
			if (!before.equals(after))
				diffs.add(new DiffRenderer(this.policy).render(before, originals.getOrDefault(path, new byte[0]),
						after, stagePrinter.read(path).bytes()));
		}
		ChangeSet set = ChangeSet.propose(UUID.randomUUID(), owner, this.work.toRealPath().toString(),
				new ModelRef("fake", "test"), List.of(UUID.randomUUID()), baseline, proposed, diffs, AT);
		try (var lease = this.store.acquire()) {
			lease.propose(set);
			if (approve) lease.decide(set.changeSetId(), owner, set.digest(), approval(set));
		}
		return set;
	}
	Approval approval(ChangeSet set) {
		return new Approval(UUID.randomUUID(), set.changeSetId(), set.digest(), set.owner(), "console", ApprovalDecision.APPROVED, AT);
	}
	ApplyEngine applyEngine() { return new ApplyEngine(this.store, this.policy, CLOCK); }
	ApplyEngine applyEngine(ApplyEngine.BoundaryHook hook) { return new ApplyEngine(this.store, this.policy, CLOCK, hook); }
	ApplyEngine.OwnershipProof proof(ApplyEngine engine, ChangeSet set) throws Exception {
		return engine.confirmExclusiveOwnership(console("exclusive " + set.changeSetId() + " " + set.digest() + "\n", true),
				set.changeSetId(), set.owner(), set.digest());
	}
	static AgentConsole console(String text, boolean interactive) {
		return new AgentConsole(new BufferedReader(new StringReader(text)), new PrintStream(new ByteArrayOutputStream()), interactive);
	}
	byte[] journal() throws Exception { return Files.readAllBytes(this.store.directory().resolve("journal.log")); }
	static void code(ApplyEngine.Code code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ApplyEngine.Failure.class, ex -> assertThat(ex.code()).isEqualTo(code));
	}
}
