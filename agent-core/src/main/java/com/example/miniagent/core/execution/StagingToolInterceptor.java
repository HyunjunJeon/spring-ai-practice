package com.example.miniagent.core.execution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import com.example.miniagent.core.ToolInterceptor;
import com.example.miniagent.core.change.PathPolicy;
import com.example.miniagent.core.change.StagedWorkspace;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.SideEffectClass;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/** Scope refusal precedes budget, approval and the durable effect journal. */
public final class StagingToolInterceptor implements ToolInterceptor {
	public enum Kind { READ, FILE_WRITE, SHELL, VERIFICATION, CONTROL }
	public record Declaration(String digest, Kind kind, List<Path> readRoots,
			java.util.function.Consumer<StagedWorkspace> preflight) {
		public Declaration { readRoots = List.copyOf(readRoots); }
		public Declaration(String digest, Kind kind, List<Path> readRoots) {
			this(digest, kind, readRoots, stage -> {});
		}
	}
	@FunctionalInterface public interface Policy { Map<String, Declaration> declarations(); }
	public enum Code { UNKNOWN_TOOL, MISSING_STAGE, DEFINITION_DRIFT }
	public static final class Refusal extends IllegalStateException {
		private final Code code;
		public Refusal(Code code) { super(code.name()); this.code = code; }
		public Code code() { return this.code; }
	}
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final Supplier<List<Policy>> policies;
	private final Map<StagedWorkspace, Map<String, Declaration>> snapshots = new java.util.concurrent.ConcurrentHashMap<>();
	public StagingToolInterceptor(Supplier<List<Policy>> policies) { this.policies = policies; }
	public void capture(StagedWorkspace stage) {
		Map<String, Declaration> declarations = new java.util.HashMap<>();
		for (var policy : this.policies.get()) {
			for (var entry : policy.declarations().entrySet()) {
				if (declarations.putIfAbsent(entry.getKey(), entry.getValue()) != null)
					throw new Refusal(Code.DEFINITION_DRIFT);
			}
		}
		this.snapshots.put(stage, Map.copyOf(declarations));
	}
	public void release(StagedWorkspace stage) { this.snapshots.remove(stage); }
	@Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 5; }
	@Override public String intercept(ToolInvocation invocation, Chain chain) {
		if (invocation.context() == null || RunContext.fromToolContext(invocation.context()).isEmpty())
			return chain.proceed(invocation);
		var stage = StagedWorkspace.from(invocation.context()).orElseThrow(() -> new Refusal(Code.MISSING_STAGE));
		var declarations = this.snapshots.get(stage);
		var declaration = declarations == null ? null : declarations.get(invocation.toolName());
		if (declaration == null) {
			// Trusted read-only contributions (for example load_skill) are authorized by
			// the actual attempt snapshot, never an unclassified tool name.
			if (ToolJournalInterceptor.declaredRead(
					RunContext.fromToolContext(invocation.context()).orElseThrow(), invocation.definition()))
				return chain.proceed(invocation);
			throw new Refusal(Code.UNKNOWN_TOOL);
		}
		if (!declaration.digest().equals(SideEffectClass.definitionDigest(invocation.definition())))
			throw new Refusal(Code.DEFINITION_DRIFT);
		if (declaration.kind() == Kind.CONTROL) {
			var owner = RunContext.fromToolContext(invocation.context()).orElseThrow();
			if (owner.spec().childId() != null)
				throw new com.example.miniagent.core.delegation.RolePolicy.Denied(
					com.example.miniagent.core.delegation.RolePolicy.Code.PARENT_ONLY, invocation.toolName());
			if (invocation.toolName().equals("apply_staged")
					&& ToolJournalInterceptor.declaredRead(owner, invocation.definition()))
				throw new Refusal(Code.DEFINITION_DRIFT);
		}
		try {
			declaration.preflight().accept(stage);
			if (declaration.kind() != Kind.SHELL && declaration.kind() != Kind.VERIFICATION
					&& declaration.kind() != Kind.CONTROL) {
				var args = JSON.readTree(invocation.input());
				var path = args.get("path");
				if (path == null || !path.isString()) throw new PathPolicy.Violation(PathPolicy.Code.INVALID_PATH, "path");
				stage.resolve(path.asString(), declaration.kind() == Kind.FILE_WRITE, declaration.readRoots());
			}
			return chain.proceed(invocation);
		}
		catch (IOException ex) { throw new UncheckedIOException(ex); }
	}
}
