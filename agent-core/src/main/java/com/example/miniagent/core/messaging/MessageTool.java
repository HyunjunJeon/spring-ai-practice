package com.example.miniagent.core.messaging;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Supplier;
import com.example.miniagent.core.delegation.TrustedMailboxTool;
import com.example.miniagent.core.model.ToolContextKeys;
import com.example.miniagent.core.run.RunContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;

/** Trusted mailbox capability. Model arguments never contain sender or parent identities. */
public final class MessageTool implements ToolCallback, TrustedMailboxTool {
	private final Supplier<MessageService> service;
	private final boolean send;
	public MessageTool(Supplier<MessageService> service, boolean send) { this.service = service; this.send = send; }
	@Override public ToolDefinition getToolDefinition() {
		return ToolDefinition.builder().name(send ? "send_message" : "read_messages")
			.description(send ? "Message an exact owned child thread. Parent dispatch requires an active managed parent; "
				+ "child replies queue for its parent. Retry only the same ID. UNCERTAIN must never be re-executed."
				: "Read at most 16 receipts from an exact owned child thread, including its CAS version.")
			.inputSchema(send
				? """
				{"type":"object","additionalProperties":false,"required":["plan","child","id","version","body"],
				 "properties":{"plan":{"type":"string"},"child":{"type":"string"},"id":{"type":"string"},
				 "version":{"type":"integer","minimum":0},"body":{"type":"string","maxLength":4096}}}
				"""
				: """
				{"type":"object","additionalProperties":false,"required":["plan","child","offset"],
				 "properties":{"plan":{"type":"string"},"child":{"type":"string"},"offset":{"type":"integer","minimum":0}}}
				""").build();
	}
	@Override public String call(String input) { throw new IllegalStateException("Captured managed context required"); }
	@Override public String call(String input, ToolContext tools) {
		var actor = RunContext.fromToolContext(tools).orElseThrow();
		if (tools.getContext().get(ToolContextKeys.TURN_CONTEXT) != actor.turnContext()
				|| RunContext.current().orElse(null) != actor
				|| com.example.miniagent.core.model.TurnContext.current().orElse(null) != actor.turnContext())
			throw new IllegalArgumentException("Exact captured message actor required");
		try {
			var args = MessageStore.JSON.readTree(input);
			String plan = required(args, "plan").asString(), child = required(args, "child").asString();
			Object result = send
				? service.get().send(actor, plan, child, required(args, "id").asString(),
					required(args, "version").asLong(), required(args, "body").asString())
				: service.get().read(actor, plan, child, required(args, "offset").asInt());
			return MessageStore.JSON.writeValueAsString(result);
		}
		catch (IOException ex) { throw new UncheckedIOException(ex); }
	}
	/** Tool arguments are not schema-validated before this call; name the absent key, never echo input. */
	private static JsonNode required(JsonNode args, String key) {
		JsonNode value = args.get(key);
		if (value == null) throw new IllegalArgumentException("Missing message argument: " + key);
		return value;
	}
}
