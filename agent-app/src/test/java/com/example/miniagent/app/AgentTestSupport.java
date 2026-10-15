package com.example.miniagent.app;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import com.example.miniagent.core.AgentConsole;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 테스트 공통: 입력이 없는 비대화형 콘솔. 코어의 AgentConsole 빈은 {@code @ConditionalOnMissingBean} 이라
 * 이 빈이 있으면 물러난다. (자동 구성을 테스트에서 갈아끼우는 표준 방법)
 */
@TestConfiguration
class AgentTestSupport {

	static final ByteArrayOutputStream OUTPUT = new ByteArrayOutputStream();

	@Bean
	AgentConsole agentConsole() {
		return new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(OUTPUT, true, StandardCharsets.UTF_8), false);
	}

}
