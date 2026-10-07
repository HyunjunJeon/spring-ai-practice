package com.example.miniagent.app;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiZaiIsolationTest {

	@TempDir
	Path home;

	record Request(String path, String auth, JsonNode body) {
	}

	@Test
	void actualOfficialClientsKeepPathsKeysThinkingAndTurnOptionsSeparate() throws Exception {
		JsonMapper json = JsonMapper.builder().build();
		List<Request> requests = Collections.synchronizedList(new ArrayList<>());
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
			requests.add(new Request(exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("Authorization"), body));
			byte[] response = """
					{"id":"chat-local","object":"chat.completion","created":1700000000,"model":"local",
					 "choices":[{"index":0,"message":{"role":"assistant","content":"local-ok"},"finish_reason":"stop"}],
					 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
					""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			try (var output = exchange.getResponseBody()) {
				output.write(response);
			}
		});
		server.start();
		String base = "http://127.0.0.1:" + server.getAddress().getPort();
		try (var context = ProviderProfileMigrationTest.start(this.home, "openai",
				"--agent.models.providers.openai.api-key=openai-synthetic",
				"--agent.models.providers.zai.api-key=zai-synthetic",
				"--agent.models.providers.openai.base-url=" + base + "/v1",
				"--agent.models.providers.zai.base-url=" + base + "/api/coding/paas/v4")) {
			Agent agent = context.getBean(Agent.class);
			assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
			assertThat(context.getBean(ChatModel.class)).isInstanceOf(OpenAiChatModel.class);
			ModelSelection openai = new ModelSelection(new ModelRef("openai", "gpt-5-mini"),
					Map.of("reasoningEffort", "high", "maxTokens", "31"));
			ModelSelection zai = new ModelSelection(new ModelRef("zai", "glm-5.3"),
					Map.of("reasoningEffort", "low", "maxTokens", "73"));
			var first = agent.modelRegistry().bind(openai);
			var second = agent.modelRegistry().bind(zai);
			var third = agent.modelRegistry().bind(openai);
			assertThat(first.bindingId()).isNotEqualTo(second.bindingId()).isNotEqualTo(third.bindingId());
			assertThat(first.client()).isNotSameAs(third.client()).isNotSameAs(second.client());
			assertThat(first.chatModel()).isNotSameAs(third.chatModel());
			assertThat(first.chatModel().getOptions()).isNotSameAs(third.chatModel().getOptions());

			agent.selectModel(openai);
			assertThat(agent.run("first").text()).isEqualTo("local-ok");
			agent.selectModel(zai);
			assertThat(agent.run("second").text()).isEqualTo("local-ok");
			agent.selectModel(openai);
			assertThat(agent.run("third").text()).isEqualTo("local-ok");

			assertThat(requests).hasSize(3);
			for (int i : List.of(0, 2)) {
				Request request = requests.get(i);
				assertThat(request.path()).isEqualTo("/v1/chat/completions");
				assertThat(request.auth()).isEqualTo("Bearer openai-synthetic");
				assertThat(request.body().path("model").asString()).isEqualTo("gpt-5-mini");
				assertThat(request.body().path("reasoning_effort").asString()).isEqualTo("high");
				assertThat(request.body().path("max_tokens").asInt()).isEqualTo(31);
				assertThat(request.body().has("thinking")).isFalse();
			}
			Request request = requests.get(1);
			assertThat(request.path()).isEqualTo("/api/coding/paas/v4/chat/completions");
			assertThat(request.auth()).isEqualTo("Bearer zai-synthetic");
			assertThat(request.body().path("model").asString()).isEqualTo("glm-5.3");
			assertThat(request.body().path("reasoning_effort").asString()).isEqualTo("low");
			assertThat(request.body().path("max_tokens").asInt()).isEqualTo(73);
			assertThat(request.body().path("thinking").path("type").asString()).isEqualTo("enabled");
		}
		finally {
			server.stop(0);
		}
	}
}
