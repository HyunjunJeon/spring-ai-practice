package com.example.miniagent.core.run;

import java.time.Duration;
import java.util.Objects;

/** Active wall time excludes queue and approval waits. Reservation uses this same type. */
public record RunBudget(Duration wall, Long tokenCap, int toolCallCap) {
	public RunBudget {
		Objects.requireNonNull(wall);
		if (wall.isNegative() || wall.isZero() || (tokenCap != null && tokenCap < 0) || toolCallCap < 0) {
			throw new IllegalArgumentException("Invalid run budget");
		}
		wall.toMillis();
	}

	public static RunBudget defaults() {
		return new RunBudget(Duration.ofMinutes(30), null, 50);
	}
}
