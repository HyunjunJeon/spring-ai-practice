package com.example.miniagent.app;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.example.miniagent.core.*;
import com.example.miniagent.core.approval.*;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.delegation.*;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.run.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Both real official HTTP providers, shared runtime, sandbox verification, console apply and undo. */
class AgentEvolutionIntegrationTest {
	@TempDir Path directory;
	private static final JsonMapper JSON = JsonMapper.builder().build();
	record Request(String path, String auth, JsonNode body) {}
	private static String plan() {
		var read = Map.of("childId", "research", "goal", "fixture-research", "role", "RESEARCH",
				"model", "zai/glm-5.3", "tools", List.of("read_file"), "wallSeconds", 60, "toolCallCap", 5);
		var implement = Map.of("childId", "implement", "goal", "fixture-implement", "role", "IMPLEMENT",
				"model", "openai/gpt-5-mini", "tools", List.of("write_file"), "dependsOn", List.of("research"),
				"wallSeconds", 60, "toolCallCap", 5);
		var verify = Map.of("childId", "verify", "goal", "fixture-verify", "role", "VERIFY",
				"model", "zai/glm-5.3", "tools", List.of("verify_staged"), "dependsOn", List.of("implement"),
				"wallSeconds", 60, "toolCallCap", 5);
		return JSON.writeValueAsString(Map.of("planId", "workflow", "children", List.of(read, implement, verify)));
	}
	@ParameterizedTest @ValueSource(strings = {"inside", "outside", "off"})
	void twoLocalProvidersRunManagedMixedWorkflowThroughConsoleApplyVerifyAndUndo(String memory) throws Exception {
		Path home = Files.createDirectory(this.directory.resolve("state"));
		Path workspace = Files.createDirectory(this.directory.resolve("work"));
		Files.writeString(workspace.resolve("source.txt"), "before");
		List<Request> requests = Collections.synchronizedList(new ArrayList<>());
		var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		http.createContext("/", exchange -> {
			var input = JSON.readTree(exchange.getRequestBody().readAllBytes());
			requests.add(new Request(exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("Authorization"), input));
			var messages = input.get("messages");
			String goal = "";
			for (var message : messages) if (message.path("role").asString().equals("user"))
				goal = message.path("content").asString();
			boolean answeredTool = messages.get(messages.size() - 1).path("role").asString().equals("tool");
			Map<String, Object> answer;
			String finish;
			if (answeredTool) {
				answer = Map.of("role", "assistant", "content", "actual local tool completed");
				finish = "stop";
			}
			else {
				String name;
				String arguments;
				switch (goal) {
					case "fixture-research" -> { name = "read_file"; arguments = "{\"path\":\"source.txt\"}"; }
					case "fixture-implement" -> { name = "write_file"; arguments = "{\"path\":\"source.txt\",\"content\":\"after\"}"; }
					case "fixture-verify" -> {
						name = "verify_staged";
						arguments = "{\"childId\":\"implement\",\"argv\":[\"/usr/bin/grep\",\"-q\",\"after\",\"source.txt\"],"
								+ "\"timeoutMillis\":5000,\"maxOutputBytes\":4096}";
					}
					default -> { name = "delegate"; arguments = plan(); }
				}
				answer = Map.of("role", "assistant", "content", "", "tool_calls", List.of(
						Map.of("id", "call-" + name, "type", "function",
								"function", Map.of("name", name, "arguments", arguments))));
				finish = "tool_calls";
			}
			byte[] output = JSON.writeValueAsBytes(Map.of("id", "local-http", "object", "chat.completion",
					"created", 1700000000, "model", input.path("model").asString(),
					"choices", List.of(Map.of("index", 0, "message", answer, "finish_reason", finish)),
					"usage", Map.of("prompt_tokens", 2, "completion_tokens", 1, "total_tokens", 3)));
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, output.length);
			try (var stream = exchange.getResponseBody()) { stream.write(output); }
		});
		http.start();
		String base = "http://127.0.0.1:" + http.getAddress().getPort();
		try (var context = ProviderProfileMigrationTest.builder().sources(ConsoleFixture.class).run(
				ProviderProfileMigrationTest.arguments(home, "openai",
						"--agent.cwd=" + workspace, "--agent.ui=plain",
						"--agent.models.providers.openai.api-key=synthetic-openai",
						"--agent.models.providers.zai.api-key=synthetic-zai",
						"--agent.models.providers.openai.base-url=" + base + "/v1",
						"--agent.models.providers.zai.base-url=" + base + "/api/coding/paas/v4",
						"--agent.plugins.memory.enabled=" + !memory.equals("off"),
						"--agent.plugins.memory.inside-loop=" + memory.equals("inside"),
						"--agent.plugins.memory.compaction=false",
						"--agent.plugins.permission.auto-allow-tools=delegate,read_file,write_file,verify_staged"))) {
			var agent = context.getBean(Agent.class);
			var session = context.getBean(AgentSession.class);
			var executor = context.getBean(RunExecutor.class);
			var result = executor.start("fixture-parent", session.id(), agent.selectedModel(), workspace, RunBudget.defaults());
			assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
			var controller = context.getBean(DelegationController.class);
			var view = controller.show(session.id(), "workflow");
			var children = ((DefaultDelegationRuntime.Completed) view.outcome()).result();
			assertThat(children.status()).isEqualTo(DelegationResult.Status.COMPLETE);
			assertThat(children.children()).hasSize(3);
			assertThat(children.children().getFirst().model().provider()).isEqualTo("zai");
			assertThat(children.children().get(1).model().provider()).isEqualTo("openai");
			assertThat(children.children().get(2).model().provider()).isEqualTo("zai");
			assertThat(Files.readString(workspace.resolve("source.txt"))).isEqualTo("before");
			var changes = context.getBean(ChangeStore.class);
			var proposal = changes.read().changes().stream().map(ChangeStore.Snapshot::changeSet)
					.filter(set -> set.owner().runId().equals(children.children().get(1).runId())).findFirst().orElseThrow();
			var verification = changes.read().changes().stream()
					.filter(set -> set.changeSet().owner().runId().equals(children.children().get(2).runId()))
					.findFirst().orElseThrow();
			assertThat(verification.evidence()).hasSize(1);
			assertThat(verification.evidence().getFirst().exitCode()).isZero();
			var screen = context.getBean(ChangeCommandsE2ETest.Screen.class);
			screen.setInteractive(true);
			screen.lines.add("exclusive " + proposal.changeSetId() + " " + proposal.digest());
			var handler = context.getBean(AgentInputHandler.class);
			var broker = context.getBean(ApprovalBroker.class);
			var worker = Executors.newSingleThreadExecutor();
			try {
				var apply = worker.submit(() -> handler.handle("/delegate apply workflow implement " + proposal.changeSetId()));
				var request = screen.questions.poll(10, TimeUnit.SECONDS);
				assertThat(request).isNotNull();
				try { assertThat(broker.respond(request, ApprovalDecision.APPROVED)).isTrue(); }
				finally { if (broker.pending().contains(request)) broker.cancel(request); }
				assertThat(apply.get(15, TimeUnit.SECONDS)).isTrue();
			}
			finally {
				for (var request : broker.pending()) broker.cancel(request);
				worker.shutdownNow();
				assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
			}
			assertThat(Files.readString(workspace.resolve("source.txt"))).isEqualTo("after");
			assertThat(handler.handle("/verify " + proposal.changeSetId() + " [\"/usr/bin/grep\",\"-q\",\"after\",\"source.txt\"]")).isTrue();
			screen.lines.add("exclusive " + proposal.changeSetId() + " " + proposal.digest());
			assertThat(handler.handle("/undo " + proposal.changeSetId())).isTrue();
			assertThat(Files.readString(workspace.resolve("source.txt"))).isEqualTo("before");
			assertThat(requests).anySatisfy(r -> {
				assertThat(r.path()).isEqualTo("/v1/chat/completions");
				assertThat(r.auth()).isEqualTo("Bearer synthetic-openai");
			}).anySatisfy(r -> {
				assertThat(r.path()).isEqualTo("/api/coding/paas/v4/chat/completions");
				assertThat(r.auth()).isEqualTo("Bearer synthetic-zai");
			});
			assertThat(requests).allSatisfy(r -> assertThat(r.body().path("tools").isArray()).isTrue());
		}
		finally { http.stop(0); }
	}
	@TestConfiguration static class ConsoleFixture {
		@Bean ChangeCommandsE2ETest.Screen agentConsole() { return new ChangeCommandsE2ETest.Screen(); }
	}
}
