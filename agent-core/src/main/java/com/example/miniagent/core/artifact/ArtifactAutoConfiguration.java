package com.example.miniagent.core.artifact;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.miniagent.core.AgentAutoConfiguration;
import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.run.SideEffectClass;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = AgentAutoConfiguration.class)
@EnableConfigurationProperties(ArtifactProperties.class)
@ConditionalOnBooleanProperty(name = "agent.artifacts.enabled", matchIfMissing = true)
public class ArtifactAutoConfiguration {
	@Bean
	@ConditionalOnMissingBean
	ArtifactStore artifactStore(AgentProperties agent, ArtifactProperties properties) throws java.io.IOException {
		return new ArtifactStore(com.example.miniagent.core.StatePaths.home(agent.getHome()), properties);
	}

	@Bean
	ArtifactTools artifactTools(ArtifactStore store) { return new ArtifactTools(store); }

	@Bean
	ToolCallbackProvider artifactToolsProvider(ArtifactTools tools) {
		// Keep callback identity stable, even if MethodToolCallbackProvider regenerates instances.
		ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
		return () -> callbacks.clone();
	}

	@Bean
	SideEffectClass.Policy artifactReadPolicy(
			@Qualifier("artifactToolsProvider") ToolCallbackProvider provider) {
		Map<String, String> declarations = Arrays.stream(provider.getToolCallbacks())
				.map(ToolCallback::getToolDefinition)
				.collect(Collectors.toUnmodifiableMap(definition -> definition.name(), SideEffectClass::definitionDigest));
		// No staging READ declaration: these tools have no filesystem path argument.
		return () -> declarations;
	}

	@Bean
	ArtifactResultInterceptor artifactResultInterceptor(ArtifactStore store, ArtifactProperties properties,
			@Qualifier("artifactReadPolicy") SideEffectClass.Policy policy) {
		return new ArtifactResultInterceptor(store, properties, policy.readOnlyDefinitions());
	}

	@Bean
	AgentPlugin artifactPlugin() {
		return AgentPlugin.of("artifacts", "Bounded session/workspace tool results, read_artifact, search_artifact");
	}
}
