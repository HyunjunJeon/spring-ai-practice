package com.example.miniagent.core.run;

import java.util.List;

public record RunRecord(RunSpec spec, long sequence, RunState state, RunEvent.Reason reason,
		List<Attempt> attempts, RunUsage usage, List<RunEvent.ToolStarted> uncertainTools) {
	public RunRecord {
		attempts = List.copyOf(attempts);
		uncertainTools = List.copyOf(uncertainTools);
	}
}
