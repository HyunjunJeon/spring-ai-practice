package com.example.miniagent.app.worker;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

class WorkerLaunchTest {
	@TempDir Path temporary;

	@Test
	void productionClasspathOmitsAllProjectTestOutputs() {
		String classpath = String.join(File.pathSeparator, "/repo/agent-app/target/test-classes",
				"/repo/agent-app/target/classes", "/repo/plugin/target/test-classes", "/m2/library.jar");
		assertThat(WorkerLaunch.productionClasspath(classpath)).isEqualTo(String.join(File.pathSeparator,
				"/repo/agent-app/target/classes", "/m2/library.jar"));
	}

	@Test
	void launchCapturesOnlyExplicitSafeCodexConfiguration() throws Exception {
		Path root = temporary.toRealPath();
		var files = new WorkerFiles(root, root.resolve("home"));
		var launch = new WorkerLaunch(new ModelSelection(new ModelRef("codex", "gpt-6.1-sol"),
				Map.of("effort", "high")), "codex-cli", "https://chatgpt.com/backend-api/codex",
				root.resolve("codex"), root, Duration.ofMinutes(5));
		var command = launch.command(files, UUID.randomUUID().toString());
		assertThat(command).startsWith("/usr/bin/nohup");
		assertThat(command).contains("--agent.worker.mode=true", "--agent.ui=repl", "--agent.repl.enabled=false",
				"--agent.models.default=codex/gpt-6.1-sol", "--agent.codex.reasoning-effort=high",
				"--agent.codex.auth=codex-cli", "--agent.codex.base-url=https://chatgpt.com/backend-api/codex",
				"--agent.codex.codex-home=" + root.resolve("codex"), "--spring.config.import=");
		assertThat(command).noneMatch(s -> s.contains("test-classes") || s.contains("api-key") || s.contains("token="));
	}

	@Test
	void unsupportedProviderAndCredentialBearingEndpointFailExplicitly() {
		assertThatThrownBy(() -> new WorkerLaunch(new ModelSelection(new ModelRef("fake", "fake-scripted")),
				"codex-cli", "https://chatgpt.com/backend-api/codex", temporary, temporary, Duration.ofMinutes(5)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new WorkerLaunch(new ModelSelection(new ModelRef("codex", "gpt-6.1-sol")),
				"auto", "https://chatgpt.com/backend-api/codex?token=secret", temporary, temporary, Duration.ofMinutes(5)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
