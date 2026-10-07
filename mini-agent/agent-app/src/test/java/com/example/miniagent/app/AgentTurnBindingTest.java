package com.example.miniagent.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.ToolContextKeys;
import com.example.miniagent.core.model.FileSessionModelStore;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelRegistry;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.ProviderAuth;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTurnBindingTest {

	private static final ModelRef A = new ModelRef("fake", "a");

	private static final ModelRef B = new ModelRef("fake", "b");

	@TempDir
	Path home;

	@Test
	void modelChangeDuringToolRoundAppliesOnlyToNextTurn() throws Exception {
		List<ModelRef> calls = new ArrayList<>();
		AtomicReference<Agent> holder = new AtomicReference<>();
		var callbacks = MethodToolCallbackProvider.builder().toolObjects(new SwitchingTool(holder)).build();
		ModelRegistry registry = new ModelRegistry(List.of(provider(calls, false)),
				model -> ChatClient.builder(model).defaultToolCallbacks(callbacks).build());
		AgentSession session = new AgentSession();
		var store = new FileSessionModelStore(this.home);
		Agent agent = new Agent(registry, store, new ModelSelection(A), session, this.home, "test",
				List.of(), List.of(callbacks), List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
		holder.set(agent);

		var reply = agent.run("!tool switch_model {}");

		assertThat(reply.text()).contains("fake/a");
		assertThat(calls).containsExactly(A, A);
		assertThat(agent.selectedModel().ref()).isEqualTo(B);
		assertThat(TurnContext.current()).isEmpty();
		agent.run("next turn");
		assertThat(calls).containsExactly(A, A, B);
		assertThat(new FileSessionModelStore(this.home).load(session.id())).contains(new ModelSelection(B));
		assertThat(this.home.resolve("sessions")).doesNotExist();
	}

	@Test
	void providerErrorMidToolFailsWithoutFallback() throws Exception {
		List<ModelRef> calls = new ArrayList<>();
		AtomicReference<Agent> holder = new AtomicReference<>();
		var callbacks = MethodToolCallbackProvider.builder().toolObjects(new SwitchingTool(holder)).build();
		ModelRegistry registry = new ModelRegistry(List.of(provider(calls, true)),
				model -> ChatClient.builder(model).defaultToolCallbacks(callbacks).build());
		Agent agent = new Agent(registry, new FileSessionModelStore(this.home), new ModelSelection(A),
				new AgentSession(), this.home, "test", List.of(), List.of(callbacks), List.of(), List.of(),
				ToolCallingAdvisor.DEFAULT_ORDER);
		holder.set(agent);

		assertThatThrownBy(() -> agent.run("!tool switch_model {}")).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("provider failed");
		assertThat(calls).containsExactly(A, A);
		assertThat(TurnContext.current()).isEmpty();
		assertThat(agent.selectedModel().ref()).isEqualTo(B);
	}

	private static ModelProvider provider(List<ModelRef> calls, boolean failSecondRound) {
		return new ModelProvider() {
			@Override
			public String providerId() {
				return "fake";
			}

			@Override
			public List<ModelEntry> models() {
				return List.of(new ModelEntry(A, "fake", null), new ModelEntry(B, "fake", null));
			}

			@Override
			public ProviderAuth auth() {
				return () -> true;
			}

			@Override
			public ChatModel create(ModelRef ref, Map<String, String> options) {
				return new ScriptedChatModel() {
					private int rounds;

					@Override
					public ChatResponse call(Prompt prompt) {
						calls.add(TurnContext.current().orElseThrow().binding().ref());
						if (failSecondRound && ++this.rounds == 2) {
							throw new IllegalStateException("provider failed");
						}
						return super.call(prompt);
					}
				};
			}
		};
	}

	public static class SwitchingTool {
		private final AtomicReference<Agent> agent;

		SwitchingTool(AtomicReference<Agent> agent) {
			this.agent = agent;
		}

		@Tool(name = "switch_model", description = "Select next-turn model and return captured current model")
		public String switchModel(ToolContext context) {
			TurnContext captured = (TurnContext) context.getContext().get(ToolContextKeys.TURN_CONTEXT);
			this.agent.get().selectModel(new ModelSelection(B));
			return captured.binding().ref().provider() + "/" + captured.binding().ref().model();
		}
	}
}
