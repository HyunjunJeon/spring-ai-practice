package com.example.miniagent.app.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ProviderAuth;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.env.Environment;

public final class OllamaModelProvider implements ModelProvider {

	private final Environment environment;

	public OllamaModelProvider(Environment environment) {
		this.environment = environment;
	}

	private String setting(String name, String fallback) {
		return this.environment.getProperty("agent.models.providers.ollama." + name, fallback);
	}

	@Override
	public String providerId() {
		return "ollama";
	}

	@Override
	public List<ModelEntry> models() {
		return List.of(new ModelEntry(new ModelRef(providerId(), setting("model", "qwen3:8b")), "ollama-chat", null));
	}

	@Override
	public ProviderAuth auth() {
		return () -> true;
	}

	@Override
	public Set<String> supportedOptions() {
		return Set.of("temperature", "topP", "topK", "maxTokens", "seed", "thinkingLevel");
	}

	@Override
	public Set<String> privateMetadataKeys() {
		return Set.of("thinking");
	}

	@Override
	public ChatModel create(ModelRef ref, Map<String, String> options) {
		var builder = OllamaChatOptions.builder().model(ref.model());
		if (options.containsKey("temperature")) {
			builder.temperature(Double.valueOf(options.get("temperature")));
		}
		if (options.containsKey("topP")) {
			builder.topP(Double.valueOf(options.get("topP")));
		}
		if (options.containsKey("topK")) {
			builder.topK(Integer.valueOf(options.get("topK")));
		}
		if (options.containsKey("maxTokens")) {
			builder.numPredict(Integer.valueOf(options.get("maxTokens")));
		}
		if (options.containsKey("seed")) {
			builder.seed(Integer.valueOf(options.get("seed")));
		}
		if (options.containsKey("thinkingLevel")) {
			switch (options.get("thinkingLevel")) {
				case "low" -> builder.thinkLow();
				case "medium" -> builder.thinkMedium();
				case "high" -> builder.thinkHigh();
				default -> throw new IllegalArgumentException("Ollama thinkingLevel must be low, medium or high");
			}
		}
		return OllamaChatModel.builder().ollamaApi(OllamaApi.builder()
				.baseUrl(setting("base-url", "http://localhost:11434")).build()).options(builder.build()).build();
	}
}
