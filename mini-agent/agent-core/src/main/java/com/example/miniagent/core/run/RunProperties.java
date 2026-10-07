package com.example.miniagent.core.run;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.runs")
public record RunProperties(@DefaultValue("30m") Duration wall, @Nullable Long tokenCap,
		@DefaultValue("50") int toolCallCap) {
	public RunProperties {
		new RunBudget(wall, tokenCap, toolCallCap);
	}

	public RunBudget budget() {
		return new RunBudget(this.wall, this.tokenCap, this.toolCallCap);
	}
}
