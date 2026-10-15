package com.example.miniagent.app.machine;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.InputQueue;
import com.example.miniagent.core.approval.ApprovalDecision;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

/**
 * Java-owned single execution lane; the stdin thread only dispatches bounded controls.
 * Methods: prompt(text), command(text), steer/follow_up(text,targetId),
 * cancel(targetId), approval(approvalId,decision,remember?), question(questionId,text),
 * shutdown(). targetId is the exact active prompt/command request ID, not "latest".
 *
 * Queue acknowledgements carry their own requestId and the parent's executionId.
 * Subsequent turns retain that parent requestId/executionId and publish their actual
 * sessionId/turnId via ModelBound events; queue acknowledgements are not executions.
 * Queue requests capture inputQueue().target() once per execution, then use
 * enqueue(expected, "/steer "+text) or enqueue(expected, "/follow-up "+text).
 * The exact generation check also protects the interval after handle returns but
 * before this runner's cleanup finishes. cancelPending() precedes interruption.
 * An accepted response reserves the lane, not queue readiness: wait for ModelBound
 * before sending queue controls; earlier controls return queue_idle. All command
 * requests are refused while busy. /run approve/reject/defer/cancel are excluded
 * from command transport; use exact approval/cancel methods instead.
 *
 * A cancellation never releases the lane. Only the worker's finally block emits
 * completion and releases it, after handle and all cleanup return. EOF drains work
 * (including follow-ups), defers approvals and releases legacy questions. Shutdown
 * cancels queued work and pending questions, interrupts work, then joins its exit.
 */
public final class MachineRunner implements ApplicationRunner, ExitCodeGenerator, AutoCloseable {
	private static final Set<String> COMMANDS = Set.of("/model", "/models", "/sessions", "/new",
			"/resume", "/tree", "/fork", "/history", "/forget", "/runs", "/run", "/changes", "/context", "/goal", "/lesson", "/worker", "/message");
	private final AgentInputHandler handler;
	private final AgentSession session;
	private final RpcConsole console;
	private final MachineProtocol protocol;
	private final InputStream input;
	private final String mode;
	private Execution active;
	private boolean stopping;
	private volatile boolean started;
	private volatile int exitCode;

	private static final class Execution {
		final String requestId;
		Thread worker;
		boolean cancelled;
		InputQueue.Target queueTarget;
		Execution(String requestId) { this.requestId = requestId; }
	}

	public MachineRunner(AgentInputHandler handler, AgentSession session, RpcConsole console,
			MachineProtocol protocol, InputStream input, String mode) {
		this.handler = handler;
		this.session = session;
		this.console = console;
		this.protocol = protocol;
		this.input = input;
		this.mode = mode;
	}

	@Override public void run(ApplicationArguments args) {
		this.started = true;
		this.protocol.emit("ready", null, Map.of("mode", this.mode, "maxLineBytes",
				MachineProtocol.MAX_LINE_BYTES, "maxTextBytes", MachineProtocol.MAX_TEXT_BYTES,
				"maxRequests", MachineProtocol.MAX_REQUESTS));
		try {
			if (this.mode.equals("json")) {
				this.console.setInteractive(false);
				this.console.clearQuestions(false);
				String text = String.join(" ", args.getNonOptionArgs());
				if (text.isBlank() || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
						> MachineProtocol.MAX_TEXT_BYTES) throw new MachineProtocol.Invalid("invalid_field");
				start("one-shot", text, text.stripLeading().startsWith("/"));
			}
			else {
				while (!isStopping()) {
					try {
						String line = this.protocol.readLine(this.input);
						if (line == null) break;
						accept(line);
					}
					catch (MachineProtocol.Invalid ex) { error(null, ex.getMessage()); }
				}
			}
		}
		catch (IOException ex) { error(null, "input_failed"); this.exitCode = 1; cancelActive(); }
		catch (MachineProtocol.Invalid ex) { error(null, ex.getMessage()); this.exitCode = 1; }
		finally {
			this.console.clearQuestions(false);
			awaitExit();
			this.console.clearQuestions(false);
			closeInput();
		}
	}

	/** One transport dispatch at a time; the worker never needs this dispatch monitor. */
	public synchronized void accept(String line) {
		MachineProtocol.Request request = null;
		try {
			request = this.protocol.parse(line);
			if (isStopping()) throw new MachineProtocol.Invalid("shutting_down");
			switch (request.method()) {
				case "prompt", "command" -> {
					request.only("text");
					start(request.id(), request.text("text"), request.method().equals("command"));
				}
				case "steer", "follow_up" -> {
					request.only("text", "targetId");
					Execution execution = target(request.text("targetId"));
					var correlation = controlCorrelation(request.id());
					String queueText = (request.method().equals("steer") ? "/steer " : "/follow-up ")
							+ request.text("text");
					InputQueue.Target expected;
					synchronized (this.stateLock) {
						if (this.active != execution) throw new MachineProtocol.Invalid("not_active");
						if (execution.queueTarget == null)
							execution.queueTarget = this.handler.inputQueue().target()
									.orElseThrow(() -> new MachineProtocol.Invalid("queue_idle"));
						expected = execution.queueTarget;
					}
					boolean accepted = this.console.control(correlation,
							() -> this.handler.enqueue(expected, queueText));
					if (!accepted) throw new MachineProtocol.Invalid("queue_refused");
					response(correlation, "queued");
				}
				case "cancel" -> {
					request.only("targetId");
					target(request.text("targetId"));
					var correlation = controlCorrelation(request.id());
					cancelActive();
					response(correlation, "cancelling");
				}
				case "approval" -> {
					request.only("approvalId", "decision", "remember");
					var correlation = controlCorrelation(request.id());
					ApprovalDecision decision;
					try { decision = ApprovalDecision.valueOf(request.text("decision")); }
					catch (IllegalArgumentException ex) { throw new MachineProtocol.Invalid("invalid_decision"); }
					var remember = request.fields().get("remember");
					if (remember != null && !remember.isBoolean()) throw new MachineProtocol.Invalid("invalid_field");
					if (remember != null && remember.asBoolean() && decision != ApprovalDecision.APPROVED)
						throw new MachineProtocol.Invalid("invalid_decision");
					if (!this.console.answerApproval(uuid(request.text("approvalId")), decision,
							remember != null && remember.asBoolean()))
						throw new MachineProtocol.Invalid("stale_approval");
					response(correlation, "answered");
				}
				case "question" -> {
					request.only("questionId", "text");
					var correlation = controlCorrelation(request.id());
					if (!this.console.answerQuestion(uuid(request.text("questionId")), request.text("text")))
						throw new MachineProtocol.Invalid("stale_question");
					response(correlation, "answered");
				}
				case "shutdown" -> {
					request.only();
					var correlation = controlCorrelation(request.id());
					synchronized (this.stateLock) { this.stopping = true; }
					cancelActive();
					response(correlation, "shutting_down");
				}
				default -> throw new MachineProtocol.Invalid("unknown_method");
			}
		}
		catch (MachineProtocol.Invalid ex) { error(request == null ? null : request.id(), ex.getMessage()); }
		catch (RuntimeException ex) { error(request == null ? null : request.id(), "execution_failed"); }
	}

	// Separate from accept's monitor: cleanup must progress while a control callback runs.
	private final Object stateLock = new Object();

	private void start(String requestId, String text, boolean command) {
		text = text.strip();
		if (command) validateCommand(text);
		else if (text.startsWith("/")) throw new MachineProtocol.Invalid("use_command_method");
		synchronized (this.stateLock) {
			if (this.active != null) throw new MachineProtocol.Invalid("busy");
			if (this.stopping) throw new MachineProtocol.Invalid("shutting_down");
			var execution = new Execution(requestId);
			var correlation = new MachineProtocol.Correlation(requestId, UUID.randomUUID().toString(),
					this.session.id(), null);
			this.console.begin(correlation);
			String inputText = text;
			execution.worker = Thread.ofVirtual().name("machine-execution").unstarted(() -> execute(execution, inputText));
			this.active = execution;
			response(correlation, "accepted");
			execution.worker.start();
		}
	}

	private void execute(Execution execution, String text) {
		boolean success = false;
		try { success = this.handler.handle(text); }
		catch (RuntimeException ex) { error(execution.requestId, "execution_failed"); }
		finally {
			try { this.console.clearQuestions(true); }
			finally {
				synchronized (this.stateLock) {
					String status = execution.cancelled ? "cancelled"
							: success && !this.console.failed() ? "completed" : "failed";
					if (!status.equals("completed")) this.exitCode = 1;
					this.protocol.emit("completion", this.console.correlation(),
							Map.of("status", status, "sessionAfter", this.session.id()));
					this.console.end();
					this.active = null;
				}
			}
		}
	}

	private static void validateCommand(String text) {
		String[] parts = text.split("\\s+", 3);
		if (!COMMANDS.contains(parts[0])) throw new MachineProtocol.Invalid("command_not_allowed");
		// Approval transport must use exact stored callbacks, never reconstructed run authority.
		if (parts[0].equals("/run") && parts.length > 1
				&& Set.of("approve", "reject", "defer", "cancel").contains(parts[1]))
			throw new MachineProtocol.Invalid("use_exact_control_method");
	}

	private Execution target(String requestId) {
		if (!requestId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))
			throw new MachineProtocol.Invalid("invalid_id");
		synchronized (this.stateLock) {
			if (this.active == null || !this.active.requestId.equals(requestId))
				throw new MachineProtocol.Invalid("not_active");
			if (this.active.cancelled) throw new MachineProtocol.Invalid("cancelling");
			return this.active;
		}
	}

	private void cancelActive() {
		synchronized (this.stateLock) {
			if (this.active != null) {
				this.active.cancelled = true;
				this.handler.cancelPending();
				this.active.worker.interrupt();
			}
		}
		this.console.clearQuestions(true);
	}

	private MachineProtocol.Correlation controlCorrelation(String id) {
		var parent = this.console.correlation();
		return new MachineProtocol.Correlation(id, parent == null ? null : parent.executionId(),
				parent == null ? this.session.id() : parent.sessionId(), parent == null ? null : parent.turnId());
	}

	private void response(MachineProtocol.Correlation correlation, String status) {
		this.protocol.emit("response", correlation, Map.of("status", status));
	}
	private void error(String requestId, String code) {
		this.protocol.emit("error", controlCorrelation(requestId), Map.of("code", code));
	}
	private static UUID uuid(String value) {
		try {
			UUID id = UUID.fromString(value);
			if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
			return id;
		}
		catch (IllegalArgumentException ex) { throw new MachineProtocol.Invalid("invalid_uuid"); }
	}
	private boolean isStopping() { synchronized (this.stateLock) { return this.stopping; } }
	private void awaitExit() {
		Execution execution;
		synchronized (this.stateLock) { execution = this.active; }
		if (execution == null) return;
		boolean interrupted = false;
		while (true) {
			try { execution.worker.join(); break; }
			catch (InterruptedException ex) { interrupted = true; cancelActive(); }
		}
		if (interrupted) Thread.currentThread().interrupt();
	}
	@Override public int getExitCode() { return this.exitCode; }
	@Override public void close() {
		synchronized (this.stateLock) { this.stopping = true; }
		closeInput();
		cancelActive();
		awaitExit();
	}
	private void closeInput() {
		if (!this.started) return;
		try { this.input.close(); }
		catch (IOException ex) { this.exitCode = 1; }
	}
}
