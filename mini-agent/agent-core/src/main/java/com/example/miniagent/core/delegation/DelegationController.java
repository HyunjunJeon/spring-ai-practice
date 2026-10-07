package com.example.miniagent.core.delegation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.*;
import org.jspecify.annotations.Nullable;

/** Transient current-plan control. RunStore and ChangeStore remain the durable owners. */
public final class DelegationController implements AutoCloseable {
	public record View(DelegationPlan plan, @Nullable TurnContext parent, ToolScope authority,
			DefaultDelegationRuntime.@Nullable Outcome outcome, boolean busy) {}
	public record Event(String conversationId, String planId, String operation, View view) {}
	private record Key(String conversation, String plan) {}
	private static final class Current {
		final DelegationPlan plan;
		final TurnContext parent;
		final ToolScope authority;
		final String original;
		volatile DefaultDelegationRuntime.Outcome outcome;
		volatile boolean busy = true;
		volatile ChildScopeFactory.Scope recovery;
		RunId recoveryRun;
		Current(DelegationPlan plan, TurnContext parent, ToolScope authority, String original) {
			this.plan = plan; this.parent = parent; this.authority = authority; this.original = original;
		}
		View view() { return new View(this.plan, this.parent, this.authority, this.outcome, this.busy); }
	}
	private final DefaultDelegationRuntime runtime;
	private final RunStore runs;
	private final StagedApplyService apply;
	private final Consumer<Event> observer;
	private Consumer<TraceEvent> traces = event -> {};
	private final Map<Key, Current> current = new ConcurrentHashMap<>();

	public DelegationController(DefaultDelegationRuntime runtime, RunStore runs,
			StagedApplyService apply, Consumer<Event> observer) {
		this.runtime = Objects.requireNonNull(runtime);
		this.runs = Objects.requireNonNull(runs);
		this.apply = Objects.requireNonNull(apply);
		this.observer = Objects.requireNonNull(observer);
		this.runtime.observed(this::childChanged);
	}
	public DelegationController observed(Consumer<TraceEvent> observer) {
		this.traces = Objects.requireNonNull(observer); return this;
	}
	public DefaultDelegationRuntime.Outcome execute(TurnContext parent, DelegationPlan plan,
			ToolScope authority, RunBudget budget, Supplier<RunUsage> usage) {
		var context = RunContext.current().filter(c -> c.turnContext() == parent).orElseThrow(
			() -> new IllegalArgumentException("Bound parent RunContext required"));
		try {
			var record = this.runs.read(context.spec().runId());
			if (!record.spec().equals(context.spec()) || record.spec().childId() != null
					|| record.state() != RunState.RUNNING || !context.spec().budget().equals(budget))
				throw new IllegalArgumentException("Actual active parent required");
			if (!this.runtime.evidenceEnabled()) throw new IllegalStateException("Evidence collector not wired");
			String original = record.spec().parentConversationId();
			try (var lease = original.equals(parent.sessionId()) ? null
					: this.runtime.sessionLock().acquire(original, context.spec().runId())) {
				var key = new Key(original, plan.planId());
				var value = new Current(plan, parent, authority, original);
				this.current.compute(key, (k, previous) -> {
					if (previous != null && (previous.busy || previous.outcome instanceof DefaultDelegationRuntime.Pending))
						throw new IllegalStateException("Current plan busy or pending");
					return value;
				});
				emit(value, "execute");
				try {
					value.outcome = this.runtime.executeOutcome(parent, plan, authority, budget, context::usage,
						"0".repeat(64), "0".repeat(64));
					return value.outcome;
				}
				catch (RuntimeException | Error ex) { this.current.remove(key, value); throw ex; }
				finally { value.busy = false; emit(value, "outcome"); }
			}
		}
		catch (IOException ex) { throw new UncheckedIOException(ex); }
	}
	public RunExecutor.OperationResult<DefaultDelegationRuntime.Outcome> executeOperator(
			RunExecutor executor, String originalConversationId, ModelSelection selection, Path cwd,
			DelegationPlan plan, ToolScope authority, RunBudget budget) throws IOException {
		var owned = new java.util.concurrent.atomic.AtomicReference<RunId>();
		try {
			return executor.startOperation("Delegate plan " + plan.planId(), originalConversationId, selection, cwd,
				budget, context -> {
					owned.set(context.spec().runId());
					return execute(context.turnContext(), plan, authority, budget, context::usage);
				});
		}
		finally {
			if (owned.get() != null) {
				var record = this.runs.read(owned.get());
				this.traces.accept(new TraceEvent.RunStateChanged(record.spec().runId().value(),
					originalConversationId, record.attempts().getLast().attemptId(), record.state(), record.spec().model()));
			}
		}
	}
	public List<View> list(String originalConversationId) {
		return this.current.entrySet().stream().filter(e -> e.getKey().conversation().equals(originalConversationId))
			.sorted(java.util.Comparator.comparing(e -> e.getKey().plan())).map(e -> e.getValue().view()).toList();
	}
	public View show(String originalConversationId, String planId) {
		return require(originalConversationId, planId).view();
	}
	public RunRecord resolve(String originalConversationId, String planId, ApprovalRequest request,
			ApprovalDecision decision) throws RunStoreException {
		var value = require(originalConversationId, planId);
		synchronized (value) {
			if (value.busy) throw new IllegalStateException("Current plan busy");
			if (value.recovery != null) {
				var record = value.recovery.executor().resolveApproval(request, decision);
				if (decision == ApprovalDecision.CANCELLED) {
					try { value.outcome = new DefaultDelegationRuntime.Completed(this.runtime.collectRecovered(
						value.plan, value.recovery, new RunExecutor.Result(record, null))); }
					catch (IOException ex) { throw new UncheckedIOException(ex); }
					value.recovery.close(); value.recovery = null;
				}
				else value.outcome = recoveredPending(value, record);
				emit(value, "resolve");
				return record;
			}
			if (value.recoveryRun != null) throw new IllegalStateException("Recovered child is not pending");
			var record = this.runtime.resolveApproval(value.parent.sessionId(), planId, request, decision);
			value.outcome = decision == ApprovalDecision.CANCELLED ? this.runtime.consume(value.parent.sessionId(), planId)
				: this.runtime.pending(value.parent.sessionId(), planId).orElseThrow();
			emit(value, "resolve");
			return record;
		}
	}
	public DefaultDelegationRuntime.Outcome resume(String originalConversationId, String planId, String childId,
			RunExecutor.ResumeOptions options) {
		var value = require(originalConversationId, planId);
		if (value.recoveryRun != null) {
			if (!value.plan.children().getFirst().childId().equals(childId)) throw new IllegalArgumentException("Foreign child");
			try {
				recover(value.recoveryRun, planId, value.plan.children().getFirst(), originalConversationId,
					value.authority, options);
				return require(originalConversationId, planId).outcome;
			}
			catch (IOException ex) { throw new UncheckedIOException(ex); }
		}
		try (var lease = originalConversationId.equals(value.parent.sessionId()) ? null
				: this.runtime.sessionLock().acquire(originalConversationId, new RunId(value.parent.runId()))) {
			synchronized (value) {
				if (value.busy) throw new IllegalStateException("Current plan busy");
				value.busy = true;
			}
			try {
				value.outcome = this.runtime.resume(value.parent.sessionId(), planId, childId, options);
				return value.outcome;
			}
			finally { value.busy = false; emit(value, "resume"); }
		}
	}
	public boolean cancel(String originalConversationId, String planId) {
		var value = this.current.get(new Key(originalConversationId, planId));
		if (value == null) return false;
		if (value.recovery != null) {
			try {
				value.recovery.cancel();
				var record = value.recovery.record().orElseThrow();
				if (!value.busy) {
					value.outcome = new DefaultDelegationRuntime.Completed(
						this.runtime.collectRecovered(value.plan, value.recovery, new RunExecutor.Result(record, null)));
					value.recovery.close(); value.recovery = null;
				}
				emit(value, "cancel");
				return true;
			}
			catch (IOException ex) { throw new UncheckedIOException(ex); }
		}
		if (value.recoveryRun != null) return false;
		boolean cancelled = this.runtime.cancel(value.parent.sessionId(), planId);
		if (cancelled && !value.busy) value.outcome = this.runtime.consume(value.parent.sessionId(), planId);
		if (cancelled) emit(value, "cancel");
		return cancelled;
	}
	public boolean apply(TurnContext parent, String planId, String childId, UUID proposalId,
			SlashCommand.CommandContext console) throws IOException {
		var record = this.runs.read(new RunId(parent.runId()));
		var value = require(record.spec().parentConversationId(), planId);
		if (value.parent != parent || !console.session().id().equals(value.original))
			throw new IllegalArgumentException("Exact original parent/console required");
		try (var lease = this.runtime.sessionLock().acquire(value.original, record.spec().runId())) {
			synchronized (value) {
				if (value.busy || !(value.outcome instanceof DefaultDelegationRuntime.Completed))
					throw new IllegalStateException("Current plan busy or pending");
				value.busy = true;
			}
			try {
				// Fresh collection on parent consumption, even after show/list returned cached metadata.
				var fresh = (DefaultDelegationRuntime.Completed) this.runtime.consume(parent.sessionId(), planId);
				value.outcome = fresh;
				if (fresh.result().status() != DelegationResult.Status.COMPLETE)
					throw new IOException("Required current plan evidence is incomplete");
				var child = value.plan.children().stream().filter(c -> c.childId().equals(childId)).findFirst().orElseThrow();
				var result = fresh.result().children().stream().filter(c -> c.childId().equals(childId)).findFirst().orElseThrow();
				if (result.evidence().stream().noneMatch(e -> e instanceof ChildEvidence.Collected)
						|| result.evidence().stream().noneMatch(e -> e instanceof ChildEvidence.Proposal p
							&& p.changeSetId().equals(proposalId))) throw new IOException("Current owned proposal proof required");
				return this.apply.apply(parent, child, value.authority, result.runId(), proposalId, console);
			}
			finally { value.busy = false; emit(value, "apply"); }
		}
	}
	private Current require(String conversation, String plan) {
		var value = this.current.get(new Key(conversation, plan));
		if (value == null) throw new IllegalArgumentException("No current owned plan");
		return value;
	}
	public RunExecutor.Result recover(RunId childRun, String planId, ChildSpec exactChild,
			String originalConversationId, ToolScope authority, RunExecutor.ResumeOptions options) throws IOException {
		var stored = this.runs.read(childRun);
		var captured = RunContext.current().orElse(null);
		if (captured != null && !captured.spec().runId().equals(stored.spec().parentRunId()))
			throw new IOException("Foreign captured recovery authority");
		if (captured == null) {
			return this.runtime.recoverParent(childRun, exactChild, originalConversationId, authority,
					() -> recover(childRun, planId, exactChild, originalConversationId, authority, options));
		}
		var key = new Key(originalConversationId, planId);
		var plan = new DelegationPlan(planId, List.of(exactChild), 1);
		try (var lease = this.runtime.sessionLock().acquire(originalConversationId, childRun)) {
			var previous = this.current.get(key);
			if (previous != null && (previous.busy || previous.recoveryRun == null
					|| !previous.recoveryRun.equals(childRun))) throw new IllegalStateException("Current plan unavailable");
			if (this.runs.read(childRun).state() != RunState.INTERRUPTED)
				throw new IOException("Only explicitly interrupted child can recover");
			var scope = this.runtime.reconstruct(childRun, exactChild, originalConversationId, authority);
			if (previous != null && previous.recovery != null) previous.recovery.close();
			// This is metadata for a recovered CHILD, not an invented parent TurnContext.
			var value = new Current(plan, null, authority, originalConversationId);
			value.recovery = scope; value.recoveryRun = childRun;
			this.current.put(key, value);
			try {
				var result = scope.resume(childRun, options);
				if (result.record().state() == RunState.WAITING_APPROVAL)
					value.outcome = recoveredPending(value, result.record());
				else {
					value.outcome = new DefaultDelegationRuntime.Completed(this.runtime.collectRecovered(plan, scope, result));
					scope.close(); value.recovery = null;
				}
				return result;
			}
			catch (IOException | RuntimeException | Error ex) {
				if (previous == null) this.current.remove(key, value);
				else this.current.replace(key, value, previous);
				scope.abandon(); scope.close();
				throw ex;
			}
			finally { value.busy = false; emit(value, "recover"); }
		}
	}
	private DefaultDelegationRuntime.Pending recoveredPending(Current value, RunRecord record) {
		return new DefaultDelegationRuntime.Pending(value.plan.planId(),
			List.of(new DefaultDelegationRuntime.PendingChild(value.plan.children().getFirst().childId(), record)),
			value.recovery.pendingApprovals(), List.of(), List.of());
	}
	@Override public void close() {
		this.current.values().forEach(value -> {
			if (value.recovery != null) { value.recovery.abandon(); value.recovery.close(); }
		});
	}
	private void emit(Current value, String operation) {
		this.observer.accept(new Event(value.original, value.plan.planId(), operation, value.view()));
		var status = value.outcome instanceof DefaultDelegationRuntime.Completed c ? c.result().status() : null;
		this.traces.accept(new TraceEvent.DelegationStateChanged(value.original, value.plan.planId(),
			null, null, null, null, null, status, null));
		if (value.outcome instanceof DefaultDelegationRuntime.Pending pending)
			for (var child : pending.children()) {
				var spec = child.record().spec();
				this.traces.accept(new TraceEvent.DelegationStateChanged(value.original, value.plan.planId(),
					child.childId(), spec.runId().value(), spec.model(), ChildStatus.RUNNING,
					child.record().state(), null, null));
			}
	}
	private void childChanged(DefaultDelegationRuntime.Execution event) {
		var value = this.current.values().stream().filter(c -> c.parent != null && c.parent.sessionId().equals(event.conversationId())
			&& c.plan.planId().equals(event.planId())).findFirst().orElse(null);
		if (value == null) return;
		RunRecord record = null;
		try { if (!event.attempts().isEmpty()) record = this.runs.read(event.attempts().getLast()); }
		catch (IOException ex) { throw new UncheckedIOException(ex); }
		var child = value.plan.children().stream().filter(c -> c.childId().equals(event.childId())).findFirst().orElseThrow();
		this.traces.accept(new TraceEvent.DelegationStateChanged(value.original, event.planId(), event.childId(),
			record == null ? null : record.spec().runId().value(), child.effectiveModel(value.parent),
			event.status(), record == null ? null : record.state(), null, event.failureCode()));
	}
}
