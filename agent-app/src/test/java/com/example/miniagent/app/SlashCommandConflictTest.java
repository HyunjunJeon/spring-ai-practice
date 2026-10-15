package com.example.miniagent.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.model.FileSessionModelStore;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.ProviderAuth;
import com.example.miniagent.core.model.command.AuthCommand;
import com.example.miniagent.core.model.registry.ModelRegistry;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlashCommandConflictTest {

	@TempDir
	Path home;

	@Test
	void simultaneousProvidersRegisterExactlyOneLoginAndLogout() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "")) {
			var names = context.getBeansOfType(SlashCommand.class).values().stream().map(SlashCommand::name).toList();
			assertThat(names.stream().filter("login"::equals)).hasSize(1);
			assertThat(names.stream().filter("logout"::equals)).hasSize(1);
			assertThat(names).doesNotHaveDuplicates().contains("codex", "claude");
		}
	}

	@Test
	void duplicateNameFailsStartup() {
		assertThatThrownBy(() -> {
			try (var ignored = ProviderProfileMigrationTest.builder().sources(DuplicateCommand.class)
					.run(ProviderProfileMigrationTest.arguments(this.home, ""))) {
				throw new AssertionError("duplicate command startup must fail");
			}
		}).hasRootCauseInstanceOf(IllegalStateException.class).hasStackTraceContaining("Duplicate key help");
	}

	@Test
	void omittedProviderAndLegacyDeviceFollowSelectionWhileExplicitProviderWins() throws Exception {
		AtomicReference<String> action = new AtomicReference<>();
		var providers = List.of(actions("codex", action), actions("claude", action));
		AgentSession session = new AgentSession();
		ModelProvider codex = new ModelProvider() {
			@Override public String providerId() { return "codex"; }
			@Override public List<ModelEntry> models() {
				return List.of(new ModelEntry(new ModelRef("codex", "test"), "fake", null));
			}
			@Override public ProviderAuth auth() { return () -> true; }
			@Override public ChatModel create(ModelRef ref, Map<String, String> options) { return new ScriptedChatModel(); }
		};
		var registry = new ModelRegistry(List.of(codex), model -> ChatClient.builder(model).build());
		Agent agent = new Agent(registry, new FileSessionModelStore(this.home),
				new ModelSelection(new ModelRef("codex", "test")), session, this.home, "test",
				List.of(), List.of(), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
		var context = new SlashCommand.CommandContext(agent, session, AgentConsole.system());
		AuthCommand login = new AuthCommand("login", providers);
		login.execute("", context);
		assertThat(action).hasValue("login:codex:");
		login.execute("device", context);
		assertThat(action).hasValue("login:codex:device");
		login.execute("claude", context);
		assertThat(action).hasValue("login:claude:");
		new AuthCommand("logout", providers).execute("", context);
		assertThat(action).hasValue("logout:codex");
		new AuthCommand("logout", providers).execute("claude", context);
		assertThat(action).hasValue("logout:claude");
		assertThatThrownBy(() -> login.execute("unregistered", context)).isInstanceOf(IllegalArgumentException.class);
	}

	private static AuthCommand.ProviderCommands actions(String id, AtomicReference<String> action) {
		return new AuthCommand.ProviderCommands() {
			@Override public String providerId() { return id; }
			@Override public void login(String args, SlashCommand.CommandContext context) {
				action.set("login:" + id + ":" + args);
			}
			@Override public void logout(SlashCommand.CommandContext context) { action.set("logout:" + id); }
		};
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DuplicateCommand {
		@Bean
		SlashCommand duplicateHelpCommand() {
			return SlashCommand.of("help", "duplicate", (args, context) -> { });
		}
	}
}
