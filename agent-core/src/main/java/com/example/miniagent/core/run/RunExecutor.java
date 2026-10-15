package com.example.miniagent.core.run;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRegistry;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.ModelUnavailableException;
import com.example.miniagent.core.model.SessionModelStore;
import com.example.miniagent.core.model.TurnContext;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.metadata.EmptyUsage;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Synchronous lifecycle around Agent's official advisor loop, never a second model loop. */
public final class RunExecutor {
	private static final java.util.Map<Agent, java.lang.ref.WeakReference<RunExecutor>> EXECUTORS =
			java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	/** Current trusted assembly for this exact Agent; durable run identity is still validated on resume. */
	public static java.util.Optional<RunExecutor> forAgent(Agent agent) {
		var reference = EXECUTORS.get(agent);
		return reference == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(reference.get());
	}
	private final Agent agent;
	private final ModelRegistry registry;
	private final SessionModelStore selections;
	private final RunStore runs;
	private final RunRequestStore requests;
	private final Clock clock;
	private final List<RunResumeContributor> contributors;
	private final ApprovalBroker approvals;
	private final DriftGate drift;
	private com.example.miniagent.core.change.StagingToolInterceptor staging;
	private java.util.function.Supplier<com.example.miniagent.core.change.ChangeStore> changes;
	private final ConcurrentHashMap<RunId, Active> active = new ConcurrentHashMap<>();
	private java.util.function.Consumer<RunRecord> created = record -> {};
	private java.util.function.Consumer<RunContext> observed = context -> {};
	private java.util.function.BiPredicate<RunSpec, String> childResumeAuthority;

	/** Acknowledged creation, with cancellation ownership installed before publication. */
	public RunExecutor created(java.util.function.Consumer<RunRecord> hook) {
		this.created = Objects.requireNonNull(hook);
		return this;
	}
	/** Exact final attempt owner, including a stage/bootstrap failure before Agent capture. */
	public RunExecutor observed(java.util.function.Consumer<RunContext> hook) {
		this.observed = Objects.requireNonNull(hook);
		return this;
	}
	/** Trusted factory reconstruction only; ordinary parent executors have no child authority. */
	public RunExecutor childResumeAuthority(java.util.function.BiPredicate<RunSpec, String> authority) {
		this.childResumeAuthority = Objects.requireNonNull(authority);
		return this;
	}

	public RunExecutor(Agent agent, ModelRegistry registry, SessionModelStore selections,
			RunStore runs, RunRequestStore requests, Clock clock, List<RunResumeContributor> contributors) {
		this(agent, registry, selections, runs, requests, clock, contributors, null);
	}

	public RunExecutor(Agent agent, ModelRegistry registry, SessionModelStore selections,
			RunStore runs, RunRequestStore requests, Clock clock, List<RunResumeContributor> contributors,
			ApprovalBroker approvals) {
		this(agent, registry, selections, runs, requests, clock, contributors, approvals,
				new DriftGate(requests.evidence()));
	}

	public RunExecutor(Agent agent, ModelRegistry registry, SessionModelStore selections,
			RunStore runs, RunRequestStore requests, Clock clock, List<RunResumeContributor> contributors,
			ApprovalBroker approvals, DriftGate drift) {
		this.agent = Objects.requireNonNull(agent);
		this.registry = Objects.requireNonNull(registry);
		this.selections = Objects.requireNonNull(selections);
		this.runs = Objects.requireNonNull(runs);
		this.requests = Objects.requireNonNull(requests);
		this.clock = Objects.requireNonNull(clock);
		this.contributors = List.copyOf(contributors);
		this.approvals = approvals;
		this.drift = Objects.requireNonNull(drift);
		if (!this.drift.home().equals(this.requests.evidence().home()))
			throw new IllegalArgumentException("Journal gate and requests must share the same private home");
		EXECUTORS.put(this.agent, new java.lang.ref.WeakReference<>(this));
	}

	public record Result(RunRecord record, Agent.AgentReply reply) {}
	public record OperationResult<T>(RunRecord record, T value) {}
	/** Console backend task under this same executor's real parent identity and ownership. */
	public <T> OperationResult<T> startOperation(String goal, String originalConversationId,
			ModelSelection selection, Path cwd, RunBudget budget, java.util.function.Function<RunContext, T> operation)
			throws IOException {
		return startOperation(RunId.random(), goal, originalConversationId, selection, cwd, budget, operation);
	}

	/** Reserved identity is durably associated by the caller before any execution begins. */
	public <T> OperationResult<T> startOperation(RunId reservedId, String goal, String originalConversationId,
			ModelSelection selection, Path cwd, RunBudget budget, java.util.function.Function<RunContext, T> operation)
			throws IOException {
		Objects.requireNonNull(reservedId);
		RunRequestStore.validateGoal(goal);
		RunId.requireSafe(originalConversationId);
		Path workspace = workspace(cwd);
		ModelBinding binding = bind(selection);
		RunSpec spec = new RunSpec(RunSpec.SCHEMA_VERSION, reservedId, UUID.randomUUID().toString(),
			originalConversationId, null, null, selection.ref(), binding.wireFamily(), workspace.toString(),
			budget, selection.optionsFingerprint(), toolsFingerprint(), this.clock.instant());
		var value = new java.util.concurrent.atomic.AtomicReference<T>();
		Result result = execute(this.runs.create(spec), binding, goal, selection, context -> {
			value.set(operation.apply(context));
			try { context.recordUsage(RunEvent.UsageKind.MODEL_ROUND, "operator-no-model", RunUsage.empty()); }
			catch (RunStoreException ex) { throw new java.io.UncheckedIOException(ex); }
		});
		return new OperationResult<>(result.record(), value.get());
	}

	/** Production wiring remains lazy: ordinary turns never initialize the change store. */
	public RunExecutor staging(com.example.miniagent.core.change.StagingToolInterceptor staging,
			java.util.function.Supplier<com.example.miniagent.core.change.ChangeStore> changes) {
		this.staging = Objects.requireNonNull(staging);
		this.changes = Objects.requireNonNull(changes);
		return this;
	}
	public record ToolResolutionInput(String oldAttemptId, String toolCallKey, RunEvent.Resolution decision,
			String operatorId, String evidenceId) {
		public ToolResolutionInput {
			RunId.requireSafe(oldAttemptId); RunId.requireSafe(toolCallKey);
			Objects.requireNonNull(decision); RunId.requireSafe(operatorId); RunId.requireSafe(evidenceId);
		}
	}
	public record ResumeOptions(List<ToolResolutionInput> resolutions, DriftGate.Acknowledgement drift) {
		public ResumeOptions { resolutions = List.copyOf(resolutions); }
		public static ResumeOptions ordinary() { return new ResumeOptions(List.of(), null); }
	}

	public enum Code { TERMINAL, NOT_RESUMABLE, IDENTITY_DRIFT, CORRUPT_REQUEST, UNKNOWN_EFFECTS, MODEL_UNAVAILABLE }

	public static final class Failure extends IOException {
		private final Code code;
		private final List<RunEvent.ToolStarted> uncertainTools;

		private Failure(Code code, String message, Throwable cause, List<RunEvent.ToolStarted> uncertainTools) {
			super(message, cause);
			this.code = code;
			this.uncertainTools = List.copyOf(uncertainTools);
		}

		public Code code() { return this.code; }
		public List<RunEvent.ToolStarted> uncertainTools() { return this.uncertainTools; }
	}

	public Result start(String goal, String parentConversationId, ModelSelection selection, Path cwd,
			RunBudget budget) throws IOException {
		return start(goal, parentConversationId, null, null, selection, cwd, budget);
	}

	/** Child lineage is captured before creating its authoritative spec, never retrofitted. */
	public Result start(String goal, String parentConversationId, RunId parentRunId, String childId,
			ModelSelection selection, Path cwd, RunBudget budget) throws IOException {
		return startConversation(goal, parentConversationId, parentRunId, childId, selection, cwd, budget,
				UUID.randomUUID().toString());
	}

	/** Trusted scoped assembly may allocate a fresh run in an existing child conversation. */
	public Result startConversation(String goal, String parentConversationId, RunId parentRunId, String childId,
			ModelSelection selection, Path cwd, RunBudget budget, String conversationId) throws IOException {
		RunRequestStore.validateGoal(goal);
		RunId.requireSafe(parentConversationId);
		RunId.requireSafe(conversationId);
		Path workspace = workspace(cwd);
		ModelBinding binding = bind(selection);
		RunSpec spec = new RunSpec(RunSpec.SCHEMA_VERSION, RunId.random(), conversationId,
				parentConversationId, parentRunId, childId, selection.ref(), binding.wireFamily(), workspace.toString(),
				budget, selection.optionsFingerprint(), toolsFingerprint(), this.clock.instant());
		RunWriter writer = this.runs.create(spec);
		return execute(writer, binding, goal, selection);
	}

	public Result resume(RunId id, ModelSelection expectedSelection, Path expectedCwd) throws IOException {
		return resume(id, expectedSelection, expectedCwd, ResumeOptions.ordinary());
	}

	public DriftGate.Difference inspectDrift(RunId id) throws IOException {
		RunRecord record = this.runs.read(id);
		return this.drift.inspect(this.agent, this.runs, record, this.requests.load(record.spec()).baselineDigest());
	}

	public Result resume(RunId id, ModelSelection expectedSelection, Path expectedCwd, ResumeOptions options) throws IOException {
		return resume(id, expectedSelection, expectedCwd, options, null);
	}

	/** Resume the SAME durable parent for an explicit backend operation, never allocate a replacement run. */
	public <T> OperationResult<T> resumeOperation(RunId id, ModelSelection expectedSelection, Path expectedCwd,
			ResumeOptions options, java.util.function.Function<RunContext, T> operation) throws IOException {
		var value = new java.util.concurrent.atomic.AtomicReference<T>();
		Result result = resume(id, expectedSelection, expectedCwd, options, context -> {
			value.set(operation.apply(context));
			try { context.recordUsage(RunEvent.UsageKind.MODEL_ROUND, "operator-no-model", RunUsage.empty()); }
			catch (RunStoreException ex) { throw new java.io.UncheckedIOException(ex); }
		});
		return new OperationResult<>(result.record(), value.get());
	}

	private Result resume(RunId id, ModelSelection expectedSelection, Path expectedCwd, ResumeOptions options,
			java.util.function.Consumer<RunContext> operation) throws IOException {
		// All refusal checks and binding happen before acquiring a writer: unavailable
		// providers must not materialize PROCESS_LOST or modify the previous journal.
		RunRecord record = this.runs.read(id);
		requireResumable(record);
		RunSpec spec = record.spec();
		if (spec.childId() != null && this.childResumeAuthority == null)
			throw failure(Code.IDENTITY_DRIFT, "Child resume requires explicit scoped capture authority", null);
		List<ToolResolutionInput> resolutions = resolutions(record, options);
		RunRequestStore.Request request;
		ModelSelection stored;
		try {
			request = this.requests.load(spec);
			stored = this.selections.load(spec.conversationId())
				.orElseThrow(() -> new IOException("Missing run model selection"));
		}
		catch (RunStoreException ex) {
			throw ex;
		}
		catch (IOException | IllegalArgumentException ex) {
			throw failure(Code.CORRUPT_REQUEST, "Cannot validate private run request", ex);
		}
		if (spec.childId() != null && !this.childResumeAuthority.test(spec, request.goal()))
			throw failure(Code.IDENTITY_DRIFT, "Child lineage/request differs from explicit scope", null);
		if (!stored.equals(request.selection()) || !stored.equals(expectedSelection)
				|| !spec.cwd().equals(workspace(expectedCwd).toString())) {
			throw failure(Code.IDENTITY_DRIFT, "Run selection, cwd or tools differ", null);
		}
		try {
			if (!this.registry.requireAvailable(stored.ref()).wireFamily().equals(spec.wireFamily()))
				throw failure(Code.IDENTITY_DRIFT, "Run wire family differs", null);
		}
		catch (ModelUnavailableException ex) { throw failure(Code.MODEL_UNAVAILABLE, "Run model unavailable", ex); }
		DriftGate.Difference difference;
		try {
			if (spec.childId() != null) {
				var baseline = this.drift.load(request.baselineDigest(), spec);
				var current = this.drift.capture(this.agent, spec);
				if (!baseline.configurationDigest().equals(current.configurationDigest()))
					throw failure(Code.IDENTITY_DRIFT, "Child role/tool scope/configuration differs", null);
			}
			difference = this.drift.inspect(this.agent, this.runs, record, request.baselineDigest());
			this.drift.require(difference, options.drift());
		}
		catch (IOException ex) { throw failure(Code.IDENTITY_DRIFT, "Run source/tool/config drift", ex); }
		ModelBinding binding = bind(stored);
		if (!binding.ref().equals(spec.model()) || !binding.optionsFingerprint().equals(spec.optionsFingerprint())
				|| !binding.wireFamily().equals(spec.wireFamily())) {
			throw failure(Code.IDENTITY_DRIFT, "Run binding wire/ref/options differ", null);
		}
		if (this.approvals != null) this.approvals.restore(id,
				record.reason() == RunEvent.Reason.PROCESS_LOST ? "" : this.runs.export(id).eventsLog());
		StringBuilder input = new StringBuilder("Explicitly resume the interrupted user goal below. "
				+ "The previous attempt did not complete. Do not assume unfinished work succeeded, "
				+ "or repeat side effects without verifying their current state.\n\nOriginal user goal:\n")
			.append(request.goal());
		for (RunResumeContributor contributor : this.contributors) {
			String evidence = contributor.verifiedEvidence(record, request.goal());
			if (evidence == null) {
				throw failure(Code.CORRUPT_REQUEST, "Resume contributor returned no evidence", null);
			}
			input.append("\n\nVerified journal evidence:\n").append(evidence);
		}
		input.append("\n\n").append(new JournalResumeContributor(this.runs, this.requests.evidence())
				.verifiedEvidence(record, request.goal()));
		for (var resolution : resolutions)
			input.append("\nUNKNOWN operator action ").append(resolution.toolCallKey()).append(": ")
					.append(resolution.decision()).append(". This is not evidence of success.");
		RunWriter writer = this.runs.openWriter(id);
		try {
			requireResumable(writer.record());
			if (this.approvals != null && writer.record().reason() == RunEvent.Reason.PROCESS_LOST) {
				this.approvals.restore(id, "");
			}
			if (!writer.record().spec().equals(spec)) {
				throw failure(Code.IDENTITY_DRIFT, "Run spec changed during resume", null);
			}
			endLostAttempt(writer);
			// Recheck exact state after acquiring ownership; never apply stale operator choices.
			List<ToolResolutionInput> ownedResolutions = resolutions(writer.record(), options);
			DriftGate.Difference owned = this.drift.inspect(this.agent, this.runs, writer.record(), request.baselineDigest());
			this.drift.require(owned, options.drift());
			if (owned.changed()) writer.append(new RunEvent.DriftAccepted(this.clock.instant(),
					owned.expectedDigest(), owned.actualDigest(), options.drift().operatorId(), options.drift().evidenceId()));
			for (var resolution : ownedResolutions)
				writer.append(new RunEvent.ToolResolution(this.clock.instant(), resolution.oldAttemptId(),
						resolution.toolCallKey(), resolution.decision(), resolution.operatorId(), resolution.evidenceId()));
		}
		catch (IOException | RuntimeException ex) {
			closeAfterFailure(writer, ex);
			throw ex;
		}
		return execute(writer, binding, input.toString(), null, operation);
	}

	/** Durable cancellation intent precedes the interrupt that triggers cleanup. */
	public RunRecord cancel(RunId id) throws RunStoreException {
		Active running = this.active.get(id);
		if (running != null) {
			synchronized (running) {
				RunWriter writer = running.context.writer();
				if (writer.record().state().terminal()) {
					throw new RunStoreException(RunStoreException.Code.TERMINAL, "Run already terminal");
				}
				if (!running.cancelled) {
					if (this.approvals != null) this.approvals.cancelRun(id);
					if (writer.record().state() == RunState.CREATED) {
						writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.RUNNING, RunEvent.Reason.NONE));
						writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.CANCELLED,
								RunEvent.Reason.USER_CANCELLED));
						running.cancelled = true;
						running.thread.interrupt();
						return writer.record();
					}
					writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.INTERRUPTED,
							RunEvent.Reason.USER_CANCELLED));
					running.cancelled = true;
					if (running.suspended) {
						writer.append(new RunEvent.AttemptEnded(this.clock.instant(), running.context.attemptId()));
						writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.CANCELLED,
								RunEvent.Reason.USER_CANCELLED));
						this.active.remove(id, running);
						writer.close();
					}
					else {
						running.thread.interrupt();
					}
				}
				return writer.record();
			}
		}
		try (RunWriter writer = this.runs.openWriter(id)) {
			if (writer.record().state().terminal()) {
				throw new RunStoreException(RunStoreException.Code.TERMINAL, "Run already terminal");
			}
			endLostAttempt(writer);
			if (writer.record().state() == RunState.CREATED) {
				writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.RUNNING, RunEvent.Reason.NONE));
			}
			return writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.CANCELLED,
					RunEvent.Reason.USER_CANCELLED));
		}
	}

	/** Resolve the retained old attempt only; explicit resume is a separate operation. */
	public RunRecord resolveApproval(ApprovalRequest request, ApprovalDecision decision) throws RunStoreException {
		Active running = this.active.get(request.runId());
		if (running == null || this.approvals == null) {
			throw new RunStoreException(RunStoreException.Code.INVALID_TRANSITION, "No deferred approval owner");
		}
		synchronized (running) {
			RunWriter writer = running.context.writer();
			if (!running.suspended || writer.record().state() != RunState.WAITING_APPROVAL
					|| !running.context.attemptId().equals(request.attemptId())
					|| !this.approvals.pending().contains(request)
					|| decision == ApprovalDecision.DEFERRED) {
				throw new RunStoreException(RunStoreException.Code.INVALID_TRANSITION, "Approval identity/state mismatch");
			}
			if (decision == ApprovalDecision.CANCELLED) return cancel(request.runId());
			if (!this.approvals.resolveDeferred(request, decision)) {
				throw new RunStoreException(RunStoreException.Code.INVALID_TRANSITION, "Approval already resolved");
			}
			writer.append(new RunEvent.AttemptEnded(this.clock.instant(), running.context.attemptId()));
			RunRecord result = writer.append(new RunEvent.StateChanged(this.clock.instant(),
					RunState.INTERRUPTED, RunEvent.Reason.NONE));
			this.active.remove(request.runId(), running);
			writer.close();
			return result;
		}
	}
	/** Release only an exact current writer; no user decision or historical scan. */
	public void abandon(RunId id) throws RunStoreException {
		Active running = this.active.get(id);
		if (running == null) return;
		synchronized (running) {
			running.abandoned = true;
			if (this.approvals != null) this.approvals.abandonRun(id, new Stop(Stop.Kind.INTERRUPTED));
			if (running.suspended) {
				this.active.remove(id, running);
				running.context.writer().close();
			}
			else running.thread.interrupt();
		}
	}

	private Result execute(RunWriter writer, ModelBinding binding, String input,
			ModelSelection creationSelection) throws IOException {
		return execute(writer, binding, input, creationSelection, null);
	}
	private Result execute(RunWriter writer, ModelBinding binding, String input,
			ModelSelection creationSelection, java.util.function.Consumer<RunContext> operation) throws IOException {
		RunSpec spec = writer.record().spec();
		String attempt = UUID.randomUUID().toString();
		TurnContext turn = new TurnContext(spec.conversationId(), UUID.randomUUID().toString(), Path.of(spec.cwd()),
				binding, spec.runId().value(), attempt);
		RunContext context = new RunContext(spec, attempt, writer, turn, this.clock);
		Active running = new Active(context);
		boolean retained = false;
		boolean restoreInterrupt = false;
		com.example.miniagent.core.change.StagedWorkspace stage = null;
		try {
			if (this.active.putIfAbsent(spec.runId(), running) != null)
				throw new RunStoreException(RunStoreException.Code.RUN_LOCKED, "Executor already owns run");
			if (creationSelection != null) {
				this.created.accept(writer.record());
				synchronized (running) {
					if (running.cancelled) return new Result(writer.record(), null);
					String baseline = this.requests.evidence().put(this.drift.capture(this.agent, spec));
					this.requests.save(spec, input, creationSelection, baseline);
					this.selections.save(spec.conversationId(), creationSelection);
				}
			}
			synchronized (running) {
				if (running.cancelled) return new Result(writer.record(), null);
				var difference = this.drift.inspect(this.agent, this.runs, writer.record(),
						this.requests.load(spec).baselineDigest());
				this.drift.require(difference, null);
				writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.RUNNING, RunEvent.Reason.NONE));
				writer.append(new RunEvent.AttemptStarted(this.clock.instant(), attempt, binding.bindingId()));
				String snapshot = this.requests.evidence().put(this.drift.capture(this.agent, spec));
				ToolJournalInterceptor.attach(context, this.drift, this.requests.evidence(), this.runs, snapshot);
			}
			Agent.AgentReply reply = null;
			RuntimeException error = null;
			try {
				if (running.cancelled) throw new Stop(Stop.Kind.CANCELLED);
				if (spec.childId() == null) context.reconcileChildUsage(this.runs);
				stage = com.example.miniagent.core.change.StagedWorkspace.open(
						this.changes == null ? new com.example.miniagent.core.change.FileChangeStore(
								this.requests.evidence().home(), turn.cwd()) : this.changes.get(), turn, this.clock);
				if (this.staging != null) this.staging.capture(stage);
				try (var stagedScope = stage.openScope(); RunContext.Scope runScope = context.open();
						TurnContext.Scope turnScope = turn.open()) {
					if (Thread.currentThread().isInterrupted()) {
						throw new Stop(Stop.Kind.INTERRUPTED);
					}
					if (operation == null) reply = this.agent.run(input, turn);
					else operation.accept(context);
				}
			}
			catch (IOException ex) {
				if (!running.cancelled) throw ex;
				error = new Stop(Stop.Kind.CANCELLED);
			}
			catch (RuntimeException ex) { error = ex; }
			for (Throwable cause = error; cause != null; cause = cause.getCause()) {
				if (cause instanceof RunStoreException storeFailure) {
					throw storeFailure;
				}
			}
			synchronized (running) {
				// Cancellation interrupts model work, not durable stage/journal settlement.
				// The cancel lock ensures its interrupt cannot race this drain.
				restoreInterrupt = Thread.interrupted() || interrupted(error);
				if (stage != null) stage.close();
				recordFinalUsage(context, reply);
				Stop signal = stopCause(error);
				if (!running.cancelled && !running.abandoned && signal != null && signal.kind() == Stop.Kind.CANCELLED) {
					if (this.approvals != null) this.approvals.cancelRun(spec.runId());
					writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.INTERRUPTED,
							RunEvent.Reason.USER_CANCELLED));
					running.cancelled = true;
				}
				if (!running.cancelled && !running.abandoned && signal != null && signal.kind() == Stop.Kind.DEFERRED) {
					writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.WAITING_APPROVAL,
							RunEvent.Reason.NONE));
					running.suspended = true;
					retained = true; // Keep writer lease: closing would mean PROCESS_LOST.
					return new Result(writer.record(), null);
				}
				writer.append(new RunEvent.AttemptEnded(this.clock.instant(), attempt));
				RunState state;
				RunEvent.Reason reason;
				if (running.cancelled) {
					state = RunState.CANCELLED;
					reason = RunEvent.Reason.USER_CANCELLED;
				}
				else if (running.abandoned) {
					state = RunState.INTERRUPTED;
					reason = RunEvent.Reason.PROCESS_LOST;
				}
				else if (signal != null || restoreInterrupt) {
					state = RunState.INTERRUPTED;
					reason = signal != null && signal.kind() == Stop.Kind.BUDGET_EXHAUSTED
							? RunEvent.Reason.BUDGET_EXHAUSTED : RunEvent.Reason.NONE;
				}
				else if (hasUnfinishedChildren(spec)) {
					// A Pending delegate tool result can be journaled successfully and followed by
					// a final model response. Neither completes the outstanding durable child work.
					state = RunState.INTERRUPTED;
					reason = error == null ? RunEvent.Reason.NONE : RunEvent.Reason.ERROR;
				}
				else if (error != null && !writer.record().uncertainTools().isEmpty()) {
					state = RunState.INTERRUPTED;
					reason = RunEvent.Reason.ERROR;
				}
				else if (error != null) {
					state = RunState.FAILED;
					reason = RunEvent.Reason.ERROR;
				}
				else if (!writer.record().uncertainTools().isEmpty()) {
					state = RunState.INTERRUPTED;
					reason = RunEvent.Reason.NONE;
				}
				else {
					state = RunState.COMPLETED;
					reason = RunEvent.Reason.NONE;
				}
				// cancel already persisted INTERRUPTED; do not append that transition twice.
				if (writer.record().state() != state) {
					writer.append(new RunEvent.StateChanged(this.clock.instant(), state, reason));
				}
				if (error != null && state == RunState.FAILED) {
					throw error;
				}
				return new Result(writer.record(), state == RunState.COMPLETED ? reply : null);
			}
		}
		catch (IOException | RuntimeException | Error ex) {
			this.active.remove(spec.runId(), running);
			if (this.approvals != null) this.approvals.abandonRun(spec.runId(), ex);
			closeAfterFailure(writer, ex);
			throw ex;
		}
		finally {
			try {
				restoreInterrupt |= Thread.interrupted();
				if (stage != null) {
					if (this.staging != null) this.staging.release(stage);
					stage.close();
				}
			}
			finally {
				ToolJournalInterceptor.detach(context);
				if (!retained && this.active.remove(spec.runId(), running)) {
					writer.close();
				}
				try {
					if (writer.record().attempts().stream().anyMatch(a -> a.attemptId().equals(attempt)))
						this.observed.accept(context);
				}
				finally { if (restoreInterrupt) Thread.currentThread().interrupt(); }
			}
		}
	}

	private boolean hasUnfinishedChildren(RunSpec parent) throws RunStoreException {
		if (parent.childId() != null) return false;
		for (RunId id : this.runs.list()) {
			if (id.equals(parent.runId())) continue;
			RunRecord child = this.runs.read(id);
			if (parent.runId().equals(child.spec().parentRunId()) && !child.state().terminal()) return true;
		}
		return false;
	}

	private void recordFinalUsage(RunContext context, Agent.AgentReply reply) throws RunStoreException {
		if (!context.roundsRecorded()) {
			Usage usage = reply == null ? null : reply.usage();
			Integer total = usage == null ? null : usage.getTotalTokens();
			boolean unknown = total == null || total < 0 || usage instanceof EmptyUsage;
			context.recordUsage(RunEvent.UsageKind.MODEL_ROUND, "loop-total",
					new RunUsage(unknown ? 0 : total, unknown, 0, 0, 0));
		}
		context.finishActiveTime();
	}

	private void endLostAttempt(RunWriter writer) throws RunStoreException {
		List<Attempt> attempts = writer.record().attempts();
		if (!attempts.isEmpty() && attempts.getLast().endedAt() == null) {
			String id = attempts.getLast().attemptId();
			writer.append(new RunEvent.UsageRecorded(this.clock.instant(), id, RunEvent.UsageKind.MODEL_ROUND,
					"executor-lost-" + writer.record().sequence(), new RunUsage(0, true, 0, 0, 0)));
			writer.append(new RunEvent.AttemptEnded(this.clock.instant(), id));
		}
	}

	private static void requireResumable(RunRecord record) throws Failure {
		if (record.state().terminal()) {
			throw failure(Code.TERMINAL, "Terminal run cannot resume", null);
		}
		if (record.state() != RunState.INTERRUPTED) {
			throw failure(Code.NOT_RESUMABLE, "Only an interrupted run can explicitly resume", null);
		}
	}

	private List<ToolResolutionInput> resolutions(RunRecord record, ResumeOptions options) throws IOException {
		List<ToolResolutionInput> result = new java.util.ArrayList<>();
		var supplied = new java.util.HashMap<String, ToolResolutionInput>();
		for (var input : options.resolutions()) {
			if (supplied.put(input.oldAttemptId() + ":" + input.toolCallKey(), input) != null)
				throw failure(Code.UNKNOWN_EFFECTS, "Duplicate operator action", null);
		}
		for (var tool : record.uncertainTools()) {
			String key = tool.attemptId() + ":" + tool.toolCallKey();
			var input = supplied.remove(key);
			boolean read = this.drift.authoritativeRead(tool, record.spec());
			if (input == null && read) input = new ToolResolutionInput(tool.attemptId(), tool.toolCallKey(),
					RunEvent.Resolution.READ_RETRY, "trusted-policy", tool.snapshotDigest());
			if (input == null || input.decision() == RunEvent.Resolution.READ_RETRY && !read)
				throw new Failure(Code.UNKNOWN_EFFECTS, "Explicit journal resolution is required", null, record.uncertainTools());
			result.add(input);
		}
		if (!supplied.isEmpty()) throw failure(Code.UNKNOWN_EFFECTS, "Operator action has no UNKNOWN key", null);
		return List.copyOf(result);
	}

	private ModelBinding bind(ModelSelection selection) throws Failure {
		try {
			return this.registry.bind(selection);
		}
		catch (ModelUnavailableException ex) {
			throw failure(Code.MODEL_UNAVAILABLE, "Run model unavailable", ex);
		}
	}

	private static Path workspace(Path cwd) throws IOException {
		Path real;
		try {
			real = cwd.toRealPath();
		}
		catch (IOException ex) {
			throw failure(Code.IDENTITY_DRIFT, "Run cwd cannot be resolved", ex);
		}
		if (!Files.isDirectory(real)) {
			throw failure(Code.IDENTITY_DRIFT, "Run cwd is not a directory", null);
		}
		return real;
	}

	/** Canonical ordered, length-framed public tool-definition snapshot. */
	private String toolsFingerprint() {
		var bytes = new java.io.ByteArrayOutputStream();
		this.agent.toolDefinitions().stream().sorted(Comparator.comparing(
				org.springframework.ai.tool.definition.ToolDefinition::name)).forEach(definition -> {
			for (String value : List.of(definition.name(), definition.description(), definition.inputSchema())) {
				byte[] field = value.getBytes(UTF_8);
				bytes.writeBytes(ByteBuffer.allocate(4).putInt(field.length).array());
				bytes.writeBytes(field);
			}
		});
		return RunRequestStore.digest(bytes.toByteArray());
	}

	private static Stop stopCause(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof Stop stop) {
				return stop;
			}
		}
		return null;
	}

	private static boolean interrupted(Throwable error) {
		if (Thread.currentThread().isInterrupted()) {
			return true;
		}
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof InterruptedException) {
				return true;
			}
		}
		return false;
	}

	private static Failure failure(Code code, String message, Throwable cause) {
		return new Failure(code, message, cause, List.of());
	}

	private static void closeAfterFailure(RunWriter writer, Throwable failure) {
		try {
			writer.close();
		}
		catch (RunStoreException cleanup) {
			failure.addSuppressed(cleanup);
		}
	}

	private static final class Active {
		private final RunContext context;
		private final Thread thread = Thread.currentThread();
		private volatile boolean cancelled;
		private volatile boolean abandoned;
		private boolean suspended;

		private Active(RunContext context) {
			this.context = context;
		}
	}
}
