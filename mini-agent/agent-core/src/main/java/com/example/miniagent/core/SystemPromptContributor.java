package com.example.miniagent.core;

import org.springframework.core.Ordered;

/**
 * 시스템 프롬프트에 한 섹션을 덧붙이는 플러그인 계약. (pi 의 context files / skill 목록에 해당)
 *
 * <p>에이전트 시작 시 한 번 호출되어 {@code ChatClient.Builder.defaultSystem(...)} 에 합쳐진다.
 * 요청마다 바뀌어야 하는 내용이라면 이 계약 대신 {@code Advisor} 로 프롬프트를 고쳐야 한다.
 */
public interface SystemPromptContributor extends Ordered {

	/** 덧붙일 텍스트. 빈 문자열이면 무시된다. */
	String contribute();

	@Override
	default int getOrder() {
		return 0;
	}

}
