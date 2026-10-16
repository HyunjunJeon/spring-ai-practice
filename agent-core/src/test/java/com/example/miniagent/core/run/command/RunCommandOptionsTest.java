package com.example.miniagent.core.run.command;

import java.util.List;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.run.DriftGate;
import com.example.miniagent.core.run.RunEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

/** `resumeOptions` is shared by /run, /delegate, /goal and the worker: a parse slip would pass wrong operator evidence. */
class RunCommandOptionsTest {
	private static final String OPERATOR = "console-operator";
	private static final String EXPECTED = "a".repeat(64);
	private static final String ACTUAL = "b".repeat(64);

	/** Resume arguments always begin with the verb and the run id; option parsing starts at index 2. */
	private static String[] resume(String... options) {
		String[] parts = new String[options.length + 2];
		parts[0] = "resume";
		parts[1] = "run-1";
		System.arraycopy(options, 0, parts, 2, options.length);
		return parts;
	}

	private static RunExecutor.ResumeOptions parse(String... options) {
		return RunCommand.resumeOptions(resume(options), OPERATOR);
	}

	@Test void noOptionsMeansNoResolutionAndNoDriftAcknowledgement() {
		var options = parse();
		assertThat(options.resolutions()).isEmpty();
		assertThat(options.drift()).isNull();
	}

	@Test void resolveWithExactlyFiveTokensIsParsedWithTheOperatorOfTheCaller() {
		var options = parse("--resolve", "attempt-1", "key-1", "SKIP", "evidence-1");
		assertThat(options.resolutions()).containsExactly(new RunExecutor.ToolResolutionInput(
				"attempt-1", "key-1", RunEvent.Resolution.SKIP, OPERATOR, "evidence-1"));
		assertThat(options.drift()).isNull();
	}

	@Test void everyResolutionDecisionNameIsAccepted() {
		for (RunEvent.Resolution decision : RunEvent.Resolution.values()) {
			var options = parse("--resolve", "attempt-1", "key-1", decision.name(), "evidence-1");
			assertThat(options.resolutions()).singleElement()
					.satisfies(resolution -> assertThat(resolution.decision()).isEqualTo(decision));
		}
	}

	@Test void repeatedResolveKeepsOperatorOrder() {
		var options = parse("--resolve", "attempt-1", "key-1", "SKIP", "evidence-1",
				"--resolve", "attempt-2", "key-2", "MUTATING_RETRY", "evidence-2");
		assertThat(options.resolutions()).extracting(RunExecutor.ToolResolutionInput::toolCallKey)
				.containsExactly("key-1", "key-2");
		assertThat(options.resolutions()).extracting(RunExecutor.ToolResolutionInput::decision)
				.containsExactly(RunEvent.Resolution.SKIP, RunEvent.Resolution.MUTATING_RETRY);
	}

	@Test void ackDriftWithExactlyFourTokensIsParsedWithTheOperatorOfTheCaller() {
		var options = parse("--ack-drift", EXPECTED, ACTUAL, "evidence-1");
		assertThat(options.resolutions()).isEmpty();
		assertThat(options.drift()).isEqualTo(new DriftGate.Acknowledgement(EXPECTED, ACTUAL, OPERATOR, "evidence-1"));
	}

	@Test void resolveAndAckDriftParseTogetherInEitherOrder() {
		var resolveFirst = parse("--resolve", "attempt-1", "key-1", "SKIP", "evidence-1",
				"--ack-drift", EXPECTED, ACTUAL, "evidence-2");
		var driftFirst = parse("--ack-drift", EXPECTED, ACTUAL, "evidence-2",
				"--resolve", "attempt-1", "key-1", "SKIP", "evidence-1");
		assertThat(driftFirst).isEqualTo(resolveFirst);
		assertThat(resolveFirst.resolutions()).hasSize(1);
		assertThat(resolveFirst.drift().evidenceId()).isEqualTo("evidence-2");
	}

	@Test void differentCallersGetTheirOwnOperatorIdentity() {
		var options = RunCommand.resumeOptions(resume("--resolve", "attempt-1", "key-1", "SKIP", "evidence-1"), "worker-9");
		assertThat(options.resolutions()).singleElement()
				.satisfies(resolution -> assertThat(resolution.operatorId()).isEqualTo("worker-9"));
	}

	static List<List<String>> malformedOptions() {
		return List.of(
				// Truncated: the flag needs four (resolve) or three (ack-drift) values behind it.
				List.of("--resolve"),
				List.of("--resolve", "attempt-1", "key-1", "SKIP"),
				List.of("--ack-drift"),
				List.of("--ack-drift", EXPECTED, ACTUAL),
				// A second acknowledgement must not silently replace the first.
				List.of("--ack-drift", EXPECTED, ACTUAL, "evidence-1", "--ack-drift", ACTUAL, EXPECTED, "evidence-2"),
				// Unknown or stray tokens are never ignored.
				List.of("--accept-drift"),
				List.of("--resolve", "attempt-1", "key-1", "SKIP", "evidence-1", "--force"),
				List.of("stray"),
				List.of("--ack-drift", EXPECTED, ACTUAL, "evidence-1", "trailing"));
	}

	@ParameterizedTest @MethodSource("malformedOptions")
	void malformedOptionListsAreRefused(List<String> options) {
		assertThatIllegalArgumentException().isThrownBy(() -> parse(options.toArray(String[]::new)))
				.withMessage("Invalid resume option");
	}

	@Test void unknownResolutionNameIsRefusedAndNeverFallsBackToADefault() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> parse("--resolve", "attempt-1", "key-1", "RETRY", "evidence-1"))
				.withMessageContaining("No enum constant").withMessageContaining("RETRY");
	}

	@Test void resolutionNamesAreCaseSensitive() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> parse("--resolve", "attempt-1", "key-1", "skip", "evidence-1"))
				.withMessageContaining("No enum constant");
	}

	@Test void aFlagCannotBeSwallowedAsAnAttemptOrEvidenceId() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> parse("--resolve", "--ack-drift", "key-1", "SKIP", "evidence-1"))
				.withMessage("Unsafe execution ID");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> parse("--ack-drift", EXPECTED, ACTUAL, "--resolve", "a", "k", "SKIP", "e"))
				.withMessage("Unsafe execution ID");
	}

	@Test void unsafeIdentifiersAreRefused() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> parse("--resolve", "../attempt", "key-1", "SKIP", "evidence-1"))
				.withMessage("Unsafe execution ID");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> parse("--resolve", "attempt-1", "key/1", "SKIP", "evidence-1"))
				.withMessage("Unsafe execution ID");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> parse("--resolve", "attempt-1", "key-1", "SKIP", "evidence 1"))
				.withMessage("Unsafe execution ID");
	}

	@Test void driftDigestsMustBeSha256Hex() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> parse("--ack-drift", "not-a-digest", ACTUAL, "evidence-1"))
				.withMessage("Invalid SHA-256 digest");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> parse("--ack-drift", EXPECTED, "B".repeat(64), "evidence-1"))
				.withMessage("Invalid SHA-256 digest");
		assertThatIllegalArgumentException()
				.isThrownBy(() -> parse("--ack-drift", EXPECTED, ACTUAL, "bad id"))
				.withMessage("Unsafe execution ID");
	}
}
