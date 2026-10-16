package com.example.miniagent.app.worker;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.example.miniagent.app.MiniAgentApplication;
import com.example.miniagent.core.goal.GoalRecord;
import com.example.miniagent.core.goal.GoalRequest;
import com.example.miniagent.core.goal.GoalStore;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * Real worker processes started from main, bypassing WorkerLaunch (which only accepts Codex).
 * The bash plugin is off, so a goal gate is refused (UNSUPPORTED_SANDBOX) instead of launching a process.
 */
class WorkerProcessTest {
	@TempDir Path temporary;

	private static final Duration WAIT = Duration.ofSeconds(60);
	private final List<Worker> launched = new ArrayList<>();

	@AfterEach
	void killEveryWorker() throws Exception {
		for (Worker worker : this.launched) worker.kill();
	}

	@Test
	void secondProcessIsRefusedAndRestartAfterSigkillRecoversGoals() throws Exception {
		Path root = this.temporary.toRealPath();
		Path work = Files.createDirectory(root.resolve("work"));
		Path state = root.resolve("state"); // A sibling of the workspace: FileChangeStore refuses a home inside it.
		Path user = Files.createDirectory(root.resolve("user"));
		var controller = new WorkerController(work, state, () -> { throw new AssertionError("Launched from main"); });

		String first = UUID.randomUUID().toString();
		var owner = launch(work, state, user, first);
		var started = controller.awaitStarted(first, WAIT);
		assertThat(started.pid()).isEqualTo(owner.process.pid());

		// The claim is an OS file lock, so it also excludes a second JVM.
		String second = UUID.randomUUID().toString();
		var refused = launch(work, state, user, second);
		assertThat(refused.awaitExit()).as(refused.output::toString).isEqualTo(1);
		assertThat(refused.output.toString()).contains("Worker workspace already owned");
		assertThat(controller.status()).isEqualTo(started);
		assertThat(controller.files().exitInstance()).isEmpty();

		// SIGKILL skips the drain: metadata still says RUNNING, but the released lock decides the status.
		owner.kill();
		var stale = controller.files().read();
		assertThat(stale.instance()).isEqualTo(first);
		assertThat(stale.state()).isEqualTo("RUNNING");
		var crashed = controller.status();
		assertThat(crashed.owned()).isFalse();
		assertThat(crashed.state()).isEqualTo("STOPPED");
		assertThat(crashed.instance()).isEqualTo(first);
		assertThat(controller.files().exitInstance()).as("a killed worker publishes no exit event").isEmpty();
		assertThatThrownBy(() -> controller.stop(first)).isInstanceOf(IOException.class)
				.hasMessage("Worker instance is no longer active");

		// Work left for the restart: an orphan that was RUNNING and a goal queued while no worker ran.
		var goals = new GoalStore(state);
		var orphan = goal("orphan", work, GoalRecord.State.RUNNING);
		var queued = goal("queued", work, GoalRecord.State.PENDING);
		goals.create(orphan);
		goals.create(queued);

		String third = UUID.randomUUID().toString();
		var restarted = launch(work, state, user, third);
		controller.awaitStarted(third, WAIT);
		// Recovery resumes the orphan with ordinary options; its run never started, so it blocks as uncertain.
		var recovered = controller.awaitGoal("orphan", g -> g.state() != GoalRecord.State.RUNNING, WAIT);
		assertThat(recovered.state()).isEqualTo(GoalRecord.State.BLOCKED);
		assertThat(recovered.reason()).isEqualTo(GoalRecord.Reason.CHECKPOINT_UNCERTAIN);
		assertThat(recovered.spec()).isEqualTo(orphan.spec());
		// The queued goal really runs the fake model and stops at the refused gate.
		var executed = controller.awaitGoal("queued",
				g -> g.state() != GoalRecord.State.PENDING && g.state() != GoalRecord.State.RUNNING, WAIT);
		assertThat(executed.state()).isEqualTo(GoalRecord.State.BLOCKED);
		assertThat(executed.reason()).isEqualTo(GoalRecord.Reason.EVIDENCE_STALE);
		assertThat(executed.feedback()).isEqualTo("Gate refused: UNSUPPORTED_SANDBOX");
		assertThat(executed.steps()).isEqualTo(1);
		var settled = WorkerController.await(controller.files().directory, WAIT, controller::status,
				s -> s.lastGoal().equals("queued") && s.activeGoal().isEmpty());
		assertThat(settled.owned()).isTrue();
		assertThat(settled.lastState()).isEqualTo("BLOCKED");
		assertThat(settled.error()).isEmpty();

		// A requested stop drains, publishes STOPPED, releases the lock and then the exit event.
		controller.stop(third);
		var stopped = controller.awaitStopped(third, WAIT);
		assertThat(restarted.awaitExit()).as(restarted.output::toString).isZero();
		assertThat(stopped.owned()).isFalse();
		assertThat(controller.files().read().state()).isEqualTo("STOPPED");
		assertThat(controller.files().exitInstance()).isEqualTo(third);
		assertThat(controller.status().state()).isEqualTo("STOPPED");
	}

	private Worker launch(Path work, Path state, Path user, String instance) throws IOException {
		// The child runs in another cwd, so relative classpath entries are resolved first.
		String classpath = Arrays.stream(System.getProperty("surefire.test.class.path",
						System.getProperty("java.class.path")).split(File.pathSeparator))
				.map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
				.collect(Collectors.joining(File.pathSeparator));
		var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Duser.home=" + user, "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", classpath,
				MiniAgentApplication.class.getName(), "--agent.worker.mode=true", "--agent.worker.instance=" + instance,
				"--agent.cwd=" + work, "--agent.home=" + state, "--agent.plugins.bash.enabled=false",
				"--agent.plugins.trace.level=off", "--agent.plugins.memory.compaction=false",
				"--agent.codex.codex-home=" + state.resolve("codex"),
				"--agent.claude.auth-store=" + state.resolve("auth").resolve("claude.json"));
		builder.directory(work.toFile()).redirectErrorStream(true);
		builder.environment().clear();
		builder.environment().put("PATH", "/usr/bin:/bin");
		builder.environment().put("HOME", user.toString());
		var worker = new Worker(builder.start());
		this.launched.add(worker);
		return worker;
	}

	private static GoalRecord goal(String id, Path workspace, GoalRecord.State state) {
		var request = new GoalRequest("Acknowledge; the host verifies completion.",
				List.of(new GoalRequest.Gate(List.of("true"), 1000, 1024)), 1, 60_000, null, 2);
		var selection = new ModelSelection(new ModelRef("fake", "fake-scripted"), Map.of());
		var spec = new GoalRecord.Spec(request, selection, "parent", workspace.toString(), RunId.random());
		return new GoalRecord(1, id, spec, state, GoalRecord.Reason.NONE,
				state == GoalRecord.State.PENDING ? 0 : 1, GoalRecord.Phase.READY, null, List.of(), false, "");
	}

	private static final class Worker {
		final Process process;
		final StringBuffer output = new StringBuffer();
		final CompletableFuture<Void> drained = new CompletableFuture<>();

		Worker(Process process) {
			this.process = process;
			Thread.ofVirtual().start(() -> {
				try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					for (String line; (line = reader.readLine()) != null;) this.output.append(line).append('\n');
					this.drained.complete(null);
				}
				catch (IOException ex) { this.drained.completeExceptionally(ex); }
			});
		}
		int awaitExit() throws Exception {
			assertThat(this.process.waitFor(WAIT.toSeconds(), TimeUnit.SECONDS)).as(this.output::toString).isTrue();
			this.drained.get(WAIT.toSeconds(), TimeUnit.SECONDS);
			return this.process.exitValue();
		}
		/** SIGKILL the descendants, then the worker, and wait until all of them are gone. */
		void kill() throws Exception {
			List<ProcessHandle> descendants = this.process.descendants().toList();
			List<CompletableFuture<ProcessHandle>> exits = descendants.stream().map(ProcessHandle::onExit).toList();
			descendants.forEach(ProcessHandle::destroyForcibly);
			this.process.destroyForcibly();
			this.process.onExit().get(WAIT.toSeconds(), TimeUnit.SECONDS);
			CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new)).get(WAIT.toSeconds(), TimeUnit.SECONDS);
			this.drained.get(WAIT.toSeconds(), TimeUnit.SECONDS);
			assertThat(this.process.isAlive()).isFalse();
		}
	}
}
