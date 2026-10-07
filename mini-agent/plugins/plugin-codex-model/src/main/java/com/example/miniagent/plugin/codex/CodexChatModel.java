package com.example.miniagent.plugin.codex;

import java.util.Map;
import java.util.Optional;

import com.example.miniagent.core.ToolContextKeys;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * ChatGPT 구독(Codex OAuth)으로 동작하는 {@link ChatModel}.
 *
 * <p>Spring AI 2.0.1 GA 의 OpenAI 모듈은 Chat Completions API 만 지원한다(Responses API 는 2.1.0-M1).
 * ChatGPT 백엔드는 Responses API 만 받으므로 ChatModel 을 직접 구현한다. 구조는 가짜 모델과 같다:
 * Prompt → 요청 JSON → HTTP → 응답 → ChatResponse. 도구 루프는 여전히 ToolCallingAdvisor 가 돈다.
 * 이 모델은 "도구를 호출하겠다"는 응답(tool call)만 돌려주고, 실행은 하지 않는다.
 */
public class CodexChatModel implements ChatModel {

	private final CodexAuth auth;

	private final CodexResponsesClient client;

	private final JsonMapper json;

	private final String model;

	private final String reasoningEffort;

	public CodexChatModel(CodexAuth auth, CodexResponsesClient client, JsonMapper json, String model,
			String reasoningEffort) {
		this.auth = auth;
		this.client = client;
		this.json = json;
		this.model = model;
		this.reasoningEffort = reasoningEffort;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		String requestModel = (prompt.getOptions() != null && prompt.getOptions().getModel() != null)
				? prompt.getOptions().getModel() : this.model;
		String sessionId = sessionId(prompt);
		ObjectNode body = CodexResponses.toRequest(prompt, requestModel, this.reasoningEffort, sessionId, this.json);

		CodexCredentials credentials = this.auth.current();
		CodexResponses.Result result;
		try {
			result = this.client.send(body, credentials, sessionId);
		}
		catch (CodexResponsesClient.CodexHttpException ex) {
			Optional<CodexCredentials> recovered = (ex.status() == 401)
					? this.auth.recoverFromUnauthorized(credentials) : Optional.empty();
			if (recovered.isEmpty()) {
				throw ex;
			}
			result = this.client.send(body, recovered.get(), sessionId);
		}
		return CodexResponses.toChatResponse(result, requestModel, this.reasoningEffort, this.json);
	}

	/** 이 예제는 {@code .call()} 만 쓴다. 스트리밍은 learning-path 의 과제로 남긴다. */
	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.defer(() -> Flux.just(call(prompt)));
	}

	/** 가짜 모델과 같은 이유로 ToolCallingChatOptions 를 돌려준다. 그래야 도구가 붙고 루프가 돈다. */
	@Override
	public ChatOptions getOptions() {
		return ToolCallingChatOptions.builder().model(this.model).build();
	}

	public String model() {
		return this.model;
	}

	public String reasoningEffort() {
		return this.reasoningEffort;
	}

	/**
	 * 세션 ID 는 코어가 매 요청 ToolContext 에 넣어 준다. ToolContext 는 ChatOptions 안에 실려 오므로
	 * 모델 구현에서도 읽을 수 있다. 같은 세션이면 같은 prompt_cache_key 를 써서 프롬프트 캐시를 노린다.
	 */
	private static @Nullable String sessionId(Prompt prompt) {
		if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
			Map<String, Object> context = options.getToolContext();
			Object value = (context != null) ? context.get(ToolContextKeys.SESSION_ID) : null;
			return (value != null) ? "mini-agent-" + value : null;
		}
		return null;
	}

}
