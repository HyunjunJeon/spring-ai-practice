package com.example.miniagent.core.goal;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import com.example.miniagent.core.change.FileChangeStore;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Negative recovery cases only; no synthetic model response or fabricated successful gate. */
class GoalRecoveryTest {
	@TempDir Path home;
	@TempDir Path work;
	@org.junit.jupiter.api.BeforeEach void canonicalPaths() throws IOException {
		home = home.toRealPath(); work = work.toRealPath();
	}
	private GoalRecord pending(GoalStore store) throws IOException {
		var spec = new GoalRecord.Spec(new GoalRequest("goal", List.of(new GoalRequest.Gate(
				List.of("/usr/bin/true"), 1000, 1024)), 6, 10000, null, 5),
				new ModelSelection(new ModelRef("codex", "gpt-6.1-sol")), "parent", work.toRealPath().toString(),
				RunId.random());
		var record = new GoalRecord(1, java.util.UUID.randomUUID().toString(), spec,
				GoalRecord.State.PENDING, GoalRecord.Reason.NONE, 0, GoalRecord.Phase.READY, null, List.of(), false, "");
		store.create(record);
		return record;
	}
	private GoalService service(GoalStore store, RunStore runs) throws IOException {
		var changes = new FileChangeStore(home, work);
		return new GoalService(store, () -> { throw new AssertionError("Model authority resolved"); },
				() -> { throw new AssertionError("Executor must not restart terminal runs"); }, () -> runs,
				() -> changes, () -> { throw new AssertionError("No candidate evidence"); });
	}
	private RunStore observed(GoalRecord goal, RunState state, RunEvent.Reason reason) {
		var spec = new RunSpec(1, goal.spec().runId(), "managed-conversation", "parent", null, null,
				goal.spec().selection().ref(), "codex", goal.spec().workspace(), goal.spec().request().budget(),
				goal.spec().selection().optionsFingerprint(), "a".repeat(64), Instant.EPOCH);
		var record = new RunRecord(spec, 1, state, reason, List.of(), RunUsage.empty(), List.of());
		return new RunStore() {
			public RunRecord read(RunId id) { assertThat(id).isEqualTo(spec.runId()); return record; }
			public List<RunId> list() { throw new AssertionError("Historical scan forbidden"); }
			public RunWriter create(RunSpec input) { throw new AssertionError("New run forbidden"); }
			public RunWriter openWriter(RunId id) { throw new AssertionError("Terminal resume forbidden"); }
			public OpaqueRun export(RunId id) { throw new AssertionError("No export needed"); }
		};
	}
	@Test void completedRunWithoutVerifiedCandidateFailsInsteadOfResurrecting() throws Exception {
		var store = new GoalStore(home);
		var pending = pending(store);
		try (var claim = store.claim(pending.id())) {
			store.write(pending.checkpoint(GoalRecord.State.BLOCKED, GoalRecord.Reason.INTERRUPTED, 1,
					GoalRecord.Phase.READY, null, List.of(), false, ""));
		}
		var result = service(store, observed(pending, RunState.COMPLETED, RunEvent.Reason.NONE))
				.resume(pending.id(), RunExecutor.ResumeOptions.ordinary());
		assertThat(result.state()).isEqualTo(GoalRecord.State.FAILED);
		assertThat(result.reason()).isEqualTo(GoalRecord.Reason.RUN_FAILED);
		assertThat(result.spec().runId()).isEqualTo(pending.spec().runId());
	}
	@Test void terminalRunFailureCannotBeConvertedIntoGoalSuccess() throws Exception {
		var store = new GoalStore(home);
		var pending = pending(store);
		try (var claim = store.claim(pending.id())) {
			store.write(pending.checkpoint(GoalRecord.State.BLOCKED, GoalRecord.Reason.GATE_FAILED, 1,
					GoalRecord.Phase.READY, null, List.of(), false, ""));
		}
		var result = service(store, observed(pending, RunState.FAILED, RunEvent.Reason.ERROR))
				.resume(pending.id(), RunExecutor.ResumeOptions.ordinary());
		assertThat(result.state()).isEqualTo(GoalRecord.State.FAILED);
		assertThat(result.reason()).isEqualTo(GoalRecord.Reason.RUN_FAILED);
		assertThatThrownBy(() -> service(store, observed(pending, RunState.COMPLETED, RunEvent.Reason.NONE))
				.execute(pending.id())).isInstanceOf(IOException.class);
	}
	@Test void unacknowledgedModelCheckpointDoesNotSearchForOrReplayPriorWork() throws Exception {
		var store = new GoalStore(home);
		var pending = pending(store);
		try (var claim = store.claim(pending.id())) {
			store.write(pending.checkpoint(GoalRecord.State.RUNNING, GoalRecord.Reason.NONE, 1,
					GoalRecord.Phase.MODEL, null, List.of(), false, ""));
		}
		var result = service(store, observed(pending, RunState.INTERRUPTED, RunEvent.Reason.PROCESS_LOST))
				.resume(pending.id(), RunExecutor.ResumeOptions.ordinary());
		assertThat(result.state()).isEqualTo(GoalRecord.State.BLOCKED);
		assertThat(result.reason()).isEqualTo(GoalRecord.Reason.CHECKPOINT_UNCERTAIN);
		assertThat(result.steps()).isEqualTo(1);
	}
}
