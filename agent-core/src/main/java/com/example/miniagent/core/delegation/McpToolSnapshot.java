package com.example.miniagent.core.delegation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.example.miniagent.core.run.SideEffectClass;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Frozen public definitions and executable callbacks, never dynamic request providers. */
public final class McpToolSnapshot {
	public record Entry(ToolDefinition definition, String digest, ToolCallback callback, ToolMetadata metadata) {}
	private final Map<String, Entry> entries;
	private final List<ToolCallback> callbacks;
	private final List<ToolCallbackProvider> providers;

	private McpToolSnapshot(List<ToolCallback> callbacks, List<ToolCallbackProvider> providers, Set<String> allowed) {
		this.callbacks = List.copyOf(callbacks);
		this.providers = List.copyOf(providers);
		Map<String, Entry> captured = new LinkedHashMap<>();
		for (ToolCallback callback : live()) {
			var definition = callback.getToolDefinition();
			if (!allowed.contains(definition.name())) continue;
			var frozen = ToolDefinition.builder().name(definition.name()).description(definition.description())
				.inputSchema(definition.inputSchema()).build();
			if (captured.putIfAbsent(frozen.name(),
					new Entry(frozen, SideEffectClass.definitionDigest(frozen), callback, callback.getToolMetadata())) != null) {
				throw new RolePolicy.Denied(RolePolicy.Code.INVALID_CLASSIFICATION, frozen.name());
			}
		}
		if (!captured.keySet().containsAll(allowed)) {
			throw new RolePolicy.Denied(RolePolicy.Code.TOOL_NOT_ALLOWED, "Missing requested tool");
		}
		this.entries = Map.copyOf(captured);
	}

	public static McpToolSnapshot capture(List<ToolCallback> callbacks, List<ToolCallbackProvider> providers,
			Set<String> allowed) {
		return new McpToolSnapshot(callbacks, providers, allowed);
	}
	public Map<String, Entry> entries() { return this.entries; }

	public void requireCurrent(String name) {
		Entry captured = this.entries.get(name);
		if (captured == null) throw new RolePolicy.Denied(RolePolicy.Code.TOOL_NOT_ALLOWED, name);
		var matching = live().stream().filter(callback -> callback.getToolDefinition().name().equals(name)).toList();
		if (matching.size() != 1 || matching.getFirst() != captured.callback() || !captured.digest().equals(
				SideEffectClass.definitionDigest(matching.getFirst().getToolDefinition()))) {
			throw new RolePolicy.Denied(RolePolicy.Code.TOOL_NOT_ALLOWED, "Tool definition drift: " + name);
		}
	}
	public void requireCurrent() { this.entries.keySet().forEach(this::requireCurrent); }

	private List<ToolCallback> live() {
		var all = new ArrayList<>(this.callbacks);
		this.providers.forEach(provider -> all.addAll(Arrays.asList(provider.getToolCallbacks())));
		return all;
	}
}
