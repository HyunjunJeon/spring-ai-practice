package com.example.miniagent.core.delegation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Trusted classification gate, before budget/permission. Not a shell sandbox or ownership lease. */
public final class RolePolicy {
	public enum Effect { READ_ONLY, MUTATING, NETWORK, DELEGATION, APPLY, UNKNOWN }
	public enum Target { LIVE, STAGE, VERIFICATION_CLONE, CACHE }
	public enum Code { TOOL_NOT_ALLOWED, SCOPE_WIDENING, ROLE_MUTATION, PARENT_ONLY, UNKNOWN_EFFECT,
		NETWORK_DENIED, PATH_DENIED, INVALID_CLASSIFICATION }

	public static final class Denied extends IllegalArgumentException {
		private final Code code;
		public Denied(Code code, String subject) {
			super(code + ": " + subject);
			this.code = Objects.requireNonNull(code);
		}
		public Code code() { return this.code; }
	}

	/** Paths and effect classifications must come from trusted adapters, not tool JSON. */
	public record Access(Path path, Target target, boolean mutation) {
		public Access {
			Objects.requireNonNull(path);
			Objects.requireNonNull(target);
			if (!path.isAbsolute() || !path.equals(path.normalize())) {
				throw new Denied(Code.PATH_DENIED, path.toString());
			}
		}
	}

	public record ToolUse(String toolName, Set<Effect> effects, List<Access> accesses) {
		public ToolUse {
			Objects.requireNonNull(toolName);
			effects = Set.copyOf(effects);
			accesses = List.copyOf(accesses);
		}
	}

	public void validateScope(ChildRole role, ToolScope child, ToolScope parent) {
		Objects.requireNonNull(role);
		Objects.requireNonNull(child);
		Objects.requireNonNull(parent);
		if (!parent.toolNames().containsAll(child.toolNames())
				|| child.mutation() && !parent.mutation() || child.network() && !parent.network()
				|| child.delegation() && !parent.delegation() || child.apply() && !parent.apply()
				|| !subset(child.readRoots(), parent.readRoots())
				|| !subset(child.writeRoots(), parent.writeRoots())
				|| !subset(child.generatedRoots(), parent.generatedRoots())) {
			throw new Denied(Code.SCOPE_WIDENING, role.name());
		}
		validateRole(role, child);
	}

	private static void validateRole(ChildRole role, ToolScope scope) {
		if (scope.apply() || scope.delegation() || scope.toolNames().contains("delegate")
				|| scope.toolNames().contains("apply_staged")) {
			throw new Denied(Code.PARENT_ONLY, role.name());
		}
		if ((!scope.mutation() && (!scope.writeRoots().isEmpty() || !scope.generatedRoots().isEmpty()))
				|| role == ChildRole.RESEARCH && scope.mutation()
				|| role == ChildRole.VERIFY && !scope.writeRoots().isEmpty()) {
			throw new Denied(Code.ROLE_MUTATION, role.name());
		}
	}

	/** Canonicalize future paths too, so a missing leaf below an escaping symlink is not authority. */
	private static Path canonical(Path path) throws IOException {
		Path existing = path;
		for (;;) {
			try {
				Files.readAttributes(existing, java.nio.file.attribute.BasicFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS);
				return existing.toRealPath().resolve(existing.relativize(path)).normalize();
			}
			catch (NoSuchFileException ex) {
				existing = existing.getParent();
				if (existing == null) throw ex;
			}
		}
	}

	private static boolean subset(Set<Path> children, Set<Path> parents) {
		for (Path child : children) {
			if (!contains(parents, child, false)) return false;
		}
		return true;
	}

	private static boolean contains(Set<Path> roots, Path path, boolean mutation) {
		try {
			Path actual = canonical(path);
			for (Path root : roots) {
				Path actualRoot = canonical(root);
				// Require both lexical and canonical containment; aliases do not expand authority.
				if (!path.startsWith(root) || !actual.startsWith(actualRoot)) continue;
				if (mutation) {
					Path prefix = root;
					if (Files.isSymbolicLink(prefix)) return false;
					for (Path part : root.relativize(path)) {
						prefix = prefix.resolve(part);
						if (Files.isSymbolicLink(prefix)) return false;
					}
				}
				return true;
			}
			return false;
		}
		catch (IOException ex) {
			throw new Denied(Code.PATH_DENIED, path.toString());
		}
	}

	public void check(ChildRole role, ToolScope scope, ToolUse use) {
		Objects.requireNonNull(role);
		Objects.requireNonNull(scope);
		Objects.requireNonNull(use);
		validateRole(role, scope);
		if (!scope.toolNames().contains(use.toolName())) throw new Denied(Code.TOOL_NOT_ALLOWED, use.toolName());
		if (use.effects().isEmpty() || use.effects().contains(Effect.UNKNOWN)) {
			throw new Denied(Code.UNKNOWN_EFFECT, use.toolName());
		}
		if (use.effects().contains(Effect.APPLY) || use.effects().contains(Effect.DELEGATION)) {
			throw new Denied(Code.PARENT_ONLY, use.toolName());
		}
		if (use.effects().contains(Effect.NETWORK) && !scope.network()) {
			throw new Denied(Code.NETWORK_DENIED, use.toolName());
		}
		boolean mutating = use.effects().contains(Effect.MUTATING);
		boolean pathMutation = use.accesses().stream().anyMatch(Access::mutation);
		if (mutating != pathMutation || mutating && use.effects().contains(Effect.READ_ONLY)
				|| !mutating && !use.effects().contains(Effect.READ_ONLY)) {
			// External mutation with no bounded filesystem effect is not staged mutation.
			throw new Denied(Code.INVALID_CLASSIFICATION, use.toolName());
		}
		if (mutating && (role == ChildRole.RESEARCH || !scope.mutation()
				|| use.effects().contains(Effect.NETWORK))) {
			throw new Denied(Code.ROLE_MUTATION, use.toolName());
		}
		for (Access access : use.accesses()) {
			if (!access.mutation()) {
				if (!contains(scope.readRoots(), access.path(), false)) {
					throw new Denied(Code.PATH_DENIED, access.path().toString());
				}
				continue;
			}
			boolean generated = contains(scope.generatedRoots(), access.path(), true);
			boolean allowed = switch (role) {
				case RESEARCH -> false;
				case IMPLEMENT -> access.target() == Target.STAGE
						&& (contains(scope.writeRoots(), access.path(), true) || generated)
						|| access.target() == Target.CACHE && generated;
				case VERIFY -> (access.target() == Target.VERIFICATION_CLONE || access.target() == Target.CACHE)
						&& generated;
			};
			if (!allowed) throw new Denied(Code.PATH_DENIED, access.path().toString());
		}
	}
}
