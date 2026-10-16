package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.change.ChangeSet;
import com.example.miniagent.core.change.ChangeStatus;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.delegation.ChildRole;
import com.example.miniagent.core.delegation.ChildSpec;
import com.example.miniagent.core.delegation.ToolScope;
import com.example.miniagent.core.delegation.runtime.ChildScopeFactory;
import com.example.miniagent.core.delegation.runtime.StagedApplyService;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunSpec;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunStore;
import com.example.miniagent.core.run.RunWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Refusal codes of the parent publication gate that StagedApplyTest does not reach. */
@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/staged-apply", "agent.plugins.memory.enabled=false",
		"agent.plugins.trace.level=off", "agent.plugins.permission.auto-allow-tools=write_file",
		"agent.plugins.permission.allow-non-interactive=true" })
@Import(StagedApplyTest.Fixture.class)
class StagedApplyRefusalTest {
	private static final String DIGEST = "0".repeat(64);
	@TempDir static Path workspace;
	@TempDir static Path state;
	@DynamicPropertySource static void paths(DynamicPropertyRegistry properties) {
		properties.add("agent.cwd", () -> workspace.toString());
		properties.add("agent.home", () -> state.resolve("home").toString());
	}
	@Autowired Agent agent;
	@Autowired AgentSession session;
	@Autowired ChildScopeFactory scopes;
	@Autowired RunStore runs;
	@Autowired ChangeStore changes;
	@Autowired ApprovalBroker approvals;
	@Autowired ChangeCommandsE2ETest.Screen screen;
	@Autowired StagedApplyService service;
	private TurnContext parent;
	private ToolScope authority;

	private record Staged(ChildSpec child, RunId run, ChangeSet proposal, Path file) {}

	@BeforeEach void freshParent() throws Exception {
		this.screen.setInteractive(false);
		this.screen.questions.clear();
		this.screen.lines.clear();
		this.session.startNew();
		this.parent = new TurnContext(this.session.id(), UUID.randomUUID().toString(), workspace,
				this.agent.modelRegistry().bind(this.agent.selectedModel()));
		Set<Path> roots = Set.of(workspace.toAbsolutePath().normalize());
		this.authority = new ToolScope(Set.of("write_file"), roots, roots, Set.of(), true, false, true, true);
	}
	@AfterEach void cleanup() throws Exception {
		for (var request : this.approvals.pending()) this.approvals.cancel(request);
		assertThat(this.approvals.pending()).isEmpty();
	}

	private Staged stage(String childId) throws Exception {
		Path file = workspace.resolve(childId + "-" + UUID.randomUUID() + ".txt");
		Files.writeString(file, "before");
		Set<Path> roots = Set.of(workspace.toAbsolutePath().normalize());
		var scope = new ToolScope(Set.of("write_file"), roots, roots, Set.of(), true, false, false, false);
		var child = new ChildSpec(childId, "!tool write_file {\"path\":\"" + file.getFileName()
				+ "\",\"content\":\"after\"}", ChildRole.IMPLEMENT, null, Set.of(), scope,
				new RunBudget(Duration.ofMinutes(1), null, 5), 1, true);
		try (var captured = this.scopes.create(this.parent, child, this.authority)) {
			var result = captured.call();
			assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
			RunId run = result.record().spec().runId();
			var proposal = this.changes.read().changes().stream().map(ChangeStore.Snapshot::changeSet)
					.filter(set -> set.owner().runId().equals(run)).findFirst().orElseThrow();
			assertThat(Files.readString(file)).isEqualTo("before");
			return new Staged(child, run, proposal, file);
		}
	}
	private RunSpec cloneOf(RunId source) throws Exception {
		var spec = this.runs.read(source).spec();
		return new RunSpec(RunSpec.SCHEMA_VERSION, RunId.random(), spec.conversationId(), spec.parentConversationId(),
				spec.parentRunId(), spec.childId(), spec.model(), spec.wireFamily(), spec.cwd(), spec.budget(),
				spec.optionsFingerprint(), spec.toolsFingerprint(), spec.createdAt());
	}
	private SlashCommand.CommandContext console() {
		return new SlashCommand.CommandContext(this.agent, this.session, this.screen);
	}
	private boolean apply(ChildSpec child, RunId run, UUID proposal) throws Exception {
		return this.service.apply(this.parent, child, this.authority, run, proposal, console());
	}
	/** The proposal stays staged and unapproved, the live file is untouched and nobody was asked. */
	private void assertNothingPublished(Staged staged) throws Exception {
		assertThat(Files.readString(staged.file())).isEqualTo("before");
		assertThat(this.approvals.pending()).isEmpty();
		assertThat(this.screen.questions).isEmpty();
		assertThat(this.changes.read().get(staged.proposal().changeSetId()).changeSet().status())
				.isEqualTo(ChangeStatus.PENDING_APPROVAL);
	}
	private void assertRefused(Staged staged, StagedApplyService.Code code, ThrowingCallable apply) throws Exception {
		assertThatThrownBy(apply).isInstanceOfSatisfying(StagedApplyService.Failure.class,
				ex -> assertThat(ex.code()).isEqualTo(code));
		assertNothingPublished(staged);
	}

	@Test void nonImplementRoleIsRefusedAsNotImplement() throws Exception {
		var staged = stage("implement");
		var asResearch = new ChildSpec(staged.child().childId(), staged.child().task(), ChildRole.RESEARCH, null,
				Set.of(), staged.child().scope(), staged.child().budget(), 1, true);
		assertRefused(staged, StagedApplyService.Code.NOT_IMPLEMENT,
				() -> apply(asResearch, staged.run(), staged.proposal().changeSetId()));
	}

	@ParameterizedTest
	@EnumSource(value = RunState.class, names = { "CREATED", "RUNNING", "WAITING_APPROVAL" })
	void aChildThatIsStillActiveIsRefused(RunState active) throws Exception {
		var staged = stage("implement");
		try (var writer = this.runs.create(cloneOf(staged.run()))) {
			Instant at = Instant.now();
			if (active != RunState.CREATED) writer.append(new RunEvent.StateChanged(at, RunState.RUNNING, RunEvent.Reason.NONE));
			if (active == RunState.WAITING_APPROVAL)
				writer.append(new RunEvent.StateChanged(at, RunState.WAITING_APPROVAL, RunEvent.Reason.NONE));
			assertThat(writer.record().state()).isEqualTo(active);
			assertRefused(staged, StagedApplyService.Code.CHILD_ACTIVE,
					() -> apply(staged.child(), writer.record().spec().runId(), staged.proposal().changeSetId()));
		}
	}

	@Test void aChildWithAnUnfinishedMutatingToolIsRefusedAsUnknownEffects() throws Exception {
		var staged = stage("implement");
		try (var writer = this.runs.create(cloneOf(staged.run()))) {
			interrupt(writer, true);
			assertThat(writer.record().state()).isEqualTo(RunState.INTERRUPTED);
			assertThat(writer.record().uncertainTools()).hasSize(1);
			assertRefused(staged, StagedApplyService.Code.UNKNOWN_EFFECTS,
					() -> apply(staged.child(), writer.record().spec().runId(), staged.proposal().changeSetId()));
		}
	}

	/** Controlled negative: an interruption alone is not the reason for UNKNOWN_EFFECTS. */
	@Test void anInterruptedChildWithoutUnfinishedToolsFallsThroughToTheProposalOwnerCheck() throws Exception {
		var staged = stage("implement");
		try (var writer = this.runs.create(cloneOf(staged.run()))) {
			interrupt(writer, false);
			assertThat(writer.record().uncertainTools()).isEmpty();
			assertRefused(staged, StagedApplyService.Code.FOREIGN_PROPOSAL,
					() -> apply(staged.child(), writer.record().spec().runId(), staged.proposal().changeSetId()));
		}
	}

	@Test void aProposalOwnedByAnotherChildRunIsForeign() throws Exception {
		var first = stage("first");
		var second = stage("second");
		assertRefused(first, StagedApplyService.Code.FOREIGN_PROPOSAL,
				() -> apply(first.child(), first.run(), second.proposal().changeSetId()));
		assertNothingPublished(second);
	}

	@Test void anUnknownProposalIdFailsAsNotFoundBeforeAnyApprovalIsRequested() throws Exception {
		var staged = stage("implement");
		UUID unknown = UUID.randomUUID();
		assertThatThrownBy(() -> apply(staged.child(), staged.run(), unknown))
				.isInstanceOfSatisfying(ChangeStore.Failure.class, ex -> {
					assertThat(ex.code()).isEqualTo(ChangeStore.Code.NOT_FOUND);
					assertThat(ex).hasMessageContaining(unknown.toString());
				});
		assertNothingPublished(staged);
	}

	@Test void aConsoleOfAnotherSessionCannotApplyTheParentsChild() throws Exception {
		var staged = stage("implement");
		assertThat(this.service.inspect(this.parent, staged.child(), this.authority, staged.run(),
				staged.proposal().changeSetId())).isEqualTo(staged.proposal());
		this.session.startNew();
		assertRefused(staged, StagedApplyService.Code.NOT_PARENT,
				() -> apply(staged.child(), staged.run(), staged.proposal().changeSetId()));
	}

	private void interrupt(RunWriter writer, boolean unfinishedTool) throws Exception {
		Instant at = Instant.now();
		writer.append(new RunEvent.StateChanged(at, RunState.RUNNING, RunEvent.Reason.NONE));
		writer.append(new RunEvent.AttemptStarted(at, "attempt-1", UUID.randomUUID()));
		if (unfinishedTool)
			writer.append(new RunEvent.ToolStarted(at, "attempt-1", "call-1", "write_file", DIGEST, RunEvent.Effect.MUTATING));
		writer.append(new RunEvent.StateChanged(at, RunState.INTERRUPTED, RunEvent.Reason.PROCESS_LOST));
	}
}
