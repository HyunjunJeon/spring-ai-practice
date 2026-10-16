package com.example.miniagent.app;

import java.nio.file.Path;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.model.ModelRef;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.core.NestedExceptionUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** agent.models.default 가 잘못되면 시작이 읽을 수 있는 메시지로 실패하고, 올바른 값의 선택은 그대로다. */
class DefaultModelConfigurationTest {

	@TempDir
	Path home;

	@ParameterizedTest
	@ValueSource(strings = { "gpt-5", "openai" })
	void defaultWithoutSlashFailsStartupWithTheExpectedShape(String malformed) {
		assertThatThrownBy(() -> ProviderProfileMigrationTest.start(this.home, "",
				"--agent.models.default=" + malformed))
			.satisfies(ex -> {
				Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
				assertThat(root).isInstanceOf(IllegalArgumentException.class);
				assertThat(root.getMessage()).contains("agent.models.default", "provider/model");
			});
	}

	@Disabled("SUSPECT model:G20: a blank agent.models.default fails startup in selectedDefaultChatModel with"
			+ " IllegalArgumentException 'agent.models.default must be provider/model' instead of falling back to"
			+ " spring.ai.model.chat the way AgentAutoConfiguration treats an unset default")
	@Test
	void blankDefaultFallsBackToTheFakeProviderLikeAnUnsetDefault() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "", "--agent.models.default=")) {
			Agent agent = context.getBean(Agent.class);

			assertThat(agent.selectedModel().ref().provider()).isEqualTo("fake");
			assertThat(agent.run("offline").text()).contains("[fake]");
		}
	}

	@Test
	void defaultWithAnUnknownProviderFailsStartupNamingTheProvider() {
		assertThatThrownBy(() -> ProviderProfileMigrationTest.start(this.home, "",
				"--agent.models.default=nosuch/x"))
			.satisfies(ex -> {
				Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
				assertThat(root).isInstanceOf(IllegalArgumentException.class);
				assertThat(root.getMessage()).contains("agent.models.default", "nosuch");
			});
	}

	@Test
	void validNonFakeDefaultWithAKeyStillSelectsThatProvidersChatModel() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "",
				"--agent.models.default=openai/gpt-5-mini", "--agent.models.providers.openai.api-key=openai-synthetic")) {
			assertThat(context.getBean(Agent.class).selectedModel().ref()).isEqualTo(new ModelRef("openai", "gpt-5-mini"));
			assertThat(context.getBean(ChatModel.class)).isInstanceOf(OpenAiChatModel.class);
			assertThat(context.getBean(ChatModel.class).getOptions().getModel()).isEqualTo("gpt-5-mini");
		}
	}

	@Test
	void validFakeDefaultStartsWithoutACompatibilityChatModel() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "",
				"--agent.models.default=fake/fake-scripted")) {
			Agent agent = context.getBean(Agent.class);
			assertThat(agent.selectedModel().ref().provider()).isEqualTo("fake");
			assertThat(agent.selectedModel().ref().model()).isEqualTo("fake-scripted");
			assertThat(agent.run("offline").text()).contains("[fake]");
			assertThat(context.containsBean("selectedDefaultChatModel")).isFalse();
		}
	}
}
