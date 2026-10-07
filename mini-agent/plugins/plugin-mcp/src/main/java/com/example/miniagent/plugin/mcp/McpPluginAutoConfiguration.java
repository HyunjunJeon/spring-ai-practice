package com.example.miniagent.plugin.mcp;

import java.util.List;

import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.SlashCommand;
import io.modelcontextprotocol.client.McpSyncClient;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 이 플러그인에는 도구 코드가 없다.
 *
 * <p>{@code spring-ai-starter-mcp-client} 가 {@code spring.ai.mcp.client.*} 설정을 읽어 MCP 서버에 연결하고
 * {@code SyncMcpToolCallbackProvider} 빈(= ToolCallbackProvider)을 등록한다. 코어는 ToolCallbackProvider 를
 * 모두 모으므로 MCP 서버의 도구가 자동으로 에이전트에 붙는다. 이 클래스는 상태를 보는 명령만 더한다.
 * 연결 설정 예시는 agent-app 의 application-mcp.yml.
 */
@AutoConfiguration(afterName = "org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration")
public class McpPluginAutoConfiguration {

	@Bean
	SlashCommand mcpCommand(ObjectProvider<List<McpSyncClient>> clients) {
		return SlashCommand.of("mcp", "연결된 MCP 서버와 도구", (args, ctx) -> {
			List<McpSyncClient> connected = clients.getIfAvailable(List::of);
			if (connected.isEmpty()) {
				ctx.console().println("연결된 MCP 서버가 없습니다. --spring.profiles.active=fake,mcp 로 실행해 보세요.");
				return;
			}
			for (McpSyncClient client : connected) {
				var info = client.getServerInfo();
				ctx.console().println("- " + ((info != null) ? info.name() + " " + info.version() : "(unknown)"));
				client.listTools().tools().forEach(tool -> ctx.console().info("    " + tool.name()));
			}
		});
	}

	@Bean
	AgentPlugin mcpPlugin(ObjectProvider<List<McpSyncClient>> clients) {
		return AgentPlugin.of("mcp", clients.getIfAvailable(List::of).size() + "개 MCP 서버 연결");
	}

}
