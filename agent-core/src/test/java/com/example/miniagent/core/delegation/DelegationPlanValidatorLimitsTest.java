package com.example.miniagent.core.delegation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/** agent.delegation.* ceilings bound each child, independent of the parent's remaining budget. */
class DelegationPlanValidatorLimitsTest {
	@TempDir Path root;
	private final DelegationPlanValidator validator = new DelegationPlanValidator();
	// The parent is RunBudget.defaults() (30m, no token cap, 50 tools), so only the per-child ceilings can refuse.
	private final DelegationProperties limits = new DelegationProperties(2, 8, 1, 2, Duration.ofMinutes(30), 100L, 2);

	private ChildSpec child(String id, String task, RunBudget budget) {
		var scope = new ToolScope(Set.of("read"), Set.of(this.root), Set.of(), Set.of(), false, false, false, false);
		return new ChildSpec(id, task, ChildRole.RESEARCH, null, Set.of(), scope, budget, 1, true);
	}

	private DelegationPlanValidator.ValidatedPlan validate(ChildSpec... children) {
		var parent = new ToolScope(Set.of("read"), Set.of(this.root), Set.of(), Set.of(), false, false, true, false);
		return this.validator.validate(new DelegationPlan("plan", List.of(children), 1), parent,
				RunBudget.defaults(), RunUsage.empty(), this.limits);
	}

	private void rejects(DelegationPlanValidator.Code code, String subject, ChildSpec... children) {
		assertThatThrownBy(() -> validate(children)).isInstanceOfSatisfying(DelegationPlanValidator.Rejection.class, ex -> {
			assertThat(ex.code()).isEqualTo(code);
			assertThat(ex.subject()).isEqualTo(subject);
		});
	}

	@Test void childToolCallCapAboveTheConfiguredCeilingRejectsThatChild() {
		var atCeiling = child("within", "Inspect", new RunBudget(Duration.ofMinutes(1), 100L, 2));
		assertThat(validate(atCeiling).topologicalOrder()).containsExactly(atCeiling);
		rejects(DelegationPlanValidator.Code.RESERVATION_EXCEEDED, "over",
				atCeiling, child("over", "Inspect", new RunBudget(Duration.ofMinutes(1), 100L, 3)));
	}

	@Test void configuredTokenCeilingRequiresAFiniteChildTokenCapWithinIt() {
		var atCeiling = child("a", "Inspect", new RunBudget(Duration.ofMinutes(1), 100L, 1));
		assertThat(validate(atCeiling).topologicalOrder()).containsExactly(atCeiling);
		rejects(DelegationPlanValidator.Code.RESERVATION_EXCEEDED, "above",
				child("above", "Inspect", new RunBudget(Duration.ofMinutes(1), 101L, 1)));
		rejects(DelegationPlanValidator.Code.RESERVATION_EXCEEDED, "uncapped",
				child("uncapped", "Inspect", new RunBudget(Duration.ofMinutes(1), null, 1)));
	}

	@Test void blankTaskRejectsAsInvalidIdNamingTheChild() {
		var budget = new RunBudget(Duration.ofMinutes(1), 100L, 1);
		assertThat(validate(child("task", "Inspect", budget)).topologicalOrder()).hasSize(1);
		rejects(DelegationPlanValidator.Code.INVALID_ID, "task", child("task", "  \t", budget));
		rejects(DelegationPlanValidator.Code.INVALID_ID, "task", child("task", "", budget));
	}
}
