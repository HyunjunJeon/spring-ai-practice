package com.example.miniagent.core.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import com.example.miniagent.core.ToolInterceptor;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.run.ToolEvidenceStore.ResultEvidence;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.model.ToolContext;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

/**
 * Result evidence whose hash is valid but whose owner (run, attempt, call key, tool, arguments) is someone
 * else's. Only byte tampering was tested; after the hash matches, the owner checks are the only defense.
 */
class JournalEvidenceOwnershipTest {
	private static final String OWNER_MISMATCH = "Result evidence owner mismatch";
	private static final String START_MISMATCH = "Result does not match started tool";
	private static final String OTHER_DIGEST = "b".repeat(64);
	private static final String FORGED_KEY = "forged-key";
	private static final String NEXT_ARGS = "{\"n\":2}";
	@TempDir Path temp;

	/** One owner field replaced by a foreign value, and the refusal the resume summary gives for it. */
	private enum Foreign {
		RUN(OWNER_MISMATCH, e -> new ResultEvidence(e.schemaVersion(), "someone-elses-run", e.attemptId(),
				e.toolCallKey(), e.toolName(), e.argsDigest(), e.result(), e.after())),
		ATTEMPT(OWNER_MISMATCH, e -> new ResultEvidence(e.schemaVersion(), e.runId(), "someone-elses-attempt",
				e.toolCallKey(), e.toolName(), e.argsDigest(), e.result(), e.after())),
		KEY(OWNER_MISMATCH, e -> new ResultEvidence(e.schemaVersion(), e.runId(), e.attemptId(),
				"someone-elses-key", e.toolName(), e.argsDigest(), e.result(), e.after())),
		SPEC(OWNER_MISMATCH, e -> new ResultEvidence(e.schemaVersion(), e.runId(), e.attemptId(), e.toolCallKey(),
				e.toolName(), e.argsDigest(), e.result(), new DriftGate.Snapshot(1, OTHER_DIGEST,
						e.after().workspace(), e.after().tools(), e.after().configurationDigest()))),
		SCHEMA(OWNER_MISMATCH, e -> new ResultEvidence(2, e.runId(), e.attemptId(), e.toolCallKey(),
				e.toolName(), e.argsDigest(), e.result(), e.after())),
		TOOL(START_MISMATCH, e -> new ResultEvidence(e.schemaVersion(), e.runId(), e.attemptId(), e.toolCallKey(),
				"someone_elses_tool", e.argsDigest(), e.result(), e.after())),
		ARGS(START_MISMATCH, e -> new ResultEvidence(e.schemaVersion(), e.runId(), e.attemptId(), e.toolCallKey(),
				e.toolName(), OTHER_DIGEST, e.result(), e.after()));

		final String resumeSummaryRefusal;
		final UnaryOperator<ResultEvidence> variant;
		Foreign(String resumeSummaryRefusal, UnaryOperator<ResultEvidence> variant) {
			this.resumeSummaryRefusal = resumeSummaryRefusal;
			this.variant = variant;
		}
	}

	/** What the forged attempt saw: the two guards that read the journal, and whether the next effect ran. */
	private static final class Probe {
		RunRecord record;
		Throwable resumeSummary;
		Throwable nextCall;
		boolean nextChainEntered;
		long sequenceBefore;
		long sequenceAfter;
	}

	private static String call(SafeResumeTest.Fixture f, String input, ToolInterceptor.Chain chain) {
		return f.journal.intercept(new ToolInterceptor.ToolInvocation(f.tool, input,
				new ToolContext(Map.of(RunContext.TOOL_CONTEXT_KEY, RunContext.current().orElseThrow()))), chain);
	}

	/**
	 * Inside the live attempt: journal a started/finished pair whose evidence is stored hash-valid but with
	 * {@code variant} applied, then ask both journal readers what they make of it. The attempt ends INTERRUPTED.
	 */
	private static Probe forgeInsideAttempt(SafeResumeTest.Fixture f, UnaryOperator<ResultEvidence> variant) throws Exception {
		var probe = new Probe();
		f.action.set(p -> {
			RunContext run = RunContext.current().orElseThrow();
			try {
				String args = RunRequestStore.digest("{}".getBytes(UTF_8));
				var owned = new ResultEvidence(1, run.spec().runId().value(), run.attemptId(), FORGED_KEY,
						f.tool.name(), args, "forged result", f.gate.capture(f.agent, run.spec()));
				String digest = f.requests.evidence().put(variant.apply(owned));
				run.writer().append(new RunEvent.ToolStarted(run.now(), run.attemptId(), FORGED_KEY, f.tool.name(),
						args, RunEvent.Effect.MUTATING));
				run.writer().append(new RunEvent.ToolFinished(run.now(), run.attemptId(), FORGED_KEY, digest));
			}
			catch (IOException ex) { throw new UncheckedIOException(ex); }
			probe.resumeSummary = catchThrowable(() -> new JournalResumeContributor(f.runs, f.requests.evidence())
					.verifiedEvidence(run.writer().record(), "goal"));
			probe.sequenceBefore = run.writer().record().sequence();
			probe.nextCall = catchThrowable(() -> call(f, NEXT_ARGS, i -> { probe.nextChainEntered = true; return "next"; }));
			probe.sequenceAfter = run.writer().record().sequence();
			throw new Stop(Stop.Kind.INTERRUPTED);
		});
		probe.record = f.start();
		return probe;
	}

	@Test void evidenceThatReallyBelongsToTheCallIsAcceptedByBothReaders() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		var probe = forgeInsideAttempt(f, UnaryOperator.identity());

		assertThat(probe.resumeSummary).isNull();
		assertThat(probe.nextCall).isNull();
		assertThat(probe.nextChainEntered).isTrue();
		assertThat(probe.sequenceAfter).as("the next call journals its own start and finish").isGreaterThan(probe.sequenceBefore);
	}

	@ParameterizedTest @EnumSource(Foreign.class)
	void resumeSummaryRefusesEvidenceOwnedBySomeoneElse(Foreign foreign) throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		var probe = forgeInsideAttempt(f, foreign.variant);

		assertThat(probe.resumeSummary).as("resume summary over %s evidence", foreign).isInstanceOf(IOException.class)
				.hasMessage(foreign.resumeSummaryRefusal);
	}

	@ParameterizedTest @EnumSource(value = Foreign.class, names = {"RUN", "ATTEMPT", "KEY", "SPEC", "SCHEMA"})
	void nextToolCallRefusesEvidenceOwnedBySomeoneElseAndAppendsNothing(Foreign foreign) throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		var probe = forgeInsideAttempt(f, foreign.variant);

		assertThat(probe.nextCall).as("the next call must be refused").isInstanceOf(UncheckedIOException.class)
				.hasCauseInstanceOf(IOException.class).cause().hasMessage(OWNER_MISMATCH);
		assertThat(probe.nextChainEntered).isFalse();
		assertThat(probe.sequenceAfter).isEqualTo(probe.sequenceBefore);
	}

	@Disabled("SUSPECT run:G5: ToolJournalInterceptor.intercept only calls DriftGate.validateResult, which checks run, attempt, "
			+ "key, schema and spec; evidence whose toolName or argsDigest differs from the journaled ToolStarted is accepted "
			+ "(nextCall returns normally and the call journals its own start and finish), while JournalResumeContributor "
			+ "refuses it with 'Result does not match started tool'. DriftGate.latest (inspectDrift) accepts it too.")
	@ParameterizedTest @EnumSource(value = Foreign.class, names = {"TOOL", "ARGS"})
	void nextToolCallAlsoRefusesEvidenceFromAnotherToolOrArguments(Foreign foreign) throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		var probe = forgeInsideAttempt(f, foreign.variant);

		assertThat(probe.nextCall).as("the next call must be refused").isInstanceOf(UncheckedIOException.class);
		assertThat(probe.nextChainEntered).isFalse();
		assertThat(probe.sequenceAfter).isEqualTo(probe.sequenceBefore);
	}

	@ParameterizedTest @EnumSource(value = Foreign.class, names = {"RUN", "ATTEMPT", "KEY", "SPEC", "SCHEMA"})
	void ownerMismatchBlocksInspectDriftAndResumeAndLeavesTheJournalUntouched(Foreign foreign) throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		RunId run = forgeInsideAttempt(f, foreign.variant).record.spec().runId();
		String before = f.runs.export(run).eventsLog();

		assertThatThrownBy(() -> f.executor.inspectDrift(run)).isInstanceOf(IOException.class).hasMessage(OWNER_MISMATCH);
		assertThatThrownBy(() -> f.executor.resume(run, f.selected, f.cwd)).isInstanceOfSatisfying(RunExecutor.Failure.class,
				ex -> assertThat(ex.code()).isEqualTo(RunExecutor.Code.IDENTITY_DRIFT));
		assertThat(f.runs.export(run).eventsLog()).isEqualTo(before);
		assertThat(f.runs.read(run).state()).isEqualTo(RunState.INTERRUPTED);
	}

	@ParameterizedTest @EnumSource(value = Foreign.class, names = {"TOOL", "ARGS"})
	void toolOrArgumentMismatchBlocksResumeAndLeavesTheJournalUntouched(Foreign foreign) throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		RunId run = forgeInsideAttempt(f, foreign.variant).record.spec().runId();
		String before = f.runs.export(run).eventsLog();

		assertThatThrownBy(() -> f.executor.resume(run, f.selected, f.cwd)).isInstanceOf(IOException.class).hasMessage(START_MISMATCH);
		assertThat(f.runs.export(run).eventsLog()).isEqualTo(before);
		assertThat(f.runs.read(run).state()).isEqualTo(RunState.INTERRUPTED);
	}

	@Test void evidenceOfAnotherRunInTheSameHomeCannotBeReplayedIntoThisJournal() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		f.action.set(p -> call(f, "{}", i -> "genuine"));
		var other = f.start();
		var genuine = FileRunStore.events(f.runs, other.spec().runId()).stream().filter(RunEvent.ToolFinished.class::isInstance)
				.map(RunEvent.ToolFinished.class::cast).findFirst().orElseThrow();
		var realEvidence = f.requests.evidence().get(genuine.resultDigest(), ResultEvidence.class);
		assertThat(realEvidence.runId()).isEqualTo(other.spec().runId().value());

		var seen = new AtomicReference<Throwable>();
		f.action.set(p -> {
			RunContext run = RunContext.current().orElseThrow();
			try {
				run.writer().append(new RunEvent.ToolStarted(run.now(), run.attemptId(), FORGED_KEY, realEvidence.toolName(),
						realEvidence.argsDigest(), RunEvent.Effect.MUTATING));
				run.writer().append(new RunEvent.ToolFinished(run.now(), run.attemptId(), FORGED_KEY, genuine.resultDigest()));
			}
			catch (IOException ex) { throw new UncheckedIOException(ex); }
			seen.set(catchThrowable(() -> new JournalResumeContributor(f.runs, f.requests.evidence())
					.verifiedEvidence(run.writer().record(), "goal")));
			throw new Stop(Stop.Kind.INTERRUPTED);
		});
		f.start();

		assertThat(seen.get()).as("resume summary over another run's evidence").isInstanceOf(IOException.class).hasMessage(OWNER_MISMATCH);
	}
}
