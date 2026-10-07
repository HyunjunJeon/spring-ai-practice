package com.example.miniagent.core.delegation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.function.Supplier;
import com.example.miniagent.core.SlashCommand;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Model requests approval; only the shared console approval/exclusive path publishes. */
public final class ApplyStagedTool implements ToolCallback {
	private final Supplier<DelegationController> controller;
	private final Supplier<SlashCommand.CommandContext> console;
	public ApplyStagedTool(Supplier<DelegationController> controller, Supplier<SlashCommand.CommandContext> console) {
		this.controller = controller; this.console = console;
	}
	public record Input(String planId, String childId, UUID proposalId) {}
	@Override public ToolDefinition getToolDefinition() {
		return ToolDefinition.builder().name("apply_staged").description(
				"Request parent-only publication of an IMPLEMENT proposal. Requires exact user approval and exclusive ownership.")
				.inputSchema("""
						{"type":"object","additionalProperties":false,"required":["planId","childId","proposalId"],
						"properties":{"planId":{"type":"string"},"childId":{"type":"string"},"proposalId":{"type":"string"}}}
						""").build();
	}
	@Override public String call(String input) { throw new IllegalStateException("Bound parent execution required"); }
	@Override public String call(String input, ToolContext tools) {
		var parent = DelegateTool.parent(tools);
		var request = DelegationInput.JSON.readValue(input, Input.class);
		try {
			return this.controller.get().apply(parent.turnContext(), request.planId(), request.childId(),
					request.proposalId(), this.console.get()) ? "APPLIED" : "PENDING_APPROVAL";
		}
		catch (IOException ex) { throw new UncheckedIOException(ex); }
	}
}
