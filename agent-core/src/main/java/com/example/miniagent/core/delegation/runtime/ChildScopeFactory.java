package com.example.miniagent.core.delegation.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.change.FileChangeStore;
import com.example.miniagent.core.delegation.ChildResult;
import com.example.miniagent.core.delegation.ChildRole;
import com.example.miniagent.core.delegation.ChildSpec;
import com.example.miniagent.core.delegation.DelegationEvidenceCollector;
import com.example.miniagent.core.delegation.McpToolSnapshot;
import com.example.miniagent.core.delegation.RolePolicy;
import com.example.miniagent.core.delegation.ToolScope;
import com.example.miniagent.core.delegation.TrustedMailboxTool;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.execution.StagingToolInterceptor;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.model.registry.ModelRegistry;
import com.example.miniagent.core.run.*;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Independent assembly around the existing durable executor and official advisor loop. */
public final class ChildScopeFactory {
	@FunctionalInterface
	public interface AdvisorContribution {
		List<Advisor> create(ChatModel childModel, Supplier<TurnContext> authority);
	}
	public record Metadata(String childId, ChildRole role, ModelRef model, ToolScope scope,
			String parentConversationId, String parentRunId) {}

	/** Scheduler-owned reservation checks and cumulative observations, not another meter. */
	public interface Boundaries {
		default void created(Metadata child, RunRecord record) {}
		default void bound(Metadata child, RunContext context) {}
		default void beforeModel(Metadata child, RunContext context) {}
		default void beforeTool(Metadata child, RunContext context) {}
		/** Newly persisted delta; callId is the durable idempotency key, not a loop total. */
		default void usage(Metadata child, RunContext context, RunEvent.UsageRecorded delta) {}
		/** Read context.writer().record() for sequence and cumulative usage; never sum it twice. */
		default void observed(Metadata child, RunContext context) {}
		default void invoked(Metadata child, RunContext context,
				com.example.miniagent.core.change.StagedWorkspace stage, ToolInterceptor.ToolInvocation invocation,
				RunEvent.ToolStarted started, RunEvent.ToolFinished finished) {}
	}
	public record Dependency(ChildSpec spec, ChildResult result, DelegationEvidenceCollector.Request evidence) {}

	private final Agent parentAgent;
	private final Path home;
	private final RunStore runs;
	private final RunRequestStore requests;
	private final SessionModelStore selections;
	private final Clock clock;
	private final ApprovalBroker approvals;
	private final List<ToolCallback> callbacks;
	private final List<ToolCallbackProvider> providers;
	private final List<ToolInterceptor> interceptors;
	private final List<StagingToolInterceptor.Policy> stagePolicies;
	private final List<SideEffectClass.Policy> readPolicies;
	private final List<AdvisorContribution> advisors;
	RunStore runStore() { return this.runs; }

	public ChildScopeFactory(Agent parentAgent, Path home, RunStore runs, RunRequestStore requests,
			SessionModelStore selections, Clock clock, ApprovalBroker approvals,
			List<ToolCallback> callbacks, List<ToolCallbackProvider> providers, List<ToolInterceptor> interceptors,
			List<StagingToolInterceptor.Policy> stagePolicies, List<SideEffectClass.Policy> readPolicies,
			List<AdvisorContribution> advisors) {
		this.parentAgent = Objects.requireNonNull(parentAgent);
		this.home = home.toAbsolutePath().normalize();
		this.runs = Objects.requireNonNull(runs);
		this.requests = Objects.requireNonNull(requests);
		this.selections = Objects.requireNonNull(selections);
		this.clock = Objects.requireNonNull(clock);
		this.approvals = Objects.requireNonNull(approvals);
		this.callbacks = List.copyOf(callbacks);
		this.providers = providers.stream().map(provider -> {
			if (provider instanceof org.springframework.ai.tool.method.MethodToolCallbackProvider) {
				// Static annotated methods recreate wrappers; retain their executable identities.
				ToolCallback[] methods = provider.getToolCallbacks();
				return (ToolCallbackProvider) () -> methods.clone();
			}
			return provider;
		}).toList();
		this.interceptors = interceptors.stream().sorted(java.util.Comparator.comparingInt(ToolInterceptor::getOrder)).toList();
		this.stagePolicies = List.copyOf(stagePolicies);
		this.readPolicies = List.copyOf(readPolicies);
		this.advisors = List.copyOf(advisors);
	}

	public Scope create(TurnContext parent, ChildSpec child, ToolScope parentScope) {
		return create(parent, child, parentScope, new Boundaries() {});
	}

	public Scope create(TurnContext parent, ChildSpec child, ToolScope parentScope, Boundaries boundaries) {
		return create(parent, child, parentScope, boundaries, List.of());
	}

	public Scope create(TurnContext parent, ChildSpec child, ToolScope parentScope, Boundaries boundaries,
			List<Dependency> dependencies) {
		requireCapturedStoredParent(parent);
		new RolePolicy().validateScope(child.role(), child.scope(), parentScope);
		return new Scope(parent, child, Objects.requireNonNull(boundaries), dependencies);
	}

	/** Read-only proposal inspection never grants a child dispatch or parent accounting lease. */
	public com.example.miniagent.core.run.DriftGate.Difference inspectDrift(TurnContext parent,
			ChildSpec child, ToolScope parentScope, RunId childRun) throws IOException {
		new RolePolicy().validateScope(child.role(), child.scope(), parentScope);
		try (var scope = new Scope(parent, child, new Boundaries() {}, List.of())) {
			return scope.executor().inspectDrift(childRun);
		}
	}

	/** A real stored lineage cannot fall back to the unowned/legacy ledger path. */
	void requireCapturedStoredParent(TurnContext parent) {
		if (parent.runId() == null) return;
		try {
			var stored = storedParent(new RunId(parent.runId()));
			if (stored.isEmpty()) return;
			var record = stored.get();
			var owner = RunContext.current().filter(context -> context.turnContext() == parent).orElseThrow(
					() -> new IllegalArgumentException("Stored parent requires exact captured RunContext"));
			if (!owner.spec().equals(record.spec())) throw new IllegalArgumentException("Foreign stored parent");
			owner.requireActiveParent();
		}
		catch (RunStoreException ex) { throw new java.io.UncheckedIOException(ex); }
	}

	private Optional<RunRecord> storedParent(RunId id) throws RunStoreException {
		try { return Optional.of(this.runs.read(id)); }
		catch (RunStoreException ex) {
			if (ex.code() == RunStoreException.Code.NOT_FOUND) return Optional.empty();
			throw ex;
		}
	}

	/** Reopen the actual nonterminal parent with its stored binding; a child is never budget authority. */
	<T> T recoverParent(RunId id, ChildSpec child, String originalConversation, ToolScope authority,
			java.util.concurrent.Callable<T> recovery) throws IOException {
		var stored = this.runs.read(id).spec();
		if (stored.parentRunId() == null || stored.childId() == null) throw new IOException("Stored child lineage required");
		var parent = this.runs.read(stored.parentRunId());
		if (parent.spec().childId() != null || parent.spec().parentRunId() != null || parent.state().terminal()
				|| !parent.spec().parentConversationId().equals(originalConversation)
				|| !parent.spec().conversationId().equals(stored.parentConversationId())
				|| !parent.spec().cwd().equals(stored.cwd()) || !stored.childId().equals(child.childId())
				|| !stored.model().equals(child.effectiveModel(parent.spec().model()))
				|| !stored.budget().equals(child.budget()) || !requests.load(stored).goal().equals(child.task()))
			throw new IOException("Exact resumable parent/child authority required");
		new RolePolicy().validateScope(child.role(), child.scope(), authority);
		var selected = selections.load(parent.spec().conversationId())
				.orElseThrow(() -> new IOException("Missing stored parent selection"));
		// Reuse the actual parent assembly: reconstructing a default gate would lose Spring's
		// permission/read-policy/configuration fingerprint and staging suppliers.
		var executor = RunExecutor.forAgent(parentAgent)
				.orElseThrow(() -> new IOException("Trusted parent executor assembly is unavailable"));
		var failure = new java.util.concurrent.atomic.AtomicReference<Exception>();
		var result = executor.resumeOperation(parent.spec().runId(), selected, Path.of(parent.spec().cwd()),
				RunExecutor.ResumeOptions.ordinary(), context -> {
					try { return recovery.call(); }
					catch (Exception ex) {
						failure.set(ex);
						throw new Stop(Stop.Kind.INTERRUPTED);
					}
				});
		if (failure.get() instanceof IOException ex) throw ex;
		if (failure.get() instanceof RuntimeException ex) throw ex;
		if (failure.get() != null) throw new IOException("Child recovery failed", failure.get());
		if (result.value() == null) throw new IOException("Parent stopped before child recovery");
		return result.value();
	}
	/** Exact persisted lineage reconstruction, without creating or pretending to bind a parent run. */
	public Scope reconstruct(RunId id, ChildSpec child, String originalConversation, ToolScope authority)
			throws IOException {
		var stored = this.runs.read(id);
		var spec = stored.spec();
		if (spec.childId() == null || spec.parentRunId() == null)
			throw new IOException("Stored child lineage required");
		var parent = this.runs.read(spec.parentRunId()).spec();
		var owner = RunContext.current().orElseThrow(
				() -> new IOException("Child recovery requires an active captured parent"));
		if (!owner.spec().equals(parent)) throw new IOException("Foreign captured recovery parent");
		owner.requireActiveParent();
		if (parent.childId() != null || !parent.conversationId().equals(spec.parentConversationId())
				|| !parent.parentConversationId().equals(originalConversation)
				|| !parent.cwd().equals(spec.cwd()) || !child.childId().equals(spec.childId())
				|| !child.effectiveModel(parent.model()).equals(spec.model()) || !child.budget().equals(spec.budget()))
			throw new IOException("Foreign child/original conversation/spec");
		new RolePolicy().validateScope(child.role(), child.scope(), authority);
		var metadata = new Metadata(child.childId(), child.role(), spec.model(), child.scope(),
			spec.parentConversationId(), spec.parentRunId().value());
		var scope = new Scope(Path.of(spec.cwd()), child, metadata, new Boundaries() {}, List.of(),
			selections.load(spec.conversationId()).orElseThrow(() -> new IOException("Missing child selection")), null);
		scope.record = stored;
		return scope;
	}

	public final class Scope implements AutoCloseable {
		private final ChildSpec child;
		private final Metadata metadata;
		private final Path cwd;
		private final ModelSelection selection;
		private final McpToolSnapshot snapshot;
		private final List<ToolCallback> frozen;
		private final AtomicBoolean started = new AtomicBoolean();
		private volatile boolean closed;
		private volatile TurnContext context;
		private volatile RunContext runContext;
		private volatile RunRecord record;
		private final AtomicBoolean cancellation = new AtomicBoolean();
		private volatile Thread dispatch;
		private final RunExecutor executor;
		private final Boundaries boundaries;
		private final RunContext parentOwner;
		private final List<Dependency> dependencies;
		private final List<DelegationEvidenceCollector.ReadInput> reads = new ArrayList<>();
		private com.example.miniagent.core.change.StagedWorkspace stage;
		private String sourceRevision, configuration;
		private com.example.miniagent.core.change.VerificationRunner.Request verificationRequest;
		private com.example.miniagent.core.change.VerificationRunner.Result verification;
		private final java.util.ArrayDeque<RunEvent.UsageRecorded> pendingUsage = new java.util.ArrayDeque<>();

		private Scope(TurnContext parent, ChildSpec child, Boundaries boundaries, List<Dependency> dependencies) {
			this(parent.cwd(), child, new Metadata(child.childId(), child.role(), child.effectiveModel(parent),
				child.scope(), parent.sessionId(), parent.runId()), boundaries, dependencies, selection(parent, child), null);
		}
		private Scope(Path source, ChildSpec child, Metadata metadata, Boundaries boundaries, List<Dependency> dependencies) {
			this(source, child, metadata, boundaries, dependencies, new ModelSelection(metadata.model()), null);
		}
		private Scope(Path source, ChildSpec child, Metadata metadata, Boundaries boundaries, List<Dependency> dependencies,
				ModelSelection selection, McpToolSnapshot frozenSnapshot) {
			this.child = child;
			this.boundaries = boundaries;
			this.dependencies = List.copyOf(dependencies);
			try { this.cwd = source.toRealPath(); }
			catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
			this.selection = selection;
			this.metadata = metadata;
			this.parentOwner = RunContext.current().filter(owner ->
				owner.spec().runId().value().equals(metadata.parentRunId())
					&& owner.spec().conversationId().equals(metadata.parentConversationId())).orElse(null);
			if (this.parentOwner != null) this.parentOwner.requireActiveParent();
			this.snapshot = frozenSnapshot == null ? McpToolSnapshot.capture(callbacks, providers, child.scope().toolNames())
					: frozenSnapshot;
			var declarations = new java.util.HashMap<String, StagingToolInterceptor.Declaration>();
			stagePolicies.forEach(policy -> policy.declarations().forEach((name, declaration) -> {
				if (declarations.putIfAbsent(name, declaration) != null)
					throw new RolePolicy.Denied(RolePolicy.Code.INVALID_CLASSIFICATION, name);
			}));
			var scopeGate = new ScopedToolInterceptor(this, this.snapshot, declarations);
			StagingToolInterceptor staging = new StagingToolInterceptor(() -> stagePolicies);
			var localInterceptors = interceptors.stream().map(interceptor ->
				interceptor instanceof StagingToolInterceptor ? staging : interceptor).toList();
			this.frozen = this.snapshot.entries().values().stream().map(entry -> (ToolCallback) new ToolCallback() {
				@Override public ToolDefinition getToolDefinition() { return entry.definition(); }
				@Override public ToolMetadata getToolMetadata() { return entry.metadata(); }
				@Override public String call(String input) { throw new RolePolicy.Denied(
					RolePolicy.Code.SCOPE_WIDENING, "Child callback requires explicit context"); }
				@Override public String call(String input, org.springframework.ai.chat.model.ToolContext tools) {
					return scopeGate.intercept(new ToolInterceptor.ToolInvocation(entry.definition(), input, tools),
						invocation -> {
							boundaries.beforeTool(metadata, runContext());
							if (parentOwner != null) {
								parentOwner.requireActiveParent();
								parentOwner.beforeModel();
								if (parentOwner.usage().toolCalls() >= parentOwner.spec().budget().toolCallCap())
									throw new Stop(Stop.Kind.BUDGET_EXHAUSTED);
							}
							try {
								long before = runContext().writer().record().sequence();
								ToolCallback recording = new ToolCallback() {
									@Override public ToolDefinition getToolDefinition() { return entry.definition(); }
									@Override public ToolMetadata getToolMetadata() { return entry.metadata(); }
									@Override public String call(String input) { throw new IllegalStateException("Context required"); }
									@Override public String call(String input, org.springframework.ai.chat.model.ToolContext tools) {
										String output = entry.callback().call(input, tools);
										if (declarations.get(entry.definition().name()).kind() == StagingToolInterceptor.Kind.FILE_WRITE) {
											try { stage.record(); }
											catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
										}
										return output;
									}
								};
								var invocationInterceptors = entry.callback() instanceof TrustedMailboxTool
									? localInterceptors.stream().filter(i -> !(i instanceof StagingToolInterceptor)).toList()
									: localInterceptors;
								String output = InterceptingToolCallback.wrap(recording, invocationInterceptors)
									.call(invocation.input(), invocation.context());
								captureInvocation(invocation, before);
								return output;
							}
							finally { observeUsage(); }
						});
				}
			}).toList();
			ModelRegistry registry = parentAgent.modelRegistry().scoped(new AgentChatClientFactory() {
				@Override public ChatModel modelBoundary(ChatModel model) { return new BoundaryModel(model); }
				@Override public ChatClient create(ChatModel model) {
					List<Advisor> scopedAdvisors = new ArrayList<>();
					advisors.forEach(contribution -> scopedAdvisors.addAll(contribution.create(model, Scope.this::context)));
					return new AgentChatClientFactory.ScopedAssembly(parentAgent.systemPrompt(), frozen,
						scopedAdvisors, parentAgent.toolCallingAdvisorOrder()).create(new ScopeModel(model));
				}
			});
			Agent childAgent = new Agent(registry, selections, this.selection, new AgentSession(), this.cwd,
				parentAgent.systemPrompt(), this.frozen, List.of(), List.of(), List.of(), parentAgent.toolCallingAdvisorOrder())
				.capturedBy(this::capture);
			DriftGate drift = new DriftGate(requests.evidence(), readPolicies,
				() -> fingerprint(this.metadata));
			// Parent interceptors retain budget/permission/journal; the stage gate is local to this executor.
			RunStore observedRuns = new RunStore() {
				@Override public RunWriter create(RunSpec spec) throws RunStoreException { return observeWriter(runs.create(spec)); }
				@Override public RunWriter openWriter(RunId id) throws RunStoreException { return observeWriter(runs.openWriter(id)); }
				@Override public RunRecord read(RunId id) throws RunStoreException { return runs.read(id); }
				@Override public List<RunId> list() throws RunStoreException { return runs.list(); }
				@Override public OpaqueRun export(RunId id) throws RunStoreException { return runs.export(id); }
			};
			this.executor = new RunExecutor(childAgent, registry, selections, observedRuns, requests, clock,
				List.of(), approvals, drift).created(this::created).observed(this::observeUsage)
				.childResumeAuthority(this::owns).staging(staging, () -> {
					try { return new FileChangeStore(home, this.cwd); }
					catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
				});
		}
		private void created(RunRecord created) {
			this.record = created;
			this.boundaries.created(this.metadata, created);
			if (this.cancellation.get()) {
				try { cancel(); }
				catch (RunStoreException ex) { throw new java.io.UncheckedIOException(ex); }
			}
		}
		public Metadata metadata() { return this.metadata; }
		/** Scope remains single-use; this copies immutable assembly authority, not a terminal writer. */
		Scope continuation(ChildSpec remaining, Boundaries boundaries) {
			if (this.child.role() == ChildRole.IMPLEMENT)
				throw new IllegalStateException("IMPLEMENT continuation requires trusted staged proposal restore; unsupported");
			if (this.record == null || this.record.state() != RunState.COMPLETED
					|| !this.record.uncertainTools().isEmpty() || this.cancellation.get())
				throw new IllegalStateException("Only a completed certain child conversation can continue");
			if (!remaining.childId().equals(this.child.childId()) || remaining.role() != this.child.role()
					|| !remaining.scope().equals(this.child.scope()) || !remaining.task().equals(this.child.task())
					|| !remaining.effectiveModel(this.metadata.model()).equals(this.metadata.model()))
				throw new IllegalArgumentException("Frozen child authority changed");
			this.snapshot.requireCurrent();
			return new Scope(this.cwd, remaining, this.metadata, boundaries, this.dependencies, this.selection, this.snapshot);
		}
		RunExecutor.Result followUp(String body, String conversationId) throws IOException {
			requireOpen();
			if (!this.started.compareAndSet(false, true)) throw new IllegalStateException("Scope already called");
			this.parentOwner.requireActiveParent();
			this.parentOwner.beforeModel();
			this.dispatch = Thread.currentThread();
			try {
				return this.executor.startConversation(body, this.metadata.parentConversationId(),
					new RunId(this.metadata.parentRunId()), this.metadata.childId(), this.selection, this.cwd,
					this.child.budget(), conversationId);
			}
			finally { if (this.runContext != null) observeUsage(); }
		}
		public List<Dependency> dependencies() { return this.dependencies; }
		private void captureInvocation(ToolInterceptor.ToolInvocation invocation, long before) {
			try {
				var events = FileRunStore.events(runs, runContext().spec().runId());
				// Sequence is one-based and contiguous. Select only this actual invocation's new pair.
				var fresh = events.subList(Math.toIntExact(before), events.size());
				var start = fresh.stream().filter(RunEvent.ToolStarted.class::isInstance)
					.map(RunEvent.ToolStarted.class::cast).filter(e -> e.attemptId().equals(runContext().attemptId())
						&& e.toolName().equals(invocation.toolName())).findFirst().orElseThrow();
				var finish = fresh.stream().filter(RunEvent.ToolFinished.class::isInstance)
					.map(RunEvent.ToolFinished.class::cast).filter(e -> e.toolCallKey().equals(start.toolCallKey())
						&& e.attemptId().equals(start.attemptId())).findFirst().orElseThrow();
				if (!start.argsDigest().equals(com.example.miniagent.core.change.Fingerprinter.sha256(invocation.input()
						.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) throw new IOException("Invocation arguments mismatch");
				if (invocation.toolName().equals("read_file"))
					this.reads.add(new DelegationEvidenceCollector.ReadInput(start.toolCallKey(), invocation.input()));
				this.boundaries.invoked(this.metadata, runContext(), this.stage, invocation, start, finish);
			}
			catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
		}
		void verified(com.example.miniagent.core.change.VerificationRunner.Request request,
				com.example.miniagent.core.change.VerificationRunner.Result result, String configuration) {
			this.verificationRequest = request;
			this.verification = result;
			this.sourceRevision = request.sourceRevision();
			this.configuration = configuration;
		}
		public DelegationEvidenceCollector.Request evidenceRequest(ChildSpec original) throws IOException {
			if (this.stage == null) throw new IOException("No captured stage");
			String revision = this.sourceRevision;
			if (original.role() == ChildRole.IMPLEMENT) {
				var tree = new java.util.TreeMap<>(new com.example.miniagent.core.change.Fingerprinter(this.stage.policy()).snapshot());
				var live = new com.example.miniagent.core.change.PathPolicy(this.cwd, home, List.of());
				new com.example.miniagent.core.change.Fingerprinter(live).snapshot().forEach((p, f) -> {
					if (f.kind() == com.example.miniagent.core.change.EntryKind.SYMBOLIC_LINK) tree.put(p, f);
				});
				revision = com.example.miniagent.core.change.Fingerprinter.treeDigest(tree);
			}
			return new DelegationEvidenceCollector.Request(original, this.stage.owner(),
				new RunId(this.metadata.parentRunId()), java.util.UUID.fromString(this.metadata.parentConversationId()),
				revision, this.configuration, this.reads, this.stage.policy(), this.verificationRequest, this.verification);
		}
		public TurnContext context() {
			if (this.context == null) throw new IllegalStateException("Child attempt is not bound");
			return this.context;
		}
		public RunContext runContext() {
			if (this.runContext == null) throw new IllegalStateException("Child attempt is not bound");
			return this.runContext;
		}
		public RunExecutor executor() { return this.executor; }
		public Optional<TurnContext> bound() { return Optional.ofNullable(this.context); }
		public Optional<RunRecord> record() { return Optional.ofNullable(this.record); }
		public List<com.example.miniagent.core.approval.ApprovalRequest> pendingApprovals() {
			RunRecord owner = this.record;
			return owner == null ? List.of() : approvals.pending().stream()
				.filter(request -> request.runId().equals(owner.spec().runId())).toList();
		}
		public void cancel() throws RunStoreException {
			this.cancellation.set(true);
			RunRecord owner = this.record;
			if (owner != null && !owner.state().terminal()) {
				try { this.executor.cancel(owner.spec().runId()); }
				catch (RunStoreException ex) {
					if (ex.code() != RunStoreException.Code.TERMINAL) throw ex;
				}
			}
			else if (owner == null && this.dispatch != null) this.dispatch.interrupt();
		}
		public void abandon() {
			try {
				if (this.record != null) this.executor.abandon(this.record.spec().runId());
				else if (this.dispatch != null) this.dispatch.interrupt();
			}
			catch (RunStoreException ex) { throw new java.io.UncheckedIOException(ex); }
		}
		public RunExecutor.Result resume(RunId id, RunExecutor.ResumeOptions options) throws IOException {
			requireOpen();
			if (!this.started.compareAndSet(false, true)) throw new IllegalStateException("Scope already called");
			this.dispatch = Thread.currentThread();
			if (this.cancellation.get()) throw new Stop(Stop.Kind.CANCELLED);
			RunRecord stored = runs.read(id);
			RunSpec spec = stored.spec();
			if (this.parentOwner == null && spec.parentRunId() != null && storedParent(spec.parentRunId()).isPresent())
				throw new IOException("Managed child resume requires an active captured parent");
			if (!owns(spec, requests.load(spec).goal()))
				throw new IOException("Explicit child reconstruction differs from stored lineage/request");
			this.record = stored;
			if (this.parentOwner != null) {
				this.parentOwner.requireActiveParent();
				for (RunEvent event : FileRunStore.events(runs, id)) {
					if (event instanceof RunEvent.UsageRecorded delta)
						this.parentOwner.chargeChildUsage(runs, stored.spec(), delta);
				}
				this.parentOwner.pauseBudget();
			}
			try { return this.executor.resume(id, this.selection, this.cwd, options); }
			finally {
				try { if (this.runContext != null) observeUsage(); }
				finally { if (this.parentOwner != null) this.parentOwner.resumeBudget(); }
			}
		}
		private boolean owns(RunSpec spec, String goal) {
			return !this.closed && this.context == null
					&& Objects.equals(spec.parentConversationId(), this.metadata.parentConversationId())
					&& Objects.equals(spec.parentRunId(), this.metadata.parentRunId() == null
						? null : new RunId(this.metadata.parentRunId()))
					&& Objects.equals(spec.childId(), this.metadata.childId())
					&& spec.model().equals(this.metadata.model()) && spec.cwd().equals(this.cwd.toString())
					&& spec.budget().equals(this.child.budget()) && goal.equals(this.child.task());
		}
		public RunExecutor.Result call() throws IOException {
			requireOpen();
			if (!this.started.compareAndSet(false, true)) throw new IllegalStateException("Scope already called");
			this.dispatch = Thread.currentThread();
			if (this.cancellation.get()) throw new Stop(Stop.Kind.CANCELLED);
			try {
				return this.executor.start(this.child.task(), this.metadata.parentConversationId(),
					this.metadata.parentRunId() == null ? null : new RunId(this.metadata.parentRunId()),
					this.metadata.childId(), this.selection, this.cwd, this.child.budget());
			}
			finally {
				if (this.runContext != null) observeUsage();
			}
		}
		private synchronized void observeUsage() {
			observeUsage(runContext());
		}
		private synchronized void observeUsage(RunContext captured) {
			while (true) {
				RunEvent.UsageRecorded delta;
				synchronized (this.pendingUsage) { delta = this.pendingUsage.peekFirst(); }
				if (delta == null) break;
				if (!delta.attemptId().equals(captured.attemptId()))
					throw new IllegalStateException("Foreign child usage event");
				if (this.parentOwner != null) {
					try { this.parentOwner.chargeChildUsage(runs, captured.spec(), delta); }
					catch (RunStoreException ex) { throw new java.io.UncheckedIOException(ex); }
				}
				this.boundaries.usage(this.metadata, captured, delta);
				synchronized (this.pendingUsage) { this.pendingUsage.removeFirst(); }
			}
			this.boundaries.observed(this.metadata, captured);
		}
		/** Observe only acknowledged durable appends; storage and lifecycle still belong to RunStore. */
		private RunWriter observeWriter(RunWriter writer) {
			return new RunWriter() {
				private String ownedAttempt;
				@Override public RunRecord record() { return writer.record(); }
				@Override public RunRecord append(RunEvent event) throws RunStoreException {
					RunRecord result = writer.append(event);
					Scope.this.record = result;
					if (event instanceof RunEvent.AttemptStarted attempt) this.ownedAttempt = attempt.attemptId();
					// Resume's endLostAttempt belongs to the previous owner, not this fresh capture.
					// RunContext seeds its meter from that cumulative authoritative record.
					if (event instanceof RunEvent.UsageRecorded delta && delta.attemptId().equals(this.ownedAttempt)) {
						synchronized (pendingUsage) { pendingUsage.addLast(delta); }
					}
					return result;
				}
				@Override public void close() throws RunStoreException { writer.close(); }
			};
		}
		private synchronized Map<String, Object> capture(TurnContext turn) {
			bind(RunContext.current().orElseThrow());
			if (this.context != turn) throw new IllegalStateException("Foreign captured turn");
			return Map.of(ToolContextKeys.CHILD_SCOPE, this);
		}
		/** Capture adapter only: the caller supplies an existing durable executor context. */
		public synchronized TurnContext bind(RunContext run) {
			requireOpen();
			TurnContext turn = run.turnContext();
			if (this.context != null || turn.sessionId().equals(this.metadata.parentConversationId())
					|| !turn.binding().ref().equals(this.metadata.model())
					|| !(turn.binding().chatModel() instanceof BoundaryModel boundary) || boundary.owner() != this
					|| !Objects.equals(run.spec().parentConversationId(), this.metadata.parentConversationId())
					|| !Objects.equals(run.spec().childId(), this.metadata.childId())
					|| !Objects.equals(run.spec().parentRunId(),
							this.metadata.parentRunId() == null ? null : new RunId(this.metadata.parentRunId()))
					|| !turn.cwd().equals(this.cwd)
					|| run.writer().record().state() != RunState.RUNNING) {
				throw new IllegalStateException("Foreign or repeated child attempt");
			}
			if (!run.spec().budget().equals(this.child.budget()))
				throw new IllegalStateException("Missing durable child owner");
			com.example.miniagent.core.change.StagedWorkspace stage =
				com.example.miniagent.core.change.StagedWorkspace.current().orElseThrow();
			if (!stage.owner().sessionId().equals(turn.sessionId())
					|| !stage.owner().runId().value().equals(turn.runId())
					|| !stage.owner().attemptId().equals(turn.attemptId())
					|| !stage.owner().turnId().equals(turn.turnId()))
				throw new IllegalStateException("Foreign stage owner");
			this.context = turn;
			this.runContext = run;
			this.stage = stage;
			if (this.parentOwner != null) {
				try {
					for (RunEvent event : FileRunStore.events(runs, run.spec().runId())) {
						if (event instanceof RunEvent.UsageRecorded delta)
							this.parentOwner.chargeChildUsage(runs, run.spec(), delta);
					}
				}
				catch (RunStoreException ex) { throw new java.io.UncheckedIOException(ex); }
			}
			try {
				this.sourceRevision = com.example.miniagent.core.change.Fingerprinter.treeDigest(
					new com.example.miniagent.core.change.Fingerprinter(new com.example.miniagent.core.change.PathPolicy(
						this.cwd, home, List.of())).snapshot());
				this.configuration = requests.evidence().get(requests.load(run.spec()).baselineDigest(),
					DriftGate.Snapshot.class).configurationDigest();
			}
			catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
			this.boundaries.bound(this.metadata, run);
			return turn;
		}
		void requireOpen() {
			if (this.closed) throw new IllegalStateException("Child scope closed");
		}
		@Override public void close() { this.closed = true; }

		/** Includes compaction, which uses this captured provenance model without a tool loop. */
		private final class BoundaryModel implements ChatModel {
			private final ChatModel delegate;
			private BoundaryModel(ChatModel delegate) { this.delegate = delegate; }
			private Scope owner() { return Scope.this; }
			@Override public ChatOptions getOptions() { return this.delegate.getOptions(); }
			@Override public ChatResponse call(Prompt prompt) {
				requireOpen();
				if (runContext().writer().record().state() != RunState.RUNNING
						|| RunContext.current().orElse(null) != runContext()
						|| TurnContext.current().orElse(null) != context()) {
					throw new IllegalStateException("Model requires active captured child owner");
				}
				boundaries.beforeModel(metadata, runContext());
				if (parentOwner != null) {
					parentOwner.requireActiveParent();
					parentOwner.beforeModel();
				}
				try { return this.delegate.call(prompt); }
				finally { observeUsage(); }
			}
		}

		private final class ScopeModel implements ChatModel {
			private final ChatModel delegate;
			private ScopeModel(ChatModel delegate) { this.delegate = delegate; }
			@Override public ChatOptions getOptions() { return this.delegate.getOptions(); }
			@Override public ChatResponse call(Prompt prompt) {
				requireOpen();
				if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
						|| options.getToolContext() == null
						|| options.getToolContext().get(ToolContextKeys.CHILD_SCOPE) != Scope.this
						|| options.getToolContext().get(ToolContextKeys.TURN_CONTEXT) != context()
						|| options.getToolContext().get(RunContext.TOOL_CONTEXT_KEY) != runContext()) {
					throw new IllegalStateException("Model call requires captured child context");
				}
				snapshot.requireCurrent();
				if (options.getToolCallbacks() != null) for (var callback : options.getToolCallbacks()) {
					var entry = snapshot.entries().get(callback.getToolDefinition().name());
					if (frozen.stream().noneMatch(captured -> captured == callback)
							|| entry == null || !entry.digest().equals(SideEffectClass.definitionDigest(callback.getToolDefinition())))
						throw new RolePolicy.Denied(RolePolicy.Code.TOOL_NOT_ALLOWED, callback.getToolDefinition().name());
				}
				ChatResponse response = this.delegate.call(prompt);
				response.getResults().forEach(result -> result.getOutput().getToolCalls().forEach(tool ->
					snapshot.requireCurrent(tool.name())));
				return response;
			}
		}
	}

	private ModelSelection selection(TurnContext parent, ChildSpec child) {
		if (!child.effectiveModel(parent).equals(parent.binding().ref()))
			return new ModelSelection(child.effectiveModel(parent));
		try {
			var stored = selections.load(parent.sessionId());
			if (stored.isPresent() && stored.get().ref().equals(parent.binding().ref())
					&& stored.get().optionsFingerprint().equals(parent.binding().optionsFingerprint())) return stored.get();
			if (!new ModelSelection(parent.binding().ref()).optionsFingerprint().equals(parent.binding().optionsFingerprint()))
				throw new IllegalStateException("Captured parent selection options unavailable");
			return new ModelSelection(parent.binding().ref());
		}
		catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
	}

	/** Length-framed sorted logical values, independent of Set implementation/order. */
	private static String fingerprint(Metadata metadata) {
		var fields = new ArrayList<String>();
		fields.add("child-scope-v2");
		fields.add(metadata.role().name());
		fields.add(Integer.toString(metadata.scope().toolNames().size()));
		ToolScope scope = metadata.scope();
		fields.addAll(scope.toolNames().stream().sorted().toList());
		fields.add("read");
		fields.add(Integer.toString(scope.readRoots().size()));
		fields.addAll(scope.readRoots().stream().map(Path::toString).sorted().toList());
		fields.add("write");
		fields.add(Integer.toString(scope.writeRoots().size()));
		fields.addAll(scope.writeRoots().stream().map(Path::toString).sorted().toList());
		fields.add("generated");
		fields.add(Integer.toString(scope.generatedRoots().size()));
		fields.addAll(scope.generatedRoots().stream().map(Path::toString).sorted().toList());
		fields.add(scope.mutation() + ":" + scope.network() + ":" + scope.delegation() + ":" + scope.apply());
		return fields.stream().map(value -> value.length() + ":" + value)
			.collect(java.util.stream.Collectors.joining());
	}
}
