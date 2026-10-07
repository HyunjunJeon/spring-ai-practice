package com.example.miniagent.core.delegation;

import java.util.List;
import java.util.Objects;

/** Depth is delegation nesting, not DAG dependency length. Validate before any child starts. */
public record DelegationPlan(String planId, List<ChildSpec> children, int depth) {
	public DelegationPlan {
		Objects.requireNonNull(planId);
		children = List.copyOf(children);
	}
}
