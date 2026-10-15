package com.example.miniagent.app;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 공식 OpenAiChatModel 과 Agent/파일 도구/JSONL 메모리로 Z.ai 호환 왕복을 검증한다. */
@ActiveProfiles("zai")
class ZaiProviderTest {

	@TempDir
	static Path workDir;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final List<Captured> REQUESTS = Collections.synchronizedList(new ArrayList<>());

	private static final ExecutorService WORKERS = Executors.newCachedThreadPool();

	private static HttpServer server;

	record Captured(String path, String authorization, JsonNode body) {
	}

	static void configuration(DynamicPropertyRegistry registry, String path) {
		if (server == null) {
			try {
				server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
				server.createContext("/", ZaiProviderTest::respond);
				server.setExecutor(WORKERS);
				server.start();
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		}
		registry.add("ZAI_BASE_URL", () -> "http://127.0.0.1:" + server.getAddress().getPort() + path);
		registry.add("ZAI_API_KEY", () -> "zai-test-key");
		registry.add("OPENAI_API_KEY", () -> "must-not-be-used");
		registry.add("ZAI_MODEL", () -> "glm-5.3");
		registry.add("ZAI_REASONING_EFFORT", () -> "low");
		registry.add("agent.cwd", () -> workDir.toString());
		registry.add("agent.home", () -> workDir.resolve(".home").toString());
	}

	@BeforeEach
	void prepare() throws IOException {
		REQUESTS.clear();
		Files.writeString(workDir.resolve("zai.txt"), "zai-file-sentinel");
	}

	@AfterAll
	static void stop() {
		if (server != null) {
			server.stop(0);
		}
		WORKERS.shutdownNow();
	}

	static void assertRoundtrip(Agent agent, AgentSession session, ChatModel model, ApplicationContext context,
			String path) {
		session.startNew();

		var reply = agent.run("Read zai.txt using read_file");

		assertThat(model).isInstanceOf(OpenAiChatModel.class);
		assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
		assertThat(model.getOptions().getModel()).isEqualTo("glm-5.3");
		assertThat(reply.text()).contains("zai-file-sentinel");
		assertThat(REQUESTS).hasSize(2);
		for (Captured request : REQUESTS) {
			assertThat(request.path()).isEqualTo(path + "/chat/completions");
			assertThat(request.authorization()).isEqualTo("Bearer zai-test-key");
			assertThat(request.body().path("model").asString()).isEqualTo("glm-5.3");
			assertThat(request.body().path("thinking").path("type").asString()).isEqualTo("enabled");
			assertThat(request.body().path("reasoning_effort").asString()).isEqualTo("low");
		}
		JsonNode continuation = REQUESTS.getLast().body();
		boolean thoughtReplayed = false;
		boolean fileReplayed = false;
		for (JsonNode message : continuation.path("messages")) {
			if ("assistant".equals(message.path("role").asString()) && message.path("tool_calls").isArray()) {
				thoughtReplayed = "reasoning-sentinel".equals(message.path("reasoning_content").asString());
			}
			if ("tool".equals(message.path("role").asString())) {
				fileReplayed = message.path("content").asString().contains("zai-file-sentinel");
			}
		}
		assertThat(thoughtReplayed).isTrue();
		assertThat(fileReplayed).isTrue();
	}

	private static void respond(HttpExchange exchange) throws IOException {
		JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
		REQUESTS.add(new Captured(exchange.getRequestURI().getPath(),
				exchange.getRequestHeaders().getFirst("Authorization"), request));
		String result = null;
		for (JsonNode message : request.path("messages")) {
			if ("tool".equals(message.path("role").asString())) {
				result = message.path("content").asString();
			}
		}
		ObjectNode response = JSON.createObjectNode();
		response.put("id", "chatcmpl-zai-test").put("object", "chat.completion").put("created", 1700000000)
			.put("model", "glm-5.3");
		ObjectNode choice = response.putArray("choices").addObject().put("index", 0);
		ObjectNode message = choice.putObject("message").put("role", "assistant");
		message.put("reasoning_content", result == null ? "reasoning-sentinel" : "final-reasoning");
		if (result == null) {
			message.putNull("content");
			ObjectNode call = message.putArray("tool_calls").addObject().put("id", "call_zai").put("type", "function");
			call.putObject("function").put("name", "read_file").put("arguments", "{\"path\":\"zai.txt\"}");
			choice.put("finish_reason", "tool_calls");
		}
		else {
			message.put("content", result);
			choice.put("finish_reason", "stop");
		}
		response.putObject("usage").put("prompt_tokens", 10).put("completion_tokens", 5).put("total_tokens", 15);
		byte[] bytes = JSON.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		try (var output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	@Nested
	@SpringBootTest(properties = { "agent.repl.enabled=false", "agent.plugins.trace.level=off" })
	@Import(AgentTestSupport.class)
	class StandardApi {

		@DynamicPropertySource
		static void paths(DynamicPropertyRegistry registry) {
			configuration(registry, "/api/paas/v4");
		}

		@Autowired Agent agent;
		@Autowired AgentSession session;
		@Autowired ChatModel model;
		@Autowired ApplicationContext context;

		@Test
		void officialModelPreservesReasoningAcrossToolRoundtrip() {
			assertRoundtrip(this.agent, this.session, this.model, this.context, "/api/paas/v4");
		}
	}

	@Nested
	@SpringBootTest(properties = { "agent.repl.enabled=false", "agent.plugins.trace.level=off",
			"agent.plugins.memory.inside-loop=true" })
	@Import(AgentTestSupport.class)
	class MemoryInsideLoop {

		@DynamicPropertySource
		static void paths(DynamicPropertyRegistry registry) {
			configuration(registry, "/api/paas/v4");
		}

		@Autowired Agent agent;
		@Autowired AgentSession session;
		@Autowired ChatModel model;
		@Autowired ApplicationContext context;

		@Test
		void persistedReasoningSurvivesMemoryInsideToolLoop() {
			assertRoundtrip(this.agent, this.session, this.model, this.context, "/api/paas/v4");
		}
	}

	@Nested
	@SpringBootTest(properties = { "agent.repl.enabled=false", "agent.plugins.trace.level=off",
			"agent.plugins.memory.enabled=false" })
	@Import(AgentTestSupport.class)
	class CodingApiWithoutMemory {

		@DynamicPropertySource
		static void paths(DynamicPropertyRegistry registry) {
			configuration(registry, "/api/coding/paas/v4");
		}

		@Autowired Agent agent;
		@Autowired AgentSession session;
		@Autowired ChatModel model;
		@Autowired ApplicationContext context;

		@Test
		void codingEndpointAndMemoryDisabledUseTheSameOfficialAdapter() {
			assertRoundtrip(this.agent, this.session, this.model, this.context, "/api/coding/paas/v4");
		}
	}
}
