package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.delegation.DelegationController;
import com.example.miniagent.core.delegation.DefaultDelegationRuntime;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.run.*;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual official advisor, child file tools, parent continuation and durable resume. */
@SpringBootTest(properties = {"agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/shared-budget", "agent.plugins.memory.enabled=false",
		"agent.plugins.trace.level=off", "agent.plugins.permission.auto-allow-tools=read_file,delegate"})
@Import(SharedParentBudgetIntegrationTest.Fixture.class)
class SharedParentBudgetIntegrationTest {
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
	@Autowired Calls calls;
	@Autowired ApprovalBroker broker;
	@BeforeEach void setup() throws Exception {
		this.session.startNew();
		Files.writeString(workspace.resolve("source.txt"), "real read evidence");
		this.calls.root.set(0); this.calls.child.set(0); this.calls.rootTokens.set(0);
		this.calls.mode = Mode.COMPLETE; this.calls.unknown = false; this.calls.childTokens = 2;
	}
	@AfterEach void cleanup() {
		for (var view : this.controller.list(this.session.id())) {
			this.controller.cancel(this.session.id(), view.plan().planId());
		}
		for (var request : this.broker.pending()) this.broker.cancel(request);
	}
	enum Mode { COMPLETE, READ_AGAIN, SECOND_PLAN }
	static final class Calls {
		final AtomicInteger root = new AtomicInteger(), child = new AtomicInteger();
		final AtomicLong rootTokens = new AtomicLong();
		volatile Mode mode = Mode.COMPLETE;
		volatile boolean unknown;
		volatile int childTokens = 2;
	}
	static String plan(String name, Long cap) {
		return "{\"planId\":\"" + name + "\",\"children\":[{\"childId\":\"read\","
				+ "\"goal\":\"!tool read_file {\\\"path\\\":\\\"source.txt\\\"}\","
				+ "\"role\":\"RESEARCH\",\"tools\":[\"read_file\"],\"toolCallCap\":1,\"wallSeconds\":60"
				+ (cap == null ? "" : ",\"tokenCap\":" + cap) + "}]}";
	}
	RunExecutor.Result start(int tools, Long tokens) throws Exception {
		return this.executor.start("!tool delegate " + plan("first", tokens == null ? null : 10L),
				this.session.id(), this.agent.selectedModel(), workspace,
				new RunBudget(Duration.ofMinutes(3), tokens, tools));
	}
	@Test void parentTotalsIncludeActualChildDeltasExactlyOnce() throws Exception {
		var parent = start(50, null).record();
		var children = ((DefaultDelegationRuntime.Completed) this.controller.show(this.session.id(), "first").outcome()).result();
		assertThat(parent.state()).isEqualTo(RunState.COMPLETED);
		assertThat(children.usage().knownTokens()).isEqualTo(4);
		assertThat(parent.usage().knownTokens()).isEqualTo(this.calls.rootTokens.get() + children.usage().knownTokens());
		assertThat(parent.usage().toolCalls()).isEqualTo(2);
		assertThat(new FileRunStore(state.resolve("home")).read(parent.spec().runId()).usage()).isEqualTo(parent.usage());
	}
	@Test void exhaustedSharedToolsStopParentContinuationAndRemainExhaustedOnResume() throws Exception {
		this.calls.mode = Mode.READ_AGAIN;
		var parent = start(2, null).record();
		assertThat(parent.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(parent.reason()).isEqualTo(RunEvent.Reason.BUDGET_EXHAUSTED);
		assertThat(parent.usage().toolCalls()).isEqualTo(2);
		assertThat(this.calls.child).hasValue(2);
		var resumed = this.executor.resume(parent.spec().runId(), this.agent.selectedModel(), workspace,
				RunExecutor.ResumeOptions.ordinary()).record();
		assertThat(resumed.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(resumed.reason()).isEqualTo(RunEvent.Reason.BUDGET_EXHAUSTED);
		assertThat(resumed.usage().toolCalls()).isEqualTo(2);
		assertThat(this.calls.child).hasValue(2);
		assertThat(resumed.attempts()).hasSize(2);
	}
	@Test void secondDelegationCannotForgetFirstChildConsumption() throws Exception {
		this.calls.mode = Mode.SECOND_PLAN;
		var parent = start(3, null).record();
		assertThat(this.calls.child).hasValue(2);
		assertThat(parent.usage().toolCalls()).isEqualTo(3);
		int children = 0;
		for (var id : this.runs.list()) {
			if (parent.spec().runId().equals(this.runs.read(id).spec().parentRunId())) children++;
		}
		assertThat(children).isEqualTo(1);
	}
	@Test void unknownChildUsageStopsParentModelAndFreshAttemptWithTokenCap() throws Exception {
		this.calls.unknown = true;
		var parent = start(50, 20L).record();
		assertThat(parent.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(parent.reason()).isEqualTo(RunEvent.Reason.BUDGET_EXHAUSTED);
		assertThat(parent.usage().tokensUnknown()).isTrue();
		assertThat(this.calls.root).hasValue(1);
		var resumed = this.executor.resume(parent.spec().runId(), this.agent.selectedModel(), workspace,
				RunExecutor.ResumeOptions.ordinary()).record();
		assertThat(resumed.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(resumed.usage().tokensUnknown()).isTrue();
		assertThat(this.calls.root).hasValue(1);
		assertThat(resumed.attempts()).hasSize(2);
	}
	@Test void inFlightChildOvershootUsesParentCapRatherThanChildOvershootTotal() throws Exception {
		this.calls.childTokens = 30;
		var parent = start(50, 20L).record();
		assertThat(parent.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(parent.reason()).isEqualTo(RunEvent.Reason.BUDGET_EXHAUSTED);
		assertThat(parent.usage().knownTokens()).isEqualTo(32);
		assertThat(parent.usage().overshootTokens()).isEqualTo(12);
		assertThat(this.calls.root).hasValue(1);
		assertThat(this.calls.child).hasValue(1);
	}
	@Test void modelPendingChildResumesUnderSameNonterminalParentAndKeepsAllCharges() throws Exception {
		String plan = "{\"planId\":\"pending-budget\",\"children\":[{\"childId\":\"write\","
				+ "\"role\":\"IMPLEMENT\",\"tools\":[\"write_file\"],\"writeRoots\":[\".\"],"
				+ "\"goal\":\"!tool write_file {\\\"path\\\":\\\"source.txt\\\",\\\"content\\\":\\\"staged-only\\\"}\","
				+ "\"toolCallCap\":3,\"wallSeconds\":60}]}";
		var parent = this.executor.start("!tool delegate " + plan, this.session.id(),
				this.agent.selectedModel(), workspace, RunBudget.defaults()).record();
		assertThat(parent.state()).isIn(RunState.INTERRUPTED, RunState.WAITING_APPROVAL);
		var view = this.controller.show(this.session.id(), "pending-budget");
		var pending = (DefaultDelegationRuntime.Pending) view.outcome();
		var child = pending.children().getFirst().record();
		assertThat(child.spec().parentRunId()).isEqualTo(parent.spec().runId());
		assertThat(parent.usage().knownTokens()).isEqualTo(this.calls.rootTokens.get() + child.usage().knownTokens());
		var request = this.broker.pending().getFirst();
		this.controller.resolve(this.session.id(), "pending-budget", request, ApprovalDecision.APPROVED);
		var outcome = this.controller.resume(this.session.id(), "pending-budget", "write", RunExecutor.ResumeOptions.ordinary());
		assertThat(outcome).isInstanceOf(DefaultDelegationRuntime.Completed.class);
		var resumedParent = this.runs.read(parent.spec().runId());
		var resumedChild = this.runs.read(child.spec().runId());
		assertThat(resumedParent.state()).isEqualTo(RunState.COMPLETED);
		assertThat(resumedParent.attempts()).hasSize(2);
		assertThat(resumedChild.attempts()).hasSize(2);
		assertThat(resumedParent.usage().knownTokens())
				.isEqualTo(this.calls.rootTokens.get() + resumedChild.usage().knownTokens());
		assertThat(resumedParent.usage().toolCalls()).isEqualTo(1 + resumedChild.usage().toolCalls());
		assertThat(Files.readString(workspace.resolve("source.txt"))).isEqualTo("real read evidence");
	}
	@TestConfiguration static class Fixture {
		@Bean Calls calls() { return new Calls(); }
		@Bean ChangeCommandsE2ETest.Screen agentConsole() { return new ChangeCommandsE2ETest.Screen(); }
		@Bean ModelProvider fakeModelProvider(Calls calls) {
			return new ModelProvider() {
				public String providerId() { return "fake"; }
				public ProviderAuth auth() { return () -> true; }
				public List<ModelEntry> models() { return List.of(new ModelEntry(new ModelRef("fake", "shared-budget"), "fake", null)); }
				public ChatModel create(ModelRef ref, Map<String, String> options) {
					return new ScriptedChatModel() {
						private int rootRound;
						@Override public ChatResponse call(Prompt prompt) {
							boolean child = RunContext.current().orElseThrow().spec().childId() != null;
							ChatResponse response;
							if (!child) this.rootRound++;
							boolean resumedRead = !child && this.rootRound == 1 && calls.mode == Mode.READ_AGAIN
									&& RunContext.current().orElseThrow().writer().record().attempts().size() > 1;
							if (!child && (this.rootRound == 2 || resumedRead) && calls.mode != Mode.COMPLETE) {
								String next = calls.mode == Mode.READ_AGAIN
										? "!tool read_file {\"path\":\"source.txt\"}" : "!tool delegate " + plan("second", null);
								response = super.call(new Prompt(next, prompt.getOptions()));
							}
							else response = super.call(prompt);
							if (child) calls.child.incrementAndGet();
							else { calls.root.incrementAndGet(); calls.rootTokens.addAndGet(2); }
							return new ChatResponse(response.getResults(), ChatResponseMetadata.builder()
									.usage(child && calls.unknown ? new EmptyUsage()
											: new DefaultUsage(1, child ? calls.childTokens - 1 : 1)).build());
						}
					};
				}
			};
		}
	}
}
