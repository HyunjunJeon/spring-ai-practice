package com.example.miniagent.app;

import java.nio.file.Path;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.ModelUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoKeyStartupTest {

	@TempDir
	Path home;

	@Test
	void keylessDefaultStartsAndListsUnavailableProvidersWithoutReadingStores() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "")) {
			Agent agent = context.getBean(Agent.class);
			assertThat(agent.run("offline").text()).contains("[fake]");
			assertThat(agent.modelRegistry().models().stream()
					.filter(entry -> !entry.ref().provider().equals("fake") && !entry.ref().provider().equals("ollama")))
				.allSatisfy(entry -> assertThat(entry.unavailableCode()).isEqualTo(ModelUnavailableException.Code.AUTH_MISSING));
			ModelSelection original = agent.selectedModel();
			assertThatThrownBy(() -> agent.selectModel(new ModelSelection(new ModelRef("openai", "gpt-5-mini"))))
				.isInstanceOfSatisfying(ModelUnavailableException.class,
						ex -> assertThat(ex.code()).isEqualTo(ModelUnavailableException.Code.AUTH_MISSING));
			assertThatThrownBy(() -> agent.selectModel(new ModelSelection(new ModelRef("unknown", "missing"))))
				.isInstanceOfSatisfying(ModelUnavailableException.class,
						ex -> assertThat(ex.code()).isEqualTo(ModelUnavailableException.Code.UNKNOWN_MODEL));
			assertThat(agent.selectedModel()).isEqualTo(original);
			assertThat(this.home.resolve("auth")).doesNotExist();
		}
	}

	@Test
	void keylessSelectedApiProviderHasNoFallbackChatModel() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "openai")) {
			assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
			Agent agent = context.getBean(Agent.class);
			assertThat(agent.selectedModel().ref().provider()).isEqualTo("openai");
			assertThatThrownBy(() -> agent.run("must not call remote"))
				.isInstanceOfSatisfying(ModelUnavailableException.class,
						ex -> assertThat(ex.code()).isEqualTo(ModelUnavailableException.Code.AUTH_MISSING));
		}
	}

	@Test
	void explicitDefaultWithoutAProfileDoesNotCreateFakeFallback() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "",
				"--agent.models.default=openai/gpt-5-mini")) {
			assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
			assertThat(context.getBean(Agent.class).selectedModel().ref().provider()).isEqualTo("openai");
		}
	}
}
