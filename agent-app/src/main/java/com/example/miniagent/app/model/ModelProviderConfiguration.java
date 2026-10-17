package com.example.miniagent.app.model;

import java.util.Map;

import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.command.AuthCommand;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class ModelProviderConfiguration {

	@Bean
	ModelProvider openAiModelProvider(Environment environment) {
		return new OpenAiCompatibleModelProvider("openai", environment);
	}

	@Bean
	ModelProvider zaiModelProvider(Environment environment) {
		return new OpenAiCompatibleModelProvider("zai", environment);
	}

	@Bean
	ModelProvider anthropicApiModelProvider(Environment environment) {
		return new AnthropicApiModelProvider(environment);
	}

	@Bean
	ModelProvider ollamaModelProvider(Environment environment) {
		return new OllamaModelProvider(environment);
	}

	/** Compatibility for consumers still injecting the startup-selected ChatModel. A blank default counts as unset. */
	@Bean
	@ConditionalOnExpression("!'${agent.models.default:}'.isBlank() && !'${agent.models.default:}'.startsWith('fake/')")
	@Nullable ChatModel selectedDefaultChatModel(Environment environment, ObjectProvider<ModelProvider> providers) {
		String configured = environment.getRequiredProperty("agent.models.default");
		String[] parts = configured.split("/", 2);
		if (parts.length != 2) {
			throw new IllegalArgumentException("agent.models.default must be provider/model: " + configured);
		}
		ModelRef ref = new ModelRef(parts[0], parts[1]);
		ModelProvider provider = providers.stream().filter(p -> p.providerId().equals(ref.provider()))
			.findFirst().orElseThrow(() -> new IllegalArgumentException(
					"agent.models.default names an unknown model provider: " + ref.provider()));
		// NullBean is not a ChatModel: missing authentication never creates a fallback.
		return provider.auth().available() ? provider.create(ref, Map.of()) : null;
	}

	@Bean
	SlashCommand loginCommand(ObjectProvider<AuthCommand.ProviderCommands> commands) {
		return new AuthCommand("login", commands.stream().toList());
	}

	@Bean
	SlashCommand logoutCommand(ObjectProvider<AuthCommand.ProviderCommands> commands) {
		return new AuthCommand("logout", commands.stream().toList());
	}
}
