package com.example.miniagent.app;

import java.nio.file.Path;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 키 없이도 모든 프로필이 시작한다. 프로필이 spring.ai.model.chat=none 을 잃으면 Spring AI starter 가
 * 키를 요구하거나 두 번째 ChatModel 이 생기므로, 프로필마다 선택된 기본 모델과 첫 턴의 AUTH_MISSING 을 확인한다.
 */
class ProfileKeylessStartupTest {

	@TempDir
	Path home;

	@ParameterizedTest
	@CsvSource({ "anthropic,anthropic,claude-sonnet-5-5", "zai,zai,glm-5.3", "codex,codex,gpt-6-luna",
			"claude,claude,claude-sonnet-5-5" })
	void keylessProfileStartsWithoutAChatModelAndTheFirstTurnReportsAuthMissing(String profile, String provider,
			String model) {
		ModelRef expected = new ModelRef(provider, model);
		try (var context = ProviderProfileMigrationTest.start(this.home, profile)) {
			Agent agent = context.getBean(Agent.class);

			assertThat(context.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("none");
			assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
			assertThat(agent.selectedModel().ref()).isEqualTo(expected);
			assertThat(agent.modelRegistry().models()).filteredOn(entry -> entry.ref().equals(expected))
				.singleElement()
				.satisfies(entry -> assertThat(entry.unavailableCode())
					.isEqualTo(ModelUnavailableException.Code.AUTH_MISSING));
			assertThatThrownBy(() -> agent.run("must not call remote"))
				.isInstanceOfSatisfying(ModelUnavailableException.class, ex -> {
					assertThat(ex.code()).isEqualTo(ModelUnavailableException.Code.AUTH_MISSING);
					assertThat(ex.ref()).isEqualTo(expected);
				});
		}
	}

	@Test
	void ollamaProfileNeedsNoKeyAndOwnsTheOnlyChatModel() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "ollama")) {
			Agent agent = context.getBean(Agent.class);

			assertThat(context.getEnvironment().getProperty("spring.ai.model.chat")).isEqualTo("none");
			assertThat(agent.selectedModel().ref()).isEqualTo(new ModelRef("ollama", "qwen3:8b"));
			assertThat(context.getBeansOfType(ChatModel.class).values()).singleElement()
				.isInstanceOf(OllamaChatModel.class);
		}
	}
}
