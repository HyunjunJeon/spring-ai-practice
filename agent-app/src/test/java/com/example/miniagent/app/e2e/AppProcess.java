package com.example.miniagent.app.e2e;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.example.miniagent.app.MiniAgentApplication;
import com.example.miniagent.e2efixture.FixtureLaunchPolicySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tier P driver: the real {@code MiniAgentApplication.main} in a child JVM from the surefire test classpath,
 * never {@code java -jar} or agent.sh (those rebuild, read .env and default to the TUI). The environment is
 * cleared, every state root lives under one {@link Layout}, both pipes are drained, every wait is bounded and
 * driven by output or exit, and {@link #killAll()} kills descendants before the parent.
 */
final class AppProcess implements AutoCloseable {
	static final Duration PROCESS_LIMIT = Duration.ofSeconds(60);
	static final Duration FRAME_LIMIT = Duration.ofSeconds(20);
	/** Goal gates and /verify without sandbox-exec: the pass-through fixture policy replaces the bash plugin's. */
	static final List<String> FIXTURE_GATES = List.of("--agent.plugins.bash.enabled=false",
			"--spring.main.sources=" + FixtureLaunchPolicySource.class.getName());
	static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Set<AppProcess> LIVE = ConcurrentHashMap.newKeySet();
	private static final int TRANSCRIPT_CHARS = 6000;

	record Result(int exit, String stdout, String stderr) {
		List<JsonNode> frames() { return this.stdout.lines().map(JSON::readTree).toList(); }
	}

	private final Process process;
	private final Writer input;
	private final List<String> lines = new ArrayList<>();
	private final StringBuffer errors = new StringBuffer();
	private final CompletableFuture<Void> drained;
	private final Set<String> answeredQuestions = new HashSet<>();
	private boolean outputClosed;
	private int replCursor;
	private int requests;

	private AppProcess(Process process) {
		this.process = process;
		this.input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
		var stdout = new CompletableFuture<Void>();
		var stderr = new CompletableFuture<Void>();
		this.drained = CompletableFuture.allOf(stdout, stderr);
		Thread.ofVirtual().start(() -> drainStdout(stdout));
		Thread.ofVirtual().start(() -> drainStderr(stderr));
	}

	static AppProcess rpc(Layout layout, List<String> extra) throws Exception {
		var app = launch(layout, "rpc", extra, null);
		app.awaitFrame(frame -> is(frame, "type", "ready"));
		return app;
	}

	/** Piped stdin: System.console() is null, so the REPL is non-interactive. */
	static AppProcess repl(Layout layout, List<String> extra) throws Exception {
		return launch(layout, "repl", extra, null);
	}

	/** One non-option argument is one input; the exit code is that input's result. */
	static Result oneShot(Layout layout, String ui, String text, List<String> extra) throws Exception {
		var app = launch(layout, ui, extra, text);
		app.closeInput();
		int exit = app.awaitExit();
		return new Result(exit, app.stdout(), app.stderr());
	}

	private static AppProcess launch(Layout layout, String ui, List<String> extra, String text) throws IOException {
		String classpath = Arrays.stream(System.getProperty("surefire.test.class.path",
						System.getProperty("java.class.path")).split(java.io.File.pathSeparator))
				.map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
				.collect(Collectors.joining(java.io.File.pathSeparator));
		List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-Duser.home=" + layout.user(), "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
				"-cp", classpath, MiniAgentApplication.class.getName(), "--agent.ui=" + ui,
				"--agent.cwd=" + layout.work(), "--agent.home=" + layout.state(),
				"--agent.plugins.trace.level=off", "--agent.plugins.memory.compaction=false",
				"--agent.codex.codex-home=" + layout.state().resolve("codex"),
				"--agent.claude.auth-store=" + layout.state().resolve("auth/claude.json")));
		command.addAll(extra);
		if (text != null) command.add(text);
		var builder = new ProcessBuilder(command).directory(layout.work().toFile());
		builder.environment().clear();
		builder.environment().put("PATH", "/usr/bin:/bin");
		builder.environment().put("HOME", layout.user().toString());
		var app = new AppProcess(builder.start());
		LIVE.add(app);
		return app;
	}

	/** For @AfterEach: every child this JVM started and did not already reap. */
	static void killAll() throws Exception {
		for (var app : List.copyOf(LIVE)) app.close();
	}

	// ---- RPC (one JSON object per line; each method returns the request id it used) ----

	String prompt(String text) { return send("prompt", "text", text); }

	String command(String text) { return send("command", "text", text); }

	JsonNode answerQuestion(String questionId, String text) {
		this.answeredQuestions.add(questionId);
		var frame = frame("question").put("questionId", questionId).put("text", text);
		return awaitReply(write(frame));
	}

	JsonNode shutdown() { return awaitReply(write(frame("shutdown"))); }

	/** A line the protocol must refuse; it is written as is. */
	void raw(String line) { writeLine(line); }

	JsonNode completion(String requestId) {
		return awaitFrame(frame -> is(frame, "requestId", requestId) && is(frame, "type", "completion"));
	}

	/** The first legacy permission question of a request that this driver has not answered yet. */
	JsonNode question(String requestId) {
		return awaitFrame(frame -> is(frame, "requestId", requestId) && is(frame, "event", "question")
				&& !this.answeredQuestions.contains(frame.path("questionId").asString()));
	}

	JsonNode awaitFrame(Predicate<JsonNode> match) {
		return JSON.readTree(lineAt(awaitIndex(0, line -> match.test(JSON.readTree(line)), FRAME_LIMIT)));
	}

	/** Frame fields are compared only when present as strings; protocol nulls never match. */
	static boolean is(JsonNode frame, String field, String value) {
		JsonNode node = frame.get(field);
		return node != null && node.isString() && node.asString().equals(value);
	}

	List<JsonNode> frames() {
		synchronized (this) { return this.lines.stream().map(JSON::readTree).toList(); }
	}

	List<JsonNode> frames(String requestId) {
		return frames().stream().filter(frame -> is(frame, "requestId", requestId)).toList();
	}

	/** The streamed reply of a request: its delta texts in order. */
	String streamed(String requestId) {
		return frames(requestId).stream().filter(frame -> is(frame, "type", "delta"))
				.map(frame -> frame.path("text").asString()).collect(Collectors.joining());
	}

	/** Lines a command printed: the text of its output events in order. */
	List<String> outputs(String requestId) {
		return frames(requestId).stream().filter(frame -> is(frame, "event", "output"))
				.map(frame -> frame.path("text").asString()).toList();
	}

	private ObjectNode frame(String method) {
		return JSON.createObjectNode().put("v", 1).put("id", "r" + ++this.requests).put("method", method);
	}

	private String send(String method, String field, String value) {
		return write(frame(method).put(field, value));
	}

	private String write(ObjectNode frame) {
		writeLine(JSON.writeValueAsString(frame));
		return frame.path("id").asString();
	}

	private JsonNode awaitReply(String requestId) {
		return awaitFrame(frame -> is(frame, "requestId", requestId)
				&& (is(frame, "type", "response") || is(frame, "type", "error")));
	}

	// ---- REPL (plain text; the "› " prompt has no newline, so matches use find) ----

	void line(String text) { writeLine(text); }

	/** The next stdout line after the previous match that contains the pattern. */
	MatchResult await(Pattern pattern) {
		int index = awaitIndex(this.replCursor, line -> pattern.matcher(line).find(), FRAME_LIMIT);
		this.replCursor = index + 1;
		Matcher matcher = pattern.matcher(lineAt(index));
		matcher.find();
		return matcher.toMatchResult();
	}

	// ---- lifecycle ----

	void closeInput() throws IOException { this.input.close(); }

	int awaitExit() throws Exception {
		this.process.onExit().get(PROCESS_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
		this.drained.get(FRAME_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
		LIVE.remove(this);
		return this.process.exitValue();
	}

	/** Subscribe to every exit, then kill descendants before the parent. */
	private void kill() throws Exception {
		List<ProcessHandle> descendants = this.process.descendants().toList();
		List<CompletableFuture<ProcessHandle>> exits = descendants.stream().map(ProcessHandle::onExit).toList();
		descendants.forEach(ProcessHandle::destroyForcibly);
		this.process.destroyForcibly();
		this.process.onExit().get(PROCESS_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
		CompletableFuture.allOf(exits.toArray(CompletableFuture[]::new)).get(PROCESS_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
		this.drained.get(FRAME_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
	}

	@Override public void close() throws Exception {
		if (this.process.isAlive() || this.process.descendants().anyMatch(ProcessHandle::isAlive)) kill();
		LIVE.remove(this);
	}

	synchronized String stdout() { return String.join("\n", this.lines); }

	private synchronized String lineAt(int index) { return this.lines.get(index); }

	String stderr() { return this.errors.toString(); }

	private void writeLine(String line) {
		try {
			this.input.write(line);
			this.input.write('\n');
			this.input.flush();
		}
		catch (IOException ex) { throw new AssertionError("Child stdin closed: " + transcript(), ex); }
	}

	private int awaitIndex(int from, Predicate<String> match, Duration limit) {
		long deadline = System.nanoTime() + limit.toNanos();
		synchronized (this) {
			for (int index = from;; ) {
				for (; index < this.lines.size(); index++)
					if (match.test(this.lines.get(index))) return index;
				long left = deadline - System.nanoTime();
				if (this.outputClosed) throw new AssertionError("Output ended without a match:\n" + transcript());
				if (left <= 0) throw new AssertionError("No match within " + limit + ":\n" + transcript());
				try { TimeUnit.NANOSECONDS.timedWait(this, left); }
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new AssertionError("Interrupted while waiting for output", ex);
				}
			}
		}
	}

	private String transcript() {
		String out = stdout();
		String err = stderr();
		return "stdout: " + out.substring(Math.max(0, out.length() - TRANSCRIPT_CHARS))
				+ "\nstderr: " + err.substring(Math.max(0, err.length() - TRANSCRIPT_CHARS));
	}

	private void drainStdout(CompletableFuture<Void> done) {
		try (var reader = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line; (line = reader.readLine()) != null;) {
				synchronized (this) { this.lines.add(line); notifyAll(); }
			}
		}
		catch (IOException ex) {
			// destroyForcibly closes the pipes under the reader; what was read is the transcript.
		}
		finally {
			done.complete(null);
			synchronized (this) { this.outputClosed = true; notifyAll(); }
		}
	}

	private void drainStderr(CompletableFuture<Void> done) {
		try (var reader = new BufferedReader(new InputStreamReader(this.process.getErrorStream(), StandardCharsets.UTF_8))) {
			for (String line; (line = reader.readLine()) != null;) this.errors.append(line).append('\n');
		}
		catch (IOException ex) {
			// destroyForcibly closes the pipes under the reader; what was read is the transcript.
		}
		finally { done.complete(null); }
	}
}
