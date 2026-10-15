package com.example.miniagent.plugin.codex;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.model.FileSessionModelStore;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.command.AuthCommand;
import com.example.miniagent.core.model.registry.ModelRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;

import static org.assertj.core.api.Assertions.assertThat;

class CodexProviderTest {

	@TempDir
	Path home;

	@Test
	void commandsChangeNextTurnSelectionWithoutMutatingExistingModel() throws Exception {
		var properties = new CodexProperties();
		properties.setCodexHome(this.home);
		properties.setReasoningEffort("xhigh");
		Files.writeString(this.home.resolve("models_cache.json"), """
				{"models":[
				  {"slug":"gpt-6-luna","visibility":"list","supported_reasoning_levels":[{"effort":"low"},{"effort":"xhigh"}]},
				  {"slug":"next-model","visibility":"list","supported_reasoning_levels":[{"effort":"low"},{"effort":"xhigh"}]}
				]}
				""");
		var auth = new CodexAuth(this.home.resolve("own.json"), this.home.resolve("cli.json"), "auto",
				new CodexOAuth("test")) {
			@Override public boolean available() { return true; }
		};
		var configuration = new CodexModelAutoConfiguration();
		var catalog = configuration.codexModelCatalog(properties);
		var provider = configuration.codexModelProvider(properties, auth, catalog);
		var initial = new ModelSelection(new ModelRef("codex", "gpt-6-luna"), Map.of("effort", "xhigh"));
		var oldModel = (CodexChatModel) provider.create(initial.ref(), initial.options());
		var registry = new ModelRegistry(List.of(provider), model -> ChatClient.builder(model).build());
		AgentSession session = new AgentSession();
		var agent = new Agent(registry, new FileSessionModelStore(this.home), initial, session, this.home, "test",
				List.of(), List.of(), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
		var console = new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(new ByteArrayOutputStream()), false);
		var context = new SlashCommand.CommandContext(agent, session, console);
		var command = configuration.codexCommand(auth, properties, catalog);
		command.execute("model next-model", context);
		command.execute("effort low", context);

		assertThat(agent.selectedModel()).isEqualTo(new ModelSelection(new ModelRef("codex", "next-model"),
				Map.of("effort", "low")));
		assertThat(new FileSessionModelStore(this.home).load(session.id())).contains(agent.selectedModel());
		assertThat(oldModel.model()).isEqualTo("gpt-6-luna");
		assertThat(oldModel.reasoningEffort()).isEqualTo("xhigh");
		var next = (CodexChatModel) provider.create(agent.selectedModel().ref(), agent.selectedModel().options());
		assertThat(next).isNotSameAs(oldModel);
		assertThat(next.reasoningEffort()).isEqualTo("low");
		assertThat(provider.privateMetadataKeys()).containsExactly("codex.outputItems");
	}

	@Test
	void unifiedLegacyDeviceSyntaxInvokesActualCodexDeviceAction() throws Exception {
		var properties = new CodexProperties();
		properties.setCodexHome(this.home);
		properties.setOpenBrowser(false);
		AtomicInteger devices = new AtomicInteger();
		var oauth = new CodexOAuth("test") {
			@Override
			public CodexCredentials deviceLogin(LoginUi ui) {
				devices.incrementAndGet();
				return new CodexCredentials("synthetic-access", "synthetic-refresh", "account",
						Instant.parse("2100-01-01T00:00:00Z"), "test", CodexCredentials.Source.MINI_AGENT);
			}
			@Override
			public CodexCredentials browserLogin(LoginUi ui) {
				throw new AssertionError("device syntax must not invoke browser login");
			}
		};
		var auth = new CodexAuth(this.home.resolve("own.json"), this.home.resolve("cli.json"), "auto", oauth);
		var configuration = new CodexModelAutoConfiguration();
		var provider = configuration.codexModelProvider(properties, auth, configuration.codexModelCatalog(properties));
		var registry = new ModelRegistry(List.of(provider), model -> ChatClient.builder(model).build());
		AgentSession session = new AgentSession();
		var agent = new Agent(registry, new FileSessionModelStore(this.home),
				new ModelSelection(new ModelRef("codex", properties.getModel())), session, this.home, "test",
				List.of(), List.of(), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		var context = new SlashCommand.CommandContext(agent, session,
				new AgentConsole(new BufferedReader(new StringReader("")), new PrintStream(output), false));
		new AuthCommand("login", List.of(configuration.codexAuthCommands(oauth, auth, properties)))
			.execute("device", context);
		assertThat(devices).hasValue(1);
		assertThat(auth.available()).isTrue();
		assertThat(output.toString()).doesNotContain("synthetic-access", "synthetic-refresh");
		new AuthCommand("logout", List.of(configuration.codexAuthCommands(oauth, auth, properties)))
			.execute("", context);
		assertThat(auth.available()).isFalse();
	}

	@Test
	void availabilityUsesPresenceOnlyAndHonorsCredentialSourceMode() throws Exception {
		Files.writeString(this.home.resolve("cli.json"), "not even JSON");
		Files.writeString(this.home.resolve("own.json"), "not even JSON");
		var oauth = new CodexOAuth("test");
		var auth = new CodexAuth(this.home.resolve("own.json"), this.home.resolve("cli.json"), "auto", oauth);
		assertThat(auth.available()).isTrue();
		var ownOnly = new CodexAuth(this.home.resolve("missing.json"), this.home.resolve("cli.json"), "mini-agent", oauth);
		assertThat(ownOnly.available()).isFalse();
		var cliOnly = new CodexAuth(this.home.resolve("own.json"), this.home.resolve("missing.json"), "codex-cli", oauth);
		assertThat(cliOnly.available()).isFalse();
	}
}
