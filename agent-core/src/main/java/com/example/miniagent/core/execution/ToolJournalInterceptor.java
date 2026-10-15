package com.example.miniagent.core.execution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import com.example.miniagent.core.ToolInterceptor;
import com.example.miniagent.core.run.DriftGate;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunRequestStore;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunStore;
import com.example.miniagent.core.run.SideEffectClass;
import com.example.miniagent.core.run.Stop;
import com.example.miniagent.core.run.ToolEvidenceStore;
import com.example.miniagent.core.run.WorkspaceFingerprint;
import org.springframework.core.Ordered;

/** Permission must settle before this interceptor is entered. */
public final class ToolJournalInterceptor implements ToolInterceptor {
	private static final Map<RunContext, Binding> BINDINGS = new java.util.concurrent.ConcurrentHashMap<>();
	private record Binding(DriftGate gate, ToolEvidenceStore evidence, RunStore runs,
			String snapshotDigest, DriftGate.Snapshot snapshot) {}

	static void attach(RunContext context, DriftGate gate, ToolEvidenceStore evidence, RunStore runs,
			String snapshotDigest) throws IOException {
		Binding binding = new Binding(gate, evidence, runs, snapshotDigest, gate.load(snapshotDigest, context.spec()));
		if (BINDINGS.putIfAbsent(context, binding) != null) throw new IOException("Duplicate journal owner");
	}

	static void detach(RunContext context) { BINDINGS.remove(context); }

	public static Set<String> capturedTools(RunContext context) {
		var binding = BINDINGS.get(context);
		if (binding == null) throw new IllegalStateException("Missing captured journal owner");
		return Set.copyOf(binding.snapshot().tools().keySet());
	}

	/** Scope checks consult this exact immutable attempt snapshot before permission. */
	public static boolean declaredRead(RunContext context, org.springframework.ai.tool.definition.ToolDefinition tool) {
		var binding = BINDINGS.get(context);
		var declaration = binding == null ? null : binding.snapshot().tools().get(tool.name());
		return declaration != null && declaration.effect() == SideEffectClass.READ_ONLY
				&& declaration.digest().equals(SideEffectClass.definitionDigest(tool));
	}

	@Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 30; }

	@Override public String intercept(ToolInvocation invocation, Chain chain) {
		if (invocation.context() == null) return chain.proceed(invocation);
		RunContext context = RunContext.fromToolContext(invocation.context()).orElse(null);
		if (context == null) return chain.proceed(invocation);
		Binding binding = BINDINGS.get(context);
		if (binding == null) throw new Stop(Stop.Kind.INTERRUPTED, "Missing captured journal owner");
		synchronized (binding) {
			try {
				if (Thread.currentThread().isInterrupted() || context.writer().record().state() != RunState.RUNNING)
					throw new Stop(Stop.Kind.INTERRUPTED);
				DriftGate.Definition declaration = binding.snapshot().tools().get(invocation.toolName());
				if (declaration == null || !declaration.digest().equals(SideEffectClass.definitionDigest(invocation.definition())))
					throw new Stop(Stop.Kind.INTERRUPTED, "Tool outside attempt snapshot");
				var events = FileRunStore.events(binding.runs(), context.spec().runId());
				String expected = binding.snapshotDigest();
				Map<String, RunEvent.ToolStarted> started = new HashMap<>();
				String args = RunRequestStore.digest(invocation.input().getBytes(java.nio.charset.StandardCharsets.UTF_8));
				for (RunEvent event : events) {
					if (event instanceof RunEvent.ToolStarted start) started.put(start.attemptId() + ":" + start.toolCallKey(), start);
					if (event instanceof RunEvent.ToolResolution resolution && resolution.decision() == RunEvent.Resolution.SKIP) {
						var skipped = started.get(resolution.oldAttemptId() + ":" + resolution.toolCallKey());
						if (skipped != null && skipped.toolName().equals(invocation.toolName()) && skipped.argsDigest().equals(args))
							throw new Stop(Stop.Kind.INTERRUPTED, "Operator skipped this UNKNOWN effect");
					}
					if (event instanceof RunEvent.ToolFinished finish) {
						var result = binding.evidence().get(finish.resultDigest(), ToolEvidenceStore.ResultEvidence.class);
						DriftGate.validateResult(result, context.spec(), finish);
						if (finish.attemptId().equals(context.attemptId())) expected = binding.evidence().put(result.after());
						var start = started.get(finish.attemptId() + ":" + finish.toolCallKey());
						if (start != null && start.effect() == RunEvent.Effect.MUTATING
								&& start.toolName().equals(invocation.toolName()) && start.argsDigest().equals(args))
							throw new Stop(Stop.Kind.INTERRUPTED, "Verified mutation must not be automatically repeated");
					}
				}
				var baseline = binding.gate().load(expected, context.spec());
				var before = WorkspaceFingerprint.capture(Path.of(context.spec().cwd()), binding.evidence().home());
				if (!baseline.workspace().equals(before))
					throw new Stop(Stop.Kind.INTERRUPTED, "Workspace drift before effect");
				String key = UUID.randomUUID().toString();
				RunEvent.Effect effect = declaration.effect() == SideEffectClass.READ_ONLY
						? RunEvent.Effect.READ_ONLY : RunEvent.Effect.MUTATING;
				context.writer().append(new RunEvent.ToolStarted(context.now(), context.attemptId(), key,
						invocation.toolName(), args, effect, binding.snapshotDigest()));
				// Append acknowledgement above is the durable force boundary.
				if (Thread.currentThread().isInterrupted() || context.writer().record().state() != RunState.RUNNING)
					throw new Stop(Stop.Kind.INTERRUPTED);
				String result = chain.proceed(invocation);
				if (result == null || Thread.currentThread().isInterrupted()
						|| context.writer().record().state() != RunState.RUNNING)
					throw new Stop(Stop.Kind.INTERRUPTED, "No verified result");
				var after = new DriftGate.Snapshot(1, baseline.specDigest(),
						WorkspaceFingerprint.capture(Path.of(context.spec().cwd()), binding.evidence().home()),
						binding.snapshot().tools(), binding.snapshot().configurationDigest());
				String digest = binding.evidence().put(new ToolEvidenceStore.ResultEvidence(1,
						context.spec().runId().value(), context.attemptId(), key, invocation.toolName(), args, result, after));
				if (Thread.currentThread().isInterrupted() || context.writer().record().state() != RunState.RUNNING)
					throw new Stop(Stop.Kind.INTERRUPTED, "Cancelled before result commit");
				context.writer().append(new RunEvent.ToolFinished(context.now(), context.attemptId(), key, digest));
				return result;
			}
			catch (IOException ex) { throw new UncheckedIOException(ex); }
		}
	}
}
