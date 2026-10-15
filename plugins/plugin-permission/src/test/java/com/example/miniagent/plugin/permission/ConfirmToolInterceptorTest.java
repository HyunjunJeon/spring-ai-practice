package com.example.miniagent.plugin.permission;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.ToolInterceptor.ToolInvocation;
import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/** 허용 목록 방식의 권한 확인: 목록 밖의 도구는 모두 묻고, bash 의 [a]lways 는 같은 명령에만 적용된다. */
class ConfirmToolInterceptorTest {

	private final ByteArrayOutputStream output = new ByteArrayOutputStream();

	private ConfirmToolInterceptor interceptor(String answers, boolean interactive) {
		var console = new AgentConsole(new BufferedReader(new StringReader(answers)),
				new PrintStream(this.output, true, StandardCharsets.UTF_8), interactive);
		return new ConfirmToolInterceptor(List.of("read_file", "list_dir", "load_skill"), List.of("bash"), console,
				false);
	}

	private static String run(ConfirmToolInterceptor interceptor, String tool, String input) {
		var definition = ToolDefinition.builder().name(tool).description(tool).inputSchema("{}").build();
		return interceptor.intercept(new ToolInvocation(definition, input, null), invocation -> "ran");
	}

	@Test
	void autoAllowedToolsRunWithoutAsking() {
		assertThat(run(interceptor("", false), "read_file", "{}")).isEqualTo("ran");
	}

	@Test
	void toolsOutsideTheListAreDeniedWhenNobodyCanConfirm() {
		// 새 플러그인이나 MCP 서버의 도구도 따로 설정하지 않으면 확인 대상이다.
		assertThat(run(interceptor("", false), "spring_ai_mcp_client_demo_delete", "{}")).startsWith("Permission denied");
	}

	@Test
	void alwaysForBashCoversOnlyTheSameCommand() {
		ConfirmToolInterceptor interceptor = interceptor("a\nn\n", true);

		assertThat(run(interceptor, "bash", "{\"command\":\"ls\"}")).isEqualTo("ran");
		assertThat(run(interceptor, "bash", "{\"command\":\"ls\"}")).isEqualTo("ran");
		assertThat(run(interceptor, "bash", "{\"command\":\"rm -rf build\"}")).startsWith("Permission denied");
	}

	@Test
	void alwaysForOtherToolsCoversTheWholeTool() {
		ConfirmToolInterceptor interceptor = interceptor("a\n", true);

		assertThat(run(interceptor, "write_file", "{\"path\":\"a.txt\"}")).isEqualTo("ran");
		assertThat(run(interceptor, "write_file", "{\"path\":\"b.txt\"}")).isEqualTo("ran");
	}

}
