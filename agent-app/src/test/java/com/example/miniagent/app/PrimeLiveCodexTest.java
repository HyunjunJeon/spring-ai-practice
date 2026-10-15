package com.example.miniagent.app;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.ToolContextKeys;
import com.example.miniagent.core.ToolInterceptor;
import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.artifact.ArtifactProperties;
import com.example.miniagent.core.artifact.ArtifactStore;
import com.example.miniagent.core.approval.ApprovalAnswer;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.goal.GoalRecord;
import com.example.miniagent.core.goal.GoalRequest;
import com.example.miniagent.core.goal.GoalService;
import com.example.miniagent.core.goal.GoalStore;
import com.example.miniagent.core.lesson.LessonAdvisor;
import com.example.miniagent.core.lesson.LessonRecord;
import com.example.miniagent.core.lesson.LessonService;
import com.example.miniagent.core.messaging.MessageService;
import com.example.miniagent.core.messaging.MessageStore;
import com.example.miniagent.core.delegation.DefaultDelegationRuntime;
import com.example.miniagent.core.delegation.DelegationInput;
import com.example.miniagent.core.delegation.DelegationPlan;
import com.example.miniagent.core.delegation.DelegationPlanValidator;
import com.example.miniagent.core.delegation.ChildSpec;
import com.example.miniagent.core.delegation.ChildRole;
import com.example.miniagent.core.delegation.ToolScope;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunExecutor;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunStore;
import com.example.miniagent.plugin.codex.CodexAuth;
import com.example.miniagent.plugin.codex.CodexCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Explicit opt-in: every scenario calls the real subscription endpoint, never a fixture model. */
@EnabledIfEnvironmentVariable(named = "AGENT_FORGE_LIVE_CODEX", matches = "true")
@SpringBootTest(properties = {
		"agent.repl.enabled=false", "agent.ui=repl", "agent.project-instructions.enabled=false",
		"agent.models.default=codex/gpt-6.1-sol", "agent.codex.model=gpt-6.1-sol",
		"agent.codex.reasoning-effort=high", "agent.codex.auth=codex-cli",
		"agent.codex.base-url=https://chatgpt.com/backend-api/codex",
		"agent.plugins.trace.level=off", "agent.plugins.memory.compaction=false"
})
@Import(PrimeLiveCodexTest.RecordingConfiguration.class)
class PrimeLiveCodexTest {

	@TempDir
	static Path root;

	static Path work;

	static Path home;

	@DynamicPropertySource
	static void paths(DynamicPropertyRegistry properties) throws IOException {
		work = Files.createDirectory(root.resolve("workspace")).toRealPath();
		home = root.toRealPath().resolve("state");
		properties.add("agent.cwd", () -> work.toString());
		properties.add("agent.home", () -> home.toString());
	}

	@Autowired Agent agent;
	@Autowired AgentSession session;
	@Autowired CodexAuth auth;
	@Autowired Recording recording;
	@Autowired ArtifactStore artifacts;
	@Autowired LiveApprovalConsole operator;
	@Autowired GoalService goals;
	@Autowired RunStore runs;
	@Autowired GoalStore goalStore;
	@Autowired LessonService lessons;
	@Autowired com.example.miniagent.app.worker.WorkerController workers;
	@Autowired MessageService messages;
	@Autowired DefaultDelegationRuntime delegation;
	@Autowired RunExecutor executor;
	@Autowired com.example.miniagent.core.run.RunRequestStore requests;
	@Autowired com.example.miniagent.core.AgentInputHandler inputs;

	@BeforeEach
	void selectExactRequestedProvider() {
		assertThat(this.auth.current().source()).isEqualTo(CodexCredentials.Source.CODEX_CLI);
		this.session.startNew();
		this.agent.selectModel(new ModelSelection(new ModelRef("codex", "gpt-6.1-sol"),
				Map.of("effort", "high")));
		this.recording.events.clear();
		this.recording.results.clear();
		this.recording.rawResults.clear();
		this.operator.allowedPaths.clear();
		this.operator.admissions.clear();
		this.operator.hold = null;
		this.operator.heldResponse = null;
		this.recording.managedCalls.set(0);
		this.recording.pauseOnManagedCall.set(0);
		this.recording.lessonSelections.clear();
		this.recording.afterModelCall.set(null);
	}

	@Test
	void exactOAuthModelAndReasoningReturnMachineConsumedNonce() {
		String nonce = "LIVE_CODEX_" + UUID.randomUUID().toString().replace("-", "");
		var response = this.agent.run("Do not use tools. Return exactly this validation value: " + nonce);
		assertThat(response.text()).contains(nonce);
		assertLiveRounds();
	}

	@Test
	void largeToolResultIsExternalizedAndRecoveredThroughBoundedArtifactSearch() throws Exception {
		String value = UUID.randomUUID().toString().replace("-", "");
		String payload = "ordinary log entry with no target\n".repeat(4_000)
				+ "\nTARGET_KEY=" + value + "\n"
				+ "ordinary log entry after target\n".repeat(1_000);
		Files.writeString(work.resolve("large-result.txt"), payload);

		var response = this.agent.run("""
				Call read_file with path=large-result.txt, offset=1, limit=6000.
				If its result is an artifact descriptor,
				use search_artifact with the literal query TARGET_KEY= to recover the value.
				Do not reread the original file or use bash. Return only the value after TARGET_KEY=.
				""");

		assertThat(response.text()).contains(value);
		assertLiveRounds();
		var read = this.recording.results.stream().filter(result -> result.name().equals("read_file"))
			.findFirst().orElseThrow();
		assertThat(read.output()).hasSizeLessThan(8_192);
		var descriptor = JsonMapper.builder().build().readTree(read.output());
		assertThat(descriptor.path("artifactId").asString("")).isNotBlank();
		assertThat(descriptor.path("sha256").asString("")).matches("[a-f0-9]{64}");
		assertThat(this.recording.results).anySatisfy(result -> {
			assertThat(result.name()).isEqualTo("search_artifact");
			assertThat(result.output()).contains(value);
			assertThat(result.output()).hasSizeLessThan(16_384);
		});
		assertThat(Files.readString(work.resolve("large-result.txt"))).isEqualTo(payload);
		var original = this.recording.rawResults.stream().filter(result -> result.name().equals("read_file"))
			.findFirst().orElseThrow();
		assertThat(descriptor.path("sha256").asString()).isEqualTo(HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(original.output().getBytes(StandardCharsets.UTF_8))));
		String id = descriptor.path("artifactId").asString();
		StringBuilder recovered = new StringBuilder();
		int offset = 0;
		while (true) {
			var page = this.artifacts.read(original.authority(), id, offset, ArtifactStore.MAX_READ_LIMIT);
			recovered.append(page.content());
			if (!page.truncated()) break;
			assertThat(page.nextOffset()).isGreaterThan(offset);
			offset = page.nextOffset();
		}
		assertThat(recovered.toString()).isEqualTo(original.output());
		var foreign = new TurnContext(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
				original.authority().cwd(), original.authority().binding());
		assertThat(assertThrows(ArtifactStore.Failure.class,
				() -> this.artifacts.read(foreign, id, 0, 16)).code()).isEqualTo(ArtifactStore.Code.NOT_FOUND);
		assertThat(assertThrows(ArtifactStore.Failure.class,
				() -> this.artifacts.read(original.authority(), "../auth/token", 0, 16)).code())
			.isEqualTo(ArtifactStore.Code.INVALID_ID);
		var limits = new ArtifactProperties();
		limits.setMaxArtifactBytes(16);
		limits.setMaxScopeBytes(8);
		limits.setMaxArtifacts(1);
		var bounded = new ArtifactStore(root.toRealPath().resolve("quota-state"), limits);
		assertThat(assertThrows(ArtifactStore.Failure.class,
				() -> bounded.put(original.authority(), value)).code()).isEqualTo(ArtifactStore.Code.TOO_LARGE);
		var first = bounded.put(original.authority(), "first");
		assertThat(assertThrows(ArtifactStore.Failure.class,
				() -> bounded.put(original.authority(), "second")).code()).isEqualTo(ArtifactStore.Code.QUOTA);
		assertThat(bounded.read(original.authority(), first.artifactId(), 0, 16).content()).isEqualTo("first");
	}

	@Test
	void goalRepairsFailedGateUnderOneRunWithoutPublishingSource() throws Exception {
		String file = "goal-answer.txt";
		Files.writeString(work.resolve(file), "BASELINE\n");
		this.operator.allowedPaths.add(file);
		var request = new GoalRequest("""
				Work only on goal-answer.txt. In goal step 1, write exactly WRONG followed by a newline,
				then finish your answer; do not fix it within that step. In a later goal step after
				host gate feedback, change it to exactly READY followed by a newline and finish.
				The host, not you, evaluates the gate. Do not run bash or apply staged changes.
				Perform the work yourself with read_file and write_file. Do not delegate or use edit_file.
				""", List.of(new GoalRequest.Gate(
						List.of("/usr/bin/grep", "-q", "^READY$", file), 30_000, 4_096)),
				4, 180_000, 100_000L, 30);
		var pending = this.goals.submit(request, this.agent.selectedModel(), this.session.id(), work);
		var completed = this.goals.execute(pending.id());

		assertLiveRounds();
		assertThat(completed.state()).isEqualTo(GoalRecord.State.SUCCEEDED);
		assertThat(completed.reason()).isEqualTo(GoalRecord.Reason.VERIFIED_PROPOSAL);
		assertThat(completed.steps()).isGreaterThanOrEqualTo(2);
		assertThat(completed.results()).anySatisfy(result ->
				assertThat(result.code()).isEqualTo(com.example.miniagent.core.change.VerificationRunner.Code.NON_ZERO_EXIT));
		assertThat(completed.results().getLast().code())
			.isEqualTo(com.example.miniagent.core.change.VerificationRunner.Code.SUCCEEDED);
		assertThat(completed.target().readOnly()).isFalse();
		assertThat(Files.readString(work.resolve(file))).isEqualTo("BASELINE\n");
		var run = this.runs.read(pending.spec().runId());
		assertThat(run.state()).isEqualTo(RunState.COMPLETED);
		assertThat(run.usage().knownTokens()).isPositive();
		assertManagedIdentity(pending);
	}

	@Test
	void unchangedWorkspaceCanSatisfyARealHostGateWithoutAnEmptyProposal() throws Exception {
		Files.writeString(work.resolve("goal-already.txt"), "READY\n");
		var request = new GoalRequest("Do not use tools or change files. The host will verify existing readiness.",
				List.of(new GoalRequest.Gate(List.of("/usr/bin/grep", "-q", "^READY$", "goal-already.txt"),
						30_000, 4_096)), 2, 180_000, 100_000L, 20);
		var pending = this.goals.submit(request, this.agent.selectedModel(), this.session.id(), work);
		var completed = this.goals.execute(pending.id());

		assertLiveRounds();
		assertThat(completed.state()).isEqualTo(GoalRecord.State.SUCCEEDED);
		assertThat(completed.reason()).isEqualTo(GoalRecord.Reason.VERIFIED_READONLY);
		assertThat(completed.target().readOnly()).isTrue();
		assertThat(completed.results()).hasSize(1);
		assertManagedIdentity(pending);
	}

	@Test
	void goalRestoresAcknowledgedStageOnExplicitResumeWithoutAllocatingAnotherRun() throws Exception {
		String file = "goal-resume.txt";
		Files.writeString(work.resolve(file), "BASELINE\n");
		this.operator.allowedPaths.add(file);
		this.recording.pauseOnManagedCall.set(2);
		var request = new GoalRequest("""
				Work only on goal-resume.txt. In goal step 1 write exactly SAVED followed by a newline
				and finish. In subsequent steps inspect that file; preserve already correct content,
				otherwise set it to exactly READY followed by a newline and finish.
				Do not use bash or apply staged changes.
				Perform the work yourself with read_file and write_file. Do not delegate or use edit_file.
				""", List.of(new GoalRequest.Gate(
						List.of("/usr/bin/grep", "-q", "^READY$", file), 30_000, 4_096)),
				5, 180_000, 100_000L, 40);
		var pending = this.goals.submit(request, this.agent.selectedModel(), this.session.id(), work);
		var interrupted = this.goals.execute(pending.id());
		assertThat(interrupted.state()).isEqualTo(GoalRecord.State.BLOCKED);
		assertThat(interrupted.target().readOnly()).isFalse();
		var previous = this.runs.read(pending.spec().runId());
		assertThat(previous.state()).isEqualTo(RunState.INTERRUPTED);
		long tokensBeforeResume = previous.usage().knownTokens();
		assertThat(tokensBeforeResume).isPositive();

		var completed = this.goals.resume(pending.id(), RunExecutor.ResumeOptions.ordinary());

		assertLiveRounds();
		assertThat(completed.state()).isEqualTo(GoalRecord.State.SUCCEEDED);
		assertThat(completed.spec().runId()).isEqualTo(pending.spec().runId());
		assertThat(completed.target().id()).isNotEqualTo(interrupted.target().id());
		assertThat(this.runs.read(pending.spec().runId()).usage().knownTokens()).isGreaterThan(tokensBeforeResume);
		assertThat(Files.readString(work.resolve(file))).isEqualTo("BASELINE\n");
		assertManagedIdentity(pending);
	}

	@Test
	void goalBudgetExhaustionIsNotSuccessfulVerification() throws Exception {
		var request = new GoalRequest("Do not use tools. Return a short answer; the host verifies completion.",
				List.of(new GoalRequest.Gate(List.of("/usr/bin/true"), 30_000, 4_096)),
				2, 180_000, 1L, 10);
		var pending = this.goals.submit(request, this.agent.selectedModel(), this.session.id(), work);
		var stopped = this.goals.execute(pending.id());
		assertLiveRounds();
		assertThat(stopped.state()).isEqualTo(GoalRecord.State.FAILED);
		assertThat(stopped.reason()).isEqualTo(GoalRecord.Reason.BUDGET_LIMIT);
		assertThat(stopped.candidate()).isFalse();
		assertThat(this.runs.read(pending.spec().runId()).usage().knownTokens()).isGreaterThan(1);
		assertThat(stopped.results()).isEmpty();
	}

	@Test
	void goalCancellationSettlesRealApprovalAndRejectsLateResponse() throws Exception {
		String file = "goal-cancel.txt";
		Files.writeString(work.resolve(file), "BASELINE\n");
		this.operator.allowedPaths.add(file);
		var approvalReady = new CompletableFuture<ApprovalRequest>();
		this.operator.hold = approvalReady;
		var request = new GoalRequest("""
				Use write_file directly to set goal-cancel.txt to READY followed by a newline.
				Do not delegate, use edit_file, run bash, or apply changes. Finish after the write.
				""", List.of(new GoalRequest.Gate(
						List.of("/usr/bin/grep", "-q", "^READY$", file), 30_000, 4_096)),
				2, 180_000, 100_000L, 20);
		var pending = this.goals.submit(request, this.agent.selectedModel(), this.session.id(), work);
		var finished = new CompletableFuture<GoalRecord>();
		Thread worker = Thread.ofVirtual().unstarted(() -> {
			try { finished.complete(this.goals.execute(pending.id())); }
			catch (Throwable ex) { finished.completeExceptionally(ex); }
		});
		worker.start();
		try {
			var displayed = approvalReady.get(180, TimeUnit.SECONDS);
			assertThat(displayed.runId()).isEqualTo(pending.spec().runId());
			this.goals.cancel(pending.id());
			var cancelled = finished.get(60, TimeUnit.SECONDS);
			assertLiveRounds();
			assertThat(cancelled.state()).isEqualTo(GoalRecord.State.CANCELLED);
			assertThat(this.runs.read(pending.spec().runId()).state()).isEqualTo(RunState.CANCELLED);
			this.operator.heldResponse.accept(new ApprovalAnswer(displayed.requestId(), ApprovalDecision.APPROVED, false));
			assertThat(this.runs.read(pending.spec().runId()).state()).isEqualTo(RunState.CANCELLED);
			assertThat(Files.readString(work.resolve(file))).isEqualTo("BASELINE\n");
			try (var claim = this.goalStore.claim(pending.id())) {
				assertThat(this.goalStore.read(pending.id()).state()).isEqualTo(GoalRecord.State.CANCELLED);
			}
		}
		finally {
			if (!finished.isDone()) {
				this.goals.cancel(pending.id());
				worker.interrupt();
				finished.handle((ignored, failure) -> null).get(60, TimeUnit.SECONDS);
			}
		}
	}

	@Test
	void changedSourceRevokesCompletedGoalEvidenceWithoutAnotherProviderCall() throws Exception {
		String file = "goal-evidence.txt";
		Files.writeString(work.resolve(file), "READY\n");
		var request = new GoalRequest("Do not use tools or change files. The host checks existing content.",
				List.of(new GoalRequest.Gate(List.of("/usr/bin/grep", "-q", "^READY$", file), 30_000, 4_096)),
				2, 180_000, 100_000L, 20);
		var pending = this.goals.submit(request, this.agent.selectedModel(), this.session.id(), work);
		var complete = this.goals.execute(pending.id());
		assertLiveRounds();
		assertThat(complete.state()).isEqualTo(GoalRecord.State.SUCCEEDED);
		int calls = this.recording.managedCalls.get();
		Files.writeString(work.resolve(file), "CHANGED_BY_OPERATOR\n");
		var stale = this.goals.execute(pending.id());
		assertThat(stale.state()).isEqualTo(GoalRecord.State.FAILED);
		assertThat(stale.reason()).isEqualTo(GoalRecord.Reason.EVIDENCE_STALE);
		assertThat(this.recording.managedCalls.get()).isEqualTo(calls);
	}

	@Test
	void lessonsRequireProofThenExplicitScopeVersionAndRollbackWhileRealRefinementCannotUseTools() throws Exception {
		String key = "qa_rule_" + UUID.randomUUID().toString().replace("-", "");
		String value = UUID.randomUUID().toString().replace("-", "");
		Files.writeString(work.resolve("lesson-proof.txt"), key + "=" + value + "\n");
		var request = new GoalRequest(
				"Do not use tools or modify files. The host verifies this project fact: " + key + "=" + value,
				List.of(new GoalRequest.Gate(List.of("/usr/bin/grep", "-q", "^" + key + "=" + value + "$",
						"lesson-proof.txt"), 30_000, 4_096)), 2, 180_000, 100_000L, 20);
		String originalSession = this.session.id();
		var pending = this.goals.submit(request, this.agent.selectedModel(), originalSession, work);
		var verified = this.goals.execute(pending.id());
		assertThat(verified.state()).isEqualTo(GoalRecord.State.SUCCEEDED);
		var actor = LessonRecord.Actor.capture(originalSession, work);
		var binding = this.agent.modelRegistry().bind(this.agent.selectedModel());
		var captured = new TurnContext(originalSession, UUID.randomUUID().toString(), work, binding);
		int toolResults = this.recording.results.size();
		var refined = this.lessons.refine(verified.id(), captured, this.agent.toolCallingAdvisorOrder());
		assertThat(refined.candidate().state()).isEqualTo(LessonRecord.State.CANDIDATE);
		assertThat(refined.candidate().generation().provider()).isEqualTo("codex");
		assertThat(refined.candidate().generation().model()).isEqualTo("gpt-6.1-sol");
		assertThat(refined.candidate().generation().totalTokens()).isPositive();
		assertThat(this.recording.results).hasSize(toolResults);

		var content = new LessonRecord.Content(key, "The verified value of " + key + " is " + value, List.of(key));
		var candidate = this.lessons.propose(verified.id(), content, actor);
		assertThat(candidate.scope()).isEqualTo(LessonRecord.Scope.SESSION);
		assertThat(this.lessons.lookup(actor, key)).isEmpty();
		var accepted = this.lessons.accept(candidate.id(), candidate.version(), LessonRecord.Scope.SESSION, actor);
		var otherSession = LessonRecord.Actor.capture(UUID.randomUUID().toString(), work);
		assertThat(this.lessons.lookup(otherSession, key)).isEmpty();
		var project = this.lessons.promote(accepted.id(), accepted.version(), LessonRecord.Scope.PROJECT, actor);

		// A fresh conversation cannot answer from the refinement prompt or goal history.
		this.session.startNew();
		this.agent.selectModel(new ModelSelection(new ModelRef("codex", "gpt-6.1-sol"), Map.of("effort", "high")));
		this.recording.lessonSelections.clear();
		int beforeLookupTools = this.recording.results.size();
		var answer = this.agent.run("Do not use tools. Return only the value of " + key + " from advisory lesson data.");
		assertThat(answer.text()).contains(value);
		assertThat(this.recording.results).hasSize(beforeLookupTools);
		assertThat(this.recording.lessonSelections).anySatisfy(selection ->
				assertThat(selection).anySatisfy(item -> {
					assertThat(item).isInstanceOf(Map.class);
					assertThat(((Map<?, ?>) item).get("id")).isEqualTo(project.id());
					assertThat(((Map<?, ?>) item).get("version")).isEqualTo(project.version());
				}));

		var edited = this.lessons.update(project.id(), project.version(), verified.id(),
				new LessonRecord.Content(key, "Operator revision for " + key, List.of(key)), actor);
		assertThrows(IOException.class, () -> this.lessons.update(project.id(), project.version(), verified.id(),
				content, actor));
		var rolledBack = this.lessons.rollback(edited.id(), edited.version(), accepted.version(), actor);
		assertThat(rolledBack.content()).isEqualTo(content);
		assertThat(rolledBack.rollbackOf()).isEqualTo(accepted.version());
		assertThat(rolledBack.scope()).isEqualTo(LessonRecord.Scope.PROJECT);
		assertThat(this.lessons.history(rolledBack.id(), actor)).hasSize(5);
		var elsewhere = LessonRecord.Actor.capture(UUID.randomUUID().toString(),
				Files.createDirectory(root.resolve("other-lesson-workspace")));
		assertThat(this.lessons.lookup(elsewhere, key)).isEmpty();
		var global = this.lessons.promote(rolledBack.id(), rolledBack.version(), LessonRecord.Scope.GLOBAL, actor);
		assertThat(this.lessons.lookup(elsewhere, key)).extracting(LessonRecord::id).containsExactly(global.id());
		assertLiveRounds();
	}

	private void assertManagedIdentity(GoalRecord goal) {
		assertThat(this.recording.events.stream().filter(TraceEvent.ModelBound.class::isInstance)
				.map(TraceEvent.ModelBound.class::cast).filter(event -> event.runId() != null).toList())
			.isNotEmpty().allSatisfy(event -> {
				assertThat(event.runId()).isEqualTo(goal.spec().runId().value());
				assertThat(event.ref()).isEqualTo(new ModelRef("codex", "gpt-6.1-sol"));
			});
	}

	@Test
	void changedProofDuringRealRefinementCannotCreateKnowledgeOrSpendOnAnotherInvalidAttempt() throws Exception {
		String file = "lesson-stale-proof.txt";
		Files.writeString(work.resolve(file), "READY\n");
		var request = new GoalRequest("Do not use tools or change files. The host verifies READY content.",
				List.of(new GoalRequest.Gate(List.of("/usr/bin/grep", "-q", "^READY$", file), 30_000, 4_096)),
				2, 180_000, 100_000L, 20);
		var pending = this.goals.submit(request, this.agent.selectedModel(), this.session.id(), work);
		assertThat(this.goals.execute(pending.id()).state()).isEqualTo(GoalRecord.State.SUCCEEDED);
		var actor = LessonRecord.Actor.capture(this.session.id(), work);
		var captured = new TurnContext(actor.session(), UUID.randomUUID().toString(), work,
				this.agent.modelRegistry().bind(this.agent.selectedModel()));
		int entries = this.lessons.list(actor).size();
		this.recording.afterModelCall.set(() -> {
			try { Files.writeString(work.resolve(file), "CHANGED_DURING_GENERATION\n"); }
			catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
		});
		assertThrows(IOException.class,
				() -> this.lessons.refine(pending.id(), captured, this.agent.toolCallingAdvisorOrder()));
		assertThat(this.recording.afterModelCall.get()).isNull();
		assertThat(this.lessons.list(actor)).hasSize(entries);
		assertThat(this.goals.inspect(pending.id()).reason()).isEqualTo(GoalRecord.Reason.EVIDENCE_STALE);
		long rounds = this.recording.events.stream().filter(TraceEvent.RoundFinished.class::isInstance).count();
		assertThrows(IOException.class,
				() -> this.lessons.refine(pending.id(), captured, this.agent.toolCallingAdvisorOrder()));
		assertThrows(IOException.class, () -> this.lessons.propose(pending.id(),
				new LessonRecord.Content("stale", "must not be accepted", List.of()), actor));
		assertThat(this.recording.events.stream().filter(TraceEvent.RoundFinished.class::isInstance).count())
			.isEqualTo(rounds);
		assertLiveRounds();
	}

	@Test
	void independentWorkerExecutesStoredGoalsAndRestartsWithoutChangingRunOrModelAuthority() throws Exception {
		String nonce = "WORKER_AUTH_" + UUID.randomUUID().toString().replace("-", "");
		assertThat(this.agent.run("Do not use tools. Return exactly " + nonce).text()).contains(nonce);
		assertLiveRounds();
		var request = new GoalRequest("Do not use tools or change files. Acknowledge; the host verifies completion.",
				List.of(new GoalRequest.Gate(List.of("/usr/bin/true"), 30_000, 4_096)),
				2, 180_000, 100_000L, 20);
		var queued = this.goals.submit(request, this.agent.selectedModel(), this.session.id(), work);
		var instance = this.workers.start();
		try {
			var ready = this.workers.awaitStarted(instance.instance(), java.time.Duration.ofSeconds(45));
			assertThat(ready.owned()).isTrue();
			assertThat(instance.pid()).isNotEqualTo(ProcessHandle.current().pid());
			var process = ProcessHandle.of(instance.pid()).orElseThrow();
			assertThat(List.of(process.info().arguments().orElseThrow()))
				.contains("--agent.models.default=codex/gpt-6.1-sol", "--agent.codex.reasoning-effort=high",
						"--agent.codex.auth=codex-cli",
						"--agent.codex.base-url=https://chatgpt.com/backend-api/codex");
			var same = this.workers.start();
			assertThat(same.instance()).isEqualTo(instance.instance());
			assertThat(same.pid()).isEqualTo(instance.pid());
			var done = this.workers.awaitGoal(queued.id(),
					g -> g.state() != GoalRecord.State.PENDING && g.state() != GoalRecord.State.RUNNING,
					java.time.Duration.ofSeconds(180));
			assertThat(done.state()).isEqualTo(GoalRecord.State.SUCCEEDED);
			assertThat(done.reason()).isEqualTo(GoalRecord.Reason.VERIFIED_READONLY);
			assertThat(done.spec().runId()).isEqualTo(queued.spec().runId());
			var firstRun = this.runs.read(queued.spec().runId());
			assertThat(firstRun.state()).isEqualTo(RunState.COMPLETED);
			assertThat(firstRun.spec().model()).isEqualTo(new ModelRef("codex", "gpt-6.1-sol"));
			assertThat(firstRun.usage().knownTokens()).isPositive();
			this.workers.stop(instance.instance());
			this.workers.awaitStopped(instance.instance(), java.time.Duration.ofSeconds(45));

			var next = this.goals.submit(request, this.agent.selectedModel(), this.session.id(), work);
			String oldInstance = instance.instance();
			instance = this.workers.start();
			this.workers.awaitStarted(instance.instance(), java.time.Duration.ofSeconds(45));
			assertThat(instance.instance()).isNotEqualTo(oldInstance);
			assertThrows(IOException.class, () -> this.workers.stop(oldInstance));
			var recovered = this.workers.awaitGoal(next.id(),
					g -> g.state() != GoalRecord.State.PENDING && g.state() != GoalRecord.State.RUNNING,
					java.time.Duration.ofSeconds(180));
			assertThat(recovered.state()).isEqualTo(GoalRecord.State.SUCCEEDED);
			assertThat(recovered.spec().runId()).isEqualTo(next.spec().runId());
			assertThat(this.runs.read(next.spec().runId()).usage().knownTokens()).isPositive();
			assertThat(this.runs.read(firstRun.spec().runId()).usage()).isEqualTo(firstRun.usage());
			System.out.println("LIVE_CODEX_WORKER_VERIFIED model=gpt-6.1-sol effort=high auth=CODEX_CLI");
		}
		finally {
			var current = this.workers.status();
			if (current.owned() && current.instance().equals(instance.instance())) {
				this.workers.stop(instance.instance());
				this.workers.awaitStopped(instance.instance(), java.time.Duration.ofSeconds(60));
			}
		}
	}

	@Test
	void messagesReuseChildMemoryAndChargeEveryNewRunBeforeFurtherParentAdmission() throws Exception {
		String value = "CHILD_" + UUID.randomUUID().toString().replace("-", "");
		Files.writeString(work.resolve("message-seed.txt"), value + "\n");
		String planId = UUID.randomUUID().toString();
		String childId = UUID.randomUUID().toString();
		var scope = new ToolScope(java.util.Set.of("read_file"), java.util.Set.of(work), java.util.Set.of(),
				java.util.Set.of(), false, false, false, false);
		var child = new ChildSpec(childId,
				"Read message-seed.txt using read_file. Remember its exact value for later questions. "
						+ "Reply with that value. Do not delegate or change files.",
				ChildRole.RESEARCH, null, java.util.Set.of(), scope,
				new RunBudget(java.time.Duration.ofMinutes(2), 60_000L, 10), 1, true);
		var plan = new DelegationPlan(planId, List.of(child), 1);
		var authority = DelegationInput.authority(this.agent, work);
		var budget = new RunBudget(java.time.Duration.ofMinutes(10), 200_000L, 50);
		var retained = new RunContext[1];
		var originalChildTokens = new long[1];
		var result = this.executor.<Long>startOperation("Live scoped message accounting", this.session.id(),
				this.agent.selectedModel(), work, budget, context -> {
					try {
					retained[0] = context;
					var outcome = this.delegation.executeOutcome(context.turnContext(), plan, authority, budget,
							context::usage, "0".repeat(64), "0".repeat(64));
					assertThat(outcome).isInstanceOf(DefaultDelegationRuntime.Completed.class);
					var original = this.delegation.messageTarget(context, planId, childId);
					var first = this.runs.read(original.childRun());
					assertThat(first.state()).isEqualTo(RunState.COMPLETED);
					assertThat(first.usage().knownTokens()).isPositive();
					long firstUsage = first.usage().knownTokens();
					assertThat(context.usage().knownTokens()).isEqualTo(firstUsage);
					Path legacy = home.resolve("run-requests").resolve(first.spec().conversationId() + ".json");
					Files.move(home.resolve("run-requests").resolve(first.spec().runId().value() + ".json"), legacy);
					byte[] originalCompanion = Files.readAllBytes(legacy);
					assertThat(this.requests.load(first.spec()).goal()).isEqualTo(child.task());

					String body = "Do not use tools. Return only the exact value you remembered from message-seed.txt.";
					String id = UUID.randomUUID().toString();
					var delivered = this.messages.send(context, planId, childId, id, 0, body);
					assertThat(delivered.state()).isEqualTo(MessageStore.State.DELIVERED);
					assertThat(delivered.response()).contains(value);
					assertThat(delivered.responseTruncated()).isFalse();
					var next = this.runs.read(new RunId(delivered.executionRun()));
					assertThat(next.spec().conversationId()).isEqualTo(first.spec().conversationId());
					assertThat(next.spec().runId()).isNotEqualTo(first.spec().runId());
					assertThat(next.spec().optionsFingerprint()).isEqualTo(first.spec().optionsFingerprint());
					int runsBeforeReplay = this.runs.list().size();
					assertThat(this.messages.send(context, planId, childId, id, 0, body)).isEqualTo(delivered);
					assertThat(this.runs.list()).hasSize(runsBeforeReplay);

					var mailbox = this.messages.read(context, planId, childId, 0);
					assertThat(mailbox.entries()).hasSize(1);
					String secondId = UUID.randomUUID().toString();
					assertThrows(IOException.class,
							() -> this.messages.send(context, planId, childId, secondId, 0, body));
					var second = this.messages.send(context, planId, childId, secondId, mailbox.version(), body);
					assertThat(second.state()).isEqualTo(MessageStore.State.DELIVERED);
					assertThat(second.response()).contains(value);
					var last = this.runs.read(new RunId(second.executionRun()));
					assertThat(last.spec().conversationId()).isEqualTo(first.spec().conversationId());
					assertThat(last.spec().runId()).isNotIn(first.spec().runId(), next.spec().runId());
					assertThat(next.usage().toolCalls()).isZero();
					assertThat(last.usage().toolCalls()).isZero();
					long childTokens = firstUsage + next.usage().knownTokens() + last.usage().knownTokens();
					assertThat(context.usage().knownTokens()).isEqualTo(childTokens);
					assertThat(this.runs.read(first.spec().runId()).usage()).isEqualTo(first.usage());
					assertThat(Files.readAllBytes(legacy)).isEqualTo(originalCompanion);
					assertThat(this.requests.load(first.spec()).goal()).isEqualTo(child.task());
					assertThat(this.requests.load(next.spec()).goal()).isEqualTo(body);
					assertThat(this.requests.load(last.spec()).goal()).isEqualTo(body);

					String parentValue = "PARENT_" + UUID.randomUUID().toString().replace("-", "");
					assertThat(this.agent.run("Do not use tools. Return exactly " + parentValue, context.turnContext())
						.text()).contains(parentValue);
					assertThat(context.usage().knownTokens()).isGreaterThan(childTokens);
					long spent = context.usage().knownTokens();
					var tooLarge = new ChildSpec(UUID.randomUUID().toString(), child.task(), child.role(), null,
							java.util.Set.of(), scope, new RunBudget(java.time.Duration.ofSeconds(30),
									budget.tokenCap() - spent + 1, 1), 1, true);
					int count = this.runs.list().size();
					var rejection = assertThrows(DelegationPlanValidator.Rejection.class,
							() -> this.delegation.executeOutcome(context.turnContext(),
									new DelegationPlan(UUID.randomUUID().toString(), List.of(tooLarge), 1),
									authority, budget, context::usage, "0".repeat(64), "0".repeat(64)));
					assertThat(rejection.code()).isEqualTo(DelegationPlanValidator.Code.RESERVATION_EXCEEDED);
					assertThat(this.runs.list()).hasSize(count);
					assertThat(this.messages.read(context, planId, childId, 0).entries()).hasSize(2);
					originalChildTokens[0] = childTokens;
					throw new RunExecutor.Stop(RunExecutor.Stop.Kind.INTERRUPTED);
					}
					catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
				});
		assertThat(result.record().state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(result.record().usage().knownTokens()).isGreaterThan(originalChildTokens[0]);
		long beforeResume = result.record().usage().knownTokens();
		var resumed = this.executor.resumeOperation(result.record().spec().runId(), this.agent.selectedModel(), work,
				RunExecutor.ResumeOptions.ordinary(), context -> {
					try {
						retained[0] = context;
						assertThat(context.usage().knownTokens()).isEqualTo(beforeResume);
						var mailbox = this.messages.read(context, planId, childId, 0);
						var reply = this.messages.send(context, planId, childId, UUID.randomUUID().toString(),
								mailbox.version(), "Do not use tools. Return the remembered value of message-seed.txt.");
						assertThat(reply.state()).isEqualTo(MessageStore.State.DELIVERED);
						assertThat(reply.response()).contains(value);
						var childRun = this.runs.read(new RunId(reply.executionRun()));
						assertThat(context.usage().knownTokens())
							.isEqualTo(beforeResume + childRun.usage().knownTokens());
						assertThat(childRun.spec().parentRunId()).isEqualTo(result.record().spec().runId());
						return context.usage().knownTokens();
					}
					catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
				});
		assertThat(resumed.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(resumed.record().spec().runId()).isEqualTo(result.record().spec().runId());
		assertThat(resumed.record().usage().knownTokens()).isEqualTo(resumed.value());
		assertThrows(IllegalStateException.class, () -> this.messages.read(retained[0], planId, childId, 0));
		var archive = this.messages.archive(this.session.id(), work, this.runs,
				result.record().spec().runId().value(), planId, childId, 0);
		assertThat(archive.entries()).hasSize(3).allSatisfy(entry -> {
			assertThat(entry.state()).isEqualTo(MessageStore.State.DELIVERED);
			assertThat(entry.sender()).isEqualTo("parent");
		});
		assertThat(this.inputs.handle("/message archive " + result.record().spec().runId().value()
				+ " " + planId + " " + childId)).isTrue();
		assertThrows(IllegalArgumentException.class, () -> this.messages.archive(UUID.randomUUID().toString(),
				work, this.runs, result.record().spec().runId().value(), planId, childId, 0));
		assertLiveRounds();
	}

	private void assertLiveRounds() {
		var rounds = this.recording.events.stream().filter(TraceEvent.RoundFinished.class::isInstance)
			.map(TraceEvent.RoundFinished.class::cast).toList();
		assertThat(rounds).isNotEmpty().allSatisfy(round -> {
			assertThat(round.model()).isEqualTo("gpt-6.1-sol");
			assertThat(round.reasoningEffort()).isEqualTo("high");
		});
		assertThat(rounds.stream().mapToLong(round -> round.tokens().input()).sum()).isPositive();
		System.out.println("LIVE_CODEX_VERIFIED model=gpt-6.1-sol effort=high auth=CODEX_CLI rounds="
				+ rounds.size());
	}

	record ObservedResult(String name, String output) {
	}

	record RawResult(String name, String output, TurnContext authority) {
	}

	static class Recording implements ToolInterceptor {
		final List<TraceEvent> events = new CopyOnWriteArrayList<>();
		final List<ObservedResult> results = new CopyOnWriteArrayList<>();
		final List<RawResult> rawResults = new CopyOnWriteArrayList<>();
		final AtomicInteger managedCalls = new AtomicInteger();
		final AtomicInteger pauseOnManagedCall = new AtomicInteger();
		final List<List<?>> lessonSelections = new CopyOnWriteArrayList<>();
		final java.util.concurrent.atomic.AtomicReference<Runnable> afterModelCall =
				new java.util.concurrent.atomic.AtomicReference<>();

		@EventListener
		void observe(TraceEvent event) {
			this.events.add(event);
			if (event instanceof TraceEvent.ModelBound binding && binding.runId() != null) {
				int call = this.managedCalls.incrementAndGet();
				if (this.pauseOnManagedCall.compareAndSet(call, 0)) {
					throw new RunExecutor.Stop(RunExecutor.Stop.Kind.INTERRUPTED);
				}
			}
		}

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE + 35;
		}

		@Override
		public String intercept(ToolInvocation invocation, Chain chain) {
			String result = chain.proceed(invocation);
			this.results.add(new ObservedResult(invocation.toolName(), result));
			return result;
		}
	}

	/** An exact-request operator transport; the real broker still owns and persists decisions. */
	static class LiveApprovalConsole extends AgentConsole {
		final Set<String> allowedPaths = ConcurrentHashMap.newKeySet();
		final Map<String, String> admissions = new ConcurrentHashMap<>();
		volatile CompletableFuture<ApprovalRequest> hold;
		volatile Consumer<ApprovalAnswer> heldResponse;

		LiveApprovalConsole() {
			super(new BufferedReader(new StringReader("")), new PrintStream(OutputStream.nullOutputStream()), true);
		}

		@Override
		public void requestApproval(ApprovalRequest request, Consumer<ApprovalAnswer> response) {
			String key = request.runId().value() + ":" + request.attemptId() + ":" + request.argsDigest();
			String path = this.admissions.remove(key);
			boolean allowed = "write_file".equals(request.toolCallKey()) && path != null
					&& this.allowedPaths.contains(path);
			if (allowed && this.hold != null) {
				this.heldResponse = response;
				this.hold.complete(request);
				return;
			}
			response.accept(new ApprovalAnswer(request.requestId(),
					allowed ? ApprovalDecision.APPROVED : ApprovalDecision.REJECTED, false));
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RecordingConfiguration {
		@Bean
		@Primary
		LiveApprovalConsole primeLiveOperator() {
			return new LiveApprovalConsole();
		}

		@Bean
		Recording primeLiveRecording() {
			return new Recording();
		}

		@Bean
		CallAdvisor primeLiveLessonSelectionWitness(Recording recording) {
			return new CallAdvisor() {
				@Override
				public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 175; }
				@Override
				public String getName() { return "LiveLessonSelectionWitness"; }
				@Override
				public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
					if (request.context().get(LessonAdvisor.SELECTED) instanceof List<?> selected)
						recording.lessonSelections.add(List.copyOf(selected));
					var response = chain.nextCall(request);
					var after = recording.afterModelCall.getAndSet(null);
					if (after != null) after.run();
					return response;
				}
			};
		}

		@Bean
		ToolInterceptor primeLiveWriteAdmission(LiveApprovalConsole console) {
			return new ToolInterceptor() {
				@Override
				public int getOrder() {
					return Ordered.HIGHEST_PRECEDENCE + 15;
				}

				@Override
				public String intercept(ToolInvocation invocation, Chain chain) {
					if (invocation.toolName().equals("write_file")) {
						var authority = (TurnContext) invocation.context().getContext().get(ToolContextKeys.TURN_CONTEXT);
						if (authority.runId() != null) {
							String path = JsonMapper.builder().build().readTree(invocation.input())
								.path("path").asString("");
							String digest = ApprovalRequest.digest(invocation.input());
							console.admissions.put(authority.runId() + ":" + authority.attemptId() + ":" + digest, path);
						}
					}
					return chain.proceed(invocation);
				}
			};
		}

		@Bean
		ToolInterceptor primeLiveRawResultWitness(Recording recording) {
			return new ToolInterceptor() {
				@Override
				public int getOrder() {
					return Ordered.HIGHEST_PRECEDENCE + 45;
				}

				@Override
				public String intercept(ToolInvocation invocation, Chain chain) {
					String result = chain.proceed(invocation);
					var authority = (TurnContext) invocation.context().getContext().get(ToolContextKeys.TURN_CONTEXT);
					recording.rawResults.add(new RawResult(invocation.toolName(), result, authority));
					return result;
				}
			};
		}
	}
}
