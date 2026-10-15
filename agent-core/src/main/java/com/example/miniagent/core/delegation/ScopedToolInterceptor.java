package com.example.miniagent.core.delegation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.example.miniagent.core.ToolContextKeys;
import com.example.miniagent.core.ToolInterceptor;
import com.example.miniagent.core.change.StagedWorkspace;
import com.example.miniagent.core.change.StagingToolInterceptor;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.SideEffectClass;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/** Exact captured child authority, ahead of all existing tool interceptors. */
public final class ScopedToolInterceptor implements ToolInterceptor {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final ChildScopeFactory.Scope scope;
	private final McpToolSnapshot snapshot;
	private final Map<String, StagingToolInterceptor.Declaration> declarations;
	private final RolePolicy policy = new RolePolicy();

	public ScopedToolInterceptor(ChildScopeFactory.Scope scope, McpToolSnapshot snapshot,
			Map<String, StagingToolInterceptor.Declaration> declarations) {
		this.scope = scope;
		this.snapshot = snapshot;
		this.declarations = Map.copyOf(declarations);
	}
	@Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

	@Override public String intercept(ToolInvocation invocation, Chain chain) {
		this.scope.requireOpen();
		var tools = invocation.context();
		if (tools == null || tools.getContext().get(ToolContextKeys.CHILD_SCOPE) != this.scope
				|| tools.getContext().get(ToolContextKeys.TURN_CONTEXT) != this.scope.context()
				|| RunContext.fromToolContext(tools).orElse(null) != this.scope.runContext()) {
			throw new RolePolicy.Denied(RolePolicy.Code.SCOPE_WIDENING, "Missing exact child context");
		}
		this.snapshot.requireCurrent(invocation.toolName());
		var entry = this.snapshot.entries().get(invocation.toolName());
		if (!entry.digest().equals(SideEffectClass.definitionDigest(invocation.definition()))) {
			throw new RolePolicy.Denied(RolePolicy.Code.TOOL_NOT_ALLOWED, invocation.toolName());
		}
		var stage = StagedWorkspace.from(tools).orElseThrow(() ->
			new RolePolicy.Denied(RolePolicy.Code.PATH_DENIED, "Missing owner stage"));
		var turn = this.scope.context();
		if (!stage.owner().sessionId().equals(turn.sessionId())
				|| !stage.owner().runId().value().equals(turn.runId())
				|| !stage.owner().attemptId().equals(turn.attemptId())
				|| !stage.owner().turnId().equals(turn.turnId())) {
			throw new RolePolicy.Denied(RolePolicy.Code.PATH_DENIED, "Foreign owner stage");
		}
		// Trusted Java mailbox callback validates exact managed lineage itself; it has no filesystem path.
		if (entry.callback() instanceof com.example.miniagent.core.messaging.MessageTool) return chain.proceed(invocation);
		var declaration = this.declarations.get(invocation.toolName());
		if (declaration != null && declaration.kind() == StagingToolInterceptor.Kind.CONTROL)
			throw new RolePolicy.Denied(RolePolicy.Code.PARENT_ONLY, invocation.toolName());
		if (declaration == null || declaration.kind() == StagingToolInterceptor.Kind.SHELL
				|| !declaration.digest().equals(entry.digest())) {
			throw new RolePolicy.Denied(RolePolicy.Code.UNKNOWN_EFFECT, invocation.toolName());
		}
		try {
			if (declaration.kind() == StagingToolInterceptor.Kind.VERIFICATION) {
				if (this.scope.metadata().role() != ChildRole.VERIFY)
					throw new RolePolicy.Denied(RolePolicy.Code.ROLE_MUTATION, invocation.toolName());
				this.policy.check(ChildRole.VERIFY, this.scope.metadata().scope(),
					new RolePolicy.ToolUse(invocation.toolName(), Set.of(RolePolicy.Effect.READ_ONLY), List.of()));
				DelegationVerificationTool.preflight(this.scope, invocation.input());
				return chain.proceed(invocation);
			}
			boolean mutation = declaration.kind() == StagingToolInterceptor.Kind.FILE_WRITE;
			var args = JSON.readTree(invocation.input());
			var path = args.get("path");
			if (path == null || !path.isString()) {
				throw new RolePolicy.Denied(RolePolicy.Code.PATH_DENIED, "Missing path");
			}
			ToolScope physical = project(this.scope.metadata().scope(), stage);
			Path resolved = stage.resolve(path.asString(), mutation,
					List.copyOf(this.scope.metadata().scope().readRoots()));
			var target = this.scope.metadata().role() == ChildRole.VERIFY
					? RolePolicy.Target.VERIFICATION_CLONE : RolePolicy.Target.STAGE;
			if (!resolved.startsWith(stage.root())) target = RolePolicy.Target.LIVE;
			this.policy.check(this.scope.metadata().role(), physical,
				new RolePolicy.ToolUse(invocation.toolName(),
					Set.of(mutation ? RolePolicy.Effect.MUTATING : RolePolicy.Effect.READ_ONLY),
					List.of(new RolePolicy.Access(resolved, target, mutation))));
			// The real tool resolves again through the exact StagedWorkspace. Do not mutate JSON.
			return chain.proceed(invocation);
		}
		catch (IOException ex) { throw new UncheckedIOException(ex); }
	}

	public static ToolScope project(ToolScope logical, StagedWorkspace stage) throws IOException {
		return new ToolScope(logical.toolNames(), project(logical.readRoots(), stage),
			project(logical.writeRoots(), stage), project(logical.generatedRoots(), stage),
			logical.mutation(), logical.network(), false, false);
	}
	private static Set<Path> project(Set<Path> roots, StagedWorkspace stage) throws IOException {
		var projected = new java.util.HashSet<Path>();
		for (Path root : roots) {
			Path existing = root;
			while (!java.nio.file.Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
				existing = existing.getParent();
				if (existing == null) throw new java.nio.file.NoSuchFileException(root.toString());
			}
			Path actual = existing.toRealPath().resolve(existing.relativize(root)).normalize();
			projected.add(actual.startsWith(stage.liveRoot())
				? stage.root().resolve(stage.liveRoot().relativize(actual)).normalize() : actual);
		}
		return Set.copyOf(projected);
	}
}
