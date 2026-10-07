package com.example.miniagent.app.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ProviderAuth;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.env.Environment;

public final class AnthropicApiModelProvider implements ModelProvider {

	private final Environment environment;

	public AnthropicApiModelProvider(Environment environment) {
		this.environment = environment;
	}

	private String setting(String name, String fallback) {
		return this.environment.getProperty("agent.models.providers.anthropic." + name, fallback);
	}

	@Override
	public String providerId() {
		return "anthropic";
	}

	@Override
	public List<ModelEntry> models() {
		return List.of(new ModelEntry(new ModelRef(providerId(), setting("model", "claude-sonnet-5-5")),
				"anthropic-messages", null));
	}

	@Override
	public ProviderAuth auth() {
		return () -> !setting("api-key", "").isBlank();
	}

	@Override
	public Set<String> supportedOptions() {
		return Set.of("maxTokens", "temperature", "topP", "topK");
	}

	@Override
	public Set<String> privateMetadataKeys() {
		return Set.of("anthropicThinkingContents");
	}

	@Override
	public ChatModel create(ModelRef ref, Map<String, String> options) {
		var builder = AnthropicChatOptions.builder().apiKey(setting("api-key", ""))
			.baseUrl(setting("base-url", "https://api.anthropic.com"))
			.model(ref.model()).maxTokens(Integer.valueOf(options.getOrDefault("maxTokens", setting("max-tokens", "4096"))));
		if (options.containsKey("temperature")) {
			builder.temperature(Double.valueOf(options.get("temperature")));
		}
		if (options.containsKey("topP")) {
			builder.topP(Double.valueOf(options.get("topP")));
		}
		if (options.containsKey("topK")) {
			builder.topK(Integer.valueOf(options.get("topK")));
		}
		return AnthropicChatModel.builder().options(builder.build()).build();
	}
}
