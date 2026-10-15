package com.example.miniagent.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ProviderAuth;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import com.example.miniagent.plugin.memory.JsonlChatMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = { "agent.repl.enabled=false", "agent.ui=repl",
		"agent.models.default=queue/gated", "agent.plugins.trace.level=off",
		"agent.plugins.memory.compaction=false", "agent.plugins.memory.inside-loop=false" })
@Import({ AgentTestSupport.class, UserQueueMemoryIntegrationTest.QueueModels.class })
class UserQueueMemoryIntegrationTest {

	@TempDir static Path work;

	@DynamicPropertySource
	static void paths(DynamicPropertyRegistry properties) {
		properties.add("agent.cwd", () -> work.toString());
		properties.add("agent.home", () -> work.resolve(".mini-agent").toString());
	}

		@Autowired AgentInputHandler inputs;
		@Autowired AgentSession session;
		@Autowired JsonlChatMemoryRepository history;
		@Autowired Fixture fixture;

		@Test
		void steeringAndFollowUpPersistExactlyOnceInOrderAcrossRealToolRounds() throws Exception {
			this.session.startNew();
			String id = this.session.id();
			String initial = "!tool list_dir {\"path\":\".\"}";
			try (var worker = Executors.newSingleThreadExecutor()) {
				var execution = worker.submit(() -> this.inputs.handle(initial));
				try {
					assertThat(this.fixture.entered.await(10, TimeUnit.SECONDS)).isTrue();
					var target = this.inputs.inputQueue().target().orElseThrow();
					assertThat(this.inputs.enqueue(target, "/steer STEERING_SENTINEL")).isTrue();
					assertThat(this.inputs.enqueue(target, "/follow-up FOLLOW_SENTINEL")).isTrue();
				}
				finally { this.fixture.release.countDown(); }
				assertThat(execution.get(10, TimeUnit.SECONDS)).isTrue();
			}
			var stored = this.history.read(id).messages();
			assertThat(stored.stream().filter(UserMessage.class::isInstance).map(Message::getText).toList())
				.containsExactly(initial, "STEERING_SENTINEL", "FOLLOW_SENTINEL");
			assertThat(stored.getLast()).isInstanceOf(org.springframework.ai.chat.messages.AssistantMessage.class);
			assertThat(this.fixture.requests).anySatisfy(request ->
					assertThat(request).contains("STEERING_SENTINEL"));
			assertThat(this.inputs.inputQueue().target()).isEmpty();
		}
	static final class Fixture {
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final List<List<String>> requests = new CopyOnWriteArrayList<>();
	}

	@TestConfiguration
	static class QueueModels {
		@Bean Fixture queueFixture() { return new Fixture(); }

		@Bean
		ModelProvider queueProvider(Fixture fixture) {
			return new ModelProvider() {
				@Override public String providerId() { return "queue"; }
				@Override public List<ModelEntry> models() {
					return List.of(new ModelEntry(new ModelRef("queue", "gated"), "gated", null));
				}
				@Override public ProviderAuth auth() { return () -> true; }
				@Override public ChatModel create(ModelRef ref, Map<String, String> options) {
					return new ScriptedChatModel() {
						@Override public Flux<ChatResponse> stream(Prompt prompt) {
							fixture.requests.add(prompt.getInstructions().stream().map(Message::getText).toList());
							if (fixture.entered.getCount() != 0) {
								fixture.entered.countDown();
								try {
									if (!fixture.release.await(10, TimeUnit.SECONDS))
										throw new IllegalStateException("Unreleased queue fixture");
								}
								catch (InterruptedException ex) {
									Thread.currentThread().interrupt();
									throw new java.util.concurrent.CancellationException();
								}
							}
							return super.stream(prompt);
						}
					};
				}
			};
		}
	}
}
