package com.example.miniagent.core.run;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.example.miniagent.core.ToolInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.model.ToolContext;
import static org.assertj.core.api.Assertions.*;

/** The only guard against workspace edits between two tool calls of one attempt: it refuses before the next effect. */
class ToolJournalDriftGuardTest {
	private static final String FIRST_ARGS = "{\"n\":1}";
	private static final String SECOND_ARGS = "{\"n\":2}";
	private static final String DRIFT_MESSAGE = "Workspace drift before effect";
	@TempDir Path temp;

	private enum Edit {
		CREATE { void apply(Path cwd) throws Exception { Files.writeString(cwd.resolve("created.txt"), "new"); } },
		MODIFY { void apply(Path cwd) throws Exception { Files.writeString(cwd.resolve("source.txt"), "after"); } },
		DELETE { void apply(Path cwd) throws Exception { Files.delete(cwd.resolve("source.txt")); } },
		CHMOD { void apply(Path cwd) throws Exception {
			Files.setPosixFilePermissions(cwd.resolve("source.txt"), PosixFilePermissions.fromString("rwxr-x---"));
		} };
		abstract void apply(Path cwd) throws Exception;
	}

	private static String call(SafeResumeTest.Fixture f, String input, ToolInterceptor.Chain chain) {
		return f.journal.intercept(new ToolInterceptor.ToolInvocation(f.tool, input,
				new ToolContext(Map.of(RunContext.TOOL_CONTEXT_KEY, RunContext.current().orElseThrow()))), chain);
	}

	private static long count(SafeResumeTest.Fixture f, RunRecord record, Class<? extends RunEvent> type) throws Exception {
		return FileRunStore.events(f.runs, record.spec().runId()).stream().filter(type::isInstance).count();
	}

	/** Two different calls with an outside edit between them; reports what the second call hit. */
	private record Outcome(RunRecord record, Stop refused, int effects, boolean secondChainEntered) {}

	private Outcome editBetweenTwoCalls(SafeResumeTest.Fixture f, Edit edit) throws Exception {
		Files.writeString(f.cwd.resolve("source.txt"), "before");
		var effects = new AtomicInteger();
		var secondChainEntered = new AtomicBoolean();
		var refused = new AtomicReference<Stop>();
		f.action.set(p -> {
			call(f, FIRST_ARGS, i -> { effects.incrementAndGet(); return "one"; });
			try {
				edit.apply(f.cwd);
				call(f, SECOND_ARGS, i -> { secondChainEntered.set(true); effects.incrementAndGet(); return "two"; });
			}
			catch (Stop stop) { refused.set(stop); throw stop; }
			catch (Exception ex) { throw new IllegalStateException(ex); }
		});
		return new Outcome(f.start(), refused.get(), effects.get(), secondChainEntered.get());
	}

	@ParameterizedTest @EnumSource(Edit.class)
	void outsideEditBetweenTwoCallsStopsTheSecondBeforeItsEffect(Edit edit) throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		var outcome = editBetweenTwoCalls(f, edit);

		assertThat(outcome.refused()).as("the second call must be stopped").isNotNull();
		assertThat(outcome.refused().kind()).isEqualTo(Stop.Kind.INTERRUPTED);
		assertThat(outcome.refused()).hasMessage(DRIFT_MESSAGE);
		assertThat(outcome.secondChainEntered()).isFalse();
		assertThat(outcome.effects()).isEqualTo(1);
		assertThat(outcome.record().state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(outcome.record().uncertainTools()).as("the refused call never started").isEmpty();
		assertThat(count(f, outcome.record(), RunEvent.ToolStarted.class)).isEqualTo(1);
		assertThat(count(f, outcome.record(), RunEvent.ToolFinished.class)).isEqualTo(1);
	}

	@Test void withoutAnOutsideEditTwoDifferentCallsBothRun() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		var effects = new AtomicInteger();
		f.action.set(p -> {
			call(f, FIRST_ARGS, i -> { effects.incrementAndGet(); return "one"; });
			call(f, SECOND_ARGS, i -> { effects.incrementAndGet(); return "two"; });
		});
		var record = f.start();

		assertThat(record.state()).isEqualTo(RunState.COMPLETED);
		assertThat(effects).hasValue(2);
		assertThat(count(f, record, RunEvent.ToolFinished.class)).isEqualTo(2);
	}

	@Test void editsMadeByTheToolItselfAdvanceTheBaselineInsteadOfCountingAsDrift() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		var effects = new AtomicInteger();
		f.action.set(p -> {
			call(f, FIRST_ARGS, i -> {
				effects.incrementAndGet();
				try { return Files.writeString(f.cwd.resolve("by-tool.txt"), "tool output").toString(); }
				catch (java.io.IOException ex) { throw new UncheckedIOException(ex); }
			});
			call(f, SECOND_ARGS, i -> { effects.incrementAndGet(); return "two"; });
		});
		var record = f.start();

		assertThat(record.state()).isEqualTo(RunState.COMPLETED);
		assertThat(effects).hasValue(2);
		assertThat(f.cwd.resolve("by-tool.txt")).hasContent("tool output");
	}

	@Test void editBeforeTheFirstCallIsDriftAgainstTheAttemptSnapshot() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		var chainEntered = new AtomicBoolean();
		var refused = new AtomicReference<Stop>();
		f.action.set(p -> {
			try {
				Files.writeString(f.cwd.resolve("early.txt"), "edited after the attempt began");
				call(f, FIRST_ARGS, i -> { chainEntered.set(true); return "one"; });
			}
			catch (Stop stop) { refused.set(stop); throw stop; }
			catch (java.io.IOException ex) { throw new UncheckedIOException(ex); }
		});
		var record = f.start();

		assertThat(refused.get()).as("the first call must be stopped").isNotNull();
		assertThat(refused.get().kind()).isEqualTo(Stop.Kind.INTERRUPTED);
		assertThat(refused.get()).hasMessage(DRIFT_MESSAGE);
		assertThat(chainEntered).isFalse();
		assertThat(record.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(record.uncertainTools()).isEmpty();
		assertThat(f.runs.export(record.spec().runId()).eventsLog()).doesNotContain("ToolStarted");
	}

	@Test void readOnlyToolsAreGuardedToo() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, true);
		var refused = new AtomicReference<Stop>();
		var effects = new AtomicInteger();
		f.action.set(p -> {
			call(f, FIRST_ARGS, i -> { effects.incrementAndGet(); return "one"; });
			try {
				Files.writeString(f.cwd.resolve("human.txt"), "edit");
				call(f, SECOND_ARGS, i -> { effects.incrementAndGet(); return "two"; });
			}
			catch (Stop stop) { refused.set(stop); throw stop; }
			catch (java.io.IOException ex) { throw new UncheckedIOException(ex); }
		});
		var record = f.start();

		assertThat(refused.get()).as("the second read-only call must be stopped").hasMessage(DRIFT_MESSAGE);
		assertThat(effects).hasValue(1);
		assertThat(record.state()).isEqualTo(RunState.INTERRUPTED);
	}

	@Test void generatedSecretAndStateFilesAreExcludedFromTheDriftCheck() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		var effects = new AtomicInteger();
		f.action.set(p -> {
			call(f, FIRST_ARGS, i -> { effects.incrementAndGet(); return "one"; });
			try {
				Files.createDirectory(f.cwd.resolve("target"));
				Files.writeString(f.cwd.resolve("target").resolve("classes.txt"), "build output");
				Files.writeString(f.cwd.resolve(".env.local"), "TOKEN=synthetic");
			}
			catch (java.io.IOException ex) { throw new UncheckedIOException(ex); }
			call(f, SECOND_ARGS, i -> { effects.incrementAndGet(); return "two"; });
		});
		var record = f.start();

		assertThat(record.state()).isEqualTo(RunState.COMPLETED);
		assertThat(effects).hasValue(2);
	}
}
