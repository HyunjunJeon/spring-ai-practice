package com.example.miniagent.app.machine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.miniagent.app.MiniAgentApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class MachineProcessTest {

	@TempDir Path home;

	@Test
	void bootLogsStayOnStderrAndStdoutContainsOnlyProtocolFrames() throws Exception {
		var result = launch(this.home);
		assertThat(result.exit()).isZero();
		var json = JsonMapper.builder().build();
		var frames = result.stdout().lines().map(json::readTree).toList();
		assertThat(frames).isNotEmpty().allSatisfy(frame -> {
			assertThat(frame.isObject()).isTrue();
			assertThat(frame.path("v").asInt()).isEqualTo(1);
		});
		assertThat(frames).anySatisfy(frame -> {
			assertThat(frame.path("type").asString()).isEqualTo("completion");
			assertThat(frame.path("status").asString()).isEqualTo("completed");
		});
		assertThat(result.stderr()).isNotEmpty();
	}

	@Test
	void startupFailureCannotPutBootDiagnosticsOnProtocolStdout() throws Exception {
		var result = launch(this.home.resolve("missing-workspace"));
		assertThat(result.exit()).isNotZero();
		assertThat(result.stdout()).isEmpty();
		assertThat(result.stderr()).isNotEmpty();
	}

	private record Result(int exit, String stdout, String stderr) { }

	private Result launch(Path cwd) throws Exception {
		List<String> command = new ArrayList<>(List.of(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				MiniAgentApplication.class.getName(), "--agent.ui=json", "--agent.cwd=" + cwd,
				"--agent.home=" + this.home.resolve("state"), "--logging.level.root=info",
				"PROCESS_STREAM_SENTINEL"));
		Process process = new ProcessBuilder(command).start();
		try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
			var stdout = readers.submit(() -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
			var stderr = readers.submit(() -> new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
			try {
				assertThat(process.waitFor(45, TimeUnit.SECONDS)).isTrue();
				return new Result(process.exitValue(), stdout.get(5, TimeUnit.SECONDS), stderr.get(5, TimeUnit.SECONDS));
			}
			finally { process.destroyForcibly(); }
		}
	}
}
