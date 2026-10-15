package com.example.miniagent.core.autoconfigure;

import java.io.IOException;
import java.util.*;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.execution.ToolJournalInterceptor;
import com.example.miniagent.core.run.DriftGate;
import com.example.miniagent.core.run.SideEffectClass;
import com.example.miniagent.core.run.ToolEvidenceStore;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Contributions are collected by AgentAutoConfiguration's existing ordered interceptor chain. */
@AutoConfiguration(afterName = "com.example.miniagent.core.AgentAutoConfiguration")
public class RunJournalAutoConfiguration {
	@Bean ToolJournalInterceptor toolJournalInterceptor() { return new ToolJournalInterceptor(); }

	@Bean @ConditionalOnMissingBean(ToolEvidenceStore.class)
	ToolEvidenceStore toolEvidenceStore(AgentProperties properties) throws IOException {
		return new ToolEvidenceStore(properties.getHome());
	}

	@Bean SideEffectClass.Policy builtInReadOnlyPolicy(ConfigurableListableBeanFactory beans) {
		// A name alone is not authority: require the exact trusted factory contribution.
		return () -> {
			Map<String, String> definitions = new HashMap<>();
			for (var contribution : Map.of(
					"fileToolsProvider", "com.example.miniagent.plugin.fs.FsPluginAutoConfiguration",
					"skillToolsProvider", "com.example.miniagent.plugin.skills.SkillsPluginAutoConfiguration").entrySet()) {
				if (!beans.containsBeanDefinition(contribution.getKey())) continue;
				var definition = beans.getBeanDefinition(contribution.getKey());
				if (!contribution.getKey().equals(definition.getFactoryMethodName())
						|| definition.getFactoryBeanName() == null) continue;
				Class<?> owner = beans.getType(definition.getFactoryBeanName());
				if (owner == null || !owner.getName().equals(contribution.getValue())) continue;
				var provider = beans.getBean(contribution.getKey(), ToolCallbackProvider.class);
				for (var callback : provider.getToolCallbacks()) {
					var tool = callback.getToolDefinition();
					if (Set.of("read_file", "list_dir", "load_skill").contains(tool.name()))
						definitions.put(tool.name(), SideEffectClass.definitionDigest(tool));
				}
			}
			return Map.copyOf(definitions);
		};
	}

	@Bean @ConditionalOnMissingBean(DriftGate.class)
	DriftGate driftGate(ToolEvidenceStore evidence, ObjectProvider<SideEffectClass.Policy> policies,
			Environment environment) {
		return new DriftGate(evidence, policies.orderedStream().toList(), () -> {
			Map<String, String> values = new TreeMap<>();
			// Explicit relevant public configuration, never enumerate environment/auth/provider keys.
			for (String key : List.of("agent.plugins.permission.enabled", "agent.plugins.permission.auto-allow-tools",
					"agent.plugins.permission.remember-exact-input",
					"agent.plugins.permission.allow-non-interactive", "agent.plugins.fs.allowed-roots",
					"agent.plugins.fs.max-read-bytes", "agent.plugins.bash.timeout",
					"agent.plugins.bash.max-timeout", "agent.plugins.bash.max-output-chars",
					"agent.plugins.bash.max-output-bytes", "agent.plugins.bash.sandbox",
					"agent.plugins.bash.sandbox-writable-roots", "agent.plugins.memory.enabled",
					"agent.plugins.memory.max-messages", "agent.plugins.memory.max-context-chars",
					"agent.plugins.memory.max-summary-request-chars", "agent.plugins.memory.max-summary-chars",
					"agent.plugins.memory.compaction", "agent.plugins.memory.inside-loop",
					"spring.ai.chat.client.tool-calling.advisor-order"))
				values.put(key, environment.getProperty(key, "<default>"));
			return values.toString();
		});
	}
}
