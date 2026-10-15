package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.change.ChangeSet;
import com.example.miniagent.core.change.ChangeStatus;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.delegation.ChildRole;
import com.example.miniagent.core.delegation.ChildSpec;
import com.example.miniagent.core.delegation.ToolScope;
import com.example.miniagent.core.delegation.runtime.ChildScopeFactory;
import com.example.miniagent.core.delegation.runtime.StagedApplyService;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ProviderAuth;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunStore;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/staged-apply", "agent.plugins.memory.enabled=false",
		"agent.plugins.trace.level=off", "agent.plugins.permission.auto-allow-tools=write_file",
		"agent.plugins.permission.allow-non-interactive=true" })
@Import(StagedApplyTest.Fixture.class)
class StagedApplyTest {
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
	private ExecutorService worker;
	private TurnContext parent;
	private ChildSpec child;
	private ToolScope authority;
	private RunId childRun;
	private ChangeSet proposal;
	private Path file;
	private Path other;

	@BeforeEach void stage() throws Exception {
		this.worker = Executors.newSingleThreadExecutor();
		this.screen.setInteractive(false);
		this.screen.questions.clear();
		this.screen.lines.clear();
		this.session.startNew();
		this.file = workspace.resolve("staged-" + UUID.randomUUID() + ".txt");
		this.other = workspace.resolve("other-" + UUID.randomUUID() + ".txt");
		Files.writeString(this.file, "before");
		Files.writeString(this.other, "untouched");
		this.parent = new TurnContext(this.session.id(), UUID.randomUUID().toString(), workspace,
				this.agent.modelRegistry().bind(this.agent.selectedModel()));
		Set<Path> roots = Set.of(workspace.toAbsolutePath().normalize());
		this.authority = new ToolScope(Set.of("write_file"), roots, roots, Set.of(), true, false, true, true);
		var scope = new ToolScope(Set.of("write_file"), roots, roots, Set.of(), true, false, false, false);
		this.child = new ChildSpec("implement", "!tool write_file {\"path\":\"" + this.file.getFileName()
				+ "\",\"content\":\"after\"}", ChildRole.IMPLEMENT, null, Set.of(), scope,
				new RunBudget(Duration.ofMinutes(1), null, 5), 1, true);
		try (var captured = this.scopes.create(this.parent, this.child, this.authority)) {
			var result = captured.call();
			assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
			this.childRun = result.record().spec().runId();
		}
		this.proposal = this.changes.read().changes().stream().map(snapshot -> snapshot.changeSet())
				.filter(set -> set.owner().runId().equals(this.childRun)).findFirst().orElseThrow();
		assertThat(Files.readString(this.file)).isEqualTo("before");
	}
	@AfterEach void cleanup() throws Exception {
		for (var request : this.approvals.pending()) this.approvals.cancel(request);
		this.worker.shutdownNow();
		assertThat(this.worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		assertThat(this.approvals.pending()).isEmpty();
	}
	private SlashCommand.CommandContext console() { return new SlashCommand.CommandContext(this.agent, this.session, this.screen); }
	private boolean apply() throws Exception {
		return this.service.apply(this.parent, this.child, this.authority, this.childRun,
				this.proposal.changeSetId(), console());
	}
	@Test void actualImplementProposalCanBeInspectedWithoutApprovalOrPublication() throws Exception {
		assertThat(this.service.inspect(this.parent, this.child, this.authority, this.childRun,
				this.proposal.changeSetId())).isEqualTo(this.proposal);
		assertThat(this.approvals.pending()).isEmpty();
		assertThat(Files.readString(this.file)).isEqualTo("before");
	}
	@Test void onlyParentApprovedExclusiveCommandPublishesChildStage() throws Exception {
		this.screen.setInteractive(true);
		this.screen.lines.add("exclusive " + this.proposal.changeSetId() + " " + this.proposal.digest());
		var future = this.worker.submit(this::apply);
		var question = this.screen.questions.poll(10, TimeUnit.SECONDS);
		assertThat(question).isNotNull();
		assertThat(question.runId()).isEqualTo(this.childRun);
		assertThat(question.diffDigest()).isEqualTo(this.proposal.digest());
		assertThat(this.approvals.respond(question, ApprovalDecision.APPROVED)).isTrue();
		assertThat(future.get(15, TimeUnit.SECONDS)).isTrue();
		assertThat(Files.readString(this.file)).isEqualTo("after");
		assertThat(Files.readString(this.other)).isEqualTo("untouched");
	}
	@Test void unattendedApplyStaysPendingAndNeverPublishes() throws Exception {
		assertThat(apply()).isFalse();
		assertThat(this.approvals.pending()).hasSize(1);
		assertThat(Files.readString(this.file)).isEqualTo("before");
		assertThat(this.changes.read().get(this.proposal.changeSetId()).changeSet().status())
				.isEqualTo(ChangeStatus.PENDING_APPROVAL);
	}
	@Test void humanEditAfterStageRejectsWholeChild() throws Exception {
		Files.writeString(this.other, "human");
		assertThatThrownBy(this::apply).isInstanceOfSatisfying(StagedApplyService.Failure.class,
				ex -> assertThat(ex.code()).isEqualTo(StagedApplyService.Code.STALE));
		assertThat(Files.readString(this.file)).isEqualTo("before");
		assertThat(Files.readString(this.other)).isEqualTo("human");
		assertThat(this.approvals.pending()).isEmpty();
		assertThat(this.changes.read().get(this.proposal.changeSetId()).changeSet().status()).isEqualTo(ChangeStatus.STALE);
	}
	@Test void foreignParentAndChildActorCannotRequestApplication() throws Exception {
		var foreign = new TurnContext(UUID.randomUUID().toString(), UUID.randomUUID().toString(), workspace, this.parent.binding());
		assertThatThrownBy(() -> this.service.inspect(foreign, this.child, this.authority, this.childRun,
				this.proposal.changeSetId())).isInstanceOfSatisfying(StagedApplyService.Failure.class,
						ex -> assertThat(ex.code()).isEqualTo(StagedApplyService.Code.FOREIGN_CHILD));
		var stored = this.runs.read(this.childRun);
		var actor = new TurnContext(stored.spec().conversationId(), UUID.randomUUID().toString(),
				workspace, this.parent.binding(), this.childRun.value(), stored.attempts().getLast().attemptId());
		assertThatThrownBy(() -> this.service.inspect(actor, this.child, this.authority, this.childRun,
				this.proposal.changeSetId())).isInstanceOfSatisfying(StagedApplyService.Failure.class,
						ex -> assertThat(ex.code()).isEqualTo(StagedApplyService.Code.NOT_PARENT));
		assertThat(this.approvals.pending()).isEmpty();
		assertThat(Files.readString(this.file)).isEqualTo("before");
	}
	@Test void reducedParentApplyAuthorityIsNotWidened() throws Exception {
		var reduced = new ToolScope(this.authority.toolNames(), this.authority.readRoots(), this.authority.writeRoots(),
				Set.of(), true, false, true, false);
		assertThatThrownBy(() -> this.service.inspect(this.parent, this.child, reduced, this.childRun,
				this.proposal.changeSetId())).isInstanceOfSatisfying(StagedApplyService.Failure.class,
						ex -> assertThat(ex.code()).isEqualTo(StagedApplyService.Code.APPLY_DENIED));
		assertThat(this.approvals.pending()).isEmpty();
	}
	@TestConfiguration static class Fixture {
		@Bean ChangeCommandsE2ETest.Screen agentConsole() { return new ChangeCommandsE2ETest.Screen(); }
		@Bean ModelProvider fakeModelProvider() {
			return new ModelProvider() {
				public String providerId() { return "fake"; }
				public ProviderAuth auth() { return () -> true; }
				public List<ModelEntry> models() { return List.of(new ModelEntry(new ModelRef("fake", "staged-apply"), "fake", null)); }
				public ChatModel create(ModelRef ref, Map<String, String> options) { return new ScriptedChatModel(); }
			};
		}
	}
}
