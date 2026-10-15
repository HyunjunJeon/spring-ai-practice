package com.example.miniagent.core.messaging;

import java.util.List;
import java.util.Map;
import com.example.miniagent.core.AgentAutoConfiguration;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.change.StagingToolInterceptor;
import com.example.miniagent.core.delegation.DefaultDelegationRuntime;
import com.example.miniagent.core.run.SideEffectClass;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Parent owns imports registration. Lazy suppliers avoid the Agent -> tools -> runtime cycle. */
@AutoConfiguration(after = AgentAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "agent.messaging.enabled", matchIfMissing = true)
public class MessagingAutoConfiguration {
	@Bean @ConditionalOnMissingBean
	MessageStore messageStore(AgentProperties properties) throws java.io.IOException {
		return new MessageStore(com.example.miniagent.core.StatePaths.home(properties.getHome()));
	}
	@Bean @ConditionalOnMissingBean @org.springframework.context.annotation.Lazy
	MessageService messageService(MessageStore store, DefaultDelegationRuntime runtime) {
		return new MessageService(store, runtime);
	}
	@Bean MessageTool sendMessageTool(ObjectProvider<MessageService> service) { return new MessageTool(service::getObject, true); }
	@Bean MessageTool readMessagesTool(ObjectProvider<MessageService> service) { return new MessageTool(service::getObject, false); }
	@Bean MessageCommand messageCommand(ObjectProvider<MessageService> service, ObjectProvider<com.example.miniagent.core.run.RunStore> runs,
			AgentProperties properties) {
		return new MessageCommand(service::getObject, runs::getObject, properties.getCwd());
	}
	@Bean StagingToolInterceptor.Policy messagingStagePolicy(
			@org.springframework.beans.factory.annotation.Qualifier("sendMessageTool") MessageTool sendMessageTool,
			@org.springframework.beans.factory.annotation.Qualifier("readMessagesTool") MessageTool readMessagesTool) {
		return () -> Map.of(
			"send_message", new StagingToolInterceptor.Declaration(
				SideEffectClass.definitionDigest(sendMessageTool.getToolDefinition()), StagingToolInterceptor.Kind.CONTROL, List.of()),
			"read_messages", new StagingToolInterceptor.Declaration(
				SideEffectClass.definitionDigest(readMessagesTool.getToolDefinition()), StagingToolInterceptor.Kind.CONTROL, List.of()));
	}
	@Bean SideEffectClass.Policy messagingReadPolicy(
			@org.springframework.beans.factory.annotation.Qualifier("readMessagesTool") MessageTool readMessagesTool) {
		return () -> Map.of("read_messages", SideEffectClass.definitionDigest(readMessagesTool.getToolDefinition()));
	}
}
