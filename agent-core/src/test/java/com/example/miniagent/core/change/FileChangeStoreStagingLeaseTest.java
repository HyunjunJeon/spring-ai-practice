package com.example.miniagent.core.change;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;
import static com.example.miniagent.core.change.ChangeStore.Code.*;

/** acquireStaging queues behind this JVM's lease, wakes on close, and fails fast behind another process. */
class FileChangeStoreStagingLeaseTest {
	@TempDir Path work;
	@TempDir Path state;
	private static final long BOUND_SECONDS = 30;

	private record Outcome(ChangeStore.Lease lease, ChangeStore.Failure failure, boolean interrupted) {}

	/** A thread that calls acquireStaging on a store and reports what happened, including its interrupt flag. */
	private static final class Waiter {
		final Thread thread;
		final CompletableFuture<Outcome> outcome = new CompletableFuture<>();

		Waiter(ChangeStore store) {
			this.thread = new Thread(() -> {
				try { this.outcome.complete(new Outcome(store.acquireStaging(), null, Thread.currentThread().isInterrupted())); }
				catch (ChangeStore.Failure ex) {
					this.outcome.complete(new Outcome(null, ex, Thread.currentThread().isInterrupted()));
				}
				catch (RuntimeException ex) { this.outcome.completeExceptionally(ex); }
			}, "staging-waiter");
			this.thread.setDaemon(true);
			this.thread.start();
		}

		Outcome result() throws Exception { return this.outcome.get(BOUND_SECONDS, TimeUnit.SECONDS); }
		/** Parked in Object.wait inside acquireStaging, not merely not finished yet. */
		boolean parked() {
			return this.thread.getState() == Thread.State.WAITING
					&& Arrays.stream(this.thread.getStackTrace()).anyMatch(frame -> frame.getMethodName().equals("acquireStaging"));
		}
	}

	private Path home() { return this.state.resolve("home"); }
	private FileChangeStore store() throws Exception { return new FileChangeStore(home(), this.work); }
	private static void code(ChangeStore.Code expected, String message, ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
			assertThat(ex.code()).isEqualTo(expected);
			assertThat(ex).hasMessageContaining(message);
		});
	}
	private static void assertLeaseUsable(ChangeStore store) throws Exception {
		try (var lease = store.acquire()) { assertThat(lease.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN); }
	}
	private static void awaitUntil(String what, BooleanSupplier condition) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime() - deadline).as("timed out waiting for " + what).isNegative();
			Thread.yield();
		}
	}
	private static void awaitParked(Waiter waiter) {
		awaitUntil("a waiter to park in acquireStaging", () -> waiter.parked() || waiter.outcome.isDone());
		assertThat(waiter.parked()).as("waiter returned instead of waiting").isTrue();
	}

	@Test void stagingAcquireBlocksWhileTheJvmLeaseIsHeldAndProceedsAfterClose() throws Exception {
		FileChangeStore store = store();
		ChangeStore.Lease held = store.acquire();
		Waiter waiter = new Waiter(store());
		try {
			awaitParked(waiter);
			assertThat(waiter.outcome).isNotDone();
			assertThat(held.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
			held.close();
			Outcome outcome = waiter.result();
			assertThat(outcome.failure()).isNull();
			assertThat(outcome.interrupted()).isFalse();
			try (var staged = outcome.lease()) {
				assertThat(staged.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
				// The staging lease is now the one writer of the workspace.
				code(LOCKED, "Workspace already leased", store::acquire);
			}
			assertLeaseUsable(store);
		}
		finally { held.close(); }
	}

	@Test void closeWakesEveryWaiterAndTheyTakeTurnsWithoutOverlap() throws Exception {
		FileChangeStore store = store();
		ChangeStore.Lease held = store.acquire();
		Waiter first = new Waiter(store());
		Waiter second = new Waiter(store());
		try {
			awaitParked(first);
			awaitParked(second);
			held.close();
			// Both are notified; one wins the lease and the other goes back to waiting instead of failing.
			awaitUntil("one waiter to take the lease", () -> first.outcome.isDone() || second.outcome.isDone());
			Waiter winner = first.outcome.isDone() ? first : second;
			Waiter loser = winner == first ? second : first;
			Outcome won = winner.result();
			assertThat(won.failure()).isNull();
			awaitParked(loser);
			assertThat(loser.outcome).isNotDone();
			won.lease().close();
			Outcome next = loser.result();
			assertThat(next.failure()).isNull();
			assertThat(next.lease().read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
			next.lease().close();
			assertLeaseUsable(store);
		}
		finally { held.close(); }
	}

	@Test void interruptAbortsTheWaitWithLockedAndRestoresTheInterruptFlag() throws Exception {
		FileChangeStore store = store();
		ChangeStore.Lease held = store.acquire();
		Waiter waiter = new Waiter(store());
		try {
			awaitParked(waiter);
			waiter.thread.interrupt();
			Outcome outcome = waiter.result();
			assertThat(outcome.lease()).isNull();
			assertThat(outcome.failure().code()).isEqualTo(LOCKED);
			assertThat(outcome.failure()).hasMessage("Interrupted while awaiting stage journal lease")
					.hasCauseInstanceOf(InterruptedException.class);
			assertThat(outcome.interrupted()).isTrue();
			// The aborted wait neither released nor duplicated the holder's lease.
			assertThat(held.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
			code(LOCKED, "Workspace already leased", store::acquire);
			held.close();
			assertLeaseUsable(store);
		}
		finally { held.close(); }
	}

	@Test void stagingAcquireOfAnotherWorkspaceDoesNotWaitForThisLease() throws Exception {
		Path otherWork = Files.createDirectory(this.state.resolve("other-workspace"));
		try (var held = store().acquire()) {
			Waiter other = new Waiter(new FileChangeStore(home(), otherWork));
			Outcome outcome = other.result();
			assertThat(outcome.failure()).isNull();
			outcome.lease().close();
			assertThat(held.read().changes()).isEmpty();
		}
	}

	@Test void stagingAcquireFailsImmediatelyWhileAnotherProcessHoldsTheOsLock() throws Exception {
		FileChangeStore store = store();
		assertLeaseUsable(store); // Creates workspace.lock.
		Path lock = store.directory().resolve("workspace.lock");
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath, LockHolder.class.getName(), lock.toString())
				.redirectErrorStream(true);
		builder.environment().clear();
		Process holder = builder.start();
		try (var output = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8))) {
			assertThat(CompletableFuture.supplyAsync(() -> {
				try { return output.readLine(); }
				catch (IOException ex) { throw new UncheckedIOException(ex); }
			}).get(BOUND_SECONDS, TimeUnit.SECONDS)).isEqualTo("HELD");
			// No waiting: the in-JVM set is empty, so the OS lock is what refuses both entry points.
			code(LOCKED, "Workspace leased by another process", store::acquireStaging);
			code(LOCKED, "Workspace leased by another process", store::acquire);
			holder.getOutputStream().close();
			assertThat(holder.waitFor(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
			assertThat(holder.exitValue()).isZero();
			// The refused attempts left no in-JVM lease behind and the lock is free again.
			try (var lease = store.acquireStaging()) {
				assertThat(lease.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
			}
		}
		finally { holder.destroyForcibly(); }
	}

	/** Child process that owns the OS lock on a workspace lock file until its stdin closes. */
	public static class LockHolder {
		public static void main(String[] args) throws Exception {
			try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
					var lock = channel.tryLock()) {
				System.out.println(lock == null ? "BUSY" : "HELD");
				System.out.flush();
				System.in.readAllBytes();
			}
		}
	}
}
