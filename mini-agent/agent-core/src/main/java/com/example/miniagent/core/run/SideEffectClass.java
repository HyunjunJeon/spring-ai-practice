package com.example.miniagent.core.run;

import java.util.Map;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Trusted Java contributions only; arguments and model annotations never grant authority. */
public enum SideEffectClass {
	READ_ONLY, MUTATING;

	@FunctionalInterface
	public interface Policy {
		Map<String, String> readOnlyDefinitions();
	}

	public static String definitionDigest(ToolDefinition definition) {
		return RunRequestStore.digest((definition.name() + "\0" + definition.description()
				+ "\0" + definition.inputSchema()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}
}
