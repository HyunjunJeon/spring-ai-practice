package com.example.miniagent.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunRecord;
import com.example.miniagent.core.run.RunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** A new root run reads every entry of runs/; an unrelated damaged entry must not take it down. */
class RunStoreIsolationTest {
	private static final String DONE = "operation-finished";

	enum Damage { STRAY_FILE, CORRUPT_RUN, FUTURE_RUN }

	@TempDir Path directory;
	private Path home;
	private Path cwd;
	private CrashRecoveryProcessFixture.RuntimeFixture runtime;

	@BeforeEach
	void runtime() throws Exception {
		this.home = Files.createDirectory(this.directory.resolve("home"));
		this.cwd = Files.createDirectory(this.directory.resolve("work")).toRealPath();
		this.runtime = new CrashRecoveryProcessFixture.RuntimeFixture(this.home, this.cwd, false);
	}

	@Test void healthyUnrelatedRunNeitherBlocksNorChargesANewRootRun() throws Exception {
		RunRecord first = operation(context -> DONE).record();
		var second = operation(context -> DONE);
		assertThat(first.state()).isEqualTo(RunState.COMPLETED);
		assertThat(second.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(second.value()).isEqualTo(DONE);
		// Child reconciliation visited the first run and charged nothing: it is not a child.
		assertThat(FileRunStore.events(this.runtime.runs, second.record().spec().runId()))
				.noneMatch(event -> event instanceof RunEvent.UsageRecorded usage && usage.callId().startsWith("child-"));
	}

	@ParameterizedTest
	@EnumSource(Damage.class)
	void damagedUnrelatedEntryDoesNotLoseANewRootRun(Damage damage) throws Exception {
		damage(damage);
		Set<Path> existing = runDirectories();
		var outcome = new AtomicReference<RunExecutor.OperationResult<String>>();
		Throwable refused = catchThrowable(() -> outcome.set(operation(context -> DONE)));
		if (refused == null) {
			assertThat(outcome.get().record().state()).isEqualTo(RunState.COMPLETED);
			assertThat(outcome.get().value()).isEqualTo(DONE);
			return;
		}
		// Failing closed is acceptable only before the new run has started an attempt.
		for (Path run : runDirectories()) {
			if (existing.contains(run)) continue;
			assertThat(read(run).attempts()).as("refused with %s", refused).isEmpty();
		}
	}

	@Test void strayFileAppearingDuringTheTurnDoesNotTurnTheFinishedTurnIntoProcessLost() throws Exception {
		var result = operation(context -> {
			try { damage(Damage.STRAY_FILE); }
			catch (IOException ex) { throw new UncheckedIOException(ex); }
			return DONE;
		});
		assertThat(result.value()).isEqualTo(DONE);
		RunRecord settled = this.runtime.runs.read(result.record().spec().runId());
		assertThat(settled.reason()).isNotEqualTo(RunEvent.Reason.PROCESS_LOST);
		assertThat(settled.attempts()).singleElement().satisfies(attempt -> assertThat(attempt.endedAt()).isNotNull());
	}

	private RunExecutor.OperationResult<String> operation(Function<RunContext, String> work) throws IOException {
		return this.runtime.executor.startOperation("isolated root run", CrashRecoveryProcessFixture.ORIGINAL,
				CrashRecoveryProcessFixture.SELECTED, this.cwd, RunBudget.defaults(), work);
	}

	private void damage(Damage kind) throws IOException {
		switch (kind) {
			case STRAY_FILE -> Files.writeString(this.home.resolve("runs").resolve(".DS_Store"), "finder metadata");
			case CORRUPT_RUN -> {
				Path log = completedRunDirectory().resolve("events.log");
				Files.writeString(log, Files.readString(log).replaceFirst("RUNNING", "FAILED"));
			}
			case FUTURE_RUN -> Files.writeString(completedRunDirectory().resolve("spec.json"), "{\"schemaVersion\":2}");
		}
	}

	private Path completedRunDirectory() throws IOException {
		return this.home.resolve("runs").resolve(operation(context -> DONE).record().spec().runId().value());
	}

	private Set<Path> runDirectories() throws IOException {
		try (var entries = Files.list(this.home.resolve("runs"))) {
			return entries.filter(Files::isDirectory).collect(Collectors.toSet());
		}
	}

	private RunRecord read(Path run) throws IOException {
		return this.runtime.runs.read(new RunId(run.getFileName().toString()));
	}
}
