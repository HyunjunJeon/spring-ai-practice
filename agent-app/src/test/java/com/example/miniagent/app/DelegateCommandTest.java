package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.delegation.DelegationResult;
import com.example.miniagent.core.delegation.runtime.DefaultDelegationRuntime;
import com.example.miniagent.core.delegation.runtime.DelegationController;
import com.example.miniagent.core.run.RunStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/staged-apply", "agent.plugins.memory.enabled=false",
		"agent.plugins.trace.level=off", "agent.plugins.permission.auto-allow-tools=read_file,delegate"})
@Import(StagedApplyTest.Fixture.class)
class DelegateCommandTest {
	@TempDir static Path workspace;
	@TempDir static Path state;
	@DynamicPropertySource static void paths(DynamicPropertyRegistry properties) {
		properties.add("agent.cwd", () -> workspace.toString());
		properties.add("agent.home", () -> state.resolve("home").toString());
	}
	@Autowired AgentInputHandler handler;
	@Autowired AgentSession session;
	@Autowired DelegationController controller;
	@Autowired RunStore runs;
	@Autowired ApprovalBroker broker;
	@Autowired ChangeCommandsE2ETest.Screen screen;
	@BeforeEach void setup() throws Exception {
		this.session.startNew();
		this.screen.setInteractive(false);
		this.screen.questions.clear(); this.screen.lines.clear();
		this.screen.output.reset();
		Files.writeString(workspace.resolve("source.txt"), "actual source");
	}
	@AfterEach void cleanup() {
		for (var request : this.broker.pending()) this.broker.cancel(request);
	}
	private static String plan(String id, String goal) {
		return "{\"planId\":\"" + id + "\",\"children\":[{\"childId\":\"research\",\"role\":\"RESEARCH\","
				+ "\"goal\":" + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(goal)
				+ ",\"tools\":[\"read_file\"]}]}";
	}
	@Test void consoleStartUsesRealParentAndReadEvidenceWithMemoryOff() throws Exception {
		assertThat(this.handler.handle("/delegate start " + plan("read", "!tool read_file {\"path\":\"source.txt\"}"))).isTrue();
		var view = this.controller.show(this.session.id(), "read");
		var result = ((DefaultDelegationRuntime.Completed) view.outcome()).result();
		assertThat(result.status()).isEqualTo(DelegationResult.Status.COMPLETE);
		var parent = this.runs.read(new com.example.miniagent.core.run.RunId(view.parent().runId()));
		assertThat(parent.spec().parentConversationId()).isEqualTo(this.session.id());
		assertThat(parent.spec().childId()).isNull();
		assertThat(this.runs.read(result.children().getFirst().runId()).spec().parentRunId()).isEqualTo(parent.spec().runId());
		assertThat(this.handler.handle("/delegate show read")).isTrue();
	}
	@Test void narrativeCommandReturnsFailureRatherThanFabricatedComplete() {
		assertThat(this.handler.handle("/delegate start " + plan("narrative", "I verified everything"))).isFalse();
		assertThat(((DefaultDelegationRuntime.Completed) this.controller.show(this.session.id(), "narrative").outcome())
				.result().status()).isEqualTo(DelegationResult.Status.FAILED);
	}
	@Test void applyWhileBusyReportsSessionBusy() throws Exception {
		this.screen.setInteractive(true);
		var worker = Executors.newSingleThreadExecutor();
		try {
			// Durable question queue exists before starting the managed run.
			var future = worker.submit(() -> this.handler.handle("/run start !tool write_file {\"path\":\"busy.txt\",\"content\":\"x\"}"));
			var request = this.screen.questions.poll(10, TimeUnit.SECONDS);
			assertThat(request).isNotNull();
			assertThat(this.handler.handle("/delegate apply unknown child " + UUID.randomUUID())).isFalse();
			try {
				assertThat(this.screen.output.toString()).contains("SESSION_BUSY");
			}
			finally { assertThat(this.broker.respond(request, ApprovalDecision.REJECTED)).isTrue(); }
			future.get(15, TimeUnit.SECONDS);
		}
		finally {
			for (var request : this.broker.pending()) this.broker.cancel(request);
			worker.shutdownNow();
			assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
		assertThat(workspace.resolve("busy.txt")).doesNotExist();
	}
}
