package com.example.miniagent.plugin.bash;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

import com.example.miniagent.core.AgentPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 샌드박스 설정값 해석: 오타나 지원하지 않는 호스트에서 샌드박스 없이 조용히 실행되면 안 된다. */
class BashPluginAutoConfigurationTest {

	/** 샌드박스가 있을 때만 도구 설명에 붙는 안내 문구(BashTool 의 sandboxNote). */
	private static final String SANDBOX_NOTE = "blocked by a sandbox";

	// SpringApplication 이 하는 것처럼 "30s" 같은 Duration 문자열을 변환할 수 있게 한다.
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withInitializer(context -> context.getBeanFactory()
			.setConversionService(ApplicationConversionService.getSharedInstance()))
		.withConfiguration(AutoConfigurations.of(BashPluginAutoConfiguration.class));

	private static ToolCallback bashTool(String sandbox) {
		return new BashPluginAutoConfiguration().bashTool(Duration.ofSeconds(30), Duration.ofSeconds(300), 1000,
				1048576, sandbox, List.of(), List.of());
	}

	private static boolean sandboxExecAvailable() {
		return Files.isExecutable(BashSandbox.SANDBOX_EXEC);
	}

	@ParameterizedTest
	@ValueSource(strings = { "off", "false", "", "   ", "OFF", " off ", "False" })
	void offFalseAndBlankRunWithoutSandbox(String mode) {
		assertThat(bashTool(mode).getToolDefinition().description()).doesNotContain(SANDBOX_NOTE);
	}

	@ParameterizedTest
	@ValueSource(strings = { "workspace-wirte", "on", "true", "read-only", "workspace_write" })
	void unknownModeFailsInsteadOfRunningUnsandboxed(String mode) {
		assertThatThrownBy(() -> bashTool(mode)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("agent.plugins.bash.sandbox")
			.hasMessageContaining(mode);
	}

	@Test
	void workspaceWriteSandboxesTheToolOnAMacHost() {
		assumeTrue(sandboxExecAvailable());

		assertThat(bashTool("workspace-write").getToolDefinition().description()).contains(SANDBOX_NOTE);
		assertThat(bashTool(" Workspace-Write ").getToolDefinition().description()).contains(SANDBOX_NOTE);
	}

	@Test
	void workspaceWriteFailsFastWithoutSandboxExec() {
		assumeFalse(sandboxExecAvailable());

		assertThatThrownBy(() -> bashTool("workspace-write")).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("sandbox-exec");
	}

	@Test
	void sandboxIsOffWhenTheModeIsNotConfigured() {
		this.runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(ToolCallback.class).getToolDefinition().description())
				.doesNotContain(SANDBOX_NOTE);
			assertThat(context.getBean(AgentPlugin.class).description()).contains("샌드박스: off");
		});
	}

	@Test
	void startupFailsOnAnUnknownConfiguredMode() {
		this.runner.withPropertyValues("agent.plugins.bash.sandbox=workspace-wirte").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("workspace-wirte");
		});
	}

	@Test
	void configuredWorkspaceWriteModeReachesTheToolOnAMacHost() {
		assumeTrue(sandboxExecAvailable());

		this.runner.withPropertyValues("agent.plugins.bash.sandbox=workspace-write").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(ToolCallback.class).getToolDefinition().description()).contains(SANDBOX_NOTE);
			assertThat(context.getBean(AgentPlugin.class).description()).contains("샌드박스: workspace-write");
		});
	}

}
