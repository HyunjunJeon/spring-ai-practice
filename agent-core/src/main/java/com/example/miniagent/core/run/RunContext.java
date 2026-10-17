package com.example.miniagent.core.run;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.io.UncheckedIOException;
import java.util.UUID;

import com.example.miniagent.core.model.TurnContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.metadata.Usage;
import org.jspecify.annotations.Nullable;

/** Ephemeral writer access. Async callbacks must carry this object, not infer a ThreadLocal. */
public final class RunContext {
	public static final String TOOL_CONTEXT_KEY = "agent.runContext";
	private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
	private final RunSpec spec;
	private final String attemptId;
	private final RunWriter writer;
	private final TurnContext turnContext;
	private final Clock clock;
	private final UsageRecorder meter;
	private boolean roundsRecorded;
	private boolean activeTimeRecorded;
	private final java.util.Map<String, RunUsage> childCharges = new java.util.HashMap<>();
	private boolean childChargesLoaded;
	private RunStoreException accountingFailure;

	public RunContext(RunSpec spec, String attemptId, RunWriter writer, TurnContext turnContext, Clock clock) {
		this.spec = Objects.requireNonNull(spec);
		RunId.requireSafe(attemptId);
		this.attemptId = attemptId;
		this.writer = Objects.requireNonNull(writer);
		this.turnContext = Objects.requireNonNull(turnContext);
		this.clock = Objects.requireNonNull(clock);
		this.meter = new UsageRecorder(spec.budget(), this::now, writer.record().usage());
		if (!spec.equals(writer.record().spec()) || !spec.conversationId().equals(turnContext.sessionId())
				|| !spec.runId().value().equals(turnContext.runId())
				|| !attemptId.equals(turnContext.attemptId())
				|| !spec.cwd().equals(turnContext.cwd().toString())
				|| !spec.model().equals(turnContext.binding().ref())
				|| !spec.wireFamily().equals(turnContext.binding().wireFamily())
				|| !spec.optionsFingerprint().equals(turnContext.binding().optionsFingerprint())) {
			throw new IllegalArgumentException("Run context identity mismatch");
		}
	}

	public RunSpec spec() { return this.spec; }
	public String attemptId() { return this.attemptId; }
	public RunWriter writer() { return this.writer; }
	public TurnContext turnContext() { return this.turnContext; }
	public Instant now() { return this.clock.instant(); }

	public synchronized void beforeModel() {
		if (this.accountingFailure != null) throw new UncheckedIOException(this.accountingFailure);
		this.meter.beforeModel();
	}

	public synchronized void recordModel(@Nullable Usage usage) {
		try {
			recordUsage(RunEvent.UsageKind.MODEL_ROUND, UUID.randomUUID().toString(), this.meter.model(usage));
		}
		catch (RunStoreException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public synchronized void toolAttempt() {
		if (this.accountingFailure != null) throw new UncheckedIOException(this.accountingFailure);
		try {
			recordUsage(RunEvent.UsageKind.TOOLS, UUID.randomUUID().toString(), this.meter.toolAttempt());
		}
		catch (RunStoreException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public void pauseBudget() { this.meter.pause(); }
	public void resumeBudget() { this.meter.resume(); }
	public RunUsage usage() { return this.meter.total(); }

	public synchronized void finishActiveTime() throws RunStoreException {
		if (this.accountingFailure != null) throw this.accountingFailure;
		recordUsage(RunEvent.UsageKind.ACTIVE_TIME, UUID.randomUUID().toString(), this.meter.activeDelta());
	}

	/** Reject stale/child authority before admitting any managed delegated continuation. */
	public synchronized void requireActiveParent() {
		if (this.accountingFailure != null) throw new UncheckedIOException(this.accountingFailure);
		RunRecord record = this.writer.record();
		if (this.spec.childId() != null || this.spec.parentRunId() != null
				|| !this.spec.equals(record.spec()) || record.state() != RunState.RUNNING
				|| record.attempts().isEmpty()
				|| !record.attempts().getLast().attemptId().equals(this.attemptId)
				|| record.attempts().getLast().endedAt() != null) {
			throw new IllegalStateException("Exact active parent required for child accounting");
		}
	}

	/**
	 * Import one acknowledged child observation, never a cumulative result. The child writer
	 * must have returned before invoking this method: no parent lease is acquired here.
	 */
	public synchronized void chargeChildUsage(RunStore runs, RunSpec child, RunEvent.UsageRecorded delta)
			throws RunStoreException {
		if (this.accountingFailure != null) throw this.accountingFailure;
		try { chargeAcknowledgedChild(runs, child, delta); }
		catch (RunStoreException ex) {
			// A failed import cannot become an ordinary failed ChildResult and permit parent continuation.
			this.accountingFailure = ex;
			throw ex;
		}
	}

	private void chargeAcknowledgedChild(RunStore runs, RunSpec child, RunEvent.UsageRecorded delta)
			throws RunStoreException {
		RunRecord owner = this.writer.record();
		if (this.spec.childId() != null || this.spec.parentRunId() != null
				|| owner.state().terminal() || owner.attempts().isEmpty()
				|| !owner.attempts().getLast().attemptId().equals(this.attemptId)
				|| owner.attempts().getLast().endedAt() != null) {
			throw new IllegalStateException("Open parent attempt required for child accounting");
		}
		if (!this.spec.runId().equals(child.parentRunId()) || child.childId() == null
				|| !this.spec.conversationId().equals(child.parentConversationId())
				|| !this.spec.cwd().equals(child.cwd())) {
			throw new IllegalArgumentException("Foreign child accounting lineage");
		}
		// Validate the source against the acknowledged journal, including its exact payload.
		if (!runs.read(child.runId()).spec().equals(child)
				|| !FileRunStore.events(runs, child.runId()).contains(delta)) {
			throw new IllegalArgumentException("Unacknowledged child usage");
		}
		String identity = child.runId().value() + "\n" + delta.attemptId() + "\n"
				+ delta.kind() + "\n" + delta.callId();
		String key = "child-" + RunRequestStore.digest(
				identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		if (!this.childChargesLoaded) {
			for (RunEvent event : FileRunStore.events(runs, this.spec.runId())) {
				if (event instanceof RunEvent.UsageRecorded recorded && recorded.callId().startsWith("child-")) {
					this.childCharges.put(recorded.callId(), recorded.usage());
				}
			}
			this.childChargesLoaded = true;
		}
		RunUsage previous = this.childCharges.get(key);
		RunUsage source = delta.usage();
		if (previous != null) {
			if (previous.knownTokens() != source.knownTokens() || previous.tokensUnknown() != source.tokensUnknown()
					|| previous.toolCalls() != source.toolCalls() || previous.activeMillis() != source.activeMillis()) {
				throw new IllegalStateException("Child usage identity changed");
			}
			return;
		}
		RunUsage charge = this.meter.importedDelta(source);
		// COMPACTION does not suppress the parent's own round/final active-time observation.
		this.writer.append(new RunEvent.UsageRecorded(now(), this.attemptId,
				RunEvent.UsageKind.COMPACTION, key, charge));
		this.meter.importAcknowledged(charge);
		this.childCharges.put(key, charge);
	}

	/** Reconcile only complete acknowledged prefixes; never infer a result from an unfinished tool. */
	public void reconcileChildUsage(RunStore runs) throws RunStoreException {
		requireActiveParent();
		for (RunId id : runs.children(this.spec.runId())) {
			RunRecord child = runs.read(id);
			if (child.state() == RunState.RUNNING || child.state() == RunState.WAITING_APPROVAL) {
				throw new Stop(Stop.Kind.INTERRUPTED,
						"Active child must stop before parent recovery");
			}
			for (RunEvent event : FileRunStore.events(runs, id)) {
				if (event instanceof RunEvent.UsageRecorded delta) chargeChildUsage(runs, child.spec(), delta);
			}
		}
	}

	/** Task 8 records deltas here; never mix per-round observations with the loop aggregate. */
	public synchronized void recordUsage(RunEvent.UsageKind kind, String callId, RunUsage delta)
			throws RunStoreException {
		this.writer.append(new RunEvent.UsageRecorded(now(), this.attemptId, kind, callId, delta));
		if (kind == RunEvent.UsageKind.MODEL_ROUND) {
			this.roundsRecorded = true;
		}
		if (kind == RunEvent.UsageKind.ACTIVE_TIME) {
			this.activeTimeRecorded = true;
		}
	}

	/** core 내부용이며 core.execution 전용이다. 플러그인 API가 아니다. */
	public synchronized boolean roundsRecorded() { return this.roundsRecorded; }
	synchronized boolean activeTimeRecorded() { return this.activeTimeRecorded; }

	public static Optional<RunContext> current() {
		Scope scope = CURRENT.get();
		return scope == null ? Optional.empty() : Optional.of(scope.context);
	}

	/** Only explicitly captured values are accepted; no ThreadLocal fallback. */
	public static Optional<RunContext> fromToolContext(ToolContext tools) {
		Object captured = tools.getContext().get(TOOL_CONTEXT_KEY);
		if (captured == null) {
			return Optional.empty();
		}
		if (!(captured instanceof RunContext context)) {
			throw new IllegalArgumentException("Invalid captured RunContext");
		}
		return Optional.of(context);
	}

	public Scope open() {
		Scope scope = new Scope(this, CURRENT.get());
		CURRENT.set(scope);
		return scope;
	}

	public static final class Scope implements AutoCloseable {
		private final RunContext context;
		private final Scope previous;
		private final Thread owner = Thread.currentThread();
		private boolean closed;

		private Scope(RunContext context, Scope previous) {
			this.context = context;
			this.previous = previous;
		}

		@Override
		public void close() {
			if (this.closed || this.owner != Thread.currentThread() || CURRENT.get() != this) {
				throw new IllegalStateException("Run scopes must close once, in order, on their owner thread");
			}
			if (this.previous == null) {
				CURRENT.remove();
			}
			else {
				CURRENT.set(this.previous);
			}
			this.closed = true;
		}
	}
}
