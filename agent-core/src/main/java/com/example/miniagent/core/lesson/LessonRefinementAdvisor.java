package com.example.miniagent.core.lesson;

import java.util.LinkedHashMap;
import java.util.List;
import com.example.miniagent.core.ToolInterceptor;
import com.example.miniagent.core.model.ToolContextKeys;
import com.example.miniagent.core.model.TurnContext;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.Ordered;

/** Request-only tool removal before the existing loop and again at its model boundary. */
public final class LessonRefinementAdvisor implements CallAdvisor {
	static final String KEY = "agent.lessons.refinement";
	static final class Capability {
		final TurnContext turn;
		Capability(TurnContext turn) { this.turn = turn; }
	}
	private final int order;
	public LessonRefinementAdvisor(int order) { this.order = order; }
	@Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		if (!(request.context().get(KEY) instanceof Capability capability)
				|| request.context().get(ToolContextKeys.TURN_CONTEXT) != capability.turn)
			throw new IllegalStateException("Captured refinement capability required");
		if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options))
			throw new IllegalStateException("Refinement requires tool-aware options");
		var context = new LinkedHashMap<String, Object>();
		if (options.getToolContext() != null) context.putAll(options.getToolContext());
		context.put(KEY, capability);
		// mutate() preserves provider-specific selected options (including reasoning effort).
		var stripped = options.mutate().toolCallbacks(List.of()).toolContext(context).build();
		return chain.nextCall(request.mutate()
				.prompt(new Prompt(request.prompt().getInstructions(), stripped)).build());
	}
	@Override public int getOrder() { return order; }
	@Override public String getName() { return "LessonRefinementAdvisor-" + order; }

	/** Defense at actual callback execution, including discovered dynamic tools. */
	public static final class EffectGuard implements ToolInterceptor {
		@Override public String intercept(ToolInvocation invocation, Chain chain) {
			if (invocation.context() != null && invocation.context().getContext().get(KEY) instanceof Capability)
				throw new IllegalStateException("Tools are disabled for lesson refinement");
			return chain.proceed(invocation);
		}
		@Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
	}
}
