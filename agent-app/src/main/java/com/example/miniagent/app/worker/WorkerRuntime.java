package com.example.miniagent.app.worker;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.goal.GoalRecord;
import com.example.miniagent.core.goal.GoalService;
import com.example.miniagent.core.goal.GoalStore;

/** One OS worker, one bounded goal at a time, using GoalService's existing managed tool loop. */
public final class WorkerRuntime {
	private final WorkerFiles files;
	private final GoalStore store;
	private final GoalService goals;
	private final String instance;
	private final ArrayBlockingQueue<Boolean> wake = new ArrayBlockingQueue<>(1);
	private final ArrayDeque<WorkerFiles.Request> resumes = new ArrayDeque<>();
	private final AtomicReference<IOException> watcherFailure = new AtomicReference<>();
	private final Object admission = new Object();
	private volatile boolean stopping;
	private String active = "";
	private String lastGoal = "";
	private String lastState = "";
	private String error = "";
	private final Set<String> busy = new HashSet<>();

	public WorkerRuntime(Path workspace, Path home, String instance, GoalStore store, GoalService goals)
			throws IOException {
		this.files = new WorkerFiles(workspace, home);
		UUID.fromString(instance);
		this.instance = instance; this.store = store; this.goals = goals;
	}

	/** Blocks the application runner until stop has drained the current goal. Never reads stdin. */
	public void run() throws IOException, InterruptedException {
		var claim = files.acquire();
		if (claim == null) throw new IOException("Worker workspace already owned");
		try (claim; var watch = files.directory.getFileSystem().newWatchService()) {
			store.list(); // Initializes GoalStore; the execution scan follows all registrations.
			Path goalDirectory = files.home.resolve("goals");
			WorkerController.register(files.inbox, watch);
			WorkerController.register(goalDirectory, watch);
			var registered = new HashSet<Path>();
			registerGoals(watch, registered);
			// Snapshot recovery candidates only once. BLOCKED goals always require explicit resume.
			var recovery = new HashSet<String>();
			for (String id : store.list()) {
				var record = readReady(id);
				if (record != null && own(record) && record.state() == GoalRecord.State.RUNNING) recovery.add(id);
			}
			consumeRequests();
			publish();
			Thread watcher = Thread.ofVirtual().name("goal-worker-events").start(() -> {
				try {
					for (;;) {
						WatchKey key = watch.take();
						key.pollEvents();
						if (!key.reset()) throw new IOException("Worker subscription lost");
						registerGoals(watch, registered); // Register new goals before scanning their state.
						consumeRequests(); // Stop admission is independent of a currently running goal.
						wake.offer(Boolean.TRUE);
					}
				}
				catch (ClosedWatchServiceException ex) { /* Runner drained and closed the watch. */ }
				catch (IOException | RuntimeException | InterruptedException ex) {
					watcherFailure.set(new IOException("Worker event source failed"));
					stopping = true;
					wake.offer(Boolean.TRUE);
				}
			});
			var attempted = new HashSet<String>();
			try {
				for (;;) {
					if (watcherFailure.get() != null) throw watcherFailure.get();
					if (stopping) break;
					WorkerFiles.Request explicit;
					synchronized (admission) { explicit = resumes.pollFirst(); }
					if (explicit != null) {
						perform(explicit.goal(), explicit.options(), attempted);
						continue;
					}
					boolean dispatched = false;
					for (String id : store.list()) {
						if (stopping) break;
						var record = readReady(id);
						if (record == null || !own(record)) continue;
						// inspect only reconciles an acknowledged completed candidate; never calls a model.
						if (record.candidate() && record.state() == GoalRecord.State.RUNNING) record = goals.inspect(id);
						boolean orphan = recovery.contains(id);
						if (!attempted.contains(id) && (record.state() == GoalRecord.State.PENDING
								|| orphan && record.state() == GoalRecord.State.RUNNING)) {
							perform(id, orphan ? RunExecutor.ResumeOptions.ordinary() : null, attempted);
							dispatched = true;
							break;
						}
					}
					if (!dispatched && !stopping) {
						wake.take();
						attempted.removeAll(busy);
						busy.clear();
					}
				}
			}
			finally {
				stopping = true;
				watch.close();
				watcher.join();
				active = "";
				files.write(metadata("STOPPED"));
			}
		}
		finally {
			// An instance-bound exit event follows lock release. It cannot overwrite a successor's state.
			files.exited(instance);
		}
	}

	private void registerGoals(WatchService watch, Set<Path> registered) throws IOException {
		for (String id : store.list()) {
			Path path = files.home.resolve("goals").resolve(id);
			if (registered.add(path)) WorkerController.register(path, watch);
		}
	}
	private GoalRecord readReady(String id) throws IOException {
		if (!Files.exists(files.home.resolve("goals").resolve(id).resolve("state.json"), LinkOption.NOFOLLOW_LINKS))
			return null; // Publication is still in progress; registered directory supplies the next event.
		return store.read(id);
	}
	private boolean own(GoalRecord record) {
		// Goal creation already records canonical paths. Do not resolve a foreign goal into this workspace.
		return record.spec().workspace().equals(files.workspace.toString());
	}
	private void perform(String id, RunExecutor.ResumeOptions options, Set<String> attempted) throws IOException {
		GoalRecord record;
		try { record = store.read(id); }
		catch (IOException | RuntimeException ex) { error = "GOAL_REFUSED"; publish(); return; }
		if (!own(record)) { error = "GOAL_REFUSED"; publish(); return; }
		synchronized (admission) {
			if (stopping) return;
			active = id;
			attempted.add(id);
		}
		publish();
		try {
			var result = options == null ? goals.execute(id) : goals.resume(id, options);
			lastGoal = id; lastState = result.state().name(); error = "";
		}
		catch (GoalStore.Busy ex) {
			busy.add(id); // Retry only on the next external event, never in the execution scan loop.
		}
		catch (IOException | RuntimeException ex) {
			// Never serialize provider exception text, prompts, tokens, or HTTP response bodies.
			lastGoal = id; lastState = store.read(id).state().name(); error = "GOAL_REFUSED";
		}
		finally {
			// Goal cancellation can leave its calling thread interrupted after its own durable drain.
			Thread.interrupted();
			active = "";
			publish();
		}
	}
	private void consumeRequests() throws IOException {
		files.privateDirectory(files.inbox);
		try (var entries = Files.list(files.inbox)) {
			var requests = entries.limit(257).toList();
			if (requests.size() > 256) throw new IOException("Worker inbox full");
			for (Path path : requests.stream().sorted().toList()) {
				if (path.getFileName().toString().startsWith(".pending-")) {
					continue; // Unpublished bytes never grant control.
				}
				var request = files.request(path);
				synchronized (admission) {
					if (request.instance().equals(instance)) {
						switch (request.operation()) {
							case "stop" -> stopping = true;
							case "wakeup" -> { }
							case "resume" -> {
								if (!stopping) {
									if (resumes.size() >= 256) throw new IOException("Worker resume queue full");
									resumes.addLast(request);
								}
							}
							default -> throw new IOException("Invalid operation");
						}
					}
				}
				// Validated private request only; stale instance requests cannot affect this worker.
				Files.delete(path);
			}
		}
		if (stopping) publish();
	}
	private WorkerFiles.Metadata metadata(String state) {
		return new WorkerFiles.Metadata(1, instance, ProcessHandle.current().pid(), files.workspace.toString(),
				files.home.toString(), state, active, lastGoal, lastState, error);
	}
	private synchronized void publish() throws IOException { files.write(metadata(stopping ? "DRAINING" : "RUNNING")); }
}
