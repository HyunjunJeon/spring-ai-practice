package com.example.miniagent.app;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.model.command.ModelCommand;
import com.example.miniagent.core.model.command.ModelsCommand;
import com.example.miniagent.core.model.registry.ModelRegistry;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCommandTest {

	static final ModelRef A = new ModelRef("fake", "a");
	static final ModelRef B = new ModelRef("fake", "b");

	@TempDir
	Path home;

	final ByteArrayOutputStream output = new ByteArrayOutputStream();
	final AgentConsole console = new AgentConsole(new BufferedReader(new StringReader("")),
			new PrintStream(this.output), false);

	static Agent agent(Path home, AgentSession session, ChatModel model, Consumer<TraceEvent> events) throws Exception {
		ModelProvider provider = new ModelProvider() {
			public String providerId() { return "fake"; }
			public List<ModelEntry> models() {
				return List.of(new ModelEntry(A, "fake", null), new ModelEntry(B, "fake", null),
						new ModelEntry(new ModelRef("fake", "locked"), "fake", ModelUnavailableException.Code.AUTH_MISSING));
			}
			public ProviderAuth auth() { return () -> true; }
			public Set<String> supportedOptions() { return Set.of("temperature"); }
			public ChatModel create(ModelRef ref, Map<String, String> options) { return model; }
		};
		return new Agent(new ModelRegistry(List.of(provider), ChatClient::create),
				new FileSessionModelStore(home), new ModelSelection(A), session, home, "test",
				List.of(), List.of(), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER, events);
	}

	AgentInputHandler handler(Agent agent, AgentSession session) {
		return new AgentInputHandler(agent, session, this.console, List.of(new ModelCommand(), new ModelsCommand()));
	}

	@Test
	void selectsImmutableSupportedOptionsAndRestoresWithoutMemory() throws Exception {
		AgentSession session = new AgentSession();
		Agent agent = agent(this.home, session, new ScriptedChatModel(), event -> {});
		assertThat(handler(agent, session).handle("/model select fake/b temperature=0.4")).isTrue();
		assertThat(agent.selectedModel()).isEqualTo(new ModelSelection(B, Map.of("temperature", "0.4")));
		assertThatThrownBy(() -> agent.selectedModel().options().put("temperature", "1"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(agent(this.home, session, new ScriptedChatModel(), event -> {}).selectedModel())
			.isEqualTo(agent.selectedModel());
		assertThat(this.home.resolve("sessions")).doesNotExist();
	}

	@ParameterizedTest
	@ValueSource(strings = {"/model select", "/model fake", "/model unknown/a", "/model fake/missing",
			"/model fake/b apiKey=x", "/model fake/b effort=high", "/model fake/b temperature=",
			"/model fake/b temperature=1 temperature=2", "/model fake/b temperature=NaN",
			"/model fake/b temperature=-1", "/model fake/b temperature=bad",
			"/model show extra", "/models extra"})
	void invalidArgumentsKeepPreviousSelection(String input) throws Exception {
		AgentSession session = new AgentSession();
		Agent agent = agent(this.home, session, new ScriptedChatModel(), event -> {});
		assertThat(handler(agent, session).handle(input)).isFalse();
		assertThat(agent.selectedModel()).isEqualTo(new ModelSelection(A));
		assertThat(new FileSessionModelStore(this.home).load(session.id())).isEmpty();
	}

	@Test
	void unavailableSelectionKeepsPrevious() throws Exception {
		AgentSession session = new AgentSession();
		Agent agent = agent(this.home, session, new ScriptedChatModel(), event -> {});
		assertThat(handler(agent, session).handle("/model fake/locked")).isFalse();
		assertThat(agent.selectedModel()).isEqualTo(new ModelSelection(A));
		assertThat(this.output.toString()).contains("AUTH_MISSING");
	}

	@Test
	void listsAllRefsWithActiveMarkerAndAvailability() throws Exception {
		AgentSession session = new AgentSession();
		Agent agent = agent(this.home, session, new ScriptedChatModel(), event -> {});
		assertThat(handler(agent, session).handle("/models")).isTrue();
		assertThat(this.output.toString()).contains("* fake/a", "fake/b", "fake/locked", "AUTH_MISSING");
		assertThat(handler(agent, session).handle("/model fake/b")).isTrue();
		assertThat(handler(agent, session).handle("/model show")).isTrue();
		assertThat(agent.selectedModel().ref()).isEqualTo(B);
	}

	@Test
	void springRegistersCommandsOnceAndPublishesActualBinding() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "", "--agent.plugins.memory.enabled=false")) {
			List<SlashCommand> commands = context.getBeansOfType(SlashCommand.class).values().stream().toList();
			assertThat(commands.stream().filter(command -> command.name().equals("model"))).hasSize(1);
			assertThat(commands.stream().filter(command -> command.name().equals("models"))).hasSize(1);
			var bindings = new java.util.ArrayList<TraceEvent.ModelBound>();
			context.addApplicationListener(event -> {
				if (event instanceof org.springframework.context.PayloadApplicationEvent<?> payload
						&& payload.getPayload() instanceof TraceEvent.ModelBound bound) {
					bindings.add(bound);
				}
			});
			Agent agent = context.getBean(Agent.class);
			agent.run("offline");
			assertThat(bindings).hasSize(1);
			assertThat(bindings.getFirst().ref()).isEqualTo(agent.selectedModel().ref());
			assertThat(bindings.getFirst().sessionId()).isEqualTo(context.getBean(AgentSession.class).id());
			assertThat(bindings.getFirst().bindingId()).isNotNull();
			assertThat(agent.currentTurn()).isEmpty();
		}
	}

	@Test
	void duplicateModelCommandFailsInsteadOfShadowing() throws Exception {
		AgentSession session = new AgentSession();
		Agent agent = agent(this.home, session, new ScriptedChatModel(), event -> {});
		assertThatThrownBy(() -> new AgentInputHandler(agent, session, this.console,
				List.of(new ModelCommand(), SlashCommand.of("model", "duplicate", (args, context) -> {}))))
			.isInstanceOf(IllegalStateException.class);
	}
}
