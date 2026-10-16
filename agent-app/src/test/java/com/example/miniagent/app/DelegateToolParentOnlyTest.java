package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.delegation.runtime.DefaultDelegationRuntime;
import com.example.miniagent.core.delegation.runtime.DelegateTool;
import com.example.miniagent.core.delegation.runtime.DelegationController;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.model.ToolContextKeys;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunRequestStore;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunStore;
import com.example.miniagent.core.run.ToolEvidenceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The delegation entry tool only serves the parent run that owns its tool call. */
@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/b16-script", "agent.plugins.memory.enabled=false",
		"agent.plugins.trace.level=off", "agent.plugins.permission.auto-allow-tools=read_file,delegate" })
@Import(ApplyStagedToolTest.Fixture.class)
class DelegateToolParentOnlyTest {
	private static final String READ_SOURCE = "!tool read_file {\"path\":\"source.txt\"}";
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
	@Autowired RunRequestStore requests;
	@Autowired ApprovalBroker approvals;
	@Autowired DelegationController controller;
	@Autowired DelegateTool delegate;
	@Autowired ApplyStagedToolTest.ScriptedModel model;

	@BeforeEach void freshSession() throws Exception {
		this.model.contexts.clear();
		this.model.applyAfterDelegate = false;
		this.session.startNew();
		Files.writeString(workspace.resolve("source.txt"), "tool source");
	}
	@AfterEach void cleanup() throws Exception {
		for (var request : this.approvals.pending()) this.approvals.cancel(request);
		assertThat(this.approvals.pending()).isEmpty();
	}

	private static String plan(String planId, String childId, String role, String goal, String tool) {
		return ApplyStagedToolTest.JSON.writeValueAsString(Map.of("planId", planId, "children", List.of(Map.of(
				"childId", childId, "goal", goal, "role", role, "tools", List.of(tool)))));
	}
	private RunExecutor.Result delegateRun(String plan) throws Exception {
		return this.executor.start("!tool delegate " + plan, this.session.id(), this.agent.selectedModel(),
				workspace, RunBudget.defaults());
	}
	private RunContext contextOf(boolean child) {
		return this.model.contexts.values().stream().filter(context -> (context.spec().childId() != null) == child)
				.findFirst().orElseThrow();
	}
	private static ToolContext toolContext(RunContext run, RunContext turnOwner) {
		return new ToolContext(Map.of(RunContext.TOOL_CONTEXT_KEY, run,
				ToolContextKeys.TURN_CONTEXT, turnOwner.turnContext()));
	}

	@Test void aChildRunContextCannotStartAPlan() throws Exception {
		var first = delegateRun(plan("first", "read", "RESEARCH", READ_SOURCE, "read_file"));
		assertThat(first.record().state()).isEqualTo(RunState.COMPLETED);
		var child = contextOf(true);
		var runsBefore = this.runs.list();
		String second = plan("second", "read", "RESEARCH", READ_SOURCE, "read_file");
		assertThatThrownBy(() -> this.delegate.call(second, toolContext(child, child)))
				.isInstanceOf(IllegalStateException.class).hasMessage("Parent-only operation");
		assertThat(this.runs.list()).isEqualTo(runsBefore);
		assertThat(this.controller.list(this.session.id())).extracting(view -> view.plan().planId())
				.containsExactly("first");
	}

	@Test void aParentContextWithAForeignTurnContextCannotStartAPlan() throws Exception {
		delegateRun(plan("first", "read", "RESEARCH", READ_SOURCE, "read_file"));
		var parent = contextOf(false);
		var foreignTurn = contextOf(true);
		var runsBefore = this.runs.list();
		String second = plan("second", "read", "RESEARCH", READ_SOURCE, "read_file");
		assertThatThrownBy(() -> this.delegate.call(second, toolContext(parent, foreignTurn)))
				.isInstanceOf(IllegalStateException.class).hasMessage("Parent-only operation");
		assertThat(this.runs.list()).isEqualTo(runsBefore);
		// Controlled negative: with its own turn context the parent passes this guard, and the call is
		// stopped later because the finished attempt no longer owns a journal.
		assertThatThrownBy(() -> this.delegate.call(second, toolContext(parent, parent)))
				.isInstanceOf(IllegalStateException.class).hasMessage("Missing captured journal owner");
		assertThat(this.runs.list()).isEqualTo(runsBefore);
		assertThat(this.controller.list(this.session.id())).hasSize(1);
	}

	@Test void callsWithoutAManagedParentAreRefused() {
		assertThatThrownBy(() -> this.delegate.call("{}"))
				.isInstanceOf(IllegalStateException.class).hasMessage("Bound parent execution required");
		assertThatThrownBy(() -> this.delegate.call("{}", new ToolContext(Map.of())))
				.isInstanceOf(IllegalStateException.class).hasMessage("Managed parent required");
	}

	@Test void unansweredChildApprovalReturnsAPendingOutcomeAsJsonToTheModel() throws Exception {
		String writer = "!tool write_file {\"path\":\"pending.txt\",\"content\":\"x\"}";
		var run = delegateRun(plan("pend", "writer", "IMPLEMENT", writer, "write_file"));
		// The model still gets its tool result, but the parent cannot complete while a child is unfinished.
		assertThat(run.record().state()).isEqualTo(RunState.INTERRUPTED);
		var returned = delegateResult(run.record().spec().runId());
		var outcome = ApplyStagedToolTest.JSON.readTree(returned);
		assertThat(outcome.propertyNames()).containsExactlyInAnyOrder("planId", "children", "approvals", "completed", "queued");
		assertThat(outcome.path("planId").asString()).isEqualTo("pend");
		assertThat(outcome.path("children")).hasSize(1);
		assertThat(outcome.path("children").path(0).path("childId").asString()).isEqualTo("writer");
		assertThat(outcome.path("children").path(0).path("record").path("state").asString()).isEqualTo("WAITING_APPROVAL");
		assertThat(outcome.path("approvals")).hasSize(1);
		assertThat(outcome.path("approvals").path(0).path("childId").asString()).isEqualTo("writer");
		assertThat(outcome.path("completed")).isEmpty();
		assertThat(outcome.path("queued")).isEmpty();
		assertThat(this.controller.show(this.session.id(), "pend").outcome()).isInstanceOfSatisfying(
				DefaultDelegationRuntime.Pending.class, pending -> assertThat(pending.approvals())
						.extracting(request -> request.requestId().toString())
						.containsExactly(outcome.path("approvals").path(0).path("requestId").asString()));
		assertThat(workspace.resolve("pending.txt")).doesNotExist();
		assertThat(this.controller.cancel(this.session.id(), "pend")).isTrue();
	}

	/** The raw text the delegate tool handed back to the model, as acknowledged by the parent's journal. */
	private String delegateResult(RunId parent) throws Exception {
		var events = FileRunStore.events(this.runs, parent);
		var started = events.stream().filter(RunEvent.ToolStarted.class::isInstance).map(RunEvent.ToolStarted.class::cast)
				.filter(event -> event.toolName().equals("delegate")).findFirst().orElseThrow();
		var finished = events.stream().filter(RunEvent.ToolFinished.class::isInstance).map(RunEvent.ToolFinished.class::cast)
				.filter(event -> event.toolCallKey().equals(started.toolCallKey())).findFirst().orElseThrow();
		return this.requests.evidence().get(finished.resultDigest(), ToolEvidenceStore.ResultEvidence.class).result();
	}
}
