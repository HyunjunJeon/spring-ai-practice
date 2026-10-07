package com.example.miniagent.core.delegation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.example.miniagent.core.delegation.RolePolicy.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RolePolicyTest {
	@TempDir Path root;
	@TempDir Path outside;
	private final RolePolicy policy = new RolePolicy();

	private ToolScope parent() {
		return new ToolScope(Set.of("read", "write", "shell", "mcp", "apply", "delegate"),
				Set.of(this.root), Set.of(this.root), Set.of(this.root.resolve("generated")),
				true, true, true, true);
	}

	private ToolScope scope(ChildRole role) {
		return new ToolScope(Set.of("read", "write", "shell", "mcp", "apply"), Set.of(this.root),
				role == ChildRole.IMPLEMENT ? Set.of(this.root.resolve("source")) : Set.of(),
				role == ChildRole.RESEARCH ? Set.of() : Set.of(this.root.resolve("generated")),
				role != ChildRole.RESEARCH, false, false, false);
	}

	private ToolUse use(String name, Effect effect, Path path, Target target, boolean mutation) {
		return new ToolUse(name, Set.of(effect), List.of(new Access(path, target, mutation)));
	}

	private void denied(ChildRole role, ToolScope scope, ToolUse use, Code code) {
		var error = assertThrows(Denied.class, () -> this.policy.check(role, scope, use));
		assertThat(error.code()).isEqualTo(code);
	}

	@Test void allowedResearchReadsRemainWithinExactToolAndRootAuthority() {
		var scope = scope(ChildRole.RESEARCH);
		this.policy.validateScope(ChildRole.RESEARCH, scope, parent());
		this.policy.check(ChildRole.RESEARCH, scope, use("read", Effect.READ_ONLY,
				this.root.resolve("source/A.java"), Target.LIVE, false));
		denied(ChildRole.RESEARCH, scope, use("new-tool", Effect.READ_ONLY,
				this.root.resolve("source/A.java"), Target.LIVE, false), Code.TOOL_NOT_ALLOWED);
		denied(ChildRole.RESEARCH, scope, use("read", Effect.READ_ONLY,
				this.outside.resolve("A.java"), Target.LIVE, false), Code.PATH_DENIED);
	}

	@Test void parentScopeCannotBeWidenedByNamesRootsOrAuthorityFlags() {
		var p = parent();
		List<ToolScope> wider = List.of(
				new ToolScope(Set.of("new-tool"), Set.of(this.root), Set.of(), Set.of(), false, false, false, false),
				new ToolScope(Set.of("read"), Set.of(this.outside), Set.of(), Set.of(), false, false, false, false),
				new ToolScope(Set.of("write"), Set.of(), Set.of(this.outside), Set.of(), true, false, false, false),
				new ToolScope(Set.of("write"), Set.of(), Set.of(), Set.of(this.root), true, false, false, false));
		for (ToolScope child : wider) {
			var error = assertThrows(Denied.class, () -> this.policy.validateScope(ChildRole.IMPLEMENT, child, p));
			assertThat(error.code()).isEqualTo(Code.SCOPE_WIDENING);
		}
		var restricted = new ToolScope(p.toolNames(), p.readRoots(), p.writeRoots(), p.generatedRoots(),
				false, false, false, false);
		for (ToolScope child : List.of(
				new ToolScope(Set.of("write"), Set.of(), Set.of(), Set.of(), true, false, false, false),
				new ToolScope(Set.of("read"), Set.of(), Set.of(), Set.of(), false, true, false, false),
				new ToolScope(Set.of("delegate"), Set.of(), Set.of(), Set.of(), false, false, true, false),
				new ToolScope(Set.of("apply"), Set.of(), Set.of(), Set.of(), false, false, false, true))) {
			assertThat(assertThrows(Denied.class, () -> this.policy.validateScope(ChildRole.IMPLEMENT, child, restricted)).code())
					.isEqualTo(Code.SCOPE_WIDENING);
		}
	}

	@Test void researchCannotRequestOrPerformMutationEvenOnGeneratedOutputs() {
		assertThat(assertThrows(Denied.class, () -> this.policy.validateScope(
				ChildRole.RESEARCH, scope(ChildRole.IMPLEMENT), parent())).code()).isEqualTo(Code.ROLE_MUTATION);
		denied(ChildRole.RESEARCH, scope(ChildRole.RESEARCH), use("write", Effect.MUTATING,
				this.root.resolve("generated/result"), Target.CACHE, true), Code.ROLE_MUTATION);
	}

	@Test void implementWritesOnlyInsideStageOrExplicitCacheNeverLiveWorkspace() {
		var scope = scope(ChildRole.IMPLEMENT);
		this.policy.validateScope(ChildRole.IMPLEMENT, scope, parent());
		Path source = this.root.resolve("source/A.java");
		this.policy.check(ChildRole.IMPLEMENT, scope, use("write", Effect.MUTATING, source, Target.STAGE, true));
		this.policy.check(ChildRole.IMPLEMENT, scope, use("write", Effect.MUTATING,
				this.root.resolve("generated/cache"), Target.CACHE, true));
		denied(ChildRole.IMPLEMENT, scope, use("write", Effect.MUTATING, source, Target.LIVE, true), Code.PATH_DENIED);
		denied(ChildRole.IMPLEMENT, scope, use("write", Effect.MUTATING,
				this.outside.resolve("A.java"), Target.STAGE, true), Code.PATH_DENIED);
		denied(ChildRole.IMPLEMENT, scope, use("write", Effect.MUTATING, source, Target.CACHE, true), Code.PATH_DENIED);
	}

	@Test void verifyPermitsCloneGeneratedAndExplicitCacheButNeverSourceMutation() {
		var scope = scope(ChildRole.VERIFY);
		this.policy.validateScope(ChildRole.VERIFY, scope, parent());
		Path generated = this.root.resolve("generated/test-output");
		this.policy.check(ChildRole.VERIFY, scope, use("shell", Effect.MUTATING, generated, Target.VERIFICATION_CLONE, true));
		this.policy.check(ChildRole.VERIFY, scope, use("shell", Effect.MUTATING, generated, Target.CACHE, true));
		for (Target target : List.of(Target.VERIFICATION_CLONE, Target.STAGE, Target.LIVE)) {
			denied(ChildRole.VERIFY, scope, use("write", Effect.MUTATING,
					this.root.resolve("source/A.java"), target, true), Code.PATH_DENIED);
		}
		denied(ChildRole.VERIFY, scope, use("write", Effect.MUTATING, generated, Target.LIVE, true), Code.PATH_DENIED);
	}

	@Test void childApplyAndDelegationAreParentOnlyRegardlessOfToolAllowlist() {
		var scope = scope(ChildRole.IMPLEMENT);
		for (Effect effect : List.of(Effect.APPLY, Effect.DELEGATION)) {
			denied(ChildRole.IMPLEMENT, scope, new ToolUse("apply", Set.of(effect), List.of()), Code.PARENT_ONLY);
		}
		var child = new ToolScope(Set.of("apply"), Set.of(), Set.of(), Set.of(), false, false, false, true);
		assertThat(assertThrows(Denied.class, () -> this.policy.validateScope(ChildRole.IMPLEMENT, child, parent())).code())
				.isEqualTo(Code.PARENT_ONLY);
		for (String name : List.of("delegate", "apply_staged")) {
			var reserved = new ToolScope(Set.of(name), Set.of(), Set.of(), Set.of(), false, false, false, false);
			denied(ChildRole.RESEARCH, reserved, new ToolUse(name, Set.of(Effect.READ_ONLY), List.of()), Code.PARENT_ONLY);
		}
	}

	@Test void unknownMcpAndUnboundedOrContradictoryEffectsAreDenied() {
		var scope = scope(ChildRole.IMPLEMENT);
		for (Set<Effect> effects : List.of(Set.<Effect>of(), Set.of(Effect.UNKNOWN),
				Set.of(Effect.READ_ONLY, Effect.UNKNOWN))) {
			denied(ChildRole.IMPLEMENT, scope, new ToolUse("mcp", effects, List.of()), Code.UNKNOWN_EFFECT);
		}
		denied(ChildRole.IMPLEMENT, scope, new ToolUse("mcp", Set.of(Effect.MUTATING), List.of()),
				Code.INVALID_CLASSIFICATION);
		var network = new ToolScope(scope.toolNames(), scope.readRoots(), scope.writeRoots(),
				scope.generatedRoots(), true, true, false, false);
		denied(ChildRole.IMPLEMENT, network, new ToolUse("mcp", Set.of(Effect.NETWORK), List.of()),
				Code.INVALID_CLASSIFICATION);
		var access = new Access(this.root.resolve("source/A.java"), Target.STAGE, true);
		denied(ChildRole.IMPLEMENT, scope, new ToolUse("write", Set.of(Effect.READ_ONLY), List.of(access)),
				Code.INVALID_CLASSIFICATION);
		denied(ChildRole.IMPLEMENT, scope, new ToolUse("write", Set.of(Effect.READ_ONLY, Effect.MUTATING), List.of(access)),
				Code.INVALID_CLASSIFICATION);
	}

	@Test void networkRequiresParentAndChildAuthorityAndNeverAllowsExternalMutation() {
		var research = scope(ChildRole.RESEARCH);
		denied(ChildRole.RESEARCH, research, new ToolUse("mcp", Set.of(Effect.READ_ONLY, Effect.NETWORK), List.of()),
				Code.NETWORK_DENIED);
		var network = new ToolScope(research.toolNames(), research.readRoots(), Set.of(), Set.of(),
				false, true, false, false);
		this.policy.validateScope(ChildRole.RESEARCH, network, parent());
		this.policy.check(ChildRole.RESEARCH, network,
				new ToolUse("mcp", Set.of(Effect.READ_ONLY, Effect.NETWORK), List.of()));
		var implement = scope(ChildRole.IMPLEMENT);
		var mutableNetwork = new ToolScope(implement.toolNames(), implement.readRoots(), implement.writeRoots(),
				implement.generatedRoots(), true, true, false, false);
		denied(ChildRole.IMPLEMENT, mutableNetwork, new ToolUse("mcp", Set.of(Effect.MUTATING, Effect.NETWORK),
				List.of(new Access(this.root.resolve("source/A.java"), Target.STAGE, true))), Code.ROLE_MUTATION);
	}

	@Test void escapingSymlinkAndMissingLeafCannotWidenReadOrWriteAuthority() throws Exception {
		Path link = this.root.resolve("source");
		Files.createSymbolicLink(link, this.outside);
		var scope = scope(ChildRole.IMPLEMENT);
		denied(ChildRole.IMPLEMENT, scope, use("read", Effect.READ_ONLY, link.resolve("missing"), Target.STAGE, false),
				Code.PATH_DENIED);
		denied(ChildRole.IMPLEMENT, scope, use("write", Effect.MUTATING, link.resolve("missing"), Target.STAGE, true),
				Code.PATH_DENIED);
		assertThat(assertThrows(Denied.class, () -> this.policy.validateScope(ChildRole.IMPLEMENT, scope, parent())).code())
				.isEqualTo(Code.SCOPE_WIDENING);
	}

	@Test void evenInScopeWriteSymlinksAreDenied() throws Exception {
		Path source = Files.createDirectories(this.root.resolve("source"));
		Path target = Files.createDirectories(source.resolve("real"));
		Path link = source.resolve("alias");
		Files.createSymbolicLink(link, target);
		denied(ChildRole.IMPLEMENT, scope(ChildRole.IMPLEMENT), use("write", Effect.MUTATING,
				link.resolve("future"), Target.STAGE, true), Code.PATH_DENIED);
	}

	@Test void snapshotsAreImmutableAndRelativeOrUnnormalizedPathsCannotGrantAuthority() {
		var names = new HashSet<>(Set.of("read"));
		var roots = new HashSet<>(Set.of(this.root));
		var scope = new ToolScope(names, roots, Set.of(), Set.of(), false, false, false, false);
		names.add("write");
		roots.add(this.outside);
		assertThat(scope.toolNames()).containsExactly("read");
		assertThat(scope.readRoots()).containsExactly(this.root);
		assertThatThrownBy(() -> scope.readRoots().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> new ToolScope(Set.of("read"), Set.of(Path.of("relative")),
				Set.of(), Set.of(), false, false, false, false)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Access(this.root.resolve("../outside"), Target.STAGE, true))
				.isInstanceOf(Denied.class);
	}
}
