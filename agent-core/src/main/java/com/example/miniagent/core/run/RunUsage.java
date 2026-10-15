package com.example.miniagent.core.run;

/** Unknown is sticky and not equivalent to a measured zero. Deltas, never loop totals. */
public record RunUsage(long knownTokens, boolean tokensUnknown, long toolCalls,
		long activeMillis, long overshootTokens) {
	public RunUsage {
		if (knownTokens < 0 || toolCalls < 0 || activeMillis < 0 || overshootTokens < 0) {
			throw new IllegalArgumentException("Negative usage");
		}
	}

	public static RunUsage empty() {
		return new RunUsage(0, false, 0, 0, 0);
	}

	public RunUsage plus(RunUsage other) {
		return new RunUsage(Math.addExact(knownTokens, other.knownTokens), tokensUnknown || other.tokensUnknown,
				Math.addExact(toolCalls, other.toolCalls), Math.addExact(activeMillis, other.activeMillis),
				Math.addExact(overshootTokens, other.overshootTokens));
	}
}
