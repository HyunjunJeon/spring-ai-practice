package com.example.miniagent.app;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.example.miniagent.app.model.AnthropicApiModelProvider;
import com.example.miniagent.core.Agent;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.ModelUnavailableException;
import com.example.miniagent.core.model.registry.ModelRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 Anthropic 공식 클라이언트가 로컬 서버로 보내는 Messages 요청을 검증한다. 키는 합성 값이다. */
class AnthropicProviderWireTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ModelRef REF = new ModelRef("anthropic", "claude-sonnet-5-5");

	@TempDir
	Path home;

	private final List<Captured> requests = Collections.synchronizedList(new ArrayList<>());

	private HttpServer server;

	record Captured(String method, String path, String apiKey, String authorization, JsonNode body) {
	}

	@BeforeEach
	void startServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", exchange -> {
			this.requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("x-api-key"),
					exchange.getRequestHeaders().getFirst("Authorization"),
					JSON.readTree(exchange.getRequestBody().readAllBytes())));
			byte[] response = """
					{"id":"msg_local","type":"message","role":"assistant","model":"claude-sonnet-5-5",
					 "content":[{"type":"text","text":"anthropic-ok"}],"stop_reason":"end_turn","stop_sequence":null,
					 "usage":{"input_tokens":3,"output_tokens":2}}
					""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			try (var output = exchange.getResponseBody()) {
				output.write(response);
			}
		});
		this.server.start();
	}

	@AfterEach
	void stopServer() {
		this.server.stop(0);
	}

	private String baseUrl() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	private MockEnvironment environment(String apiKey) {
		return new MockEnvironment().withProperty("agent.models.providers.anthropic.api-key", apiKey)
			.withProperty("agent.models.providers.anthropic.base-url", baseUrl());
	}

	private Captured call(AnthropicApiModelProvider provider, Map<String, String> options) {
		ChatModel model = provider.create(REF, options);
		assertThat(model.call(new Prompt("hello anthropic")).getResult().getOutput().getText())
			.isEqualTo("anthropic-ok");
		assertThat(this.requests).hasSize(1);
		return this.requests.getFirst();
	}

	@Test
	void requestCarriesTheApiKeyModelAndDefaultMaxTokensOnly() {
		Captured request = call(new AnthropicApiModelProvider(environment("sk-ant-synthetic")), Map.of());

		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/v1/messages");
		assertThat(request.apiKey()).isEqualTo("sk-ant-synthetic");
		assertThat(request.authorization()).isNull();
		assertThat(request.body().path("model").asString()).isEqualTo("claude-sonnet-5-5");
		assertThat(request.body().path("max_tokens").asInt()).isEqualTo(4096);
		assertThat(request.body().path("messages").get(0).path("role").asString()).isEqualTo("user");
		assertThat(request.body().toString()).contains("hello anthropic");
		assertThat(request.body().has("temperature")).isFalse();
		assertThat(request.body().has("top_p")).isFalse();
		assertThat(request.body().has("top_k")).isFalse();
	}

	@Test
	void selectionOptionsMapToTheirWireFields() {
		Captured request = call(new AnthropicApiModelProvider(environment("sk-ant-synthetic")),
				Map.of("maxTokens", "512", "temperature", "0.25", "topP", "0.8", "topK", "40"));

		assertThat(request.body().path("max_tokens").asInt()).isEqualTo(512);
		assertThat(request.body().path("temperature").asDouble()).isEqualTo(0.25);
		assertThat(request.body().path("top_p").asDouble()).isEqualTo(0.8);
		assertThat(request.body().path("top_k").asInt()).isEqualTo(40);
	}

	@Test
	void configuredMaxTokensIsTheDefaultAndASelectionOverridesIt() {
		MockEnvironment environment = environment("sk-ant-synthetic")
			.withProperty("agent.models.providers.anthropic.max-tokens", "2048");

		assertThat(call(new AnthropicApiModelProvider(environment), Map.of()).body().path("max_tokens").asInt())
			.isEqualTo(2048);
		this.requests.clear();
		assertThat(call(new AnthropicApiModelProvider(environment), Map.of("maxTokens", "64")).body()
			.path("max_tokens").asInt()).isEqualTo(64);
	}

	@Test
	void unsupportedGenerationOptionsAreRefusedBeforeAnyRequest() {
		ModelRegistry registry = new ModelRegistry(List.of(new AnthropicApiModelProvider(environment("sk-ant-synthetic"))),
				ChatClient::create);

		for (String option : List.of("effort", "reasoningEffort", "seed")) {
			assertThatThrownBy(() -> registry.bind(new ModelSelection(REF, Map.of(option, "high"))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("does not support selected generation options");
		}
		assertThat(this.requests).isEmpty();
	}

	@Test
	void blankApiKeyIsAuthMissingAndNeverReachesTheServer() {
		ModelRegistry registry = new ModelRegistry(List.of(new AnthropicApiModelProvider(environment(""))),
				ChatClient::create);

		assertThat(registry.models()).singleElement()
			.satisfies(entry -> assertThat(entry.unavailableCode()).isEqualTo(ModelUnavailableException.Code.AUTH_MISSING));
		assertThatThrownBy(() -> registry.bind(new ModelSelection(REF)))
			.isInstanceOfSatisfying(ModelUnavailableException.class,
					ex -> assertThat(ex.code()).isEqualTo(ModelUnavailableException.Code.AUTH_MISSING));
		assertThat(this.requests).isEmpty();
	}

	@Test
	void anthropicProfileTurnReachesTheServerWithTheProfileModelAndKey() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "anthropic",
				"--agent.models.providers.anthropic.api-key=sk-ant-profile",
				"--agent.models.providers.anthropic.base-url=" + baseUrl())) {
			Agent agent = context.getBean(Agent.class);

			assertThat(agent.selectedModel().ref()).isEqualTo(REF);
			assertThat(agent.run("hello profile").text()).isEqualTo("anthropic-ok");
		}
		assertThat(this.requests).isNotEmpty().allSatisfy(request -> {
			assertThat(request.path()).isEqualTo("/v1/messages");
			assertThat(request.apiKey()).isEqualTo("sk-ant-profile");
			assertThat(request.body().path("model").asString()).isEqualTo("claude-sonnet-5-5");
			assertThat(request.body().path("max_tokens").asInt()).isEqualTo(4096);
		});
	}
}
