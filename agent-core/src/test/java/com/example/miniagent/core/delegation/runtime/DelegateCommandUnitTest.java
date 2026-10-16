package com.example.miniagent.core.delegation.runtime;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.delegation.ChildSpec;
import com.example.miniagent.core.delegation.DelegationPlan;
import com.example.miniagent.core.delegation.ToolScope;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** /delegate parsing and dispatch; the controller is faked at the command's supplier seam (agent-app runs the real one). */
class DelegateCommandUnitTest {
	private static final String USAGE = "Usage: /delegate start <plan JSON> | list";
	@TempDir Path cwd;
	private final DelegationController service = mock(DelegationController.class);
	private final ByteArrayOutputStream screen = new ByteArrayOutputStream();
	private final AgentConsole console = new AgentConsole(new BufferedReader(new StringReader("")),
			new PrintStream(this.screen, true, StandardCharsets.UTF_8), false);
	private final AgentSession session = new AgentSession();
	private final UUID requestId = UUID.randomUUID();
	private DelegateCommand command;
	private SlashCommand.CommandContext context;

	@BeforeEach void setup() {
		this.session.switchTo("conv");
		this.command = new DelegateCommand(() -> this.service,
				() -> { throw new AssertionError("Only a parsed /delegate start obtains the executor"); },
				new RunProperties(Duration.ofMinutes(30), null, 50), this.cwd);
		ChatModel model = new ChatModel() {
			@Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
			@Override public ChatResponse call(Prompt prompt) { throw new AssertionError("Commands never call the model"); }
		};
		var agent = new Agent(ChatClient.builder(model).build(), this.session, this.cwd, "system",
				List.of(readFile()), List.of(), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
		this.context = new SlashCommand.CommandContext(agent, this.session, this.console);
	}

	private static ToolCallback readFile() {
		var definition = ToolDefinition.builder().name("read_file").description("read bounded path")
				.inputSchema("{\"type\":\"object\"}").build();
		return new ToolCallback() {
			@Override public ToolDefinition getToolDefinition() { return definition; }
			@Override public String call(String input) { throw new AssertionError("Commands never call tools"); }
		};
	}

	private boolean run(String args) {
		return this.command.executeWithStatus(args, this.context);
	}

	private String screen() {
		return this.screen.toString(StandardCharsets.UTF_8);
	}

	private void fails(String args, String error) {
		this.screen.reset();
		assertThat(run(args)).isFalse();
		assertThat(screen()).contains(error).contains(USAGE);
	}

	private DelegationController.View view(String planId, DefaultDelegationRuntime.Outcome outcome, boolean busy) {
		var authority = new ToolScope(Set.of(), Set.of(), Set.of(), Set.of(), false, false, false, false);
		return new DelegationController.View(new DelegationPlan(planId, List.of(), 1), null, authority, outcome, busy);
	}

	private DefaultDelegationRuntime.Pending pending(String planId) {
		var request = new ApprovalRequest(this.requestId, RunId.random(), "attempt", planId, "child", "tool",
				"a".repeat(64), null);
		return new DefaultDelegationRuntime.Pending(planId, List.of(), List.of(request), List.of(), List.of());
	}

	private RunRecord record(RunState state) {
		var spec = new RunSpec(1, RunId.random(), "child-conversation", "conv", RunId.random(), "child",
				new ModelRef("fake", "test"), "fake", this.cwd.toString(), RunBudget.defaults(),
				"a".repeat(64), "a".repeat(64), Instant.EPOCH);
		return new RunRecord(spec, 1, state, RunEvent.Reason.NONE, List.of(), RunUsage.empty(), List.of());
	}

	private static String plan(String... childIds) {
		var children = new StringBuilder();
		for (String id : childIds) {
			if (!children.isEmpty()) children.append(',');
			children.append("{\"childId\":\"").append(id)
					.append("\",\"goal\":\"Finish\",\"role\":\"RESEARCH\",\"tools\":[\"read_file\"]}");
		}
		return "{\"planId\":\"p\",\"children\":[" + children + "]}";
	}

	@Test void argumentErrorsPrintTheErrorAndUsageWithoutReachingTheController() {
		fails("", "IllegalArgumentException: Missing plan ID");
		fails("show", "IllegalArgumentException: Missing plan ID");
		fails("list extra", "IllegalArgumentException: Unknown delegation operation");
		fails("frobnicate p", "IllegalArgumentException: Unknown delegation operation");
		fails("show p extra", "IllegalArgumentException: Unexpected arguments");
		fails("cancel p extra", "IllegalArgumentException: Unexpected arguments");
		fails("approve p", "IllegalArgumentException: Unexpected arguments");
		fails("apply p c", "IllegalArgumentException: Unexpected arguments");
		fails("resume p", "IllegalArgumentException: Missing child ID");
		fails("start", "IllegalArgumentException: Missing plan JSON");
		fails("recover " + RunId.random().value(), "IllegalArgumentException: Expected -- before exact one-child plan JSON");
		fails("recover -- " + plan("a"), "IllegalArgumentException: Missing child run ID");
		verifyNoInteractions(this.service);
	}

	@Test void planJsonErrorsFailBeforeTheControllerOrExecutorIsUsed() {
		fails("start {\"planId\":", "UnexpectedEndOfInputException: ");
		fails("start {\"planId\":\"p\",\"children\":[],\"extra\":1}", "UnrecognizedPropertyException: ");
		fails("recover " + RunId.random().value() + " -- " + plan("a", "b"),
				"IllegalArgumentException: Recover accepts one exact child, not a reconstructed DAG");
		verifyNoInteractions(this.service);
	}

	@Test void listShowsOnlyThePlansOfTheConsoleConversation() {
		when(this.service.list("conv")).thenReturn(List.of(view("a", null, true), view("b", pending("b"), false)));
		assertThat(run("list")).isTrue();
		assertThat(screen()).contains("plan=a busy=true\n", "plan=b busy=false\n",
				"\"planId\":\"b\"", this.requestId.toString()).doesNotContain(USAGE);
		this.screen.reset();
		this.session.switchTo("other");
		assertThat(run("list")).isTrue();
		assertThat(screen()).isEmpty();
	}

	@Test void cancelSucceedsOnlyWhenTheControllerCancelledThePlan() {
		when(this.service.cancel("conv", "p")).thenReturn(true);
		assertThat(run("cancel p")).isTrue();
		assertThat(screen()).contains("CANCELLED p").doesNotContain(USAGE);
		fails("cancel q", "IllegalArgumentException: No active owned plan");
		assertThat(screen()).doesNotContain("CANCELLED q");
		this.screen.reset();
		this.command.execute("cancel p", this.context);
		assertThat(screen()).contains("CANCELLED p");
	}

	@Test void approveRejectAndDeferResolveTheRetainedRequestAndDeferIsNotDone() throws Exception {
		var pending = pending("p");
		var request = pending.approvals().getFirst();
		var resolved = record(RunState.RUNNING);
		when(this.service.show("conv", "p")).thenReturn(view("p", pending, false));
		when(this.service.resolve(eq("conv"), eq("p"), any(), any())).thenReturn(resolved);
		assertThat(run("approve p " + this.requestId)).isTrue();
		assertThat(run("reject p " + this.requestId)).isTrue();
		assertThat(run("defer p " + this.requestId)).isFalse();
		verify(this.service).resolve("conv", "p", request, ApprovalDecision.APPROVED);
		verify(this.service).resolve("conv", "p", request, ApprovalDecision.REJECTED);
		verify(this.service).resolve("conv", "p", request, ApprovalDecision.DEFERRED);
		assertThat(screen()).contains("child run=" + resolved.spec().runId().value() + " state=RUNNING")
				.doesNotContain(USAGE);
	}

	@Test void approvalThatDoesNotNameTheRetainedRequestIsRefused() throws Exception {
		when(this.service.show("conv", "p")).thenReturn(view("p", pending("p"), false));
		when(this.service.show("conv", "idle")).thenReturn(view("idle", null, false));
		fails("approve p " + UUID.randomUUID(), "IllegalArgumentException: Foreign/stale request");
		fails("reject p not-a-uuid", "not-a-uuid");
		fails("defer idle " + this.requestId, "IllegalArgumentException: No retained pending approval");
		verify(this.service, never()).resolve(any(), any(), any(), any());
	}

	@Test void resumeForwardsTheChildAndParsedResumeOptions() {
		when(this.service.resume(eq("conv"), eq("p"), eq("c"), any())).thenReturn(pending("p"));
		assertThat(run("resume p c")).isFalse();
		assertThat(screen()).contains("\"planId\":\"p\"").doesNotContain(USAGE);
		assertThat(run("resume p c --resolve old-attempt call READ_RETRY evidence-1")).isFalse();
		fails("resume p c --bogus", "IllegalArgumentException: Invalid resume option");
		var options = ArgumentCaptor.forClass(RunExecutor.ResumeOptions.class);
		verify(this.service, times(2)).resume(eq("conv"), eq("p"), eq("c"), options.capture());
		assertThat(options.getAllValues().getFirst()).isEqualTo(RunExecutor.ResumeOptions.ordinary());
		var resolution = options.getAllValues().getLast().resolutions();
		assertThat(resolution).singleElement().satisfies(input -> {
			assertThat(input.oldAttemptId()).isEqualTo("old-attempt");
			assertThat(input.toolCallKey()).isEqualTo("call");
			assertThat(input.decision()).isEqualTo(RunEvent.Resolution.READ_RETRY);
			assertThat(input.evidenceId()).isEqualTo("evidence-1");
			assertThat(input.operatorId()).startsWith("console-");
		});
	}

	@Test void recoverPassesTheExactChildRunPlanAndConsoleAuthority() throws Exception {
		String childRun = RunId.random().value();
		var completed = record(RunState.COMPLETED);
		when(this.service.recover(any(), any(), any(), any(), any(), any()))
				.thenReturn(new RunExecutor.Result(completed, new Agent.AgentReply("child summary", null)));
		assertThat(run("recover " + childRun + " -- " + plan("lost"))).isTrue();
		assertThat(screen()).contains("recovered execution run=" + completed.spec().runId().value() + " state=COMPLETED",
				"child summary").doesNotContain(USAGE);
		var child = ArgumentCaptor.forClass(ChildSpec.class);
		var authority = ArgumentCaptor.forClass(ToolScope.class);
		verify(this.service).recover(eq(new RunId(childRun)), eq("p"), child.capture(), eq("conv"),
				authority.capture(), eq(RunExecutor.ResumeOptions.ordinary()));
		assertThat(child.getValue().childId()).isEqualTo("lost");
		assertThat(child.getValue().task()).isEqualTo("Finish");
		assertThat(authority.getValue().toolNames()).containsExactly("read_file");
		assertThat(authority.getValue().readRoots()).containsExactly(this.cwd.toRealPath());
		when(this.service.recover(any(), any(), any(), any(), any(), any()))
				.thenReturn(new RunExecutor.Result(record(RunState.INTERRUPTED), null));
		this.screen.reset();
		assertThat(run("recover " + childRun + " -- " + plan("lost"))).isFalse();
		assertThat(screen()).contains("state=INTERRUPTED").doesNotContain(USAGE);
	}

	@Test void showPrintsTheViewAndApplyWithoutAManagedParentIsRefused() throws Exception {
		when(this.service.show("conv", "p")).thenReturn(view("p", null, true));
		assertThat(run("show p")).isTrue();
		assertThat(screen()).contains("plan=p busy=true").doesNotContain(USAGE);
		fails("apply p c " + UUID.randomUUID(),
				"IllegalArgumentException: Recovered child has no current managed parent apply authority");
		verify(this.service, never()).apply(any(), any(), any(), any(), any());
	}
}
