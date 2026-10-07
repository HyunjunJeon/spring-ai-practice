package com.example.miniagent.core.run;

import java.io.IOException;
import java.util.*;

/** Verified result identities and bounded text only; never tool-call message replay. */
public final class JournalResumeContributor implements RunResumeContributor {
	private final RunStore runs;
	private final ToolEvidenceStore evidence;
	public JournalResumeContributor(RunStore runs, ToolEvidenceStore evidence) {
		this.runs = runs; this.evidence = evidence;
	}

	@Override public String verifiedEvidence(RunRecord record, String originalGoal) throws IOException {
		StringBuilder text = new StringBuilder();
		Map<String, RunEvent.ToolStarted> starts = new HashMap<>();
		for (RunEvent event : FileRunStore.events(this.runs, record.spec().runId())) {
			if (event instanceof RunEvent.ToolStarted start) starts.put(start.attemptId() + ":" + start.toolCallKey(), start);
			if (event instanceof RunEvent.ToolFinished finish) {
				var result = this.evidence.get(finish.resultDigest(), ToolEvidenceStore.ResultEvidence.class);
				DriftGate.validateResult(result, record.spec(), finish);
				var start = starts.get(finish.attemptId() + ":" + finish.toolCallKey());
				if (start == null || !start.toolName().equals(result.toolName()) || !start.argsDigest().equals(result.argsDigest()))
					throw new IOException("Result does not match started tool");
				text.append("\nVerified tool result ").append(result.toolName()).append(" key=")
						.append(result.toolCallKey()).append(" effect=").append(start.effect())
						.append(" sha256=").append(finish.resultDigest()).append("\n");
				text.append(result.result(), 0, Math.min(result.result().length(), 2048)).append("\n");
				if (result.result().length() > 2048) text.append("[result excerpt truncated; hash identifies full private evidence]\n");
			}
			if (event instanceof RunEvent.ToolResolution resolution)
				text.append("\nUNKNOWN acknowledged: ").append(resolution.toolCallKey()).append(" ")
						.append(resolution.decision()).append("; not verified success. Operator=")
						.append(resolution.operatorId()).append(" evidence=").append(resolution.evidenceId()).append("\n");
			if (text.length() > 32768) throw new IOException("Resume evidence exceeds text bound");
		}
		for (var unknown : record.uncertainTools())
			text.append("\nUNKNOWN: ").append(unknown.toolName()).append(" key=").append(unknown.toolCallKey()).append("\n");
		return text.toString();
	}
}
