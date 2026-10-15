package com.example.miniagent.app.worker;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.goal.GoalRecord;
import com.example.miniagent.core.goal.GoalStore;

/** Lazy controller; construction and command registration do not create files or processes. */
public final class WorkerController {
	private final Path workspace;
	private final Path home;
	private final java.util.function.Supplier<WorkerLaunch> launch;
	public record Started(String instance, long pid) {}
	public record Status(boolean owned, String instance, long pid, String state, String activeGoal,
			String lastGoal, String lastState, String error) {}

	public WorkerController(Path workspace, Path home, java.util.function.Supplier<WorkerLaunch> launch) {
		this.workspace = workspace; this.home = home; this.launch = launch;
	}
	WorkerFiles files() throws IOException { return new WorkerFiles(workspace, home); }
	public WorkerLaunch launchConfiguration() { return launch.get(); }
	public Started start() throws IOException {
		var files = files();
		var current = status(files);
		if (current.owned()) return new Started(current.instance(), current.pid());
		String instance = UUID.randomUUID().toString();
		Process process = launchConfiguration().start(files, instance);
		return new Started(instance, process.pid());
	}
	public Status status() throws IOException { return status(files()); }
	private Status status(WorkerFiles files) throws IOException {
		boolean held = files.held();
		if (!Files.exists(files.metadata, LinkOption.NOFOLLOW_LINKS))
			return new Status(held, "", 0, held ? "STARTING" : "STOPPED", "", "", "", "");
		var value = files.read();
		return new Status(held, value.instance(), value.pid(), held ? value.state() : "STOPPED",
				value.activeGoal(), value.lastGoal(), value.lastState(), value.error());
	}
	public String stop(String instance) throws IOException { return send(instance, "stop", "", null); }
	public String wakeup(String instance) throws IOException { return send(instance, "wakeup", "", null); }
	public String resume(String instance, String goalId, RunExecutor.ResumeOptions options) throws IOException {
		return send(instance, "resume", goalId, Objects.requireNonNull(options));
	}
	private String send(String instance, String operation, String goal, RunExecutor.ResumeOptions options)
			throws IOException {
		var files = files();
		var current = status(files);
		if (!current.owned() || !current.instance().equals(instance) || current.state().equals("STOPPED"))
			throw new IOException("Worker instance is no longer active");
		String request = UUID.randomUUID().toString();
		files.send(new WorkerFiles.Request(1, instance, request, operation, goal, options));
		return request;
	}
	public Status awaitStarted(String instance, Duration timeout)
			throws IOException, InterruptedException, TimeoutException {
		UUID.fromString(instance);
		var files = files();
		return await(files.directory, timeout, () -> status(files),
				s -> s.owned() && s.instance().equals(instance)
						&& Set.of("RUNNING", "DRAINING").contains(s.state()));
	}
	/** The instance-bound exit event is published after draining AND releasing the ownership lock. */
	public Status awaitStopped(String instance, Duration timeout)
			throws IOException, InterruptedException, TimeoutException {
		UUID.fromString(instance);
		var files = files();
		return await(files.directory, timeout, () -> {
			var value = status(files);
			boolean exited = files.exitInstance().equals(instance);
			return new ExitStatus(value, exited);
		}, s -> !s.status().instance().equals(instance) || !s.status().owned() || s.exited()).status();
	}
	private record ExitStatus(Status status, boolean exited) {}
	public GoalRecord awaitGoal(String goalId, Predicate<GoalRecord> predicate, Duration timeout)
			throws IOException, InterruptedException, TimeoutException {
		WorkerFiles.goalId(goalId);
		var files = files();
		var goals = new GoalStore(files.home);
		goals.list(); // Initialize authoritative goal directory, not an alternate worker queue.
		Path directory = files.home.resolve("goals");
		long deadline = deadline(timeout);
		try (var watch = directory.getFileSystem().newWatchService()) {
			register(directory, watch);
			boolean registered = false;
			for (;;) {
				Path goal = directory.resolve(goalId);
				if (!registered && Files.exists(goal, LinkOption.NOFOLLOW_LINKS)) {
					WorkerFiles.ancestors(goal);
					register(goal, watch);
					registered = true;
				}
				if (registered && Files.exists(goal.resolve("state.json"), LinkOption.NOFOLLOW_LINKS)) {
					var record = goals.read(goalId);
					if (!record.spec().workspace().equals(files.workspace.toString()))
						throw new IOException("Foreign workspace goal");
					if (predicate.test(record)) return record;
				}
				next(watch, deadline);
			}
		}
	}
	interface Read<T> { T get() throws IOException; }
	static <T> T await(Path directory, Duration timeout, Read<T> read, Predicate<T> done)
			throws IOException, InterruptedException, TimeoutException {
		long deadline = deadline(timeout);
		try (var watch = directory.getFileSystem().newWatchService()) {
			register(directory, watch); // Subscription precedes first observation.
			for (;;) {
				T value = read.get();
				if (done.test(value)) return value;
				next(watch, deadline);
			}
		}
	}
	static long deadline(Duration timeout) {
		if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(24)) > 0)
			throw new IllegalArgumentException("Worker wait must be bounded within 24 hours");
		return System.nanoTime() + timeout.toNanos();
	}
	static void register(Path directory, WatchService watch) throws IOException {
		directory.register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
				StandardWatchEventKinds.ENTRY_DELETE);
	}
	static void next(WatchService watch, long deadline)
			throws IOException, InterruptedException, TimeoutException {
		long remaining = deadline - System.nanoTime();
		if (remaining <= 0) throw new TimeoutException("Worker event deadline exceeded");
		WatchKey key = watch.poll(remaining, TimeUnit.NANOSECONDS);
		if (key == null) throw new TimeoutException("Worker event deadline exceeded");
		key.pollEvents();
		if (!key.reset()) throw new IOException("Worker event subscription lost");
	}
}
