package com.example.miniagent.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingPipelineTest {

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void deltaTraversesTheActualToolAdvisorBeforeProviderCompletion(boolean requestTool) {
		var visibleDelta = new CountDownLatch(1);
		var terminal = new AtomicInteger();
		var probe = new Probe(terminal);
		ChatModel model = new ChatModel() {
			@Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
			@Override public ToolCallingChatOptions getOptions() {
				return ToolCallingChatOptions.builder().model("pipeline-fixture").build();
			}
			@Override public Flux<ChatResponse> stream(Prompt prompt) {
				if (prompt.getInstructions().getLast() instanceof ToolResponseMessage) {
					return Flux.just(response(new AssistantMessage("FINAL")));
				}
				return Flux.create(sink -> {
					sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage("VISIBLE_DELTA")))));
					try {
						if (!visibleDelta.await(10, TimeUnit.SECONDS))
							throw new IllegalStateException("Provider completion requires a visible UI delta");
						terminal.incrementAndGet();
						AssistantMessage completed = requestTool
								? AssistantMessage.builder().content("").toolCalls(List.of(
										new AssistantMessage.ToolCall("call", "function", "probe", "{}"))).build()
								: new AssistantMessage("");
						sink.next(response(completed));
						sink.complete();
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						sink.error(ex);
					}
				});
			}
		};
		var provider = MethodToolCallbackProvider.builder().toolObjects(probe).build();
		var tools = provider.getToolCallbacks();
		var client = ChatClient.builder(model).defaultToolCallbacks(provider).build();
		var agent = new Agent(client, new AgentSession(), Path.of("."), "",
				List.of(tools), List.of(), List.of(), List.of(), 0);
		var pieces = new ArrayList<String>();
		var reply = agent.stream("GO", text -> {
			pieces.add(text);
			if (text.contains("VISIBLE_DELTA")) {
				assertThat(terminal).hasValue(0);
				assertThat(probe.calls).hasValue(0);
				visibleDelta.countDown();
			}
		});
		assertThat(String.join("", pieces)).isEqualTo(reply.text()).contains("VISIBLE_DELTA");
		assertThat(probe.calls).hasValue(requestTool ? 1 : 0);
		assertThat(reply.usage()).isNotNull();
		if (requestTool) assertThat(reply.text()).contains("FINAL");
	}

	private static ChatResponse response(AssistantMessage message) {
		return new ChatResponse(List.of(new Generation(message, ChatGenerationMetadata.builder()
				.finishReason(message.hasToolCalls() ? "TOOL_CALLS" : "STOP").build())),
				ChatResponseMetadata.builder().usage(new DefaultUsage(3, 2)).build());
	}

	static final class Probe {
		final AtomicInteger calls = new AtomicInteger();
		final AtomicInteger terminal;
		Probe(AtomicInteger terminal) { this.terminal = terminal; }
		@Tool String probe() {
			assertThat(this.terminal).hasValue(1);
			this.calls.incrementAndGet();
			return "OK";
		}
	}
}
