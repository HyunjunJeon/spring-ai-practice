package com.example.miniagent.core.goal;

import java.time.Duration;
import java.util.List;
import com.example.miniagent.core.run.RunBudget;

/** Operator authority. Neither model prose nor a saved tool result may add gates. */
public record GoalRequest(String goal, List<Gate> gates, int maxSteps, long wallMillis,
		Long tokenCap, int toolCallCap) {
	public record Gate(List<String> argv, long timeoutMillis, int maxOutputBytes) {
		public Gate {
			argv = List.copyOf(argv);
			if (argv.isEmpty() || argv.size() > 64 || argv.getFirst().isBlank()
					|| argv.stream().anyMatch(a -> a == null || a.length() > 4096 || a.indexOf('\0') >= 0)
					|| argv.stream().mapToInt(String::length).sum() > 16384
					|| timeoutMillis < 1 || timeoutMillis > 3_600_000
					|| maxOutputBytes < 1 || maxOutputBytes > 65536)
				throw new IllegalArgumentException("Invalid bounded gate");
		}
	}
	public GoalRequest {
		if (goal == null || goal.isBlank() || goal.length() > 32768
				|| goal.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
			throw new IllegalArgumentException("Goal must contain 1..65536 UTF-8 bytes");
		gates = List.copyOf(gates);
		if (gates.isEmpty() || gates.size() > 8 || maxSteps < 1 || maxSteps > 32
				|| wallMillis < 1 || wallMillis > 86_400_000 || tokenCap != null && tokenCap < 0
				|| toolCallCap < 0 || toolCallCap > 10000)
			throw new IllegalArgumentException("Invalid bounded goal");
	}
	public RunBudget budget() { return new RunBudget(Duration.ofMillis(wallMillis), tokenCap, toolCallCap); }
}
