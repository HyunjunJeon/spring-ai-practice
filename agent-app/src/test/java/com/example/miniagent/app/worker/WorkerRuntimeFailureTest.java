package com.example.miniagent.app.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.example.miniagent.core.change.AtomicFiles;
import com.example.miniagent.core.goal.*;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A broken goal or a failing store must be published as a worker error, never stop the whole worker. */
class WorkerRuntimeFailureTest {
	@TempDir Path temporary;

	private static final Duration WAIT = Duration.ofSeconds(30);

	@Test
	void unreadableGoalAtStartupIsRefusedWhileHealthyGoalStillRuns() throws Exception {
		Path workspace = temporary.toRealPath();
		Path home = workspace.resolve("home");
		var store = new GoalStore(home);
		var healthy = goal("a-healthy", workspace, GoalRecord.State.PENDING, GoalRecord.Phase.READY);
		store.create(healthy);
		store.create(goal("z-corrupt", workspace, GoalRecord.State.PENDING, GoalRecord.Phase.READY));
		Path corrupt = home.resolve("goals").resolve("z-corrupt").resolve("state.json");
		Files.write(corrupt, "{".getBytes(StandardCharsets.UTF_8)); // Same 0600 inode, unparseable bytes.
		var service = mock(GoalService.class);
		var executed = new CompletableFuture<Void>();
		when(service.execute("a-healthy")).thenAnswer(call -> {
			executed.complete(null);
			return succeeded(healthy);
		});
		String instance = UUID.randomUUID().toString();
		var controller = controller(workspace, home);
		var done = run(new WorkerRuntime(workspace, home, instance, store, service));
		try {
			CompletableFuture.anyOf(executed, done).handle((value, failure) -> null).get(30, TimeUnit.SECONDS);
			assertThat(done).as("one unreadable goal must not stop the worker").isNotDone();
			var status = WorkerController.await(controller.files().directory, WAIT, controller::status,
					s -> s.lastGoal().equals("a-healthy") && s.error().equals("GOAL_REFUSED"));
			assertThat(status.owned()).isTrue();
			assertThat(status.state()).isEqualTo("RUNNING");
			assertThat(status.lastState()).isEqualTo("SUCCEEDED");
			assertThat(Files.readString(corrupt)).isEqualTo("{");
			controller.stop(instance);
			done.get(30, TimeUnit.SECONDS);
			verify(service, never()).execute("z-corrupt");
			verify(service, never()).resume(eq("z-corrupt"), any());
		}
		finally { stop(controller, instance, done); }
	}

	@Test
	void goalThatBecomesUnreadableIsRefusedWhileLaterGoalStillRuns() throws Exception {
		Path workspace = temporary.toRealPath();
		Path home = workspace.resolve("home");
		var store = new GoalStore(home);
		var first = goal("a-first", workspace, GoalRecord.State.PENDING, GoalRecord.Phase.READY);
		var later = goal("b-later", workspace, GoalRecord.State.PENDING, GoalRecord.Phase.READY);
		store.create(first);
		var service = mock(GoalService.class);
		var laterRan = new CompletableFuture<Void>();
		when(service.execute("a-first")).thenReturn(succeeded(first));
		when(service.execute("b-later")).thenAnswer(call -> {
			laterRan.complete(null);
			return succeeded(later);
		});
		String instance = UUID.randomUUID().toString();
		var controller = controller(workspace, home);
		var done = run(new WorkerRuntime(workspace, home, instance, store, service));
		try {
			controller.awaitStarted(instance, WAIT);
			WorkerController.await(controller.files().directory, WAIT, controller::status,
					s -> s.lastGoal().equals("a-first") && s.activeGoal().isEmpty());
			// The worker is idle; a checksum mismatch now makes a-first unreadable on the next scan.
			Path checkpoint = home.resolve("goals").resolve("a-first").resolve("state.json");
			String tampered = Files.readString(checkpoint)
					.replaceFirst("\"sha256\":\"[0-9a-f]{64}\"", "\"sha256\":\"" + "0".repeat(64) + "\"");
			AtomicFiles.write(checkpoint, tampered.getBytes(StandardCharsets.UTF_8), 1 << 20);
			assertThatThrownBy(() -> store.read("a-first")).isInstanceOf(IOException.class)
					.hasMessage("Corrupt goal checkpoint");
			store.create(later);
			CompletableFuture.anyOf(laterRan, done).handle((value, failure) -> null).get(30, TimeUnit.SECONDS);
			assertThat(done).as("a goal that became unreadable must not stop the worker").isNotDone();
			var status = WorkerController.await(controller.files().directory, WAIT, controller::status,
					s -> s.lastGoal().equals("b-later") && s.error().equals("GOAL_REFUSED"));
			assertThat(status.owned()).isTrue();
			assertThat(status.state()).isEqualTo("RUNNING");
			assertThat(status.lastState()).isEqualTo("SUCCEEDED");
			assertThat(Files.readString(checkpoint)).isEqualTo(tampered);
			controller.stop(instance);
			done.get(30, TimeUnit.SECONDS);
			verify(service, times(1)).execute("a-first");
			verify(service, times(1)).execute("b-later");
		}
		finally { stop(controller, instance, done); }
	}

	@Test
	void storageFailureAfterAFailedGoalIsPublishedAndWorkerKeepsRunning() throws Exception {
		Path workspace = temporary.toRealPath();
		Path home = workspace.resolve("home");
		var store = new GoalStore(home);
		store.create(goal("vanishing", workspace, GoalRecord.State.PENDING, GoalRecord.Phase.READY));
		var after = goal("after", workspace, GoalRecord.State.PENDING, GoalRecord.Phase.READY);
		Path checkpoint = home.resolve("goals").resolve("vanishing").resolve("state.json");
		var service = mock(GoalService.class);
		when(service.execute("vanishing")).thenAnswer(call -> {
			// The goal directory stays; only its checkpoint disappears before the failure is reported.
			Files.delete(checkpoint);
			throw new IOException("synthetic provider failure");
		});
		when(service.execute("after")).thenReturn(succeeded(after));
		String instance = UUID.randomUUID().toString();
		var controller = controller(workspace, home);
		var done = run(new WorkerRuntime(workspace, home, instance, store, service));
		try {
			var failed = WorkerController.await(controller.files().directory, WAIT, controller::status,
					s -> s.lastGoal().equals("vanishing") && s.activeGoal().isEmpty());
			assertThat(failed.error()).isEqualTo("STORAGE_FAILURE");
			assertThat(failed.lastState()).isEmpty();
			assertThat(failed.owned()).isTrue();
			assertThat(failed.state()).isEqualTo("RUNNING");
			assertThat(done).isNotDone();
			// The surviving worker still executes the next goal and a success clears the error.
			store.create(after);
			var recovered = WorkerController.await(controller.files().directory, WAIT, controller::status,
					s -> s.lastGoal().equals("after"));
			assertThat(recovered.lastState()).isEqualTo("SUCCEEDED");
			assertThat(recovered.error()).isEmpty();
			controller.stop(instance);
			done.get(30, TimeUnit.SECONDS);
			assertThat(controller.awaitStopped(instance, WAIT).owned()).isFalse();
			verify(service, times(1)).execute("vanishing");
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
