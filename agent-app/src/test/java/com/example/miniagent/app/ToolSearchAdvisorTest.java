package com.example.miniagent.app;

import java.nio.file.Path;

import com.example.miniagent.core.Agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 도구 검색은 기본 도구 루프를 하나 교체한다. 첫 모델 호출에는 검색 도구만 보인다.
 */
@SpringBootTest(properties = { "agent.repl.enabled=false", "agent.plugins.trace.level=off",
		"spring.ai.chat.client.tool-search-advisor.enabled=true" })
@Import(AgentTestSupport.class)
class ToolSearchAdvisorTest {

	@TempDir
	static Path workDir;

	@DynamicPropertySource
	static void paths(DynamicPropertyRegistry registry) {
		registry.add("agent.cwd", () -> workDir.toString());
		registry.add("agent.home", () -> workDir.resolve(".home").toString());
	}

	@Autowired
	Agent agent;

	@Test
	void replacesTheSingleToolAdvisorAndHidesConcreteTools() {
		assertThat(this.agent.toolCallingAdvisorName()).isEqualTo("ToolSearchToolCallingAdvisor");

		Agent.AgentReply reply = this.agent.run("안녕");
		String tools = reply.text().lines().filter(line -> line.contains("- 도구 ")).findFirst().orElse("");

		assertThat(tools).contains("toolSearchTool").doesNotContain("write_file");
	}

}
