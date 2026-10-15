package com.example.miniagent.core.delegation.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.example.miniagent.core.delegation.BudgetLedger;
import com.example.miniagent.core.delegation.ChildEvidence;
import com.example.miniagent.core.delegation.ChildFailure;
import com.example.miniagent.core.delegation.ChildResult;
import com.example.miniagent.core.delegation.ChildRole;
import com.example.miniagent.core.delegation.ChildSpec;
import com.example.miniagent.core.delegation.ChildStatus;
import com.example.miniagent.core.delegation.DelegationEvidenceCollector;
import com.example.miniagent.core.delegation.DelegationPlan;
import com.example.miniagent.core.delegation.DelegationPlanValidator;
import com.example.miniagent.core.delegation.DelegationProperties;
import com.example.miniagent.core.delegation.DelegationResult;
import com.example.miniagent.core.delegation.RolePolicy;
import com.example.miniagent.core.delegation.SessionRunLock;
import com.example.miniagent.core.delegation.ToolScope;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.*;

/**
 * Bounded scheduling around ChildScopeFactory and RunExecutor, never a model loop.
 * Results describe execution; only task22's owned evidence can establish verification.
 */
public final class DefaultDelegationRuntime implements AutoCloseable {
	/** Only ModelProvider.create may signal a transient binding refusal before any effects. */
	public static final class TransientFailure extends RuntimeException {
		private final boolean beforeAttempt;
		public TransientFailure(String message) {
			super(message);
			this.beforeAttempt = RunContext.current().isEmpty();
		}
	}
	public record Execution(String conversationId, String planId, String childId,
			ChildStatus status, List<RunId> attempts, boolean cancellationRequested, String failureCode) {
		public Execution { attempts = List.copyOf(attempts); }
		public Execution(String conversationId, String planId, String childId, ChildStatus status,
				List<RunId> attempts, boolean cancellationRequested) {
			this(conversationId, planId, childId, status, attempts, cancellationRequested, null);
		}
	}
	public sealed interface Outcome permits Completed, Pending {}
	public record Completed(DelegationResult result) implements Outcome {}
	public record PendingChild(String childId, RunRecord record) {}
	public record Pending(String planId, List<PendingChild> children,
			List<com.example.miniagent.core.approval.ApprovalRequest> approvals,
			List<ChildResult> completed, List<String> queued) implements Outcome {
		public Pending {
			children = List.copyOf(children); approvals = List.copyOf(approvals);
			completed = List.copyOf(completed); queued = List.copyOf(queued);
		}
	}
	public static final class PendingApproval extends RuntimeException {
		private final Pending pending;
		public PendingApproval(Pending pending) { super("Delegation awaits explicit approval/resume"); this.pending = pending; }
		public Pending pending() { return this.pending; }
	}
	private record PlanKey(String conversationId, String planId) {}
	private record UsageKey(RunId runId, String attemptId, RunEvent.UsageKind kind, String callId) {}
	private final ChildScopeFactory factory;
	private final DelegationProperties properties;
	private final SessionRunLock sessions;
	private final ThreadPoolExecutor workers;
	private final Semaphore capacity;
	private final Map<PlanKey, PlanExecution> executions = new ConcurrentHashMap<>();
	private volatile boolean closed;
	private DelegationEvidenceCollector collector;
	private java.util.function.Consumer<Execution> observer = event -> {};
	SessionRunLock sessionLock() { return this.sessions; }
	boolean evidenceEnabled() { return this.collector != null; }
	ChildScopeFactory.Scope reconstruct(RunId run, ChildSpec child, String original, ToolScope authority)
			throws java.io.IOException {
		if (this.closed) throw new IllegalStateException("Delegation runtime closed");
		return this.factory.reconstruct(run, child, original, authority);
	}
	<T> T recoverParent(RunId childRun, ChildSpec child, String original, ToolScope authority,
			java.util.concurrent.Callable<T> recovery) throws java.io.IOException {
		return this.factory.recoverParent(childRun, child, original, authority, recovery);
	}
	DelegationResult collectRecovered(DelegationPlan plan, ChildScopeFactory.Scope scope, RunExecutor.Result result)
			throws java.io.IOException {
		var record = result.record();
		var spec = plan.children().getFirst();
		var attempt = record.attempts().getLast();
		ChildStatus status = switch (record.state()) {
			case COMPLETED -> ChildStatus.SUCCEEDED;
			case CANCELLED -> ChildStatus.CANCELLED;
			default -> ChildStatus.FAILED;
		};
		var failure = status == ChildStatus.SUCCEEDED ? null : failure(status == ChildStatus.CANCELLED
			? ChildFailure.Code.CANCELLED : ChildFailure.Code.INTERRUPTED);
		var child = new ChildResult(spec.childId(), UUID.fromString(record.spec().conversationId()),
			record.spec().runId(), UUID.fromString(attempt.attemptId()), record.spec().model(), status, failure,
			result.reply() == null ? List.of() : List.of(new ChildEvidence.ModelNarrative(result.reply().text())),
			record.usage());
		if (this.collector == null) throw new IllegalStateException("Evidence collector not wired");
		if (scope.bound().isEmpty()) return DelegationResult.aggregate(plan, List.of(child),
			status == ChildStatus.CANCELLED, "0".repeat(64), "0".repeat(64));
		return this.collector.aggregate(plan, List.of(child), status == ChildStatus.CANCELLED,
			Map.of(spec.childId(), scope.evidenceRequest(spec)));
	}
	public DefaultDelegationRuntime observed(java.util.function.Consumer<Execution> observer) {
		this.observer = Objects.requireNonNull(observer); return this;
	}

	/** Configure before dispatch; collector consumes existing stores, never model metadata. */
	public synchronized DefaultDelegationRuntime evidence(DelegationEvidenceCollector collector) {
		if (!this.executions.isEmpty()) throw new IllegalStateException("Runtime already used");
		this.collector = Objects.requireNonNull(collector);
		return this;
	}
	public Outcome consume(String conversationId, String planId) {
		return requirePlan(conversationId, planId).outcome();
	}

	public record MessageTarget(String originalConversation, RunId parentRun, String parentConversation,
			String childConversation, RunId childRun, String sender) {}

	/** Console supplies both original conversation and exact plan; completed parents are never reopened. */
	public RunContext messageOperator(String originalConversation, String planId) {
		if (this.closed) throw new IllegalStateException("Delegation runtime closed");
		var matches = this.executions.values().stream().filter(p -> p.validated.plan().planId().equals(planId)
			&& p.accountingOwner.get() != null
			&& p.accountingOwner.get().spec().parentConversationId().equals(originalConversation)).toList();
		if (matches.size() != 1) throw new IllegalArgumentException("No exact owned plan");
		var owner = matches.getFirst().accountingOwner.get();
		owner.requireActiveParent();
		return owner;
	}

	/** No sender IDs supplied by a model: identity comes from an exact live managed owner. */
	public MessageTarget messageTarget(RunContext actor, String planId, String childId) {
		if (this.closed) throw new IllegalStateException("Delegation runtime closed");
		String conversation = actor.spec().childId() == null ? actor.spec().conversationId()
				: actor.spec().parentConversationId();
		var plan = requirePlan(conversation, planId);
		synchronized (plan) {
			if (requirePlan(conversation, planId) != plan)
				throw new IllegalStateException("Plan identity changed");
			var owner = plan.accountingOwner.get();
			if (owner == null) throw new IllegalStateException("Managed messaging requires a parent owner");
			if (actor != owner && actor.spec().childId() == null && actor.spec().equals(owner.spec())
					&& RunContext.current().orElse(null) == actor) {
				actor.requireActiveParent();
				if (plan.busy) throw new IllegalStateException("Cannot transfer a busy plan");
				plan.accountingOwner.set(actor);
				plan.parent = actor.turnContext();
				owner = actor;
			}
			owner.requireActiveParent();
			var child = plan.children.get(childId);
			if (child == null || child.attempts.isEmpty()) throw new IllegalArgumentException("No owned child conversation");
			String sender;
			if (actor == owner && actor.turnContext() == plan.parent) sender = "parent";
			else {
				if (child.scope == null || child.scope.bound().isEmpty() || child.scope.runContext() != actor
						|| actor.writer().record().state() != RunState.RUNNING
						|| !child.run.equals(actor.spec().runId()))
					throw new IllegalArgumentException("Foreign child/parent/attempt");
				sender = actor.spec().runId().value();
			}
			plan.messaging = true;
			return new MessageTarget(owner.spec().parentConversationId(), owner.spec().runId(),
				plan.parent.sessionId(), child.conversation.toString(), child.run, sender);
		}
	}

	/** Fresh run, same conversation and frozen authority; the terminal original is never reopened. */
	public RunExecutor.Result followUp(RunContext parent, String planId, String childId, String body) {
		if (body == null || body.isBlank() || body.length() > 4096) throw new IllegalArgumentException("Invalid message body");
		var target = messageTarget(parent, planId, childId);
		if (!target.sender().equals("parent")) throw new IllegalArgumentException("Only parent dispatches");
		var plan = requirePlan(target.parentConversation(), planId);
		ChildExecution child;
		synchronized (plan) {
			if (plan.busy || plan.cancelled || plan.abandoned || plan.hasPending())
				throw new IllegalStateException("Plan busy, cancelled or pending");
			child = plan.children.get(childId);
			if (child.status != ChildStatus.SUCCEEDED || child.retainedScope == null)
				throw new IllegalStateException("Only a successfully completed child can continue");
			// A fresh stage cannot claim the prior unapplied proposal is present.
			if (child.spec.role() == ChildRole.IMPLEMENT)
				throw new IllegalStateException("IMPLEMENT continuation requires trusted staged proposal restore; unsupported");
			parent.requireActiveParent();
			parent.beforeModel();
			if (parent.usage().toolCalls() >= parent.spec().budget().toolCallCap())
				throw new Stop(Stop.Kind.BUDGET_EXHAUSTED);
			plan.ledger.continueChild(childId);
			plan.busy = true;
		}
		boolean admitted = false;
		parent.pauseBudget();
		try (var lease = this.sessions.acquire(target.parentConversation(), parent.spec().runId());
				var captured = parent.open()) {
			if (!this.capacity.tryAcquire()) throw new RejectedExecutionException("Messaging capacity exhausted");
			admitted = true;
			var original = child.spec;
			var remaining = plan.ledger.remaining(childId);
			var spec = new ChildSpec(original.childId(), original.task(), original.role(), original.effectiveModel(plan.parent),
				original.dependencies(), original.scope(), remaining, original.maxAttempts(), original.required());
			var scope = child.retainedScope.continuation(spec, plan.boundaries(child));
			synchronized (plan) { child.scope = scope; child.status = ChildStatus.RUNNING; }
			try (scope) {
				var result = scope.followUp(body, target.childConversation());
				synchronized (plan) {
					child.retainedScope = scope;
					child.status = result.record().state() == RunState.COMPLETED ? ChildStatus.SUCCEEDED
						: result.record().state() == RunState.CANCELLED ? ChildStatus.CANCELLED : ChildStatus.FAILED;
					if (result.record().state() == RunState.WAITING_APPROVAL) {
						// Never manufacture success or automatically retry a deferred execution.
						scope.abandon();
					}
					child.result = new ChildResult(childId, child.conversation, child.run, child.attempt,
						original.effectiveModel(plan.parent), child.status,
						child.status == ChildStatus.SUCCEEDED ? null : failure(child.status == ChildStatus.CANCELLED
							? ChildFailure.Code.CANCELLED : ChildFailure.Code.INTERRUPTED),
						result.reply() == null ? List.of() : List.of(new ChildEvidence.ModelNarrative(result.reply().text())),
						plan.ledger.usageFor(childId));
					child.collected = false;
					if (plan.collector != null && scope.bound().isPresent()) child.request = scope.evidenceRequest(original);
				}
				return result;
			}
		}
		catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
		finally {
			try {
				parent.reconcileChildUsage(this.factory.runStore());
			}
			catch (RunStoreException ex) { throw new java.io.UncheckedIOException(ex); }
			finally {
				synchronized (plan) {
					plan.ledger.settle(childId);
					child.scope = null;
					plan.busy = false;
				}
				if (admitted) this.capacity.release();
				parent.resumeBudget();
			}
		}
	}

	public DefaultDelegationRuntime(ChildScopeFactory factory, DelegationProperties properties,
			SessionRunLock sessions) {
		this.factory = Objects.requireNonNull(factory);
		this.properties = Objects.requireNonNull(properties);
		this.sessions = Objects.requireNonNull(sessions);
		this.capacity = new Semaphore(Math.addExact(properties.concurrency(), properties.queueCapacity()));
		this.workers = new ThreadPoolExecutor(properties.concurrency(), properties.concurrency(), 0,
				TimeUnit.MILLISECONDS, properties.queueCapacity() == 0 ? new SynchronousQueue<>()
					: new ArrayBlockingQueue<>(properties.queueCapacity()),
				task -> new Thread(task, "delegation-worker"), new ThreadPoolExecutor.AbortPolicy());
	}

	/**
	 * Structural rejection throws before reservation, binding or workers start.
	 * Overload is explicit QUEUE_REJECTED; dependencies of rejected children are SKIPPED.
	 * parentUsage is live, parent-only usage, excluding delegation wait and child deltas.
	 */
	public DelegationResult execute(TurnContext parent, DelegationPlan plan, ToolScope parentScope,
			RunBudget parentBudget, Supplier<RunUsage> parentUsage, String revision, String configurationDigest) {
		Outcome outcome = executeOutcome(parent, plan, parentScope, parentBudget, parentUsage, revision, configurationDigest);
		if (outcome instanceof Completed completed) return completed.result();
		throw new PendingApproval((Pending) outcome);
	}

	public Outcome executeOutcome(TurnContext parent, DelegationPlan plan, ToolScope parentScope,
			RunBudget parentBudget, Supplier<RunUsage> parentUsage, String revision, String configurationDigest) {
		this.factory.requireCapturedStoredParent(parent);
		RunContext owner = RunContext.current().filter(context -> context.turnContext() == parent).orElse(null);
		if (owner != null) owner.requireActiveParent();
		if (owner != null && !owner.spec().budget().equals(parentBudget))
			throw new IllegalArgumentException("Parent budget must match captured run");
		var accountingOwner = new java.util.concurrent.atomic.AtomicReference<>(owner);
		Supplier<RunUsage> currentUsage = owner == null ? parentUsage : () -> accountingOwner.get().usage();
		var validated = new DelegationPlanValidator().validate(plan, parentScope, parentBudget,
				currentUsage.get(), this.properties);
		var ledger = new BudgetLedger(parentBudget, currentUsage, owner != null);
		var reservations = new LinkedHashMap<String, RunBudget>();
		validated.topologicalOrder().forEach(child -> reservations.put(child.childId(), child.budget()));
		ledger.reserve(reservations);
		var key = new PlanKey(parent.sessionId(), plan.planId());
		RunId lockId = parent.runId() == null ? RunId.random() : new RunId(parent.runId());
		try (var lease = this.sessions.acquire(parent.sessionId(), lockId)) {
			var execution = new PlanExecution(parent, validated, parentScope, ledger, accountingOwner, revision, configurationDigest);
			synchronized (this) {
				if (this.closed) throw new IllegalStateException("Delegation runtime closed");
				var previous = this.executions.get(key);
				if (previous != null) synchronized (previous) {
					if (previous.messaging) throw new IllegalStateException("Messaging plan identity already used");
					if (!previous.busy && !previous.hasPending()) this.executions.remove(key, previous);
				}
				if (this.executions.size() >= 128) throw new IllegalStateException("Retained plan limit");
				if (this.executions.putIfAbsent(key, execution) != null)
					throw new IllegalStateException("Delegation already active");
			}
			if (owner != null) owner.pauseBudget();
			try {
				execution.run();
				return execution.outcome();
			}
			finally {
				if (owner != null) owner.resumeBudget();
				execution.busy = false;
				if (!execution.hasPending() && this.collector == null && owner == null) this.executions.remove(key, execution);
			}
		}
	}

	/** Only this runtime's current plans, never a RunStore.list scan of historical runs. */
	public List<Execution> executions() {
		var result = new ArrayList<Execution>();
		this.executions.values().stream().filter(plan -> plan.busy || plan.hasPending())
			.forEach(plan -> result.addAll(plan.snapshot()));
		return List.copyOf(result);
	}

	/** Retained exact state/questions after one-shot return or explicit resolution. */
	public java.util.Optional<Pending> pending(String conversationId, String planId) {
		PlanExecution plan = this.executions.get(new PlanKey(conversationId, planId));
		if (plan == null) return java.util.Optional.empty();
		synchronized (plan) {
			return plan.hasPending() ? java.util.Optional.of((Pending) plan.outcome()) : java.util.Optional.empty();
		}
	}

	/** Queued cancellation is logical; bound attempts use durable RunExecutor.cancel first. */
	public boolean cancel(String conversationId, String planId) {
		var execution = this.executions.get(new PlanKey(conversationId, planId));
		if (execution == null) return false;
		if (!execution.busy && !execution.hasPending()) return false;
		execution.cancel();
		if (!execution.busy && this.collector == null) this.executions.remove(new PlanKey(conversationId, planId), execution);
		return true;
	}

	public RunRecord resolveApproval(String conversationId, String planId,
			com.example.miniagent.core.approval.ApprovalRequest request,
			com.example.miniagent.core.approval.ApprovalDecision decision) throws RunStoreException {
		PlanExecution plan = requirePlan(conversationId, planId);
		synchronized (plan) {
			ChildExecution child = plan.children.values().stream().filter(c -> c.pending
				&& c.run.equals(request.runId()) && c.scope.pendingApprovals().contains(request))
				.findFirst().orElseThrow(() -> new IllegalArgumentException("Foreign retained approval"));
			if (decision == com.example.miniagent.core.approval.ApprovalDecision.CANCELLED) {
				plan.cancel();
				if (!plan.busy && this.collector == null) this.executions.remove(new PlanKey(conversationId, planId), plan);
				return child.scope.record().orElseThrow();
			}
			return child.scope.executor().resolveApproval(request, decision);
		}
	}

	public Outcome resume(String conversationId, String planId, String childId,
			RunExecutor.ResumeOptions options) {
		PlanExecution plan = requirePlan(conversationId, planId);
		RunContext owner = null;
		if (plan.accountingOwner.get() != null) {
			if (RunContext.current().isEmpty()) {
				var child = plan.children.get(childId);
				if (child == null || !child.pending) throw new IllegalStateException("No pending owned child");
				try {
					return recoverParent(child.run, child.spec, plan.accountingOwner.get().spec().parentConversationId(),
							plan.parentScope, () -> resume(conversationId, planId, childId, options));
				}
				catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
			}
			owner = RunContext.current().orElseThrow(
					() -> new IllegalStateException("Managed child continuation requires captured parent"));
			owner.requireActiveParent();
			if (!owner.spec().equals(plan.accountingOwner.get().spec()))
				throw new IllegalStateException("Foreign continuation parent");
		}
		RunId lockId = plan.parent.runId() == null ? RunId.random() : new RunId(plan.parent.runId());
		try (var lease = this.sessions.acquire(conversationId, lockId)) {
			synchronized (plan) {
				if (plan.busy || plan.cancelled) throw new IllegalStateException("Plan unavailable for resume");
				if (owner != null) {
					plan.accountingOwner.set(owner);
					plan.parent = owner.turnContext();
				}
				ChildExecution child = plan.children.get(childId);
				if (child == null || !child.pending || child.scope.record().orElseThrow().state() != RunState.INTERRUPTED)
					throw new IllegalStateException("Child requires explicitly resolved interrupted owner");
				child.resumeId = child.run;
				child.resumeOptions = Objects.requireNonNull(options);
				child.resumeBudget = child.scope.record().orElseThrow().spec().budget();
				child.scope.close();
				child.scope = null;
				child.pending = false;
				child.status = ChildStatus.QUEUED;
				plan.busy = true;
			}
			if (owner != null) owner.pauseBudget();
			try { plan.run(); return plan.outcome(); }
			finally {
				if (owner != null) owner.resumeBudget();
				plan.busy = false;
				if (!plan.hasPending() && this.collector == null) this.executions.remove(new PlanKey(conversationId, planId), plan);
			}
		}
	}

	private PlanExecution requirePlan(String conversation, String planId) {
		PlanExecution plan = this.executions.get(new PlanKey(conversation, planId));
		if (plan == null) throw new IllegalArgumentException("No current owned plan");
		return plan;
	}

	@Override public void close() {
		synchronized (this) {
			this.closed = true;
			this.executions.values().forEach(PlanExecution::abandon);
			this.workers.shutdown();
		}
		try {
			if (!this.workers.awaitTermination(10, TimeUnit.SECONDS))
				throw new IllegalStateException("Child provider did not honor cancellation; workers still active");
			this.executions.clear();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while closing delegation workers", ex);
		}
	}

	private final class ChildExecution {
		final ChildSpec spec;
		final List<RunId> attempts = new ArrayList<>();
		final Set<UsageKey> consumed = new HashSet<>();
		ChildStatus status = ChildStatus.QUEUED;
		ChildScopeFactory.Scope scope;
		ChildScopeFactory.Scope retainedScope;
		ChildResult result;
		boolean pending;
		boolean capacityHeld;
		RunId resumeId;
		RunExecutor.ResumeOptions resumeOptions;
		RunBudget resumeBudget;
		DelegationEvidenceCollector.Request request;
		boolean collected;
		// Rejection descriptors are not persisted run identities. Actual attempts replace them.
		UUID conversation = UUID.randomUUID(), attempt = UUID.randomUUID();
		RunId run = RunId.random();
		ChildExecution(ChildSpec spec) { this.spec = spec; }
	}

	private final class PlanExecution {
		TurnContext parent;
		final java.util.concurrent.atomic.AtomicReference<RunContext> accountingOwner;
		final DelegationPlanValidator.ValidatedPlan validated;
		final ToolScope parentScope;
		final BudgetLedger ledger;
		final DelegationEvidenceCollector collector;
		final Map<String, ChildExecution> children = new LinkedHashMap<>();
		volatile boolean cancelled;
		volatile boolean abandoned;
		volatile boolean busy = true;
		volatile boolean messaging;
		final String revision, configurationDigest;
		PlanExecution(TurnContext parent, DelegationPlanValidator.ValidatedPlan validated,
				ToolScope parentScope, BudgetLedger ledger, java.util.concurrent.atomic.AtomicReference<RunContext> accountingOwner,
				String revision, String configurationDigest) {
			this.parent = parent;
			this.accountingOwner = accountingOwner;
			// Legacy unmanaged execution has no authoritative parent run to verify.
			this.collector = parent.runId() == null ? null : DefaultDelegationRuntime.this.collector;
			this.validated = validated;
			this.parentScope = parentScope;
			this.ledger = ledger;
			this.revision = revision;
			this.configurationDigest = configurationDigest;
			validated.topologicalOrder().forEach(spec -> this.children.put(spec.childId(), new ChildExecution(spec)));
		}
		synchronized List<Execution> snapshot() {
			return this.children.values().stream().map(child -> new Execution(this.parent.sessionId(),
					this.validated.plan().planId(), child.spec.childId(), child.status,
					child.attempts, this.cancelled)).toList();
		}
		synchronized void cancel() {
			this.cancelled = true;
			for (var child : this.children.values()) {
				if (child.result != null) continue;
				cancelBound(child);
				if (child.pending || child.status == ChildStatus.QUEUED) {
					child.pending = false;
					finish(child, ChildStatus.CANCELLED, failure(ChildFailure.Code.CANCELLED), List.of());
					if (child.scope != null) child.scope.close();
					releaseCapacity(child);
				}
			}
		}
		synchronized void abandon() {
			this.abandoned = true;
			for (var child : this.children.values()) {
				if (child.scope != null) child.scope.abandon();
				if (child.pending) {
					child.pending = false;
					finish(child, ChildStatus.FAILED, failure(ChildFailure.Code.INTERRUPTED), List.of());
					child.scope.close();
					releaseCapacity(child);
				}
				if (child.status == ChildStatus.QUEUED && child.result == null)
					finish(child, ChildStatus.FAILED, failure(ChildFailure.Code.INTERRUPTED), List.of());
			}
		}
		void releaseCapacity(ChildExecution child) {
			if (child.capacityHeld) { child.capacityHeld = false; capacity.release(); }
		}
		void cancelBound(ChildExecution child) {
			if (child.scope == null) return;
			try { child.scope.cancel(); }
			catch (RunStoreException ex) {
				if (ex.code() != RunStoreException.Code.TERMINAL)
					throw new java.io.UncheckedIOException(ex);
			}
		}
		synchronized boolean hasPending() {
			return this.children.values().stream().anyMatch(child -> child.pending);
		}
		synchronized Outcome outcome() {
			if (hasPending()) return new Pending(this.validated.plan().planId(),
				this.children.values().stream().filter(child -> child.pending)
					.map(child -> new PendingChild(child.spec.childId(), child.scope.record().orElseThrow())).toList(),
				this.children.values().stream().filter(child -> child.pending)
					.flatMap(child -> child.scope.pendingApprovals().stream()).toList(),
				this.children.values().stream().filter(child -> child.result != null).map(child -> child.result).toList(),
				this.children.values().stream().filter(child -> child.status == ChildStatus.QUEUED)
					.map(child -> child.spec.childId()).toList());
			var results = this.validated.plan().children().stream()
				.map(spec -> this.children.get(spec.childId()).result).toList();
			if (collector == null) return new Completed(DelegationResult.aggregate(this.validated.plan(),
				results, this.cancelled, this.revision, this.configurationDigest));
			try {
				var requests = new LinkedHashMap<String, DelegationEvidenceCollector.Request>();
				for (var child : this.children.values()) requests.put(child.spec.childId(), expectation(child));
				return new Completed(collector.aggregate(this.validated.plan(), results, this.cancelled, requests));
			}
			catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
		}
		DelegationEvidenceCollector.Request expectation(ChildExecution child) {
			if (child.request != null) return child.request;
			return new DelegationEvidenceCollector.Request(child.spec,
				new com.example.miniagent.core.change.ChangeOwner(child.conversation.toString(), child.run,
					child.attempt.toString(), child.attempt.toString()),
				new RunId(this.parent.runId()), UUID.fromString(this.parent.sessionId()),
				"0".repeat(64), "0".repeat(64), List.of(), null, null, null);
		}
		List<ChildResult> run() {
			CompletionService<ChildExecution> completed = new ExecutorCompletionService<>(workers,
					new ArrayBlockingQueue<>(Math.addExact(properties.concurrency(), properties.queueCapacity())));
			var submitted = new LinkedHashMap<java.util.concurrent.Future<ChildExecution>, ChildExecution>();
			var admitted = new ArrayList<ChildExecution>();
			boolean interrupted = false;
			try {
				synchronized (this) {
					for (var child : this.children.values()) {
						if (child.result != null || child.pending) continue;
						if (child.capacityHeld || capacity.tryAcquire()) {
							child.capacityHeld = true;
							admitted.add(child);
						}
						else finish(child, ChildStatus.FAILED, failure(ChildFailure.Code.QUEUE_REJECTED), List.of());
					}
				}
				int active = 0, finished = 0;
				while (finished < admitted.size()) {
					synchronized (this) {
						// Recollect dependency proof only at an existing scheduler quiescent boundary:
						// no sibling can still hold a change-journal lease during collection.
						if (collector != null && active == 0) {
							for (var child : this.children.values()) {
								if (child.result == null || child.status != ChildStatus.SUCCEEDED || child.collected) continue;
								try {
									child.result = collector.aggregate(new DelegationPlan(this.validated.plan().planId(),
										List.of(child.spec), this.validated.plan().depth()), List.of(child.result), this.cancelled,
										Map.of(child.spec.childId(), expectation(child))).children().getFirst();
									child.collected = true;
								}
								catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
							}
						}
						for (var child : admitted) {
							if (child.status != ChildStatus.QUEUED) continue;
							if (this.cancelled) {
								finish(child, ChildStatus.CANCELLED, failure(ChildFailure.Code.CANCELLED), List.of());
								finished++;
								continue;
							}
							var dependencies = child.spec.dependencies().stream().map(this.children::get).toList();
							if (dependencies.stream().anyMatch(d -> d.result != null && (d.status != ChildStatus.SUCCEEDED
									|| collector != null && d.collected && !d.result.verifiedSuccess(d.spec.role(),
										expectation(d).revision(), expectation(d).configurationDigest())))) {
								finish(child, ChildStatus.SKIPPED, failure(ChildFailure.Code.DEPENDENCY_FAILED), List.of());
								finished++;
							}
							else if (dependencies.stream().allMatch(d -> d.result != null)
									&& (collector == null || dependencies.stream().allMatch(d -> d.collected))
									&& active < properties.concurrency()) {
								child.status = ChildStatus.RUNNING;
								try {
									submitted.put(completed.submit(() -> { attempt(child); return child; }), child);
									active++;
								}
								catch (RejectedExecutionException ex) {
									finish(child, ChildStatus.FAILED, failure(ChildFailure.Code.QUEUE_REJECTED), List.of());
									finished++;
								}
							}
						}
					}
					if (active == 0) break; // Remaining dependencies can await an explicitly retained child.
					try {
						var future = completed.take();
						var child = submitted.remove(future);
						active--;
						finished++;
						try { future.get(); }
						catch (java.util.concurrent.ExecutionException ex) {
							// An abnormal worker must not release permits while siblings still execute.
							synchronized (this) {
								if (child.result == null) finish(child, ChildStatus.FAILED,
										failure(ChildFailure.Code.UNKNOWN_EFFECT), List.of());
							}
							cancel();
						}
					}
					catch (InterruptedException ex) {
						interrupted = true;
						cancel();
					}
				}
				synchronized (this) {
					return this.validated.plan().children().stream()
							.map(spec -> this.children.get(spec.childId()).result).toList();
				}
			}
			finally {
				synchronized (this) {
					admitted.stream().filter(child -> child.result != null).forEach(this::releaseCapacity);
				}
				if (interrupted) Thread.currentThread().interrupt();
			}
		}
		void attempt(ChildExecution child) {
			List<ChildEvidence> evidence = new ArrayList<>();
			ChildFailure failure = null;
			ChildStatus status = ChildStatus.FAILED;
			for (int number = 0; number < child.spec.maxAttempts(); number++) {
				boolean transientFailure = false;
				try {
					this.ledger.beforeModel(child.spec.childId());
					RunBudget remaining = child.resumeId == null ? this.ledger.remaining(child.spec.childId()) : child.resumeBudget;
					var original = child.spec;
					var attemptSpec = new ChildSpec(original.childId(), original.task(), original.role(),
							original.effectiveModel(this.parent), original.dependencies(), original.scope(),
							remaining, original.maxAttempts(), original.required());
					var dependencies = child.spec.dependencies().stream().map(this.children::get)
						.map(d -> new ChildScopeFactory.Dependency(d.spec, d.result, d.request)).toList();
					ChildScopeFactory.Scope scope;
					var owner = this.accountingOwner.get();
					try (var captured = owner == null ? null : owner.open()) {
						scope = factory.create(this.parent, attemptSpec, this.parentScope, boundaries(child), dependencies);
					}
					try {
						synchronized (this) {
							child.scope = scope;
							if (this.cancelled) throw new Stop(Stop.Kind.CANCELLED);
							if (this.abandoned) throw new Stop(Stop.Kind.INTERRUPTED);
						}
						try {
							RunExecutor.Result result = child.resumeId == null ? scope.call()
								: scope.resume(child.resumeId, child.resumeOptions);
							RunRecord record = result.record();
							if (record.state() == RunState.WAITING_APPROVAL) {
								synchronized (this) {
									if (this.cancelled) scope.cancel();
									else { child.pending = true; return; }
								}
								status = ChildStatus.CANCELLED;
								failure = failure(ChildFailure.Code.CANCELLED);
							}
							else if (!record.uncertainTools().isEmpty()) failure = failure(ChildFailure.Code.UNKNOWN_EFFECT);
							else if (record.state() == RunState.COMPLETED) {
								status = ChildStatus.SUCCEEDED;
								failure = null;
								if (result.reply() != null) evidence.add(new ChildEvidence.ModelNarrative(result.reply().text()));
							}
							else if (record.state() == RunState.CANCELLED) {
								status = ChildStatus.CANCELLED;
								failure = failure(ChildFailure.Code.CANCELLED);
							}
							else failure = failure(record.reason() == RunEvent.Reason.BUDGET_EXHAUSTED
									? ChildFailure.Code.BUDGET_EXHAUSTED : ChildFailure.Code.INTERRUPTED);
						}
						finally {
							if (scope.bound().isPresent()) capture(child, scope.runContext());
							if (collector != null && scope.bound().isPresent())
								child.request = scope.evidenceRequest(child.spec);
						}
					}
					finally {
						child.retainedScope = scope;
						if (!child.pending) scope.close();
					}
				}
				catch (Exception ex) {
					failure = classify(ex);
					status = failure.code() == ChildFailure.Code.CANCELLED ? ChildStatus.CANCELLED : ChildStatus.FAILED;
					synchronized (this) {
						RunRecord record = child.scope == null ? null : child.scope.record().orElse(null);
						transientFailure = preBindingTransient(ex) && record == null
								&& failure.code() == ChildFailure.Code.MODEL_UNAVAILABLE && !this.cancelled;
					}
					if (failure.code() == ChildFailure.Code.CANCELLED) status = ChildStatus.CANCELLED;
				}
				finally {
					synchronized (this) { if (!child.pending) child.scope = null; }
					Thread.interrupted();
				}
				if (!transientFailure || number + 1 == child.spec.maxAttempts()) break;
				// A provider's explicit pre-effect transient classification is necessary, not sufficient.
				// The next attempt still checks shared parent authority and remaining total reservation.
			}
			synchronized (this) {
				if (this.cancelled) {
					status = ChildStatus.CANCELLED;
					failure = failure(ChildFailure.Code.CANCELLED);
				}
				finish(child, status, failure, evidence);
			}
			// Durable cancellation interrupts a worker; never hand that flag to another child.
			Thread.interrupted();
		}
		ChildScopeFactory.Boundaries boundaries(ChildExecution child) {
			return new ChildScopeFactory.Boundaries() {
				@Override public void created(ChildScopeFactory.Metadata metadata, RunRecord record) {
					synchronized (PlanExecution.this) {
						child.conversation = UUID.fromString(record.spec().conversationId());
						child.run = record.spec().runId();
						if (!child.attempts.contains(child.run)) child.attempts.add(child.run);
						observer.accept(new Execution(parent.sessionId(), validated.plan().planId(),
							child.spec.childId(), child.status, child.attempts, cancelled));
						if (cancelled) cancelBound(child);
					}
				}
				@Override public void bound(ChildScopeFactory.Metadata metadata, RunContext context) {
					synchronized (PlanExecution.this) {
						capture(child, context);
						observer.accept(new Execution(parent.sessionId(), validated.plan().planId(),
							child.spec.childId(), child.status, child.attempts, cancelled));
						if (cancelled) cancelBound(child);
					}
				}
				private void check(RunContext context, boolean tool) {
					synchronized (PlanExecution.this) {
						capture(child, context);
						if (cancelled) {
							cancelBound(child);
							throw new Stop(Stop.Kind.CANCELLED);
						}
						if (abandoned) throw new Stop(Stop.Kind.INTERRUPTED);
						if (tool) ledger.beforeTool(child.spec.childId());
						else ledger.beforeModel(child.spec.childId());
					}
				}
				@Override public void beforeModel(ChildScopeFactory.Metadata metadata, RunContext context) { check(context, false); }
				@Override public void beforeTool(ChildScopeFactory.Metadata metadata, RunContext context) { check(context, true); }
				@Override public void usage(ChildScopeFactory.Metadata metadata, RunContext context, RunEvent.UsageRecorded delta) {
					synchronized (PlanExecution.this) {
						capture(child, context);
						if (!delta.attemptId().equals(context.attemptId()))
							throw new IllegalStateException("Foreign durable usage attempt");
						var key = new UsageKey(context.spec().runId(), delta.attemptId(), delta.kind(), delta.callId());
						if (!child.consumed.contains(key)) {
							ledger.observe(child.spec.childId(), delta.usage());
							child.consumed.add(key);
						}
					}
				}
			};
		}
		void capture(ChildExecution child, RunContext context) {
			synchronized (this) {
				child.conversation = UUID.fromString(context.spec().conversationId());
				child.run = context.spec().runId();
				child.attempt = UUID.fromString(context.attemptId());
				if (!child.attempts.contains(child.run)) child.attempts.add(child.run);
			}
		}
		void finish(ChildExecution child, ChildStatus status, ChildFailure failure, List<ChildEvidence> evidence) {
			child.status = status;
			child.result = new ChildResult(child.spec.childId(), child.conversation, child.run, child.attempt,
					child.spec.effectiveModel(this.parent), status, failure, evidence,
					this.ledger.settle(child.spec.childId()));
			observer.accept(new Execution(this.parent.sessionId(), this.validated.plan().planId(),
				child.spec.childId(), status, child.attempts, this.cancelled,
				failure == null ? null : failure.code().name()));
		}
	}
	private static ChildFailure failure(ChildFailure.Code code) {
		return new ChildFailure(code, code.name(), false);
	}
	private static boolean preBindingTransient(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause())
			if (cause instanceof TransientFailure transientFailure) return transientFailure.beforeAttempt;
		return false;
	}
	private static ChildFailure classify(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof BudgetLedger.Refusal refusal)
				return new ChildFailure(ChildFailure.Code.BUDGET_EXHAUSTED, refusal.code().name(), false);
			if (cause instanceof RolePolicy.Denied denied)
				return failure(denied.code() == RolePolicy.Code.UNKNOWN_EFFECT
						? ChildFailure.Code.UNKNOWN_EFFECT : ChildFailure.Code.SCOPE_DENIED);
			if (cause instanceof Stop stop)
				return failure(switch (stop.kind()) {
					case CANCELLED -> ChildFailure.Code.CANCELLED;
					case BUDGET_EXHAUSTED -> ChildFailure.Code.BUDGET_EXHAUSTED;
					case DEFERRED, INTERRUPTED -> ChildFailure.Code.INTERRUPTED;
				});
			if (cause instanceof RunExecutor.Failure failed)
				return failure(failed.code() == RunExecutor.Code.MODEL_UNAVAILABLE
						? ChildFailure.Code.MODEL_UNAVAILABLE : ChildFailure.Code.UNKNOWN_EFFECT);
			if (cause instanceof TransientFailure) return failure(ChildFailure.Code.MODEL_UNAVAILABLE);
		}
		return failure(ChildFailure.Code.UNKNOWN_EFFECT);
	}
}
