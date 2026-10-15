package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.model.AuthCommand;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.ProviderAuth;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Codex factory와 자체 인증 action을 등록한다. 선택 변경은 다음 턴에 적용한다.
 */
@AutoConfiguration
@EnableConfigurationProperties(CodexProperties.class)
public class CodexModelAutoConfiguration {

	private final JsonMapper json = JsonMapper.builder().build();

	@Bean
	CodexOAuth codexOAuth(CodexProperties properties) {
		return new CodexOAuth(properties.getOriginator());
	}

	@Bean
	CodexAuth codexAuth(CodexProperties properties, AgentProperties agentProperties, CodexOAuth oauth) {
		return new CodexAuth(agentProperties.getHome().resolve("auth").resolve("openai-codex.json"),
				properties.getCodexHome().resolve("auth.json"), properties.getAuth(), oauth);
	}

	@Bean
	ModelProvider codexModelProvider(CodexProperties properties, CodexAuth auth, CodexModelCatalog catalog) {
		var client = new CodexResponsesClient(this.json, properties.getBaseUrl(), properties.getOriginator(),
				properties.getIdleTimeout());
		return new ModelProvider() {
			@Override
			public String providerId() {
				return "codex";
			}

			@Override
			public List<ModelEntry> models() {
				List<String> models = new ArrayList<>();
				models.add(properties.getModel());
				catalog.models().stream().map(CodexModelCatalog.ModelInfo::slug)
					.filter(slug -> !models.contains(slug)).forEach(models::add);
				return models.stream().map(slug -> new ModelEntry(new ModelRef("codex", slug),
						"codex-responses", null)).toList();
			}

			@Override
			public ProviderAuth auth() {
				return auth::available;
			}

			@Override
			public Set<String> supportedOptions() {
				return Set.of("effort");
			}

			@Override
			public Set<String> privateMetadataKeys() {
				return Set.of(CodexResponses.OUTPUT_ITEMS);
			}

			@Override
			public ChatModel create(ModelRef ref, Map<String, String> options) {
				String effort = options.getOrDefault("effort", properties.getReasoningEffort());
				catalog.find(ref.model()).ifPresent(info -> {
					if (!info.supports(effort)) {
						throw new IllegalArgumentException("Unsupported Codex effort for " + ref.model());
					}
				});
				return new CodexChatModel(auth, client, json, ref.model(), effort);
			}
		};
	}

	@Bean
	AuthCommand.ProviderCommands codexAuthCommands(CodexOAuth oauth, CodexAuth auth, CodexProperties properties) {
		return new AuthCommand.ProviderCommands() {
			@Override
			public String providerId() {
				return "codex";
			}

			@Override
			public void login(String args, SlashCommand.CommandContext ctx) {
				if (!args.isBlank() && !"device".equals(args.strip())) {
					throw new IllegalArgumentException("Usage: /login codex [device]");
				}
				CodexOAuth.LoginUi ui = consoleUi(ctx.console(), properties.isOpenBrowser());
				CodexCredentials credentials = "device".equals(args.strip()) ? oauth.deviceLogin(ui) : oauth.browserLogin(ui);
				auth.saveOwn(credentials);
				ctx.console().println("로그인 완료 (plan: %s, 만료: %s). 토큰 위치: %s".formatted(credentials.plan(),
						credentials.expiresAt(), auth.ownStore()));
			}

			@Override
			public void logout(SlashCommand.CommandContext ctx) {
				ctx.console().println(auth.deleteOwn() ? "삭제했습니다: " + auth.ownStore() : "자체 토큰이 없습니다.");
			}
		};
	}

	@Bean
	CodexModelCatalog codexModelCatalog(CodexProperties properties) {
		return new CodexModelCatalog(properties.getCodexHome().resolve("models_cache.json"), this.json);
	}

	@Bean
	SlashCommand codexCommand(CodexAuth auth, CodexProperties properties, CodexModelCatalog catalog) {
		return SlashCommand.of("codex", "상태 | models | model [번호|slug] | effort [level] | refresh", (args, ctx) -> {
			var picker = new CodexModelPicker(catalog, () -> {
				ModelSelection current = ctx.agent().selectedModel();
				return "codex".equals(current.ref().provider()) ? current
						: new ModelSelection(new ModelRef("codex", properties.getModel()),
								Map.of("effort", properties.getReasoningEffort()));
			}, ctx.agent()::selectModel, ctx.console(), properties.getReasoningEffort());
			String[] parts = args.strip().split("\\s+", 2);
			String rest = (parts.length > 1) ? parts[1] : "";
			switch (parts[0]) {
				case "models" -> picker.listModels();
				case "model" -> picker.chooseModel(rest);
				case "effort" -> picker.chooseEffort(rest);
				case "refresh" -> {
					CodexCredentials refreshed = auth.refreshOwnNow();
					ctx.console().println("자체 토큰을 갱신했습니다. 새 만료 시각: " + refreshed.expiresAt());
				}
				default -> {
					ModelSelection selection = ctx.agent().selectedModel();
					ctx.console().println("selected: " + selection.ref() + " options: " + selection.options());
					ctx.console().println("codex model: " + properties.getModel());
					ctx.console().println("agent-forge token present: " + java.nio.file.Files.isRegularFile(auth.ownStore()));
					ctx.console().println("codex authentication available: " + auth.available());
				}
			}
		});
	}

	@Bean
	AgentPlugin codexPlugin(CodexProperties properties) {
		return AgentPlugin.of("codex", "ChatGPT 구독 모델 factory (auth=" + properties.getAuth() + ")");
	}

	private static CodexOAuth.LoginUi consoleUi(AgentConsole console, boolean openBrowser) {
		return new CodexOAuth.LoginUi() {

			@Override
			public void show(String message) {
				console.println(message);
			}

			@Override
			public @Nullable String ask(String prompt) {
				return console.readLine(prompt);
			}

			@Override
			public void openBrowser(String url) {
				if (!openBrowser) {
					return;
				}
				String os = System.getProperty("os.name").toLowerCase();
				String opener = os.contains("mac") ? "open" : os.contains("win") ? "explorer" : "xdg-open";
				try {
					new ProcessBuilder(opener, url).start();
				}
				catch (IOException ex) {
					console.info("브라우저를 열지 못했습니다. URL 을 직접 여세요.");
				}
			}

		};
	}

}
