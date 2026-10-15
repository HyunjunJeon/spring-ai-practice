package com.example.miniagent.core.delegation;

import java.util.Objects;
import java.util.Set;

import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.RunBudget;
import org.jspecify.annotations.Nullable;

/** No execution IDs until task19 allocates the isolated run. Budget covers all attempts. */
public record ChildSpec(String childId, String task, ChildRole role, @Nullable ModelRef model,
		Set<String> dependencies, ToolScope scope, RunBudget budget, int maxAttempts, boolean required) {
	public ChildSpec {
		Objects.requireNonNull(childId);
		Objects.requireNonNull(task);
		Objects.requireNonNull(role);
		dependencies = Set.copyOf(dependencies);
		Objects.requireNonNull(scope);
		Objects.requireNonNull(budget);
	}

	public ModelRef effectiveModel(ModelRef parent) {
		return this.model == null ? Objects.requireNonNull(parent) : this.model;
	}

	public ModelRef effectiveModel(TurnContext parent) {
		return effectiveModel(parent.binding().ref());
	}
}
