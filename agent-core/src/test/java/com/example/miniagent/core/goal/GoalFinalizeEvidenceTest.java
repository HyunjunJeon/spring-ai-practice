package com.example.miniagent.core.goal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import com.example.miniagent.core.change.ChangeOwner;
import com.example.miniagent.core.change.FileChangeStore;
import com.example.miniagent.core.change.Fingerprinter;
import com.example.miniagent.core.change.StagedWorkspace;
import com.example.miniagent.core.change.VerificationEvidence;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.change.VerificationRunner.Code;
import com.example.miniagent.core.change.VerificationTarget;
import com.example.miniagent.core.goal.GoalRecord.GateResult;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunRecord;
import com.example.miniagent.core.run.RunSpec;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunStore;
import com.example.miniagent.core.run.RunUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * goal-lesson:G6. A candidate becomes SUCCEEDED only when every gate of its latest round carries evidence
 * bound to the operator's argv, the workspace, the owner, the revision, the log and (for a proposal) the change store.
 */
class GoalFinalizeEvidenceTest {
	private static final String OTHER = "b".repeat(64);
	@TempDir Path root;

	/** One gate result and the evidence behind it; a tamper changes exactly one field. */
	private static final class Draft {
		int index;
		Code code = Code.SUCCEEDED;
		UUID evidenceId = UUID.randomUUID();
		UUID changeSetId;
		ChangeOwner owner;
		List<String> argv;
		String sourceCwd;
		String sourceRevision;
		String revision;
		String configuration = "a".repeat(64);
		final String evidenceConfiguration = "a".repeat(64);
		byte[] log;
		String logBase64;
		Integer exit = 0;
		boolean cancelled;
		boolean noEvidence;
		boolean stored;
		Instant finishedAt = Instant.EPOCH;
	}
	private enum Tamper {
		ARGV(d -> d.argv = List.of("other-gate")),
		SOURCE_CWD(d -> d.sourceCwd = "/not-the-workspace"),
		OWNER(d -> d.owner = new ChangeOwner("other-session", d.owner.runId(), d.owner.attemptId(), d.owner.turnId())),
		CHANGE_SET(d -> d.changeSetId = UUID.randomUUID()),
		RESULT_REVISION(d -> d.revision = OTHER),
		EVIDENCE_REVISION(d -> d.sourceRevision = OTHER),
		RESULT_CONFIGURATION(d -> d.configuration = OTHER),
		LOG(d -> d.logBase64 = Base64.getEncoder().encodeToString("tampered".getBytes(StandardCharsets.UTF_8))),
		NON_ZERO_EXIT(d -> d.exit = 1),
		CANCELLATION(d -> { d.exit = null; d.cancelled = true; }),
		FAILED_CODE(d -> d.code = Code.NON_ZERO_EXIT),
		MISSING_EVIDENCE(d -> d.noEvidence = true);
		final Consumer<Draft> change;
		Tamper(Consumer<Draft> change) { this.change = change; }
	}

	/** A real workspace, change store, goal store and runner; no model, process or sandbox. */
	private final class Scenario implements AutoCloseable {
		final Path workspace;
		final FileChangeStore changes;
		final GoalStore goals;
		final GoalService service;
		final StagedWorkspace stage;
		final VerificationTarget target;
		final ModelSelection selection = new ModelSelection(new ModelRef("codex", "gpt-6.1-sol"));

		/** @param proposal whether the stage holds an edit (a published proposal) or is unchanged (a read-only snapshot) */
		Scenario(boolean proposal) throws Exception {
			Path real = root.toRealPath();
			workspace = Files.createDirectory(real.resolve("workspace")).toRealPath();
			Path home = Files.createDirectory(real.resolve("home")).toRealPath();
			Files.writeString(workspace.resolve("source"), "baseline");
			changes = new FileChangeStore(home, workspace);
			goals = new GoalStore(home);
			var verifier = new VerificationRunner(changes, (context, argv) -> {
				throw new AssertionError("No verification process authorized");
			}, Clock.systemUTC());
			ChatModel neverCalled = new ChatModel() {
				@Override public ChatResponse call(Prompt prompt) { throw new AssertionError("No model invocation authorized"); }
				@Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
			};
			var binding = new ModelBinding(UUID.randomUUID(), selection.ref(), selection.optionsFingerprint(),
					"test-binding-only", neverCalled, ChatClient.builder(neverCalled).build());
			stage = StagedWorkspace.open(changes, new TurnContext("managed-session", UUID.randomUUID().toString(),
					workspace, binding, "run", "attempt"), Clock.systemUTC());
			if (proposal) Files.writeString(stage.resolve("source", true, List.of()), "edit");
			stage.record();
			target = verifier.capture(stage);
			assertThat(target.readOnly()).isEqualTo(!proposal);
			var spec = new RunSpec(1, target.owner().runId(), target.owner().sessionId(), "parent", null, null,
					selection.ref(), "codex", workspace.toString(), request(1).budget(), selection.optionsFingerprint(),
					OTHER, Instant.EPOCH);
			RunStore runs = mock(RunStore.class);
			when(runs.read(spec.runId())).thenReturn(new RunRecord(spec, 1, RunState.COMPLETED, RunEvent.Reason.NONE,
					List.of(), RunUsage.empty(), List.of()));
			service = new GoalService(goals, () -> { throw new AssertionError("Agent resolved"); },
					() -> { throw new AssertionError("Executor resolved"); }, () -> runs, () -> changes, () -> verifier);
		}
		@Override public void close() throws IOException { stage.close(); }

		GoalRequest request(int gates) {
			var operatorGates = new ArrayList<GoalRequest.Gate>();
			for (int i = 0; i < gates; i++) operatorGates.add(new GoalRequest.Gate(List.of("gate-" + i), 1000, 1024));
			return new GoalRequest("repair parser", operatorGates, 2, 10000, 1000L, 0);
		}
		Draft draft(int index) {
			var d = new Draft();
			d.index = index;
			d.argv = List.of("gate-" + index);
			d.log = ("output of gate-" + index).getBytes(StandardCharsets.UTF_8);
			d.changeSetId = target.id();
			d.owner = target.owner();
			d.sourceCwd = workspace.toString();
			d.sourceRevision = target.revision();
			d.revision = target.revision();
			d.stored = !target.readOnly();
			return d;
		}
		private VerificationEvidence evidence(Draft d, Instant finishedAt) {
			return new VerificationEvidence(d.evidenceId, d.changeSetId, d.owner, d.argv, workspace.toString(),
					d.sourceCwd, d.sourceRevision, d.evidenceConfiguration, Fingerprinter.sha256(d.log), d.exit, d.cancelled,
					Instant.EPOCH, finishedAt);
		}
		/** The gate result a goal would hold; a stored draft is also recorded in the change store as it ran. */
		GateResult result(Draft d) throws Exception {
			if (d.stored) try (var lease = changes.acquire()) {
				lease.recordEvidence(target.id(), target.owner(), target.digest(), evidence(d, Instant.EPOCH), d.log);
			}
			return new GoalRecord.GateResult(d.index, d.revision, d.configuration, d.code,
					d.noEvidence ? null : evidence(d, d.finishedAt),
					d.logBase64 != null ? d.logBase64 : Base64.getEncoder().encodeToString(d.log));
		}
		GateResult valid(int index) throws Exception { return result(draft(index)); }
		/** A candidate checkpoint exactly as the goal loop leaves it, finalized by the public inspect. */
		GoalRecord inspect(VerificationTarget cited, int gates, GateResult... results) throws Exception {
			var spec = new GoalRecord.Spec(request(gates), selection, "parent", workspace.toString(), target.owner().runId());
			var candidate = new GoalRecord(1, UUID.randomUUID().toString(), spec, GoalRecord.State.RUNNING,
					GoalRecord.Reason.NONE, 1, GoalRecord.Phase.READY, cited, List.of(results), true, "");
			goals.create(candidate);
			var finalized = service.inspect(candidate.id());
			assertThat(goals.read(candidate.id())).isEqualTo(finalized);
			return finalized;
		}
	}

	private static void assertRefused(GoalRecord record) {
		assertThat(record.state()).isEqualTo(GoalRecord.State.FAILED);
		assertThat(record.reason()).isEqualTo(GoalRecord.Reason.EVIDENCE_STALE);
		assertThat(record.candidate()).isFalse();
	}
	private static void assertAccepted(GoalRecord record, GoalRecord.Reason reason) {
		assertThat(record.state()).isEqualTo(GoalRecord.State.SUCCEEDED);
		assertThat(record.reason()).isEqualTo(reason);
		assertThat(record.candidate()).isTrue();
	}

	@Test void aReadOnlyCandidateWithBoundEvidenceForEveryGateIsVerified() throws Exception {
		try (var s = new Scenario(false)) {
			assertAccepted(s.inspect(s.target, 2, s.valid(0), s.valid(1)), GoalRecord.Reason.VERIFIED_READONLY);
		}
	}

	@ParameterizedTest @EnumSource(Tamper.class)
	void aTamperedGateRecordNeverFinalizesASuccess(Tamper tamper) throws Exception {
		try (var s = new Scenario(false)) {
			var forged = s.draft(1);
			tamper.change.accept(forged);
			assertRefused(s.inspect(s.target, 2, s.valid(0), s.result(forged)));
		}
	}

	@Test void aProposalBackedCandidateWithStoredEvidenceIsVerified() throws Exception {
		try (var s = new Scenario(true)) {
			assertAccepted(s.inspect(s.target, 2, s.valid(0), s.valid(1)), GoalRecord.Reason.VERIFIED_PROPOSAL);
		}
	}

	@Test void evidenceTheChangeStoreNeverRecordedIsRefused() throws Exception {
		try (var s = new Scenario(true)) {
			var d = s.draft(0);
			d.stored = false;
			assertRefused(s.inspect(s.target, 1, s.result(d)));
		}
	}

	@Test void evidenceThatDiffersFromTheStoredCopyIsRefused() throws Exception {
		try (var s = new Scenario(true)) {
			var d = s.draft(0);
			d.finishedAt = Instant.EPOCH.plusSeconds(1);
			assertRefused(s.inspect(s.target, 1, s.result(d)));
		}
	}

	@Test void aTargetWhoseDigestIsNotTheSavedProposalDigestIsRefused() throws Exception {
		try (var s = new Scenario(true)) {
			var swapped = new VerificationTarget(s.target.id(), s.target.owner(), OTHER, s.target.revision(), false);
			assertRefused(s.inspect(swapped, 1, s.valid(0)));
		}
	}

	@Test void aProposalWhoseLiveSourceChangedSinceTheGatesRanIsRefused() throws Exception {
		try (var s = new Scenario(true)) {
			var gate = s.valid(0);
			Files.writeString(s.workspace.resolve("source"), "external edit");
			assertRefused(s.inspect(s.target, 1, gate));
		}
	}

	@Test void aStoredLogAlteredAfterTheGateRanIsRefused() throws Exception {
		try (var s = new Scenario(true)) {
			var gate = s.valid(0);
			Path blob = s.changes.directory().resolve("evidence").resolve(gate.evidence().logSha256());
			Files.setPosixFilePermissions(blob, PosixFilePermissions.fromString("rw-------"));
			Files.write(blob, "altered after the gate ran".getBytes(StandardCharsets.UTF_8));
			assertRefused(s.inspect(s.target, 1, gate));
		}
	}

	@Test void aSourceThatChangedSinceTheGatesRanIsRefused() throws Exception {
		try (var s = new Scenario(false)) {
			var gate = s.valid(0);
			Files.writeString(s.workspace.resolve("source"), "external edit");
			assertRefused(s.inspect(s.target, 1, gate));
		}
	}

	@Test void onlyTheLatestRoundOfGatesDecidesTheVerdictAndTheirOrderMustMatchTheGates() throws Exception {
		try (var s = new Scenario(false)) {
			var forged = s.draft(0);
			Tamper.ARGV.change.accept(forged);
			// An earlier failed round does not count against a later passing one.
			assertAccepted(s.inspect(s.target, 2, s.result(forged), s.valid(1), s.valid(0), s.valid(1)),
					GoalRecord.Reason.VERIFIED_READONLY);
			// A passing earlier round does not rescue a forged latest one.
			var latest = s.draft(1);
			Tamper.NON_ZERO_EXIT.change.accept(latest);
			assertRefused(s.inspect(s.target, 2, s.valid(0), s.valid(1), s.valid(0), s.result(latest)));
			// The latest round must list gate 0 before gate 1.
			assertRefused(s.inspect(s.target, 2, s.valid(0), s.valid(1), s.valid(1), s.valid(0)));
		}
	}
}
