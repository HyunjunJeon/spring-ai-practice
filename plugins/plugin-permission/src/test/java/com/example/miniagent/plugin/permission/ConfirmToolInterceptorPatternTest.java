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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/** 허용 목록의 {@code *} 로 끝나는 항목은 접두어 일치, 나머지는 정확히 같은 이름만 묻지 않고 실행된다. */
class ConfirmToolInterceptorPatternTest {

	private final ByteArrayOutputStream output = new ByteArrayOutputStream();

	/** 아무도 확인할 수 없는 콘솔: 목록에 없는 도구는 질문 없이 거부되므로 허용 여부가 결과 문자열로 드러난다. */
	private ConfirmToolInterceptor interceptor(String... autoAllowPatterns) {
		var console = new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(this.output, true, StandardCharsets.UTF_8), false);
		return new ConfirmToolInterceptor(List.of(autoAllowPatterns), List.of("bash"), console, false);
	}

	private static String run(ConfirmToolInterceptor interceptor, String tool) {
		var definition = ToolDefinition.builder().name(tool).description(tool).inputSchema("{}").build();
		return interceptor.intercept(new ToolInvocation(definition, "{}", null), invocation -> "ran");
	}

	@ParameterizedTest
	@ValueSource(strings = { "get_issue", "get_", "get_a_b_c" })
	void trailingStarAllowsEveryToolWithThePrefix(String tool) {
		assertThat(run(interceptor("get_*"), tool)).isEqualTo("ran");
	}

	@ParameterizedTest
	@ValueSource(strings = { "delete_issue", "forget_issue", "set_get_issue", "GET_issue", "get", "ge" })
	void trailingStarDeniesToolsOutsideThePrefix(String tool) {
		assertThat(run(interceptor("get_*"), tool)).isEqualTo(("Permission denied: '%s' was not executed because "
				+ "non-interactive mode (set agent.plugins.permission.allow-non-interactive=true). "
				+ "Do not retry it; ask the user how to proceed.").formatted(tool));
	}

	@Test
	void prefixOfAMcpServerAllowsItsReadOnlyToolsButNotItsMutatingOrForeignOnes() {
		ConfirmToolInterceptor interceptor = interceptor("read_file", "spring_ai_mcp_client_server1_get_*");

		assertThat(run(interceptor, "spring_ai_mcp_client_server1_get_issue")).isEqualTo("ran");
		assertThat(run(interceptor, "spring_ai_mcp_client_server1_delete_issue")).startsWith("Permission denied");
		assertThat(run(interceptor, "spring_ai_mcp_client_server2_get_issue")).startsWith("Permission denied");
	}

	@Test
	void exactEntryDoesNotMatchLongerNames() {
		ConfirmToolInterceptor interceptor = interceptor("read_file");

		assertThat(run(interceptor, "read_file")).isEqualTo("ran");
		assertThat(run(interceptor, "read_file_extra")).startsWith("Permission denied");
		assertThat(run(interceptor, "read_fil")).startsWith("Permission denied");
	}

	@Test
	void loneStarAllowsEveryTool() {
		ConfirmToolInterceptor interceptor = interceptor("*");

		assertThat(run(interceptor, "delete_everything")).isEqualTo("ran");
		assertThat(run(interceptor, "bash")).isEqualTo("ran");
		assertThat(run(interceptor, "x")).isEqualTo("ran");
	}

	@Test
	void starInTheMiddleIsALiteralNotAWildcard() {
		ConfirmToolInterceptor interceptor = interceptor("get_*_all");

		assertThat(run(interceptor, "get_x_all")).startsWith("Permission denied");
		assertThat(run(interceptor, "get_*_all")).isEqualTo("ran");
	}

	@Test
	void anyMatchingEntryOfTheListAllowsTheTool() {
		ConfirmToolInterceptor interceptor = interceptor("list_dir", "get_*", "search_*");

		assertThat(run(interceptor, "list_dir")).isEqualTo("ran");
		assertThat(run(interceptor, "get_user")).isEqualTo("ran");
		assertThat(run(interceptor, "search_content")).isEqualTo("ran");
		assertThat(run(interceptor, "write_file")).startsWith("Permission denied");
	}

	@Test
	void emptyListAllowsNothing() {
		assertThat(run(interceptor(), "read_file")).startsWith("Permission denied");
	}

	@Test
	void prefixMatchedToolRunsWithoutAskingOnAnInteractiveConsole() {
		// 질문하지 않았다는 것은 콘솔에 아무것도 출력되지 않았다는 뜻이다. 입력 스트림은 비어 있다.
		var console = new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(this.output, true, StandardCharsets.UTF_8), true);
		var interceptor = new ConfirmToolInterceptor(List.of("get_*"), List.of("bash"), console, false);

		assertThat(run(interceptor, "get_issue")).isEqualTo("ran");
		assertThat(this.output.toString(StandardCharsets.UTF_8)).isEmpty();
	}

}
