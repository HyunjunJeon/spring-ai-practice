package com.example.miniagent.plugin.permission;

import java.util.List;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.ToolInterceptor;
import com.example.miniagent.core.approval.ApprovalBroker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnBooleanProperty(name = "agent.plugins.permission.enabled", matchIfMissing = true)
public class PermissionPluginAutoConfiguration {

	/**
	 * 확인 없이 실행할 도구 목록. 그 밖의 도구는 모두 묻는다. {@code *} 로 끝나면 접두어 일치.
	 * 예: MCP 서버의 읽기 전용 도구를 묻지 않으려면 {@code read_file,list_dir,load_skill,spring_ai_mcp_client_server1_get_*}.
	 */
	@Bean
	ToolInterceptor confirmToolInterceptor(AgentConsole console, ApprovalBroker approvals,
			@Value("${agent.plugins.permission.auto-allow-tools:read_file,list_dir,load_skill}") List<String> autoAllowTools,
			@Value("${agent.plugins.permission.remember-exact-input:bash}") List<String> rememberExactInput,
			@Value("${agent.plugins.permission.allow-non-interactive:false}") boolean allowNonInteractive) {
		return new ConfirmToolInterceptor(autoAllowTools, rememberExactInput, console, allowNonInteractive, approvals);
	}

	@Bean
	AgentPlugin permissionPlugin(
			@Value("${agent.plugins.permission.auto-allow-tools:read_file,list_dir,load_skill}") List<String> autoAllowTools) {
		return AgentPlugin.of("permission", "확인 없이 허용: " + String.join(", ", autoAllowTools) + " (그 밖의 도구는 실행 전 확인)");
	}

}
