package com.example.miniagent.plugin.fakemodel;

import com.example.miniagent.core.AgentPlugin;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Fake factory는 항상 등록하고, 호환 singleton은 시작 선택이 fake일 때만 만든다.
 */
@AutoConfiguration
public class FakeModelAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(name = "fakeModelProvider")
	FakeModelProvider fakeModelProvider() {
		return new FakeModelProvider();
	}

	@Bean
	@ConditionalOnExpression("'${agent.models.default:}'.isBlank() || '${agent.models.default:}'.startsWith('fake/')")
	ChatModel scriptedChatModel() {
		return new ScriptedChatModel();
	}

	@Bean
	AgentPlugin fakeModelPlugin() {
		return AgentPlugin.of("fake-model", "가짜 ChatModel (spring.ai.model.chat=fake)");
	}

}
