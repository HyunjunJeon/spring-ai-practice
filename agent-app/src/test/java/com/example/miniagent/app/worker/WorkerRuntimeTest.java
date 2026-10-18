package com.example.miniagent.app.worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.goal.*;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Scheduler/control unit tests only. The parent's real OAuth process tests own detachment proof. */
class WorkerRuntimeTest {
	@TempDir Path temporary;

	private static final Duration WAIT = Duration.ofSeconds(10);

	@Test
	void stopDrainsAcceptedGoalWithoutClaimingAnother() throws Exception {
		Path workspace = temporary.toRealPath();
		Path home = workspace.resolve("home");
		var store = new GoalStore(home);
		var first = goal("first", workspace, GoalRecord.State.PENDING, GoalRecord.Phase.READY);
		store.create(first);
		var service = mock(GoalService.class);
		var entered = new CompletableFuture<Void>();
		var drain = new CompletableFuture<Void>();
		when(service.execute("first")).thenAnswer(call -> {
			entered.complete(null);
			drain.get(10, TimeUnit.SECONDS);
			return first;
		});
		String instance = UUID.randomUUID().toString();
		var controller = controller(workspace, home);
		var done = run(new WorkerRuntime(workspace, home, instance, store, service));
		try {
			controller.awaitStarted(instance, WAIT);
			entered.get(10, TimeUnit.SECONDS);
			store.create(goal("second", workspace, GoalRecord.State.PENDING, GoalRecord.Phase.READY));
			controller.stop(instance);
			WorkerController.await(controller.files().directory, WAIT, controller::status,
					s -> s.state().equals("DRAINING"));
			assertThat(done.isDone()).isFalse();
			drain.complete(null);
			done.get(10, TimeUnit.SECONDS);
			assertThat(controller.awaitStopped(instance, WAIT).owned()).isFalse();
			verify(service, times(1)).execute("first");
			verify(service, never()).execute("second");
		}
		finally {
			drain.complete(null);
			stop(controller, instance, done);
		}
	}

	@Test
	void staleInstanceCannotStopSuccessorAndExplicitResumeKeepsExactOptions() throws Exception {
		Path workspace = temporary.toRealPath();
		Path home = workspace.resolve("home");
		var store = new GoalStore(home);
		var blocked = goal("blocked", workspace, GoalRecord.State.BLOCKED, GoalRecord.Phase.READY);
		store.create(blocked);
		var service = mock(GoalService.class);
		var observed = new CompletableFuture<RunExecutor.ResumeOptions>();
		when(service.resume(eq("blocked"), any())).thenAnswer(call -> {
			observed.complete(call.getArgument(1));
			return blocked;
		});
		var files = new WorkerFiles(workspace, home);
		files.send(new WorkerFiles.Request(1, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
				"stop", "", null));
		String instance = UUID.randomUUID().toString();
		var controller = controller(workspace, home);
		var done = run(new WorkerRuntime(workspace, home, instance, store, service));
		try {
			assertThat(controller.awaitStarted(instance, WAIT).owned()).isTrue();
			var options = new RunExecutor.ResumeOptions(List.of(new RunExecutor.ToolResolutionInput(
					"attempt", "call", RunEvent.Resolution.SKIP, "operator", "evidence")),
					new DriftGate.Acknowledgement("a".repeat(64), "b".repeat(64), "operator", "evidence"));
			controller.resume(instance, "blocked", options);
			assertThat(observed.get(10, TimeUnit.SECONDS)).isEqualTo(options);
			controller.stop(instance);
			done.get(10, TimeUnit.SECONDS);
			verify(service, times(1)).resume("blocked", options);
			verify(service, never()).execute(anyString());
		}
		finally { stop(controller, instance, done); }
	}

	@Test
	void startupUsesResumeForOrphanModelAndNeverRetriesBlockedOrForeignGoals() throws Exception {
		Path workspace = temporary.toRealPath();
		Path home = workspace.resolve("home");
		Path foreign = Files.createDirectory(workspace.resolve("foreign")).toRealPath();
		var store = new GoalStore(home);
		var orphan = goal("orphan", workspace, GoalRecord.State.RUNNING, GoalRecord.Phase.MODEL);
		store.create(orphan);
		store.create(goal("blocked", workspace, GoalRecord.State.BLOCKED, GoalRecord.Phase.READY));
		store.create(goal("foreign", foreign, GoalRecord.State.PENDING, GoalRecord.Phase.READY));
		var service = mock(GoalService.class);
		var resumed = new CompletableFuture<Void>();
		when(service.resume("orphan", RunExecutor.ResumeOptions.ordinary())).thenAnswer(call -> {
			resumed.complete(null);
			return new GoalRecord(1, orphan.id(), orphan.spec(), GoalRecord.State.BLOCKED,
					GoalRecord.Reason.CHECKPOINT_UNCERTAIN, 1, GoalRecord.Phase.MODEL, null, List.of(), false, "");
		});
		String instance = UUID.randomUUID().toString();
		var controller = controller(workspace, home);
		var done = run(new WorkerRuntime(workspace, home, instance, store, service));
		try {
			controller.awaitStarted(instance, WAIT);
			resumed.get(10, TimeUnit.SECONDS);
			controller.stop(instance);
			done.get(10, TimeUnit.SECONDS);
			verify(service, times(1)).resume("orphan", RunExecutor.ResumeOptions.ordinary());
			verify(service, never()).resume(eq("blocked"), any());
			verify(service, never()).execute(anyString());
		}
		finally { stop(controller, instance, done); }
	}

	@Test
	void awaitGoalObservesAuthoritativeStorePublication() throws Exception {
		Path workspace = temporary.toRealPath();
		Path home = workspace.resolve("home");
		var controller = controller(workspace, home);
		var store = new GoalStore(home);
		// The wait registers first internally; both publish-before and publish-after are supported.
		var expected = goal("pending", workspace, GoalRecord.State.PENDING, GoalRecord.Phase.READY);
		store.create(expected);
		assertThat(controller.awaitGoal("pending", g -> g.state() == GoalRecord.State.PENDING, WAIT))
				.isEqualTo(expected);
	}

	@Test
	void osMetadataInTheInboxIsNotARequestAndIsLeftInPlace() throws Exception {
		Path workspace = temporary.toRealPath();
		Path home = workspace.resolve("home");
		var store = new GoalStore(home);
		var files = new WorkerFiles(workspace, home);
		Path finder = Files.createFile(files.inbox.resolve(".DS_Store"));
		Path appleDouble = Files.createFile(files.inbox.resolve("._" + UUID.randomUUID() + ".json"));
		String instance = UUID.randomUUID().toString();
		var controller = controller(workspace, home);
		var done = run(new WorkerRuntime(workspace, home, instance, store, mock(GoalService.class)));
		try {
			assertThat(controller.awaitStarted(instance, WAIT).owned()).isTrue();
			controller.stop(instance);
			done.get(10, TimeUnit.SECONDS);
			assertThat(finder).exists();
			assertThat(appleDouble).exists();
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
	private static void stop(WorkerController controller, String instance, CompletableFuture<Void> done)
			throws Exception {
		if (!done.isDone() && controller.status().owned()) controller.stop(instance);
		done.get(10, TimeUnit.SECONDS);
	}
	private static GoalRecord goal(String id, Path workspace, GoalRecord.State state, GoalRecord.Phase phase) {
		var request = new GoalRequest("bounded", List.of(new GoalRequest.Gate(List.of("true"), 1000, 1024)),
				2, 10000, null, 2);
		var selection = new ModelSelection(new ModelRef("codex", "gpt-6.1-sol"), Map.of("effort", "high"));
		var spec = new GoalRecord.Spec(request, selection, "parent", workspace.toString(), RunId.random());
		return new GoalRecord(1, id, spec, state, GoalRecord.Reason.NONE,
				state == GoalRecord.State.PENDING ? 0 : 1, phase, null, List.of(), false, "");
	}
}
