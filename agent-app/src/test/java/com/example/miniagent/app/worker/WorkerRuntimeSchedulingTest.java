package com.example.miniagent.app.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.miniagent.core.change.ChangeOwner;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.change.VerificationTarget;
import com.example.miniagent.core.goal.*;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** Scheduling without the live gate: GoalService is the model boundary and is replaced by a mock. */
class WorkerRuntimeSchedulingTest {
	@TempDir Path temporary;

	private static final Duration WAIT = Duration.ofSeconds(30);

	@Test
	void goalSubmittedAfterStartRunsOnceAndAnAttemptedGoalIsNotRepeated() throws Exception {
		Path workspace = temporary.toRealPath();
		Path home = workspace.resolve("home");
		var store = new GoalStore(home);
		var early = goal("a-early", workspace, GoalRecord.State.PENDING, GoalRecord.Phase.READY);
		var late = goal("b-late", workspace, GoalRecord.State.PENDING, GoalRecord.Phase.READY);
		store.create(early);
		var service = mock(GoalService.class);
		// The mock never writes the store, so a-early stays PENDING on disk after its attempt.
		when(service.execute("a-early")).thenReturn(succeeded(early));
		when(service.execute("b-late")).thenReturn(succeeded(late));
		String instance = UUID.randomUUID().toString();
		var controller = controller(workspace, home);
		var done = run(new WorkerRuntime(workspace, home, instance, store, service));
		try {
			controller.awaitStarted(instance, WAIT);
			WorkerController.await(controller.files().directory, WAIT, controller::status,
					s -> s.lastGoal().equals("a-early") && s.activeGoal().isEmpty());
			store.create(late);
			var status = WorkerController.await(controller.files().directory, WAIT, controller::status,
					s -> s.lastGoal().equals("b-late") && s.activeGoal().isEmpty());
			assertThat(status.owned()).isTrue();
			assertThat(status.state()).isEqualTo("RUNNING");
			assertThat(status.lastState()).isEqualTo("SUCCEEDED");
			assertThat(status.error()).isEmpty();
			controller.stop(instance);
			done.get(30, TimeUnit.SECONDS);
			// a-early sorts first, so the scan that found b-late passed it and the attempted set skipped it.
			verify(service, times(1)).execute("a-early");
			verify(service, times(1)).execute("b-late");
		}
		finally { stop(controller, instance, done); }
	}

	@Test
	void secondRuntimeOnAnOwnedWorkspaceIsRefusedWithoutTouchingTheOwner() throws Exception {
		Path workspace = temporary.toRealPath();
		Path home = workspace.resolve("home");
		var store = new GoalStore(home);
		String owner = UUID.randomUUID().toString();
		String intruder = UUID.randomUUID().toString();
		var controller = controller(workspace, home);
		var done = run(new WorkerRuntime(workspace, home, owner, store, mock(GoalService.class)));
		try {
			var before = controller.awaitStarted(owner, WAIT);
			var second = new WorkerRuntime(workspace, home, intruder, store, mock(GoalService.class));
			assertThatThrownBy(second::run).isInstanceOf(IOException.class)
					.hasMessage("Worker workspace already owned");
			assertThat(controller.status()).isEqualTo(before);
			assertThat(controller.files().exitInstance()).as("a refused runtime publishes no exit event").isEmpty();
			controller.stop(owner);
			done.get(30, TimeUnit.SECONDS);
			var after = controller.awaitStopped(owner, WAIT);
			assertThat(after.owned()).isFalse();
			assertThat(after.state()).isEqualTo("STOPPED");
			assertThat(controller.files().read().state()).isEqualTo("STOPPED");
			assertThat(controller.files().exitInstance()).isEqualTo(owner);
		}
		finally { stop(controller, owner, done); }
	}

	@Test
	void completedCandidateIsReconciledByInspectWithoutAModelCall() throws Exception {
		Path workspace = temporary.toRealPath();
		Path home = workspace.resolve("home");
		var store = new GoalStore(home);
		var pending = goal("candidate", workspace, GoalRecord.State.RUNNING, GoalRecord.Phase.READY);
		var spec = pending.spec();
		String digest = "a".repeat(64);
		var target = new VerificationTarget(UUID.randomUUID(), new ChangeOwner("parent", spec.runId(), "attempt", "turn"),
				digest, digest, true);
		var gate = new GoalRecord.GateResult(0, digest, "b".repeat(64), VerificationRunner.Code.SUCCEEDED, null, "");
		store.create(new GoalRecord(1, "candidate", spec, GoalRecord.State.RUNNING, GoalRecord.Reason.NONE,
				1, GoalRecord.Phase.READY, target, List.of(gate), true, ""));
		var service = mock(GoalService.class);
		var inspected = new CompletableFuture<Void>();
		// A RUNNING goal at start is also an orphan: only a terminal inspect result keeps resume away.
		when(service.inspect("candidate")).thenAnswer(call -> {
			inspected.complete(null);
			return new GoalRecord(1, "candidate", spec, GoalRecord.State.SUCCEEDED, GoalRecord.Reason.VERIFIED_READONLY,
					1, GoalRecord.Phase.READY, target, List.of(gate), true, "");
		});
		String instance = UUID.randomUUID().toString();
		var controller = controller(workspace, home);
		var done = run(new WorkerRuntime(workspace, home, instance, store, service));
		try {
			controller.awaitStarted(instance, WAIT);
			inspected.get(30, TimeUnit.SECONDS);
			controller.stop(instance);
			done.get(30, TimeUnit.SECONDS);
			var stopped = controller.awaitStopped(instance, WAIT);
			assertThat(stopped.lastGoal()).as("no goal was dispatched").isEmpty();
			assertThat(stopped.error()).isEmpty();
			verify(service, atLeastOnce()).inspect("candidate");
			verify(service, never()).resume(anyString(), any());
			verify(service, never()).execute(anyString());
		}
		finally { stop(controller, instance, done); }
	}

	@Test
	void busyGoalIsRetriedOnlyAfterTheNextEvent() throws Exception {
		Path workspace = temporary.toRealPath();
		Path home = workspace.resolve("home");
		var store = new GoalStore(home);
		var busyGoal = goal("busy", workspace, GoalRecord.State.PENDING, GoalRecord.Phase.READY);
		store.create(busyGoal);
		GoalStore.Busy busy;
		// A real Busy, taken before start: claiming creates claim.lock, which would itself be a watch event.
		try (var held = store.claim("busy")) {
			busy = assertThrows(GoalStore.Busy.class, () -> store.claim("busy"));
		}
		assertThat(busy).hasMessage("Goal already claimed");
		var service = mock(GoalService.class);
		var calls = new AtomicInteger();
		var firstCall = new CompletableFuture<Void>();
		var secondCall = new CompletableFuture<Void>();
		when(service.execute("busy")).thenAnswer(call -> {
			if (calls.incrementAndGet() == 1) {
				firstCall.complete(null);
				throw busy;
			}
			secondCall.complete(null);
			return succeeded(busyGoal);
		});
		String instance = UUID.randomUUID().toString();
		var controller = controller(workspace, home);
		var done = run(new WorkerRuntime(workspace, home, instance, store, service));
		try {
			controller.awaitStarted(instance, WAIT);
			firstCall.get(30, TimeUnit.SECONDS);
			var idle = WorkerController.await(controller.files().directory, WAIT, controller::status,
					s -> s.activeGoal().isEmpty());
			assertThat(idle.lastGoal()).as("Busy is neither a result nor a refusal").isEmpty();
			assertThat(idle.error()).isEmpty();
			assertThat(calls).hasValue(1);
			controller.wakeup(instance);
			secondCall.get(30, TimeUnit.SECONDS);
			var status = WorkerController.await(controller.files().directory, WAIT, controller::status,
					s -> s.lastGoal().equals("busy"));
			assertThat(status.lastState()).isEqualTo("SUCCEEDED");
			assertThat(status.state()).isEqualTo("RUNNING");
			try (var inbox = Files.list(controller.files().inbox)) {
				assertThat(inbox).as("the wakeup request was consumed").isEmpty();
			}
			controller.stop(instance);
			done.get(30, TimeUnit.SECONDS);
			assertThat(calls).hasValue(2);
		}
		finally { stop(controller, instance, done); }
	}

	private static WorkerController controller(Path workspace, Path home) {
		return new WorkerController(workspace, home, () -> { throw new AssertionError("Unit test must not launch"); });
	}
	private static CompletableFuture<Void> run(WorkerRuntime runtime) {
		var result = new CompletableFuture<Void>();
		Thread.ofVirtual().start(() -> {
			try { runtime.run(); result.complete(null); }
			catch (Throwable ex) { result.completeExceptionally(ex); }
		});
		return result;
	}
	/** Cleanup only. Each test asserts the worker outcome itself, so a worker failure is not masked here. */
	private static void stop(WorkerController controller, String instance, CompletableFuture<Void> done)
			throws Exception {
		if (!done.isDone() && controller.status().owned()) controller.stop(instance);
		done.handle((value, failure) -> null).get(30, TimeUnit.SECONDS);
	}
	private static GoalRecord goal(String id, Path workspace, GoalRecord.State state, GoalRecord.Phase phase) {
		var request = new GoalRequest("bounded", List.of(new GoalRequest.Gate(List.of("true"), 1000, 1024)),
				2, 10000, null, 2);
		var selection = new ModelSelection(new ModelRef("codex", "gpt-6.1-sol"), Map.of("effort", "high"));
		var spec = new GoalRecord.Spec(request, selection, "parent", workspace.toString(), RunId.random());
		return new GoalRecord(1, id, spec, state, GoalRecord.Reason.NONE,
				state == GoalRecord.State.PENDING ? 0 : 1, phase, null, List.of(), false, "");
	}
	private static GoalRecord succeeded(GoalRecord record) {
		return new GoalRecord(1, record.id(), record.spec(), GoalRecord.State.SUCCEEDED,
				GoalRecord.Reason.VERIFIED_READONLY, 1, GoalRecord.Phase.READY, null, List.of(), false, "");
	}
}
