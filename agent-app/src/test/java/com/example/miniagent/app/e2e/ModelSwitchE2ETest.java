package com.example.miniagent.app.e2e;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static com.example.miniagent.app.e2e.AppProcess.JSON;
import static com.example.miniagent.app.e2e.AppProcess.is;
import static org.assertj.core.api.Assertions.assertThat;

/** S18: switching between the fake model and a local OpenAI-compatible fixture, kept per session across a restart. */
class ModelSwitchE2ETest {
	private static final String REPLY = "OPENAI_FIXTURE_REPLY";
	private static final String KEY = "synthetic-openai";

	@TempDir Path directory;
	private HttpServer server;
	private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

	private record Request(String path, String authorization, boolean stream) {}

	@BeforeEach void serve() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", this::answer);
		this.server.start();
	}

	@AfterEach void stop() throws Exception {
		try { AppProcess.killAll(); }
		finally { this.server.stop(0); }
	}

	@Test void openAiSelectionIsUsedRefusesAnUnauthenticatedSwitchAndIsRestoredByResume() throws Exception {
		var layout = Layout.under(this.directory);
		var openai = List.of("--agent.models.providers.openai.api-key=" + KEY,
				"--agent.models.providers.openai.base-url=http://127.0.0.1:" + this.server.getAddress().getPort() + "/v1");

		var first = AppProcess.rpc(layout, openai);
		assertThat(outputs(first, "/models")).anySatisfy(line -> assertThat(line).contains("fake/fake-scripted [available]"))
				.anySatisfy(line -> assertThat(line).contains("openai/gpt-5-mini [available]"))
				.anySatisfy(line -> assertThat(line).contains("zai/glm-5.3 [AUTH_MISSING]"));
		assertThat(outputs(first, "/model openai/gpt-5-mini")).singleElement().asString().contains("openai/gpt-5-mini");
		String hi = first.prompt("hi");
		var answered = first.completion(hi);
		assertThat(is(answered, "status", "completed")).as(first.stderr()).isTrue();
		assertThat(bound(first, hi)).isEqualTo("openai/gpt-5-mini");
		assertThat(first.streamed(hi)).isEqualTo(REPLY);
		assertThat(this.requests).isNotEmpty().allSatisfy(request -> {
			assertThat(request.path()).isEqualTo("/v1/chat/completions");
			assertThat(request.authorization()).isEqualTo("Bearer " + KEY);
			assertThat(request.stream()).isTrue();
		});
		String session = answered.path("sessionAfter").asString();

		String zai = first.command("/model zai/glm-5.3");
		assertThat(is(first.completion(zai), "status", "failed")).isTrue();
		assertThat(first.frames(zai)).anySatisfy(frame -> assertThat(is(frame, "code", "execution_failed")).isTrue());
		assertThat(outputs(first, "/model show")).singleElement().asString().contains("openai/gpt-5-mini");
		first.shutdown();
		assertThat(first.awaitExit()).as("the refused switch is a failed completion").isEqualTo(1);

		int served = this.requests.size();
		var second = AppProcess.rpc(layout, openai);
		assertThat(outputs(second, "/model show")).as("a new session starts on the default")
				.singleElement().asString().contains("fake/fake-scripted");
		outputs(second, "/resume " + session);
		assertThat(outputs(second, "/model show")).singleElement().asString().contains("openai/gpt-5-mini");
		outputs(second, "/model fake/fake-scripted");
		String again = second.prompt("hi");
		assertThat(is(second.completion(again), "status", "completed")).isTrue();
		assertThat(bound(second, again)).isEqualTo("fake/fake-scripted");
		assertThat(second.streamed(again)).contains("[fake] 모델이 받은 요청");
		second.shutdown();
		assertThat(second.awaitExit()).isZero();
		assertThat(this.requests).as("the fake turn never reached the OpenAI fixture").hasSize(served);
	}

	private static String bound(AppProcess app, String requestId) {
		var bound = app.frames(requestId).stream().filter(frame -> is(frame, "event", "ModelBound")).findFirst().orElseThrow();
		return bound.path("provider").asString() + "/" + bound.path("model").asString();
	}

	private static List<String> outputs(AppProcess app, String command) {
		String id = app.command(command);
		assertThat(is(app.completion(id), "status", "completed")).as(command).isTrue();
		return app.outputs(id);
	}

	/** Chat completions in the OpenAI wire format: SSE chunks for a streaming request, one JSON body otherwise. */
	private void answer(HttpExchange exchange) throws IOException {
		JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
		boolean stream = body.path("stream").asBoolean(false);
		this.requests.add(new Request(exchange.getRequestURI().getPath(),
				exchange.getRequestHeaders().getFirst("Authorization"), stream));
		String model = body.path("model").asString();
		ObjectNode usage = JSON.createObjectNode().put("prompt_tokens", 2).put("completion_tokens", 1).put("total_tokens", 3);
		byte[] output;
		if (stream) {
			ObjectNode content = completion(model, "chat.completion.chunk", "delta", message(), null);
			ObjectNode finish = completion(model, "chat.completion.chunk", "delta", JSON.createObjectNode(), "stop");
			finish.set("usage", usage);
			output = ("data: " + JSON.writeValueAsString(content) + "\n\ndata: " + JSON.writeValueAsString(finish)
					+ "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
		}
		else {
			ObjectNode completion = completion(model, "chat.completion", "message", message(), "stop");
			completion.set("usage", usage);
			output = JSON.writeValueAsBytes(completion);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
		}
		exchange.sendResponseHeaders(200, output.length);
		try (var response = exchange.getResponseBody()) { response.write(output); }
	}

	private static ObjectNode message() {
		return JSON.createObjectNode().put("role", "assistant").put("content", REPLY);
	}

	private static ObjectNode completion(String model, String object, String field, ObjectNode payload, String finishReason) {
		ObjectNode completion = JSON.createObjectNode().put("id", "chatcmpl-e2e").put("object", object)
				.put("created", 1700000000).put("model", model);
		ObjectNode choice = completion.putArray("choices").addObject().put("index", 0);
		choice.set(field, payload);
		if (finishReason == null) choice.putNull("finish_reason");
		else choice.put("finish_reason", finishReason);
		return completion;
	}
}
