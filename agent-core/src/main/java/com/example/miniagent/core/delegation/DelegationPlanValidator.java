package com.example.miniagent.core.delegation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunUsage;

/** Pure preflight: no model lookup, reservation side effects or child start callbacks. */
public final class DelegationPlanValidator {
	public enum Code { INVALID_ID, EMPTY_PLAN, DUPLICATE_CHILD, INVALID_EDGE, CYCLE, DEPTH_EXCEEDED,
		INVALID_ATTEMPTS, INVALID_RESERVATION, RESERVATION_EXCEEDED, UNKNOWN_USAGE, SCOPE_DENIED }

	public static final class Rejection extends IllegalArgumentException {
		private final Code code;
		private final String subject;
		public Rejection(Code code, String subject) {
			super(code + ": " + subject);
			this.code = Objects.requireNonNull(code);
			this.subject = Objects.requireNonNull(subject);
		}
		public Code code() { return this.code; }
		public String subject() { return this.subject; }
	}

	public static final class ValidatedPlan {
		private final DelegationPlan plan;
		private final List<ChildSpec> topologicalOrder;
		private ValidatedPlan(DelegationPlan plan, List<ChildSpec> topologicalOrder) {
			this.plan = plan;
			this.topologicalOrder = List.copyOf(topologicalOrder);
		}
		public DelegationPlan plan() { return this.plan; }
		public List<ChildSpec> topologicalOrder() { return this.topologicalOrder; }
	}

	public ValidatedPlan validate(DelegationPlan plan, ToolScope parentScope, RunBudget parentBudget,
			RunUsage parentUsage, DelegationProperties properties) {
		Objects.requireNonNull(plan);
		Objects.requireNonNull(parentScope);
		Objects.requireNonNull(parentBudget);
		Objects.requireNonNull(parentUsage);
		Objects.requireNonNull(properties);
		requireId(plan.planId());
		if (plan.children().isEmpty()) throw new Rejection(Code.EMPTY_PLAN, plan.planId());
		if (plan.depth() < 1 || plan.depth() > properties.maxDepth()) {
			throw new Rejection(Code.DEPTH_EXCEEDED, plan.planId());
		}
		if (!parentScope.delegation()) throw new Rejection(Code.SCOPE_DENIED, plan.planId());
		Map<String, ChildSpec> children = new LinkedHashMap<>();
		var policy = new RolePolicy();
		long wall = 0, tools = 0, tokens = 0;
		boolean unboundedTokens = false;
		for (ChildSpec child : plan.children()) {
			requireId(child.childId());
			if (children.putIfAbsent(child.childId(), child) != null) {
				throw new Rejection(Code.DUPLICATE_CHILD, child.childId());
			}
			if (child.task().isBlank()) throw new Rejection(Code.INVALID_ID, child.childId());
			if (child.maxAttempts() < 1 || child.maxAttempts() > properties.maxAttempts()) {
				throw new Rejection(Code.INVALID_ATTEMPTS, child.childId());
			}
			try { policy.validateScope(child.role(), child.scope(), parentScope); }
			catch (RolePolicy.Denied ex) { throw new Rejection(Code.SCOPE_DENIED, child.childId()); }
			RunBudget reservation = child.budget();
			if (reservation.wall().toMillis() == 0) {
				throw new Rejection(Code.INVALID_RESERVATION, child.childId());
			}
			if (reservation.wall().compareTo(properties.wall()) > 0
					|| reservation.toolCallCap() > properties.toolCallCap()
					|| properties.tokenCap() != null && (reservation.tokenCap() == null
						|| reservation.tokenCap() > properties.tokenCap())) {
				throw new Rejection(Code.RESERVATION_EXCEEDED, child.childId());
			}
			try {
				wall = Math.addExact(wall, reservation.wall().toMillis());
				tools = Math.addExact(tools, reservation.toolCallCap());
				if (reservation.tokenCap() == null) unboundedTokens = true;
				else tokens = Math.addExact(tokens, reservation.tokenCap());
			}
			catch (ArithmeticException ex) { throw new Rejection(Code.INVALID_RESERVATION, child.childId()); }
		}
		Map<String, Integer> indegrees = new LinkedHashMap<>();
		Map<String, List<String>> dependents = new LinkedHashMap<>();
		for (ChildSpec child : plan.children()) {
			indegrees.put(child.childId(), child.dependencies().size());
			for (String dependency : child.dependencies()) {
				requireId(dependency);
				if (!children.containsKey(dependency) || dependency.equals(child.childId())) {
					throw new Rejection(Code.INVALID_EDGE, child.childId());
				}
				dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(child.childId());
			}
		}
		var ready = new ArrayDeque<String>();
		indegrees.forEach((id, count) -> { if (count == 0) ready.add(id); });
		var sorted = new ArrayList<ChildSpec>();
		while (!ready.isEmpty()) {
			String id = ready.remove();
			sorted.add(children.get(id));
			for (String dependent : dependents.getOrDefault(id, List.of())) {
				int left = indegrees.compute(dependent, (key, count) -> count - 1);
				if (left == 0) ready.add(dependent);
			}
		}
		if (sorted.size() != children.size()) throw new Rejection(Code.CYCLE, plan.planId());
		if (parentBudget.tokenCap() != null && parentUsage.tokensUnknown()) {
			throw new Rejection(Code.UNKNOWN_USAGE, plan.planId());
		}
		if (wall > parentBudget.wall().toMillis() - parentUsage.activeMillis()
				|| tools > (long) parentBudget.toolCallCap() - parentUsage.toolCalls()
				|| parentBudget.tokenCap() != null && (unboundedTokens
					|| tokens > parentBudget.tokenCap() - parentUsage.knownTokens())) {
			throw new Rejection(Code.RESERVATION_EXCEEDED, plan.planId());
		}
		return new ValidatedPlan(plan, sorted);
	}

	private static void requireId(String id) {
		if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
			throw new Rejection(Code.INVALID_ID, id);
		}
	}
}
