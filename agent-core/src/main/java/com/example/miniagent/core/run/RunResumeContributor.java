package com.example.miniagent.core.run;

import java.io.IOException;

/** Verified journal evidence as text; never raw tool-call messages or invented successes. */
@FunctionalInterface
public interface RunResumeContributor {
	String verifiedEvidence(RunRecord record, String originalGoal) throws IOException;
}
