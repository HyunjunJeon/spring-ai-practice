package com.example.miniagent.plugin.claude;

import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** Java OAuth 와 Anthropic Messages 직접 호출. 도구 실행은 Spring AI 가 소유한다. */
public class ClaudeChatModel implements ChatModel {

	private final String model;

	private final int maxTokens;

	private final ClaudeAuth auth;

	private final ClaudeMessagesClient client;

	private final JsonMapper json = JsonMapper.builder().build();

	ClaudeChatModel(ClaudeProperties properties, ClaudeAuth auth, ClaudeMessagesClient client) {
		this.model = properties.getModel();
		this.maxTokens = properties.getMaxTokens();
		this.auth = auth;
		this.client = client;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		String model = prompt.getOptions() != null && prompt.getOptions().getModel() != null
				? prompt.getOptions().getModel() : this.model;
		int maxTokens = prompt.getOptions() != null && prompt.getOptions().getMaxTokens() != null
				? prompt.getOptions().getMaxTokens() : this.maxTokens;
		return ClaudeMessages.toResponse(this.client.call(this.auth.accessToken(),
				ClaudeMessages.toRequest(prompt, model, maxTokens, this.json)), prompt, this.json);
	}

	/** 요청 변환은 호출 스레드에서 끝내고, 구독마다 독립적인 HTTP 교환을 시작한다. */
	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		String model = prompt.getOptions() != null && prompt.getOptions().getModel() != null
				? prompt.getOptions().getModel() : this.model;
		int maxTokens = prompt.getOptions() != null && prompt.getOptions().getMaxTokens() != null
				? prompt.getOptions().getMaxTokens() : this.maxTokens;
		var payload = ClaudeMessages.toRequest(prompt, model, maxTokens, this.json);
		return Flux.defer(() -> this.client.stream(this.auth.accessToken(), payload))
			.map(event -> "text_delta".equals(event.path("type").asString(""))
					? new ChatResponse(java.util.List.of(new Generation(new AssistantMessage(event.path("text").asString()))))
					: ClaudeMessages.toResponse(event, prompt, this.json, false));
	}

	@Override
	public ChatOptions getOptions() {
		return ToolCallingChatOptions.builder().model(this.model).maxTokens(this.maxTokens).build();
	}

}
