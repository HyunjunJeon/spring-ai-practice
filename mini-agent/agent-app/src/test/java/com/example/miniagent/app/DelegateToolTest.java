package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.delegation.*;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/staged-apply", "agent.plugins.memory.enabled=false",
		"agent.plugins.trace.level=off", "agent.plugins.permission.auto-allow-tools=read_file,delegate"})
@Import(StagedApplyTest.Fixture.class)
class DelegateToolTest {
	@TempDir static Path workspace;
	@TempDir static Path state;
	@DynamicPropertySource static void paths(DynamicPropertyRegistry properties) {
		properties.add("agent.cwd", () -> workspace.toString());
		properties.add("agent.home", () -> state.resolve("home").toString());
	}
	@Autowired Agent agent;
	@Autowired AgentSession session;
	@Autowired RunExecutor executor;
	@Autowired RunStore runs;
	@Autowired DelegationController controller;
	@Autowired ChildScopeFactory scopes;
	@Test void modelDelegateUsesOfficialAdvisorAndActualParentJournal() throws Exception {
		this.session.startNew();
		Files.writeString(workspace.resolve("source.txt"), "tool source");
		String goal = "!tool delegate {\"planId\":\"model\",\"children\":[{\"childId\":\"read\","
				+ "\"goal\":\"!tool read_file {\\\"path\\\":\\\"source.txt\\\"}\",\"role\":\"RESEARCH\",\"tools\":[\"read_file\"]}]}";
		var result = this.executor.start(goal, this.session.id(), this.agent.selectedModel(), workspace, RunBudget.defaults());
		assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
		var owned = ((DefaultDelegationRuntime.Completed) this.controller.show(this.session.id(), "model").outcome()).result();
		assertThat(owned.status()).isEqualTo(DelegationResult.Status.COMPLETE);
		assertThat(FileRunStore.events(this.runs, result.record().spec().runId()))
				.anyMatch(event -> event instanceof RunEvent.ToolStarted started && started.toolName().equals("delegate"));
		assertThat(result.record().uncertainTools()).isEmpty();
	}
	@Test void childCannotInvokeParentApplyBeforeToolBudget() throws Exception {
		this.session.startNew();
		var root = workspace.toRealPath();
		var before = this.runs.list();
		var parent = new com.example.miniagent.core.model.TurnContext(this.session.id(), "turn", root,
				this.agent.modelRegistry().bind(this.agent.selectedModel()));
		var authority = new ToolScope(Set.of("apply_staged"), Set.of(root), Set.of(root), Set.of(),
				true, false, true, true);
		var child = new ChildSpec("child", "!tool apply_staged {}", ChildRole.RESEARCH, null, Set.of(),
				new ToolScope(Set.of("apply_staged"), Set.of(root), Set.of(), Set.of(), false, false, false, false),
				RunBudget.defaults(), 1, true);
		assertThatThrownBy(() -> this.scopes.create(parent, child, authority)).isInstanceOfSatisfying(RolePolicy.Denied.class,
						ex -> assertThat(ex.code()).isEqualTo(RolePolicy.Code.PARENT_ONLY));
		assertThat(this.runs.list()).isEqualTo(before);
	}
}
