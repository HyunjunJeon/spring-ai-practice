package com.example.miniagent.app;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.model.*;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

class ModelDisplayTest {

	@TempDir
	Path home;

	@Test
	void capturedRefOptionsAndBindingSurviveMidTurnSelectionAndChildExecution() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<TurnContext> captured = new AtomicReference<>();
		AtomicReference<TraceEvent.ModelBound> event = new AtomicReference<>();
		ScriptedChatModel model = new ScriptedChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				TurnContext context = TurnContext.current().orElseThrow();
				if (context.runId() == null) {
					captured.set(context);
					started.countDown();
					try {
						assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(ex);
					}
				}
				return super.call(prompt);
			}
		};
		Agent agent = ModelCommandTest.agent(this.home, new AgentSession(), model, trace -> {
			if (trace instanceof TraceEvent.ModelBound bound && bound.runId() == null) {
				event.set(bound);
			}
		});
		var initial = new ModelSelection(ModelCommandTest.A, Map.of("temperature", "0.4"));
		agent.selectModel(initial);
		try (var worker = Executors.newSingleThreadExecutor()) {
			var turn = worker.submit(() -> agent.run("first"));
			try {
				assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
				var next = new ModelSelection(ModelCommandTest.A, Map.of("temperature", "0.9"));
				agent.selectModel(next);
				assertThat(agent.currentTurn()).contains(captured.get());
				assertThat(event.get().bindingId()).isEqualTo(captured.get().binding().bindingId());
				assertThat(event.get().turnId()).isEqualTo(captured.get().turnId());
				assertThat(event.get().optionsFingerprint()).isEqualTo(initial.optionsFingerprint());
				assertThat(ModelDisplay.label(agent)).contains("running: fake/a",
						initial.optionsFingerprint(), "next: fake/a temperature=0.9");
				var childBinding = agent.modelRegistry().bind(new ModelSelection(ModelCommandTest.B));
				agent.run("child", new TurnContext("child-session", "child-turn", this.home, childBinding,
						"child-run", "child-attempt"));
				assertThat(agent.currentTurn()).contains(captured.get());
				assertThat(ModelDisplay.label(agent)).contains(initial.optionsFingerprint());
				agent.selectModel(new ModelSelection(ModelCommandTest.B));
				assertThat(ModelDisplay.label(agent)).contains("running: fake/a", "next: fake/b");
			}
			finally {
				release.countDown();
			}
			assertThat(turn.get(5, TimeUnit.SECONDS).text()).isNotEmpty();
		}
		assertThat(agent.currentTurn()).isEmpty();
		assertThat(agent.selectedModel().ref()).isEqualTo(ModelCommandTest.B);
	}

	@Test
	void keylessSelectedModelDisplaysAuthMissingWithoutBinding() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "openai", "--agent.ui=tui")) {
			assertThat(ModelDisplay.label(context.getBean(Agent.class))).contains("openai/", "AUTH_MISSING")
				.doesNotContain("running:");
			var ui = context.getBean(com.example.miniagent.plugin.tui.TuiFrontEnd.class);
			Object label = org.springframework.test.util.ReflectionTestUtils.getField(ui, "modelLabel");
			assertThat(label).isInstanceOfSatisfying(java.util.function.Supplier.class,
					supplier -> assertThat(supplier.get()).isEqualTo(ModelDisplay.label(context.getBean(Agent.class))));
			assertThat(this.home.resolve("auth")).doesNotExist();
		}
	}
}
