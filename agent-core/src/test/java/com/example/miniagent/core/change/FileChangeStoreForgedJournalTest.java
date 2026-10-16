package com.example.miniagent.core.change;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.zip.CRC32C;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;
import static com.example.miniagent.core.change.ChangeStore.Code.*;

/**
 * Hand-framed journal rows with a correct sequence and CRC that break the record chain's rules.
 * validate() is the only thing between such a row and the workspace, so each must quarantine the journal.
 */
class FileChangeStoreForgedJournalTest {
	@TempDir Path work;
	@TempDir Path state;
	private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
	private static final byte[] BEFORE = bytes("old\n");
	private static final byte[] AFTER = bytes("new\n");
	private static final ChangeProperties LIMITS = ChangeProperties.defaults();
	private int stores;

	private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
	/** Every call opens a store on its own agent home, so one test can quarantine several journals. */
	private FileChangeStore store() throws Exception {
		return new FileChangeStore(this.state.resolve("home" + this.stores++), this.work);
	}
	private ChangeOwner owner() { return new ChangeOwner("session", new RunId("run"), "attempt", "turn"); }
	private ChangeSet proposal() throws Exception {
		Fingerprint before = new Fingerprint("source", EntryKind.REGULAR_FILE, Fingerprinter.sha256(BEFORE), BEFORE.length, 0644, null);
		Fingerprint after = new Fingerprint("source", EntryKind.REGULAR_FILE, Fingerprinter.sha256(AFTER), AFTER.length, 0644, null);
		Diff diff = new DiffRenderer(new PathPolicy(this.work, this.state.resolve("home"), List.of())).render(before, BEFORE, after, AFTER);
		return ChangeSet.propose(UUID.randomUUID(), owner(), this.work.toRealPath().toString(), new ModelRef("fake", "test"),
				List.of(UUID.randomUUID()), Map.of("source", before), Map.of("source", after), List.of(diff), AT);
	}
	private Approval approval(ChangeSet set, ApprovalDecision decision) {
		return new Approval(UUID.randomUUID(), set.changeSetId(), set.digest(), set.owner(), "console", decision, AT);
	}
	private VerificationEvidence evidence(ChangeSet set, UUID id, byte[] log, int exitCode) {
		return new VerificationEvidence(id, set.changeSetId(), set.owner(), List.of("fixture"), set.cwd(),
				Fingerprinter.treeDigest(set.proposed()), Fingerprinter.sha256(bytes("configuration")),
				Fingerprinter.sha256(log), exitCode, false, AT, AT);
	}
	private void approved(ChangeStore.Lease lease, ChangeSet set) throws Exception {
		lease.propose(set);
		lease.decide(set.changeSetId(), set.owner(), set.digest(), approval(set, ApprovalDecision.APPROVED));
		lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.APPLYING);
	}
	private Checkpoint prepare(ChangeStore.Lease lease, ChangeSet set, Instant at) throws Exception {
		return lease.prepare(set.changeSetId(), set.owner(), set.digest(), UUID.randomUUID(), "source", BEFORE, AFTER, at);
	}
	private ChangeStore.Snapshot latest(ChangeStore.Lease lease, ChangeSet set) throws Exception {
		return lease.read().get(set.changeSetId());
	}
	private static ChangeStore.Snapshot next(ChangeStore.Snapshot last, ChangeSet set,
			List<Checkpoint> checkpoints, List<VerificationEvidence> evidence) {
		return new ChangeStore.Snapshot(last.sequence() + 1, set, set.digest(), checkpoints, evidence);
	}
	private static Path journal(FileChangeStore store) { return store.directory().resolve("journal.log"); }
	private static void code(ChangeStore.Code expected, ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> assertThat(ex.code()).isEqualTo(expected));
	}

	/** version TAB sequence TAB CRC32C TAB JSON, the framing the journal documents. */
	private static byte[] frame(ChangeStore.Snapshot snapshot) {
		String payload = ChangeJournal.JSON.writeValueAsString(snapshot);
		String prefix = "1\t" + snapshot.sequence();
		CRC32C crc = new CRC32C();
		crc.update(bytes(prefix + "\t" + payload));
		return bytes(prefix + "\t" + String.format(Locale.ROOT, "%08x", crc.getValue()) + "\t" + payload + "\n");
	}
	/** Appends the row and proves it is well framed, so only semantic validation can refuse it. */
	private byte[] forge(FileChangeStore store, ChangeStore.Snapshot row) throws Exception {
		Files.write(journal(store), frame(row), StandardOpenOption.APPEND);
		ChangeJournal.Scan scan = new ChangeJournal(journal(store), LIMITS.maxJournalBytes(), LIMITS.maxManifestBytes()).scan();
		assertThat(scan.state()).isEqualTo(ChangeJournal.State.CLEAN);
		assertThat(scan.records()).last().isEqualTo(row);
		return Files.readAllBytes(journal(store));
	}
	/** The forged tail must leave the valid prefix readable, block every write and keep the bytes as found. */
	private void assertQuarantined(FileChangeStore store, List<ChangeStore.Snapshot> validPrefix, byte[] forged) throws Exception {
		ChangeStore.View view = store.read();
		assertThat(view.journalState()).isEqualTo(ChangeJournal.State.CORRUPT);
		assertThat(view.recoveryRequired()).isTrue();
		assertThat(view.changes()).isEqualTo(validPrefix);
		try (var lease = store.acquire()) {
			code(CORRUPT, () -> lease.propose(proposal()));
			for (ChangeStore.Snapshot kept : validPrefix) {
				ChangeSet set = kept.changeSet();
				code(CORRUPT, () -> lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.STALE));
			}
		}
		assertThat(Files.readAllBytes(journal(store))).isEqualTo(forged);
	}

	@Test void handFramedLegalRowIsAcceptedSoTheForgeriesBelowFailOnRulesNotOnFraming() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		ChangeStore.Snapshot proposed;
		try (var lease = store.acquire()) { proposed = lease.propose(set); }
		ChangeSet approved = set.decide(approval(set, ApprovalDecision.APPROVED));
		ChangeStore.Snapshot row = next(proposed, approved, List.of(), List.of());
		forge(store, row);
		ChangeStore.View view = store.read();
		assertThat(view.journalState()).isEqualTo(ChangeJournal.State.CLEAN);
		assertThat(view.get(set.changeSetId())).isEqualTo(row);
		assertThat(view.get(set.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.APPROVED);
	}

	@Test void firstRecordThatIsAlreadyApprovedIsQuarantinedWithAnEmptyPrefix() throws Exception {
		FileChangeStore store = store();
		try (var lease = store.acquire()) { assertThat(lease.read().changes()).isEmpty(); } // Creates the empty journal.
		ChangeSet set = proposal();
		ChangeSet approved = set.decide(approval(set, ApprovalDecision.APPROVED));
		byte[] forged = forge(store, new ChangeStore.Snapshot(1, approved, approved.digest(), List.of(), List.of()));
		assertQuarantined(store, List.of(), forged);
	}

	@Test void recordThatChangesTheImmutableProposalIsQuarantined() throws Exception {
		ChangeOwner other = new ChangeOwner("session", new RunId("run"), "other-attempt", "turn");
		Map<String, UnaryOperator<ChangeSet>> forgeries = new LinkedHashMap<>();
		forgeries.put("owner", s -> ChangeSet.propose(s.changeSetId(), other, s.cwd(), s.model(), s.bindingIds(),
				s.baseline(), s.proposed(), s.diffs(), s.createdAt()));
		forgeries.put("bindings", s -> ChangeSet.propose(s.changeSetId(), s.owner(), s.cwd(), s.model(),
				List.of(UUID.randomUUID()), s.baseline(), s.proposed(), s.diffs(), s.createdAt()));
		forgeries.put("model", s -> ChangeSet.propose(s.changeSetId(), s.owner(), s.cwd(), new ModelRef("fake", "swapped"),
				s.bindingIds(), s.baseline(), s.proposed(), s.diffs(), s.createdAt()));
		// createdAt is not part of the digest, so only the explicit immutability check can catch it.
		forgeries.put("createdAt", s -> ChangeSet.propose(s.changeSetId(), s.owner(), s.cwd(), s.model(), s.bindingIds(),
				s.baseline(), s.proposed(), s.diffs(), s.createdAt().plusSeconds(1)));
		for (var forgery : forgeries.entrySet()) {
			FileChangeStore store = store();
			ChangeSet set = proposal();
			ChangeStore.Snapshot proposed;
			try (var lease = store.acquire()) { proposed = lease.propose(set); }
			ChangeSet changed = forgery.getValue().apply(set);
			assertThat(changed.changeSetId()).as(forgery.getKey()).isEqualTo(set.changeSetId());
			byte[] forged = forge(store, next(proposed, changed, List.of(), List.of()));
			assertQuarantined(store, List.of(proposed), forged);
		}
	}

	@Test void stateUpdateThatAlsoAddsEvidenceOrCheckpointsIsQuarantined() throws Exception {
		// The log and checkpoint bytes below are planted on disk, so only the state-update rule can refuse the rows.
		// With evidence: a legal PENDING_APPROVAL -> STALE edge, but the same record smuggles evidence in.
		FileChangeStore store = store();
		ChangeSet set = proposal();
		ChangeStore.Snapshot proposed;
		try (var lease = store.acquire()) { proposed = lease.propose(set); }
		byte[] log = bytes("log");
		new BlobStore(store.directory().resolve("evidence"), LIMITS.maxBlobBytes()).put(log);
		ChangeSet stale = set.transition(ChangeStatus.STALE);
		var smuggled = evidence(set, UUID.randomUUID(), log, 0);
		byte[] forged = forge(store, next(proposed, stale, List.of(), List.of(smuggled)));
		assertQuarantined(store, List.of(proposed), forged);

		// With a checkpoint: APPROVED -> APPLYING, but the checkpoint arrives in the same record as the status.
		store = store();
		ChangeSet approvedSet = proposal();
		ChangeStore.Snapshot approved;
		try (var lease = store.acquire()) {
			lease.propose(approvedSet);
			approved = lease.decide(approvedSet.changeSetId(), approvedSet.owner(), approvedSet.digest(),
					approval(approvedSet, ApprovalDecision.APPROVED));
		}
		BlobStore blobs = new BlobStore(store.directory().resolve("blobs"), LIMITS.maxBlobBytes());
		blobs.put(BEFORE);
		blobs.put(AFTER);
		ChangeSet applying = approved.changeSet().transition(ChangeStatus.APPLYING);
		Diff diff = applying.diffs().getFirst();
		var checkpoint = new Checkpoint(UUID.randomUUID(), applying.changeSetId(), applying.owner(), diff.before(), diff.after(),
				new Checkpoint.BlobRef(Fingerprinter.sha256(BEFORE), BEFORE.length),
				new Checkpoint.BlobRef(Fingerprinter.sha256(AFTER), AFTER.length), Checkpoint.State.PREPARED, AT);
		forged = forge(store, next(approved, applying, List.of(checkpoint), List.of()));
		assertQuarantined(store, List.of(approved), forged);
	}

	@Test void recordThatDropsAnExistingCheckpointIsQuarantined() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		ChangeStore.Snapshot prepared;
		try (var lease = store.acquire()) {
			approved(lease, set);
			prepare(lease, set, AT);
			prepared = latest(lease, set);
		}
		assertThat(prepared.checkpoints()).hasSize(1);
		byte[] forged = forge(store, next(prepared, prepared.changeSet(), List.of(), prepared.evidence()));
		assertQuarantined(store, List.of(prepared), forged);
	}

	@Test void recordThatDropsOrRewritesRecordedEvidenceIsQuarantined() throws Exception {
		byte[] log = bytes("1 test failed\n");
		for (boolean rewrite : new boolean[] { false, true }) {
			FileChangeStore store = store();
			ChangeSet set = proposal();
			UUID evidenceId = UUID.randomUUID();
			ChangeStore.Snapshot recorded;
			try (var lease = store.acquire()) {
				lease.propose(set);
				recorded = lease.recordEvidence(set.changeSetId(), set.owner(), set.digest(), evidence(set, evidenceId, log, 1), log);
			}
			// Either the failed verification disappears, or it is rewritten as a pass under the same id.
			List<VerificationEvidence> forgedEvidence = rewrite ? List.of(evidence(set, evidenceId, log, 0)) : List.of();
			byte[] forged = forge(store, next(recorded, recorded.changeSet(), recorded.checkpoints(), forgedEvidence));
			assertQuarantined(store, List.of(recorded), forged);
		}
	}

	@Test void checkpointCompletionOutsideTheEdgeTableIsQuarantined() throws Exception {
		// APPLIED may not go back to PREPARED.
		FileChangeStore store = store();
		ChangeSet set = proposal();
		ChangeStore.Snapshot applied;
		try (var lease = store.acquire()) {
			approved(lease, set);
			Checkpoint prepared = prepare(lease, set, AT);
			lease.complete(set.changeSetId(), set.owner(), set.digest(), prepared.checkpointId(), Checkpoint.State.APPLIED, AT);
			applied = latest(lease, set);
		}
		Checkpoint appliedCheckpoint = applied.checkpoints().getFirst();
		byte[] forged = forge(store, next(applied, applied.changeSet(), List.of(withState(appliedCheckpoint, Checkpoint.State.PREPARED, AT)), applied.evidence()));
		assertQuarantined(store, List.of(applied), forged);

		// RESTORED is terminal.
		store = store();
		set = proposal();
		ChangeStore.Snapshot restored;
		try (var lease = store.acquire()) {
			approved(lease, set);
			Checkpoint prepared = prepare(lease, set, AT);
			lease.complete(set.changeSetId(), set.owner(), set.digest(), prepared.checkpointId(), Checkpoint.State.CONFLICT, AT);
			lease.complete(set.changeSetId(), set.owner(), set.digest(), prepared.checkpointId(), Checkpoint.State.RESTORED, AT);
			restored = latest(lease, set);
		}
		forged = forge(store, next(restored, restored.changeSet(),
				List.of(withState(restored.checkpoints().getFirst(), Checkpoint.State.APPLIED, AT)), restored.evidence()));
		assertQuarantined(store, List.of(restored), forged);

		// A completion may not be recorded earlier than the checkpoint it completes.
		store = store();
		set = proposal();
		ChangeStore.Snapshot prepared;
		try (var lease = store.acquire()) {
			approved(lease, set);
			prepare(lease, set, AT.plusSeconds(10));
			prepared = latest(lease, set);
		}
		forged = forge(store, next(prepared, prepared.changeSet(),
				List.of(withState(prepared.checkpoints().getFirst(), Checkpoint.State.APPLIED, AT.plusSeconds(5))), prepared.evidence()));
		assertQuarantined(store, List.of(prepared), forged);
	}

	private static Checkpoint withState(Checkpoint checkpoint, Checkpoint.State state, Instant at) {
		return new Checkpoint(checkpoint.checkpointId(), checkpoint.changeSetId(), checkpoint.owner(), checkpoint.before(),
				checkpoint.after(), checkpoint.beforeBlob(), checkpoint.afterBlob(), state, at);
	}
}
