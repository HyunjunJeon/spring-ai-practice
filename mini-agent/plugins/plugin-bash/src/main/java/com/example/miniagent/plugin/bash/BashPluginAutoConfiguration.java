package com.example.miniagent.plugin.bash;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.example.miniagent.core.AgentPlugin;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;

/**
 * fs 플러그인은 {@code ToolCallbackProvider} 빈을, 이 플러그인은 {@code ToolCallback} 빈 하나를 등록한다.
 * 코어는 두 타입을 모두 모으므로 둘 다 동작한다.
 */
@AutoConfiguration
@ConditionalOnBooleanProperty(name = "agent.plugins.bash.enabled", matchIfMissing = true)
public class BashPluginAutoConfiguration {

	@Bean
	com.example.miniagent.core.change.VerificationRunner.LaunchPolicy verificationLaunchPolicy(
			@Value("${agent.changes.verification-cache-roots:}") List<String> configuredCaches) {
		return BashSandbox.verificationPolicy(configuredCaches.stream().filter(s -> !s.isBlank())
				.map(s -> Path.of(s.strip())).toList());
	}

	@Bean
	com.example.miniagent.core.change.StagingToolInterceptor.Policy stagedShellPolicy(ToolCallback bashTool,
			@Value("${agent.changes.shell-cache-roots:}") List<String> managedCaches) {
		List<Path> caches = managedCaches.stream().filter(s -> !s.isBlank()).map(s -> Path.of(s.strip())).toList();
		var definition = bashTool.getToolDefinition();
		return () -> java.util.Map.of(definition.name(),
				new com.example.miniagent.core.change.StagingToolInterceptor.Declaration(
						com.example.miniagent.core.run.SideEffectClass.definitionDigest(definition),
						com.example.miniagent.core.change.StagingToolInterceptor.Kind.SHELL, List.of(),
						stage -> BashSandbox.verify(stage, caches)));
	}

	@Bean
	ToolCallback bashTool(@Value("${agent.plugins.bash.timeout:30s}") Duration timeout,
			@Value("${agent.plugins.bash.max-timeout:300s}") Duration maxTimeout,
			@Value("${agent.plugins.bash.max-output-chars:20000}") int maxOutputChars,
			@Value("${agent.plugins.bash.max-output-bytes:1048576}") int maxOutputBytes,
			@Value("${agent.plugins.bash.sandbox:off}") String sandbox,
			@Value("${agent.plugins.bash.sandbox-writable-roots:}") List<String> writableRoots,
			@Value("${agent.changes.shell-cache-roots:}") List<String> managedCaches) {
		var caches = managedCaches.stream().filter(s -> !s.isBlank()).map(s -> Path.of(s.strip())).toList();
		return BashTool.create(timeout, maxTimeout, maxOutputChars, maxOutputBytes, sandbox(sandbox, writableRoots),
				() -> java.nio.file.Files.isExecutable(BashSandbox.SANDBOX_EXEC), caches);
	}

	@Bean
	AgentPlugin bashPlugin(@Value("${agent.plugins.bash.sandbox:off}") String sandbox) {
		return AgentPlugin.of("bash", "bash 명령 실행 도구 (샌드박스: " + sandbox.strip() + ")");
	}

	/**
	 * {@code off}(기본): 샌드박스 없음. {@code workspace-write}: 작업 디렉터리, 임시 폴더, {@code sandbox-writable-roots}
	 * 밖 쓰기를 막는다(macOS 전용). 기본이 off 인 이유: {@code ./mvnw} 처럼 {@code ~/.m2} 같은 홈 캐시에 쓰는 명령이 많아서,
	 * 켜면 그런 명령이 실패한다. 켤 때는 그런 경로를 {@code sandbox-writable-roots} 에 절대 경로로 넣는다.
	 */
	private static @Nullable BashSandbox sandbox(String mode, List<String> extraRoots) {
		return switch (mode.strip().toLowerCase(Locale.ROOT)) {
			case "off", "false", "" -> null;
			case "workspace-write" -> {
				List<Path> roots = new ArrayList<>(List.of(Path.of("/tmp"), Path.of(System.getProperty("java.io.tmpdir"))));
				extraRoots.stream().filter(root -> !root.isBlank()).map(root -> Path.of(root.strip())).forEach(roots::add);
				yield BashSandbox.workspaceWrite(roots);
			}
			default -> throw new IllegalArgumentException(
					"agent.plugins.bash.sandbox 는 off 또는 workspace-write 여야 합니다: " + mode);
		};
	}

}
