package com.example.miniagent.app.machine;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.approval.ApprovalAnswer;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import org.springframework.context.event.EventListener;

/** No stdin reader: legacy tool questions and managed approvals use exact one-use UUIDs. */
public final class RpcConsole extends AgentConsole {
	private final MachineProtocol protocol;
	private final Object questionsLock = new Object();
	private final Map<UUID, Pending> questions = new LinkedHashMap<>();
	private final ThreadLocal<MachineProtocol.Correlation> control = new ThreadLocal<>();
	private volatile MachineProtocol.Correlation execution;
	private boolean inputClosed;
	private boolean cancelling;
	private boolean failed;

	private record Pending(Consumer<ApprovalAnswer> approval, CompletableFuture<String> question) {}

	public RpcConsole(MachineProtocol protocol) {
		super(new BufferedReader(new StringReader("")), new PrintStream(java.io.OutputStream.nullOutputStream()), true);
		this.protocol = protocol;
	}

	void begin(MachineProtocol.Correlation correlation) {
		synchronized (this.questionsLock) {
			this.execution = correlation;
			this.cancelling = false;
			this.failed = false;
		}
	}

	void end() { this.execution = null; }
	boolean failed() { synchronized (this.questionsLock) { return this.failed; } }
	MachineProtocol.Correlation correlation() {
		var local = this.control.get();
		return local == null ? this.execution : local;
	}

	boolean control(MachineProtocol.Correlation correlation, java.util.function.BooleanSupplier action) {
		this.control.set(correlation);
		try { return action.getAsBoolean(); }
		finally { this.control.remove(); }
	}

	@Override public boolean hasTraceView() { return true; }
	@Override public void println(String text) { output("output", text); }
	@Override public void reply(String text) { output("reply", text); }
	@Override public void info(String text) { /* Human usage/trace prose is not a protocol event. */ }
	@Override public void error(String text) {
		// Queue refusal is reported once by the request dispatcher, not as turn failure.
		if (this.control.get() != null) return;
		synchronized (this.questionsLock) { this.failed = true; }
		this.protocol.emit("error", correlation(), Map.of("code", "execution_failed"));
	}
	@Override public void appendReply(String delta) {
		if (delta != null && !delta.isEmpty())
			this.protocol.emit("delta", correlation(), Map.of("text", delta));
	}
	@Override public void endReply() {}

	private void output(String kind, String text) {
		this.protocol.emit("event", correlation(), Map.of("event", kind, "text", text));
	}

	@Override public String readLine(String prompt) {
		UUID id = UUID.randomUUID();
		var future = new CompletableFuture<String>();
		synchronized (this.questionsLock) {
			if (this.inputClosed || this.cancelling) return null;
			if (this.questions.size() >= 64) throw new MachineProtocol.Invalid("question_limit");
			this.questions.put(id, new Pending(null, future));
			this.protocol.emit("event", correlation(), Map.of("event", "question",
					"questionId", id.toString(), "prompt", prompt));
		}
		try { return future.get(); }
		catch (InterruptedException ex) { Thread.currentThread().interrupt(); return null; }
		catch (ExecutionException ex) { return null; }
		finally { synchronized (this.questionsLock) { this.questions.remove(id); } }
	}

	@Override public void requestApproval(ApprovalRequest request, Consumer<ApprovalAnswer> response) {
		ApprovalDecision immediate = null;
		synchronized (this.questionsLock) {
			if (this.cancelling) immediate = ApprovalDecision.CANCELLED;
			else if (this.inputClosed) immediate = ApprovalDecision.DEFERRED;
			else {
				if (this.questions.size() >= 64) throw new MachineProtocol.Invalid("question_limit");
				this.questions.put(request.requestId(), new Pending(response, null));
				Map<String, Object> fields = new LinkedHashMap<>();
				fields.put("event", "approval");
				fields.put("approvalId", request.requestId().toString());
				fields.put("runId", request.runId().value());
				fields.put("attemptId", request.attemptId());
				fields.put("planId", request.planId());
				fields.put("childId", request.childId());
				fields.put("toolCallKey", request.toolCallKey());
				fields.put("argsDigest", request.argsDigest());
				fields.put("diffDigest", request.diffDigest());
				this.protocol.emit("event", correlation(), fields);
			}
		}
		// Broker may already own its monitor. Never acquire it while holding questionsLock.
		if (immediate != null) response.accept(new ApprovalAnswer(request.requestId(), immediate, false));
	}

	@Override public void dismissApproval(UUID requestId) {
		synchronized (this.questionsLock) { this.questions.remove(requestId); }
	}

	boolean answerApproval(UUID id, ApprovalDecision decision, boolean remember) {
		Pending pending;
		synchronized (this.questionsLock) {
			pending = this.questions.get(id);
			if (pending == null || pending.approval() == null) return false;
			this.questions.remove(id);
		}
		pending.approval().accept(new ApprovalAnswer(id, decision, remember));
		return true;
	}

	boolean answerQuestion(UUID id, String text) {
		Pending pending;
		synchronized (this.questionsLock) {
			pending = this.questions.get(id);
			if (pending == null || pending.question() == null) return false;
			this.questions.remove(id);
		}
		return pending.question().complete(text);
	}

	/** EOF defers managed decisions; cancellation uses the original CANCELLED callback. */
	void clearQuestions(boolean cancel) {
		ArrayList<Map.Entry<UUID, Pending>> pending;
		synchronized (this.questionsLock) {
			if (cancel) this.cancelling = true;
			else this.inputClosed = true;
			pending = new ArrayList<>(this.questions.entrySet());
			this.questions.clear();
		}
		for (var entry : pending) {
			try {
				if (entry.getValue().approval() != null)
					entry.getValue().approval().accept(new ApprovalAnswer(entry.getKey(),
							cancel ? ApprovalDecision.CANCELLED : ApprovalDecision.DEFERRED, false));
				else entry.getValue().question().complete(null);
			}
			catch (RuntimeException ex) {
				// The broker owns durable failure handling. Still release every other waiter.
				error("");
			}
		}
	}

	@EventListener public void onTrace(TraceEvent event) {
		var current = this.execution;
		if (current == null) return;
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("event", event.getClass().getSimpleName());
		if (event instanceof TraceEvent.ModelBound bound) {
			if (current.turnId() == null || bound.sessionId().equals(current.sessionId())) {
				current = new MachineProtocol.Correlation(current.requestId(), current.executionId(),
						bound.sessionId(), bound.turnId());
				this.execution = current;
			}
			fields.put("eventSessionId", bound.sessionId());
			fields.put("eventTurnId", bound.turnId());
			fields.put("provider", bound.ref().provider());
			fields.put("model", bound.ref().model());
			fields.put("runId", bound.runId());
			fields.put("attemptId", bound.attemptId());
		}
		else if (event instanceof TraceEvent.TurnFinished finished) {
			fields.put("answered", finished.answered());
			fields.put("inputTokens", finished.tokens().input());
			fields.put("outputTokens", finished.tokens().output());
		}
		else if (event instanceof TraceEvent.ToolExecuted tool) fields.put("tool", tool.name());
		else if (event instanceof TraceEvent.ToolFailed tool) {
			fields.put("tool", tool.name());
			fields.put("cancelled", tool.cancelled());
		}
		else if (event instanceof TraceEvent.RunStateChanged run) {
			fields.put("runId", run.runId());
			fields.put("state", run.state().name());
		}
		this.protocol.emit("event", current, fields);
	}
}
