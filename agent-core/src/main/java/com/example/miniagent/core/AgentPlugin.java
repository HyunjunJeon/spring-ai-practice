package com.example.miniagent.core;

/**
 * 플러그인 설명자(descriptor).
 *
 * <p>플러그인이 실제로 기여하는 것은 아래의 "Spring AI 확장점" 빈들이다. 이 인터페이스는
 * {@code /plugins} 명령으로 "지금 어떤 플러그인이 붙어 있는지" 보여주기 위한 이름표일 뿐이다.
 *
 * <ul>
 *   <li>도구: {@link org.springframework.ai.tool.ToolCallbackProvider} 또는
 *       {@link org.springframework.ai.tool.ToolCallback} 빈</li>
 *   <li>요청/응답 가로채기: {@link org.springframework.ai.chat.client.advisor.api.Advisor} 빈</li>
 *   <li>도구 실행 가로채기: {@link ToolInterceptor} 빈</li>
 *   <li>시스템 프롬프트: {@link SystemPromptContributor} 빈</li>
 *   <li>REPL 명령: {@link SlashCommand} 빈</li>
 * </ul>
 */
public interface AgentPlugin {

	String id();

	String description();

	static AgentPlugin of(String id, String description) {
		return new SimpleAgentPlugin(id, description);
	}

	record SimpleAgentPlugin(String id, String description) implements AgentPlugin {
	}

}
