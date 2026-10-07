package com.example.miniagent.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.model.ModelProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderProfileMigrationTest {

	@TempDir
	Path home;

	static ConfigurableApplicationContext start(Path home, String profile, String... extra) {
		return builder().run(arguments(home, profile, extra));
	}

	static SpringApplicationBuilder builder() {
		return new SpringApplicationBuilder(MiniAgentApplication.class);
	}

	static String[] arguments(Path home, String profile, String... extra) {
		List<String> args = new ArrayList<>(List.of("--agent.repl.enabled=false", "--agent.ui=repl",
				"--agent.plugins.trace.level=off", "--agent.home=" + home, "--agent.cwd=" + home,
				"--agent.codex.codex-home=" + home.resolve("codex"),
				"--agent.claude.auth-store=" + home.resolve("auth/claude.json"),
				"--agent.models.providers.openai.api-key=", "--agent.models.providers.zai.api-key=",
				"--agent.models.providers.anthropic.api-key="));
		if (!profile.isBlank()) {
			args.add("--spring.profiles.active=" + profile);
		}
		// Extra values replace rather than duplicate command-line properties.
		for (String value : extra) {
			String key = value.substring(0, value.indexOf('=') + 1);
			args.removeIf(arg -> arg.startsWith(key));
			args.add(value);
		}
		return args.toArray(String[]::new);
	}

	@ParameterizedTest
	@CsvSource({ "default,fake", "openai,openai", "zai,zai", "anthropic,anthropic", "ollama,ollama",
			"codex,codex", "claude,claude" })
	void profilesSelectProviderWithoutDisablingOtherFactories(String profile, String provider) {
		try (var context = start(this.home, "default".equals(profile) ? "" : profile)) {
			assertThat(context.getBean(Agent.class).selectedModel().ref().provider()).isEqualTo(provider);
			assertThat(context.getBeansOfType(ModelProvider.class).values()).extracting(ModelProvider::providerId)
				.containsExactlyInAnyOrder("fake", "openai", "zai", "anthropic", "ollama", "codex", "claude");
			assertThat(context.getEnvironment().getProperty("spring.ai.chat.client.enabled")).isEqualTo("false");
		}
	}

	@Test
	void zaiAliasesDoNotOverwriteOpenAiConfiguration() {
		try (var context = start(this.home, "zai", "--ZAI_MODEL=synthetic-glm", "--OPENAI_MODEL=synthetic-gpt",
				"--ZAI_BASE_URL=http://localhost:11/coding", "--OPENAI_BASE_URL=http://localhost:12/v1",
				"--ZAI_REASONING_EFFORT=medium")) {
			var environment = context.getEnvironment();
			assertThat(context.getBean(Agent.class).selectedModel().ref().model()).isEqualTo("synthetic-glm");
			assertThat(environment.getProperty("agent.models.providers.openai.model")).isEqualTo("synthetic-gpt");
			assertThat(environment.getProperty("agent.models.providers.openai.base-url"))
				.isEqualTo("http://localhost:12/v1");
			assertThat(environment.getProperty("agent.models.providers.zai.base-url"))
				.isEqualTo("http://localhost:11/coding");
			assertThat(environment.getProperty("agent.models.providers.zai.reasoning-effort")).isEqualTo("medium");
		}
	}
}
