package com.example.miniagent.app.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ProviderAuth;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.env.Environment;

/** OpenAI and Zai share a wire format, never credentials, clients or options. */
public final class OpenAiCompatibleModelProvider implements ModelProvider {

	private final String id;
	private final Environment environment;

	public OpenAiCompatibleModelProvider(String id, Environment environment) {
		this.id = id;
		this.environment = environment;
	}

	private String setting(String name, String fallback) {
		return this.environment.getProperty("agent.models.providers." + this.id + "." + name, fallback);
	}

	@Override
	public String providerId() {
		return this.id;
	}

	@Override
	public List<ModelEntry> models() {
		return List.of(new ModelEntry(new ModelRef(this.id, setting("model",
				"zai".equals(this.id) ? "glm-5.3" : "gpt-5-mini")), "openai-chat", null));
	}

	@Override
	public ProviderAuth auth() {
		return () -> !setting("api-key", "").isBlank();
	}

	@Override
	public Set<String> supportedOptions() {
		return Set.of("reasoningEffort", "temperature", "topP", "maxTokens", "seed");
	}

	@Override
	public Set<String> privateMetadataKeys() {
		// These are the actual GA adapter message metadata names.
		return Set.of("reasoningContent", "openai.tool_calls.additional_properties");
	}

	@Override
	public ChatModel create(ModelRef ref, Map<String, String> options) {
		var builder = OpenAiChatOptions.builder().apiKey(setting("api-key", ""))
			.baseUrl(setting("base-url", "zai".equals(this.id)
					? "https://api.z.ai/api/paas/v4" : "https://api.openai.com/v1"))
			.model(ref.model());
		String effort = options.getOrDefault("reasoningEffort", setting("reasoning-effort", ""));
		if (!effort.isBlank()) {
			builder.reasoningEffort(effort);
		}
		String maxTokens = options.getOrDefault("maxTokens", setting("max-tokens", ""));
		if (!maxTokens.isBlank()) {
			builder.maxTokens(Integer.valueOf(maxTokens));
		}
		if (options.containsKey("temperature")) {
			builder.temperature(Double.valueOf(options.get("temperature")));
		}
		if (options.containsKey("topP")) {
			builder.topP(Double.valueOf(options.get("topP")));
		}
		if (options.containsKey("seed")) {
			builder.seed(Integer.valueOf(options.get("seed")));
		}
		String thinking = setting("thinking", "");
		if (!thinking.isBlank()) {
			builder.extraBody(Map.of("thinking", Map.of("type", thinking)));
		}
		return OpenAiChatModel.builder().options(builder.build()).build();
	}
}
