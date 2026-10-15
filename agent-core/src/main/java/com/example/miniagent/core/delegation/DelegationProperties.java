package com.example.miniagent.core.delegation;

import java.time.Duration;

import com.example.miniagent.core.run.RunBudget;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuration contribution only; parent integration registers it. */
@ConfigurationProperties("agent.delegation")
public record DelegationProperties(@DefaultValue("2") int concurrency, @DefaultValue("8") int queueCapacity,
		@DefaultValue("1") int maxDepth, @DefaultValue("2") int maxAttempts,
		@DefaultValue("30m") Duration wall, @Nullable Long tokenCap, @DefaultValue("50") int toolCallCap) {
	public DelegationProperties {
		if (concurrency < 1 || queueCapacity < 0 || maxDepth < 1 || maxAttempts < 1) {
			throw new IllegalArgumentException("Invalid delegation limits");
		}
		RunBudget limits = new RunBudget(wall, tokenCap, toolCallCap);
		if (limits.wall().toMillis() == 0) throw new IllegalArgumentException("Wall must be at least one millisecond");
	}

	public static DelegationProperties defaults() {
		RunBudget budget = RunBudget.defaults();
		return new DelegationProperties(2, 8, 1, 2, budget.wall(), budget.tokenCap(), budget.toolCallCap());
	}

	public RunBudget budget() {
		return new RunBudget(this.wall, this.tokenCap, this.toolCallCap);
	}
}
