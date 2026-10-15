package com.example.miniagent.core.run;

import java.util.List;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@link DriftGate} 스냅숏이 에이전트에서 읽는 값. 구현은 {@code core.Agent} 다.
 *
 * <p>스냅숏 digest 는 이 네 값을 정해진 순서로 이어 만든다. 값이나 순서가 바뀌면 진행 중인 durable run 이
 * 재개할 때 구성 drift 로 보인다.
 */
public interface DriftSource {
	List<ToolDefinition> toolDefinitions();
	String systemPrompt();
	int toolCallingAdvisorOrder();
	List<Advisor> advisors();
}
