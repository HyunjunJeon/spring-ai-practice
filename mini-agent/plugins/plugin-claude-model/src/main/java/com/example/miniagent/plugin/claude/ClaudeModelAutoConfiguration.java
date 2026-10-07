package com.example.miniagent.plugin.claude;

import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ProviderAuth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.model.ChatModel;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(beforeName = "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration")
@EnableConfigurationProperties(ClaudeProperties.class)
public class ClaudeModelAutoConfiguration {

	@Bean
	ClaudeOAuth claudeOAuth(ClaudeProperties properties) {
		return new ClaudeOAuth(properties.getOauthTimeout());
	}

	@Bean
	ClaudeAuth claudeAuth(ClaudeProperties properties, ClaudeOAuth oauth) {
		return new ClaudeAuth(properties.getAuthStore(), oauth);
	}

	@Bean
	ModelProvider claudeModelProvider(ClaudeProperties properties, ClaudeAuth auth) {
		var client = new ClaudeMessagesClient(properties);
		return new ModelProvider() {
			@Override
			public String providerId() {
				return "claude";
			}

			@Override
			public List<ModelEntry> models() {
				return List.of(new ModelEntry(new ModelRef("claude", properties.getModel()), "claude-messages", null));
			}

			@Override
			public ProviderAuth auth() {
				return auth::available;
			}

			@Override
			public Set<String> supportedOptions() {
				return Set.of("maxTokens");
			}

			@Override
			public Set<String> privateMetadataKeys() {
				return Set.of(ClaudeMessages.CONTENT_KEY);
			}

			@Override
			public ChatModel create(ModelRef ref, Map<String, String> options) {
				var turn = new ClaudeProperties();
				turn.setModel(ref.model());
				turn.setMaxTokens(Integer.parseInt(options.getOrDefault("maxTokens",
						Integer.toString(properties.getMaxTokens()))));
				return new ClaudeChatModel(turn, auth, client);
			}
		};
	}

	@Bean
	ClaudeAuthCommands claudeAuthCommands(ClaudeAuth auth, AgentConsole console) {
		return new ClaudeAuthCommands(auth, console);
	}

	@Bean
	AgentPlugin claudePlugin(ClaudeProperties properties) {
		return AgentPlugin.of("claude", "Claude 구독 OAuth · " + properties.getModel());
	}

	@Bean
	SlashCommand claudeCommand(ClaudeProperties properties, ClaudeAuth auth) {
		return SlashCommand.of("claude", "Claude 구독 연동 설정", (args, ctx) -> {
			ctx.console().println("model: " + properties.getModel());
			ctx.console().println("auth store: " + properties.getAuthStore());
			ctx.console().println("authentication available: " + auth.available());
			ctx.console().println("/login 으로 Claude 구독 로그인, /logout 으로 이 앱의 토큰 삭제");
		});
	}

}
