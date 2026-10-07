package com.example.miniagent.core.model;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/** Application-owned assembly of official clients with all tools and advisors. */
@FunctionalInterface
public interface AgentChatClientFactory {

	ChatClient create(ChatModel model);

	/** Optional explicit execution boundary; ordinary registry bindings are unchanged. */
	default ChatModel modelBoundary(ChatModel model) { return model; }

	/** Explicit assembly only: no parent defaults, dynamic providers or customizers. */
	record ScopedAssembly(String systemPrompt, java.util.List<org.springframework.ai.tool.ToolCallback> tools,
			java.util.List<org.springframework.ai.chat.client.advisor.api.Advisor> advisors, int toolOrder) {
		public ScopedAssembly {
			java.util.Objects.requireNonNull(systemPrompt);
			tools = java.util.List.copyOf(tools);
			advisors = java.util.List.copyOf(advisors);
		}
		public ChatClient create(ChatModel model) {
			if (model.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
					&& (options.getToolCallbacks() != null && !options.getToolCallbacks().isEmpty()
						|| options.getToolContext() != null && !options.getToolContext().isEmpty())) {
				throw new IllegalArgumentException("Scoped model must not own default tools or tool context");
			}
			var toolAdvisor = org.springframework.ai.chat.client.advisor.ToolCallingAdvisor.builder()
				.advisorOrder(this.toolOrder)
				.toolCallingManager(org.springframework.ai.model.tool.DefaultToolCallingManager.builder()
					.resolutionFallbackEnabled(false).build());
			return ChatClient.builder(model, io.micrometer.observation.ObservationRegistry.NOOP,
					null, null, toolAdvisor)
				.defaultSystem(this.systemPrompt).defaultTools(this.tools)
				.defaultAdvisors(this.advisors).build();
		}
	}
}
