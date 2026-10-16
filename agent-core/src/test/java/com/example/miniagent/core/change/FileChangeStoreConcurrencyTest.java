package com.example.miniagent.core.change;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;
import static com.example.miniagent.core.change.ChangeStore.Code.*;

/** The process-wide single-writer rule: one lease per workspace in this JVM, released on every path. */
class FileChangeStoreConcurrencyTest {
	@TempDir Path work;
	@TempDir Path state;
	private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
	private static final byte[] BEFORE = bytes("old\n");
	private static final byte[] AFTER = bytes("new\n");
	private static final int THREADS = 8;
	private static final long BOUND_SECONDS = 30;

	private record Outcome(ChangeStore.Lease lease, ChangeStore.Failure failure) {}

	private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
	private Path home() { return this.state.resolve("home"); }
	private FileChangeStore store() throws Exception { return new FileChangeStore(home(), this.work); }
	private ChangeOwner owner() { return new ChangeOwner("session", new RunId("run"), "attempt", "turn"); }
	private ChangeSet proposal() throws Exception {
		Fingerprint before = new Fingerprint("source", EntryKind.REGULAR_FILE, Fingerprinter.sha256(BEFORE), BEFORE.length, 0644, null);
		Fingerprint after = new Fingerprint("source", EntryKind.REGULAR_FILE, Fingerprinter.sha256(AFTER), AFTER.length, 0644, null);
		Diff diff = new DiffRenderer(new PathPolicy(this.work, home(), List.of())).render(before, BEFORE, after, AFTER);
		return ChangeSet.propose(UUID.randomUUID(), owner(), this.work.toRealPath().toString(), new ModelRef("fake", "test"),
				List.of(UUID.randomUUID()), Map.of("source", before), Map.of("source", after), List.of(diff), AT);
	}
	private static void code(ChangeStore.Code expected, ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> assertThat(ex.code()).isEqualTo(expected));
	}
	private static void assertLeaseUsable(ChangeStore store) throws Exception {
		try (var lease = store.acquire()) { assertThat(lease.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN); }
	}
	/**
	 * POSIX locks belong to the process: closing a second descriptor on the lock file would silently drop the
	 * holder's lock, so a refused in-JVM attempt must never open one. Only another process can see that.
	 */
	private static void assertProcessLocked(Path lockFile) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath, LockProbe.class.getName(), lockFile.toString())
				.redirectErrorStream(true);
		builder.environment().clear();
		Process probe = builder.start();
		try {
			assertThat(probe.waitFor(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
			assertThat(new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim()).isEqualTo("LOCKED");
		}
		finally { probe.destroyForcibly(); }
	}

	/** Child process that reports whether another process holds the OS lock on a file. */
	public static class LockProbe {
		public static void main(String[] args) throws Exception {
			try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
					var lock = channel.tryLock()) {
				System.out.println(lock == null ? "LOCKED" : "STOLEN");
			}
		}
	}

	@Test void racingAcquiresYieldExactlyOneLeaseAndTheRestAreLocked() throws Exception {
		var start = new CyclicBarrier(THREADS);
		Path lockFile = store().directory().resolve("workspace.lock");
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		List<Outcome> outcomes = new ArrayList<>();
		try {
			List<Future<Outcome>> futures = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				FileChangeStore contender = store(); // Each thread has its own store object for the same workspace.
				Callable<Outcome> attempt = () -> {
					start.await(BOUND_SECONDS, TimeUnit.SECONDS);
					try { return new Outcome(contender.acquire(), null); }
					catch (ChangeStore.Failure ex) { return new Outcome(null, ex); }
				};
				futures.add(pool.submit(attempt));
			}
			for (Future<Outcome> future : futures) outcomes.add(future.get(BOUND_SECONDS, TimeUnit.SECONDS));
			List<ChangeStore.Lease> winners = outcomes.stream().map(Outcome::lease).filter(Objects::nonNull).toList();
			assertThat(winners).hasSize(1);
			List<ChangeStore.Failure> losers = outcomes.stream().map(Outcome::failure).filter(Objects::nonNull).toList();
			assertThat(losers).hasSize(THREADS - 1).allSatisfy(ex -> {
				assertThat(ex.code()).isEqualTo(LOCKED);
				assertThat(ex).hasMessage("Workspace already leased");
			});
			assertThat(winners.getFirst().read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
			assertProcessLocked(lockFile);
		}
		finally {
			pool.shutdownNow();
			for (Outcome outcome : outcomes) if (outcome.lease() != null) outcome.lease().close();
		}
		assertLeaseUsable(store());
	}

	@Test void contendedStagingAcquiresNeverAdmitTwoHoldersAndNobodyIsLeftWaiting() throws Exception {
		int workers = 4, rounds = 20;
		var holders = new AtomicInteger();
		var peak = new AtomicInteger();
		var completed = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(workers);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < workers; i++) {
				FileChangeStore contender = store();
				futures.add(pool.submit(() -> {
					for (int round = 0; round < rounds; round++) {
						try (var lease = contender.acquireStaging()) {
							peak.accumulateAndGet(holders.incrementAndGet(), Math::max);
							assertThat(lease.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
							holders.decrementAndGet();
							completed.incrementAndGet();
						}
						catch (ChangeStore.Failure ex) { throw new AssertionError(ex.code().toString(), ex); }
					}
				}));
			}
			// A lost wakeup would leave a worker parked forever and surface here as a timeout.
			for (Future<?> future : futures) future.get(BOUND_SECONDS, TimeUnit.SECONDS);
		}
		finally { pool.shutdownNow(); }
		assertThat(peak).hasValue(1);
		assertThat(completed).hasValue(workers * rounds);
		assertLeaseUsable(store());
	}

	@Test void leaseIsReleasedWhenTheBodyThrowsAndAfterEachConvenienceRead() throws Exception {
		FileChangeStore store = store();
		assertThatThrownBy(() -> {
			try (var lease = store.acquire()) { throw new IllegalStateException("body failed"); }
		}).isInstanceOf(IllegalStateException.class).hasMessage("body failed");
		assertLeaseUsable(store);
		for (int i = 0; i < 3; i++) assertThat(store.read().changes()).isEmpty();
		assertLeaseUsable(store);
	}

	@Test void secondCloseIsNoOpAndDoesNotReleaseALaterLease() throws Exception {
		FileChangeStore store = store();
		ChangeStore.Lease first = store.acquire();
		first.close();
		ChangeStore.Lease second = store.acquire();
		first.close();
		code(LOCKED, store::acquire);
		assertThat(second.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
		assertProcessLocked(store.directory().resolve("workspace.lock"));
		second.close();
		assertLeaseUsable(store);
	}

	@Test void leasesOfDifferentWorkspacesDoNotExcludeEachOther() throws Exception {
		Path otherWork = Files.createDirectory(this.state.resolve("other-workspace"));
		FileChangeStore first = store();
		FileChangeStore second = new FileChangeStore(home(), otherWork);
		try (var held = first.acquire()) {
			assertThat(second.workspaceHash()).isNotEqualTo(first.workspaceHash());
			assertLeaseUsable(second);
			code(LOCKED, first::acquire);
			assertThat(held.read().changes()).isEmpty();
		}
		assertLeaseUsable(first);
	}

	@Test void closedLeaseRefusesEveryOperationAndLeavesTheJournalUntouched() throws Exception {
		FileChangeStore store = store();
		ChangeSet set = proposal();
		byte[] log = bytes("output\n");
		var evidence = new VerificationEvidence(UUID.randomUUID(), set.changeSetId(), set.owner(), List.of("fixture"),
				set.cwd(), Fingerprinter.treeDigest(set.proposed()), Fingerprinter.sha256(bytes("configuration")),
				Fingerprinter.sha256(log), 0, false, AT, AT);
		var approval = new Approval(UUID.randomUUID(), set.changeSetId(), set.digest(), set.owner(), "console",
				ApprovalDecision.APPROVED, AT);
		ChangeStore.Lease lease = store.acquire();
		lease.propose(set);
		byte[] journal = Files.readAllBytes(store.directory().resolve("journal.log"));
		lease.close();
		Map<String, ThrowingCallable> operations = new LinkedHashMap<>();
		operations.put("read", lease::read);
		operations.put("stagingDirectory", () -> lease.stagingDirectory(set.owner()));
		operations.put("propose", () -> lease.propose(set));
		operations.put("decide", () -> lease.decide(set.changeSetId(), set.owner(), set.digest(), approval));
		operations.put("transition", () -> lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.STALE));
		operations.put("prepare", () -> lease.prepare(set.changeSetId(), set.owner(), set.digest(), UUID.randomUUID(),
				"source", BEFORE, AFTER, AT));
		operations.put("complete", () -> lease.complete(set.changeSetId(), set.owner(), set.digest(), UUID.randomUUID(),
				Checkpoint.State.APPLIED, AT));
		operations.put("recordEvidence", () -> lease.recordEvidence(set.changeSetId(), set.owner(), set.digest(), evidence, log));
		operations.put("readBlob", () -> lease.readBlob(new Checkpoint.BlobRef(Fingerprinter.sha256(BEFORE), BEFORE.length)));
		operations.put("readLog", () -> lease.readLog(set.changeSetId(), set.owner(), set.digest(), evidence.evidenceId()));
		operations.forEach((name, operation) -> assertThatThrownBy(operation).as(name)
				.isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> assertThat(ex.code()).isEqualTo(CLOSED)));
		assertThat(Files.readAllBytes(store.directory().resolve("journal.log"))).isEqualTo(journal);
		try (var next = store.acquire()) {
			assertThat(next.read().get(set.changeSetId()).changeSet()).isEqualTo(set);
		}
	}
}
