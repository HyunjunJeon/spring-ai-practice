package com.example.miniagent.core.change;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;
import static com.example.miniagent.core.change.ChangeStore.Code.*;

/** Write-side size limits: the limits are measured from real records, never hard-coded byte counts. */
class ChangeStoreLimitsTest {
	@TempDir Path work;
	@TempDir Path state;
	private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
	private static final byte[] BEFORE = bytes("old\n");
	private static final byte[] SMALL = bytes("new\n");
	private static final byte[] LARGE = bytes("replacement line of some length\n".repeat(80));
	private static final int BLOB_LIMIT = 1024;
	private static final int ROOMY_JOURNAL = 1 << 20;

	private record Sizes(int manifest, int journal) {}

	private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
	private FileChangeStore store(String home, ChangeProperties limits) throws Exception {
		return new FileChangeStore(this.state.resolve(home), this.work, limits);
	}
	private ChangeOwner owner() { return new ChangeOwner("session", new RunId("run"), "attempt", "turn"); }
	private ChangeSet proposal(byte[] after) throws Exception {
		Fingerprint before = new Fingerprint("source", EntryKind.REGULAR_FILE, Fingerprinter.sha256(BEFORE), BEFORE.length, 0644, null);
		Fingerprint changed = new Fingerprint("source", EntryKind.REGULAR_FILE, Fingerprinter.sha256(after), after.length, 0644, null);
		Diff diff = new DiffRenderer(new PathPolicy(this.work, this.state.resolve("unused"), List.of())).render(before, BEFORE, changed, after);
		return ChangeSet.propose(UUID.randomUUID(), owner(), this.work.toRealPath().toString(), new ModelRef("fake", "test"),
				List.of(UUID.randomUUID()), Map.of("source", before), Map.of("source", changed), List.of(diff), AT);
	}
	private static Path journal(FileChangeStore store) { return store.directory().resolve("journal.log"); }
	private static Path manifest(FileChangeStore store, ChangeSet set) {
		return store.directory().resolve("manifests").resolve(set.changeSetId() + ".json");
	}
	private static void refused(ChangeStore.Code code, String message, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
			assertThat(ex.code()).isEqualTo(code);
			assertThat(ex).hasMessage(message);
		});
	}

	/** Manifest size of the first proposal and journal size after all of them, under default limits. */
	private Sizes measure(byte[] after, int proposals) throws Exception {
		FileChangeStore probe = store("probe-" + UUID.randomUUID(), ChangeProperties.defaults());
		ChangeSet first = proposal(after);
		try (var lease = probe.acquire()) {
			lease.propose(first);
			for (int i = 1; i < proposals; i++) lease.propose(proposal(after));
		}
		return new Sizes((int) Files.size(manifest(probe, first)), (int) Files.size(journal(probe)));
	}

	@Test void manifestExactlyAtTheLimitIsPublished() throws Exception {
		int limit = measure(SMALL, 1).manifest();
		FileChangeStore store = store("tight", new ChangeProperties(BLOB_LIMIT, limit, ROOMY_JOURNAL));
		ChangeSet set = proposal(SMALL);
		try (var lease = store.acquire()) { lease.propose(set); }
		assertThat(Files.size(manifest(store, set))).isEqualTo(limit);
		assertThat(store.read().get(set.changeSetId()).changeSet()).isEqualTo(set);
	}

	@Test void manifestOneByteOverTheLimitIsRefusedAndNothingIsWritten() throws Exception {
		int limit = measure(SMALL, 1).manifest() - 1;
		FileChangeStore store = store("tight", new ChangeProperties(BLOB_LIMIT, limit, ROOMY_JOURNAL));
		try (var lease = store.acquire()) {
			refused(LIMIT, "Manifest exceeds limit", () -> lease.propose(proposal(SMALL)));
		}
		assertThat(Files.size(journal(store))).isZero();
		try (var manifests = Files.list(store.directory().resolve("manifests"))) { assertThat(manifests).isEmpty(); }
	}

	@Test void oversizedProposalIsRefusedAndTheLeaseStillAcceptsASmallerOne() throws Exception {
		int limit = measure(SMALL, 1).manifest();
		FileChangeStore store = store("tight", new ChangeProperties(BLOB_LIMIT, limit, ROOMY_JOURNAL));
		ChangeSet small = proposal(SMALL);
		try (var lease = store.acquire()) {
			refused(LIMIT, "Manifest exceeds limit", () -> lease.propose(proposal(LARGE)));
			assertThat(Files.size(journal(store))).isZero();
			assertThat(lease.propose(small).changeSet()).isEqualTo(small);
		}
		assertThat(store.read().changes()).extracting(s -> s.changeSet().changeSetId()).containsExactly(small.changeSetId());
	}

	@Test void journalAcceptsRecordsUpToItsLimitByteAndRefusesTheNext() throws Exception {
		int manifest = measure(SMALL, 1).manifest();
		int journalLimit = measure(SMALL, 2).journal();
		FileChangeStore store = store("tight", new ChangeProperties(BLOB_LIMIT, manifest, journalLimit));
		try (var lease = store.acquire()) {
			lease.propose(proposal(SMALL));
			lease.propose(proposal(SMALL));
			assertThat(Files.size(journal(store))).isEqualTo(journalLimit);
			byte[] full = Files.readAllBytes(journal(store));
			refused(LIMIT, "Journal exceeds limit", () -> lease.propose(proposal(SMALL)));
			assertThat(Files.readAllBytes(journal(store))).isEqualTo(full);
		}
		assertThat(store.read().changes()).hasSize(2);
		assertThat(store.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
	}

	@Test void journalOneByteBelowTwoRecordsRefusesTheSecond() throws Exception {
		int manifest = measure(SMALL, 1).manifest();
		int journalLimit = measure(SMALL, 2).journal() - 1;
		FileChangeStore store = store("tight", new ChangeProperties(BLOB_LIMIT, manifest, journalLimit));
		ChangeSet first = proposal(SMALL);
		try (var lease = store.acquire()) {
			lease.propose(first);
			byte[] one = Files.readAllBytes(journal(store));
			refused(LIMIT, "Journal exceeds limit", () -> lease.propose(proposal(SMALL)));
			assertThat(Files.readAllBytes(journal(store))).isEqualTo(one);
		}
		assertThat(store.read().changes()).extracting(s -> s.changeSet().changeSetId()).containsExactly(first.changeSetId());
	}

	@Test void journalLimitRefusalWroteNothingSoTheLeaseStaysUsable() throws Exception {
		Sizes small = measure(SMALL, 1);
		Sizes large = measure(LARGE, 1);
		// The journal can hold two small records, but not a small one followed by the large one.
		assertThat(2 * small.journal()).isLessThanOrEqualTo(large.manifest());
		assertThat(small.journal() + large.journal()).isGreaterThan(large.manifest());
		FileChangeStore store = store("tight", new ChangeProperties(BLOB_LIMIT, large.manifest(), large.manifest()));
		ChangeSet first = proposal(SMALL);
		ChangeSet second = proposal(SMALL);
		try (var lease = store.acquire()) {
			lease.propose(first);
			byte[] one = Files.readAllBytes(journal(store));
			refused(LIMIT, "Journal exceeds limit", () -> lease.propose(proposal(LARGE)));
			assertThat(Files.readAllBytes(journal(store))).isEqualTo(one);
			assertThat(lease.propose(second).changeSet()).isEqualTo(second);
		}
		assertThat(store.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
		assertThat(store.read().changes()).extracting(s -> s.changeSet().changeSetId())
				.containsExactly(first.changeSetId(), second.changeSetId());
	}
}
