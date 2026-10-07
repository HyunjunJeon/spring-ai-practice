package com.example.miniagent.plugin.claude;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import tools.jackson.databind.JsonNode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 제한된 SSE 응답을 완성해 돌려준다. 불완전한 도구 JSON 은 실행 루프에 넘기지 않는다. */
final class ClaudeMessagesClient {

	private final HttpClient http;

	private final URI endpoint;

	private final Duration timeout;

	private final int maxBytes;

	private final String cliVersion;

	private final JsonMapper json = JsonMapper.builder().build();

	ClaudeMessagesClient(ClaudeProperties properties) {
		this(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/v1/messages?beta=true"),
				properties.getTimeout(), properties.getMaxResponseBytes(), properties.getCliVersion());
	}

	ClaudeMessagesClient(URI endpoint, Duration timeout, int maxBytes, String cliVersion) {
		if (timeout.isZero() || timeout.isNegative() || maxBytes <= 0 || !cliVersion.matches("\\d+\\.\\d+\\.\\d+")) {
			throw new IllegalArgumentException("Invalid Claude transport configuration");
		}
		this.endpoint = endpoint;
		this.timeout = timeout;
		this.maxBytes = maxBytes;
		this.cliVersion = cliVersion;
		this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	JsonNode call(String accessToken, ObjectNode payload) {
		if (!accessToken.matches("sk-ant-oat[A-Za-z0-9._~+/=-]+")) {
			throw new IllegalArgumentException("Claude 구독 OAuth access token 형식이 올바르지 않습니다");
		}
		HttpRequest request = HttpRequest.newBuilder(this.endpoint).timeout(this.timeout)
			.header("Authorization", "Bearer " + accessToken)
			.header("Content-Type", "application/json").header("Accept", "application/json")
			.header("anthropic-version", "2023-06-01")
			.header("anthropic-beta", "claude-code-20250219,oauth-2025-04-20")
			.header("User-Agent", "claude-cli/" + this.cliVersion)
			.header("x-app", "cli").header("anthropic-dangerous-direct-browser-access", "true")
			.POST(HttpRequest.BodyPublishers.ofString(this.json.writeValueAsString(payload), StandardCharsets.UTF_8)).build();
		CompletableFuture<HttpResponse<byte[]>> result = this.http.sendAsync(request, info -> boundedBody());
		try {
			HttpResponse<byte[]> response = result.get(this.timeout.toNanos(), TimeUnit.NANOSECONDS);
			if (response.statusCode() != 200) {
				// 서버 본문은 토큰이나 요청 내용을 반사할 수 있어 예외에 포함하지 않는다.
				throw new IllegalStateException("Claude HTTP " + response.statusCode()
						+ (response.statusCode() == 401 ? " (Claude 구독 /login 을 다시 실행하세요)" : ""));
			}
			return parseSse(response.body());
		}
		catch (TimeoutException ex) {
			result.cancel(true);
			throw new UncheckedIOException(new HttpTimeoutException("Claude request timed out after " + this.timeout));
		}
		catch (InterruptedException ex) {
			result.cancel(true);
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Claude 호출이 중단되었습니다", ex);
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof IOException io) {
				throw new UncheckedIOException(io);
			}
			throw new IllegalStateException("Claude transport failed", ex.getCause());
		}
	}

	private HttpResponse.BodySubscriber<byte[]> boundedBody() {
		return new HttpResponse.BodySubscriber<>() {
			private final CompletableFuture<byte[]> body = new CompletableFuture<>();
			private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			private Flow.Subscription subscription;

			@Override
			public CompletionStage<byte[]> getBody() {
				return this.body;
			}

			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				this.subscription = subscription;
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(List<ByteBuffer> chunks) {
				for (ByteBuffer chunk : chunks) {
					if (chunk.remaining() > maxBytes - this.bytes.size()) {
						this.subscription.cancel();
						this.body.completeExceptionally(new IOException("Claude response exceeds max-response-bytes"));
						return;
					}
					byte[] next = new byte[chunk.remaining()];
					chunk.get(next);
					this.bytes.writeBytes(next);
				}
			}

			@Override
			public void onError(Throwable error) {
				this.body.completeExceptionally(error);
			}

			@Override
			public void onComplete() {
				this.body.complete(this.bytes.toByteArray());
			}
		};
	}

	JsonNode parseSse(byte[] bytes) {
		ObjectNode message = null;
		ArrayNode content = this.json.createArrayNode();
		var inputs = new HashMap<Integer, StringBuilder>();
		var closed = new HashSet<Integer>();
		boolean stopped = false;
		try (var reader = new BufferedReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
			StringBuilder data = new StringBuilder();
			for (String line; (line = reader.readLine()) != null;) {
				if (line.startsWith("data:")) {
					if (!data.isEmpty()) {
						data.append('\n');
					}
					data.append(line.substring(5).stripLeading());
				}
				else if (line.isEmpty() && !data.isEmpty()) {
					JsonNode event = parseJson(data.toString());
					data.setLength(0);
					String type = event.path("type").asString("");
					if ("ping".equals(type)) {
						continue;
					}
					if (stopped) {
						throw new IllegalStateException("Claude SSE event after message_stop");
					}
					if ("message_start".equals(type)) {
						if (message != null || !event.path("message").isObject()) {
							throw new IllegalStateException("Invalid Claude message_start");
						}
						message = (ObjectNode) event.path("message").deepCopy();
						message.set("content", content);
					}
					else if ("error".equals(type)) {
						throw new IllegalStateException("Claude SSE error");
					}
					else {
						if (message == null) {
							throw new IllegalStateException("Claude SSE missing message_start");
						}
						int index = event.path("index").asInt(-1);
						switch (type) {
							case "content_block_start" -> {
								if (index != content.size() || !event.path("content_block").isObject()) {
									throw new IllegalStateException("Invalid Claude block index");
								}
								content.add(event.path("content_block").deepCopy());
							}
							case "content_block_delta" -> {
								if (index < 0 || index >= content.size() || closed.contains(index)) {
									throw new IllegalStateException("Invalid Claude delta index");
								}
								ObjectNode block = (ObjectNode) content.get(index);
								JsonNode delta = event.path("delta");
								String deltaType = delta.path("type").asString("");
								String blockType = block.path("type").asString("");
								if (("text_delta".equals(deltaType) && !"text".equals(blockType))
										|| (("thinking_delta".equals(deltaType) || "signature_delta".equals(deltaType))
											&& !"thinking".equals(blockType))
										|| ("input_json_delta".equals(deltaType) && !"tool_use".equals(blockType))) {
									throw new IllegalStateException("Claude delta does not match its content block");
								}
								switch (deltaType) {
									case "text_delta" -> block.put("text",
											block.path("text").asString("") + delta.path("text").asString(""));
									case "thinking_delta" -> block.put("thinking",
											block.path("thinking").asString("") + delta.path("thinking").asString(""));
									case "signature_delta" -> block.put("signature",
											block.path("signature").asString("") + delta.path("signature").asString(""));
									case "input_json_delta" -> inputs.computeIfAbsent(index, unused -> new StringBuilder())
										.append(delta.path("partial_json").asString(""));
									default -> throw new IllegalStateException("Unsupported Claude delta type");
								}
							}
							case "content_block_stop" -> {
								if (index < 0 || index >= content.size() || !closed.add(index)) {
									throw new IllegalStateException("Invalid Claude block_stop");
								}
								StringBuilder input = inputs.remove(index);
								if (input != null) {
									((ObjectNode) content.get(index)).set("input", parseJson(input.toString()));
								}
							}
							case "message_delta" -> {
								JsonNode reason = event.path("delta").path("stop_reason");
								if (!reason.isMissingNode() && !reason.isNull()) {
									message.set("stop_reason", reason);
								}
								ObjectNode usage = message.path("usage").isObject()
										? (ObjectNode) message.path("usage") : message.putObject("usage");
								for (var field : event.path("usage").properties()) {
									if (!field.getValue().isNull()) {
										usage.set(field.getKey(), field.getValue());
									}
								}
							}
							case "message_stop" -> stopped = true;
							default -> throw new IllegalStateException("Unsupported Claude SSE event");
						}
					}
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		if (message == null || !stopped || closed.size() != content.size() || !inputs.isEmpty()
				|| message.path("stop_reason").isNull() || message.path("stop_reason").isMissingNode()) {
			throw new IllegalStateException("Claude SSE response is incomplete");
		}
		return message;
	}

	private JsonNode parseJson(String data) {
		try {
			return this.json.readTree(data);
		}
		catch (JacksonException ex) {
			// 파서 예외도 원문 일부를 포함하므로 서버 응답을 밖으로 반사하지 않는다.
			throw new IllegalStateException("Claude SSE JSON is invalid");
		}
	}

}
