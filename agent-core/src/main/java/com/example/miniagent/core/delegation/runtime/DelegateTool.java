package com.example.miniagent.core.delegation.runtime;

import java.util.function.Supplier;
import com.example.miniagent.core.model.ToolContextKeys;
import com.example.miniagent.core.run.RunContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Synchronous parent delegation; the official advisor still owns each model loop. */
public final class DelegateTool implements ToolCallback {
	private final Supplier<DelegationController> controller;
	public DelegateTool(Supplier<DelegationController> controller) {
		this.controller = controller;
	}
	@Override public ToolDefinition getToolDefinition() {
		return ToolDefinition.builder().name("delegate").description(
				"Run scoped children synchronously. COMPLETE requires real role evidence; unanswered approvals remain pending.")
				.inputSchema(DelegationInput.SCHEMA).build();
	}
	@Override public String call(String input) { throw new IllegalStateException("Bound parent execution required"); }
	@Override public String call(String input, ToolContext tools) {
		var parent = parent(tools);
		var cwd = parent.turnContext().cwd();
		return DelegationInput.outcome(this.controller.get().execute(parent.turnContext(),
				DelegationInput.parse(input).plan(cwd), DelegationInput.authority(
						com.example.miniagent.core.execution.ToolJournalInterceptor.capturedTools(parent), cwd),
				parent.spec().budget(), parent::usage));
	}
	static RunContext parent(ToolContext tools) {
		var context = RunContext.fromToolContext(tools).orElseThrow(() -> new IllegalStateException("Managed parent required"));
		if (context.spec().childId() != null || tools.getContext().get(ToolContextKeys.TURN_CONTEXT) != context.turnContext())
			throw new IllegalStateException("Parent-only operation");
		return context;
	}
}
