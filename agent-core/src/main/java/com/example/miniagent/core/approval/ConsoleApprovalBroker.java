package com.example.miniagent.core.approval;

import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunStoreException;
import tools.jackson.databind.json.JsonMapper;

/** One queue and one question, shared by all run/child/change producers. */
public final class ConsoleApprovalBroker implements ApprovalBroker {
	private final AgentConsole console;
	private final Map<UUID, Entry> pending = new LinkedHashMap<>();
	private final Set<UUID> used = new HashSet<>();
	private final ArrayDeque<Entry> queue = new ArrayDeque<>();
	private final Set<Scope> remembered = new HashSet<>();
	private final Map<Scope, ApprovalDecision> replay = new HashMap<>();
	private final Set<RunId> cancelled = new HashSet<>();
	private Entry displayed;

	public ConsoleApprovalBroker(AgentConsole console) {
		this.console = console;
	}

	@Override
	public synchronized CompletableFuture<ApprovalDecision> requestChange(ApprovalRequest request,
			java.util.function.Consumer<ApprovalDecision> durableDecision) {
		if (request.diffDigest() == null) throw new IllegalArgumentException("Change approval requires diff digest");
		if (!this.used.add(request.requestId())) throw new IllegalArgumentException("Approval UUID reused");
		Entry entry = new Entry(request, java.util.Objects.requireNonNull(durableDecision));
		this.pending.put(request.requestId(), entry);
		this.queue.add(entry);
		pump();
		return entry.result;
	}

	@Override
	public synchronized CompletableFuture<ApprovalDecision> request(ApprovalRequest request, RunContext context) {
		if (!request.runId().equals(context.spec().runId()) || !request.attemptId().equals(context.attemptId())
				|| !java.util.Objects.equals(request.childId(), context.spec().childId())) {
			throw new IllegalArgumentException("Approval context mismatch");
		}
		if (this.cancelled.contains(request.runId()) || this.cancelled.contains(context.spec().parentRunId())) {
			return CompletableFuture.completedFuture(ApprovalDecision.CANCELLED);
		}
		if (!this.used.add(request.requestId())) throw new IllegalArgumentException("Approval UUID reused");
		Entry entry = new Entry(request, context);
		append(entry, new RunEvent.ApprovalRequested(context.now(), request.attemptId(),
				request.requestId().toString(), request.toolCallKey(), request.argsDigest(), request.diffDigest()));
		context.pauseBudget();
		entry.waiting = true;
		this.pending.put(request.requestId(), entry);
		ApprovalDecision replayed = this.replay.remove(scope(request));
		if (this.remembered.contains(scope(request)) || replayed != null) {
			settle(entry, replayed == null ? ApprovalDecision.APPROVED : replayed);
		}
		else {
			this.queue.add(entry);
			pump();
		}
		return entry.result;
	}

	@Override
	public synchronized boolean respond(ApprovalRequest request, ApprovalDecision decision) {
		Entry entry = this.pending.get(request.requestId());
		if (entry == null || entry != this.displayed || !entry.request.equals(request) || entry.deferred) return false;
		settle(entry, decision);
		pump();
		return true;
	}

	@Override
	public synchronized boolean cancel(ApprovalRequest request) {
		Entry entry = this.pending.get(request.requestId());
		if (entry == null || !entry.request.equals(request)) return false;
		if (entry.deferred) return resolveDeferred(request, ApprovalDecision.CANCELLED);
		settle(entry, ApprovalDecision.CANCELLED);
		pump();
		return true;
	}

	@Override
	public synchronized boolean resolveDeferred(ApprovalRequest request, ApprovalDecision decision) {
		Entry entry = this.pending.get(request.requestId());
		if (entry == null || !entry.deferred || !entry.request.equals(request)
				|| decision == ApprovalDecision.DEFERRED) return false;
		recordDecision(entry, decision);
		this.pending.remove(request.requestId());
		if (decision == ApprovalDecision.APPROVED || decision == ApprovalDecision.REJECTED) {
			this.replay.put(scope(request), decision);
		}
		return true;
	}

	@Override
	public synchronized void cancelRun(RunId id) {
		this.cancelled.add(id);
		// Cancel children as well: no queued question may surface midway through cleanup.
		List<Entry> owned = this.pending.values().stream().filter(e -> e.request.runId().equals(id)
				|| e.context != null && id.equals(e.context.spec().parentRunId())).toList();
		for (Entry entry : owned) {
			this.cancelled.add(entry.request.runId());
			if (entry.deferred) {
				recordDecision(entry, ApprovalDecision.CANCELLED);
				this.pending.remove(entry.request.requestId());
			}
			else settle(entry, ApprovalDecision.CANCELLED);
		}
		this.remembered.removeIf(s -> this.cancelled.contains(s.runId));
		this.replay.keySet().removeIf(s -> this.cancelled.contains(s.runId));
		pump();
	}

	@Override
	public synchronized List<ApprovalRequest> pending() {
		return this.pending.values().stream().map(e -> e.request).toList();
	}

	@Override
	public synchronized void abandonRun(RunId id, Throwable failure) {
		List<Entry> owned = this.pending.values().stream().filter(e -> e.request.runId().equals(id)).toList();
		for (Entry entry : owned) {
			this.pending.remove(entry.request.requestId());
			this.queue.remove(entry);
			if (this.displayed == entry) {
				this.console.dismissApproval(entry.request.requestId());
				this.displayed = null;
			}
			if (entry.waiting && entry.context != null) {
				entry.context.resumeBudget();
				entry.waiting = false;
			}
			entry.result.completeExceptionally(failure);
		}
		this.remembered.removeIf(s -> s.runId.equals(id));
		this.replay.keySet().removeIf(s -> s.runId.equals(id));
		pump();
	}

	private void pump() {
		if (this.displayed != null || this.queue.isEmpty()) return;
		Entry entry = this.queue.remove();
		this.displayed = entry;
		if (!this.console.isInteractive()) {
			settle(entry, ApprovalDecision.DEFERRED);
			pump();
			return;
		}
		this.console.requestApproval(entry.request, answer -> {
			synchronized (this) {
				if (this.displayed != entry || answer == null
						|| !entry.request.requestId().equals(answer.requestId())) return;
				if (answer.remember() && answer.decision() == ApprovalDecision.APPROVED) {
					this.remembered.add(scope(entry.request));
				}
				respond(entry.request, answer.decision());
			}
		});
	}

	private void settle(Entry entry, ApprovalDecision decision) {
		recordDecision(entry, decision);
		this.queue.remove(entry);
		if (this.displayed == entry) {
			this.console.dismissApproval(entry.request.requestId());
			this.displayed = null;
		}
		entry.deferred = decision == ApprovalDecision.DEFERRED;
		if (!entry.deferred) this.pending.remove(entry.request.requestId());
		if (entry.context != null) entry.context.resumeBudget();
		entry.waiting = false;
		entry.result.complete(decision);
	}

	private static RunEvent.ApprovalResolved resolution(Entry entry, ApprovalDecision decision) {
		return new RunEvent.ApprovalResolved(entry.context.now(), entry.request.attemptId(),
				entry.request.requestId().toString(), RunEvent.Decision.valueOf(decision.name()));
	}

	private void recordDecision(Entry entry, ApprovalDecision decision) {
		if (entry.context != null) append(entry, resolution(entry, decision));
		else {
			try { entry.durableDecision.accept(decision); }
			catch (RuntimeException ex) {
				abandonRun(entry.request.runId(), ex);
				throw ex;
			}
		}
	}

	private void append(Entry entry, RunEvent event) {
		try {
			entry.context.writer().append(event);
		}
		catch (RunStoreException ex) {
			abandonRun(entry.request.runId(), ex);
			throw new UncheckedIOException(ex);
		}
	}

	@Override
	public synchronized void restore(RunId id, String events) {
		this.replay.keySet().removeIf(s -> s.runId.equals(id));
		Map<String, Scope> requests = new HashMap<>();
		Set<String> deferred = new HashSet<>();
		var json = JsonMapper.builder().build();
		for (String line : events.lines().toList()) {
			var frame = json.readTree(line.split("\\t", 4)[3]);
			var payload = frame.path("payload");
			switch (frame.path("type").asString()) {
				case "ApprovalRequested" -> {
					Scope scope = new Scope(id, null, payload.path("toolCallKey").asString(),
							payload.path("argsDigest").asString(),
							payload.path("diffDigest").isNull() ? null : payload.path("diffDigest").asString());
					// A later admitted exact call consumes the old deferred grant durably.
					this.replay.remove(scope);
					requests.put(payload.path("requestId").asString(), scope);
				}
				case "ApprovalResolved" -> {
					String key = payload.path("requestId").asString();
					String decision = payload.path("decision").asString();
					if (decision.equals("DEFERRED")) deferred.add(key);
					else if ((decision.equals("APPROVED") || decision.equals("REJECTED")) && deferred.remove(key)) {
						this.replay.put(requests.get(key), ApprovalDecision.valueOf(decision));
					}
					else deferred.remove(key);
				}
				case "StateChanged" -> {
					String reason = payload.path("reason").asString();
					if (reason.equals("PROCESS_LOST") || reason.equals("USER_CANCELLED")) {
						this.replay.keySet().removeIf(s -> s.runId.equals(id));
						deferred.clear();
					}
				}
				default -> {}
			}
		}
	}

	private static Scope scope(ApprovalRequest request) {
		return new Scope(request.runId(), request.planId(), request.toolCallKey(), request.argsDigest(), request.diffDigest());
	}

	private record Scope(RunId runId, String planId, String tool, String args, String diff) {}

	private static final class Entry {
		private final ApprovalRequest request;
		private final RunContext context;
		private final java.util.function.Consumer<ApprovalDecision> durableDecision;
		private final CompletableFuture<ApprovalDecision> result = new CompletableFuture<>();
		private boolean deferred;
		private boolean waiting;
		private Entry(ApprovalRequest request, RunContext context) {
			this.request = request;
			this.context = context;
			this.durableDecision = null;
		}
		private Entry(ApprovalRequest request, java.util.function.Consumer<ApprovalDecision> durableDecision) {
			this.request = request;
			this.context = null;
			this.durableDecision = durableDecision;
		}
	}
}
