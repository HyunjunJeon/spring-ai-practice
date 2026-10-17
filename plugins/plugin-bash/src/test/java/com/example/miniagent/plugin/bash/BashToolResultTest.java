package com.example.miniagent.plugin.bash;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.miniagent.core.model.ToolContextKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** 모델이 결과 문자열만 보고 명령의 성패를 판단한다: 종료 코드, 병합된 출력, 입력·실행 실패, 기본 타임아웃. */
class BashToolResultTest {

	@TempDir
	Path work;

	private final ToolCallback bash = BashTool.create(Duration.ofSeconds(30), Duration.ofSeconds(300), 1000, 1048576, null);

	private ToolContext context() {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString()));
	}

	// 도구 결과는 JSON 문자열로 직렬화되어 모델과 테스트에 전달된다.
	@Test
	void nonZeroExitCodeIsReportedToTheModel() {
		assertThat(this.bash.call("{\"command\":\"printf boom; exit 7\"}", context()))
			.isEqualTo("\"boom\\n(exit code 7)\"");
		assertThat(this.bash.call("{\"command\":\"exit 5\"}", context())).isEqualTo("\"(no output, exit code 5)\"");
	}

	@Test
	void stderrIsMergedWithStdoutInWriteOrder() {
		assertThat(this.bash.call("{\"command\":\"printf out; printf err >&2; exit 3\"}", context()))
			.isEqualTo("\"outerr\\n(exit code 3)\"");
	}

	@Test
	void emptyOrBlankOutputReportsNoOutputWithTheStatus() {
		assertThat(this.bash.call("{\"command\":\"true\"}", context())).isEqualTo("\"(no output, exit code 0)\"");
		assertThat(this.bash.call("{\"command\":\"printf ' \\n\\t'\"}", context()))
			.isEqualTo("\"(no output, exit code 0)\"");
	}

	@Test
	void missingOrBlankCommandIsRejected() {
		for (String arguments : new String[] { "{\"command\":\"\"}", "{\"command\":\"   \"}", "{}" }) {
			assertThatThrownBy(() -> this.bash.call(arguments, context()))
				.isInstanceOf(ToolExecutionException.class)
				.cause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("command is required");
		}
	}

	@Test
	void missingWorkingDirectoryFailsWithCannotRunBash() {
		ToolContext missing = new ToolContext(Map.of(ToolContextKeys.CWD, this.work.resolve("missing").toString()));

		assertThatThrownBy(() -> this.bash.call("{\"command\":\"touch launched\"}", missing))
			.isInstanceOf(ToolExecutionException.class)
			.cause()
			.isInstanceOf(UncheckedIOException.class)
			.hasMessageStartingWith("Cannot run bash: ");
		assertThat(this.work.resolve("launched")).doesNotExist();
	}

	@Test
	void omittedTimeoutFallsBackToTheConfiguredDefault() throws Exception {
		ToolCallback shortDefault = BashTool.create(Duration.ofSeconds(1), Duration.ofSeconds(300), 100, 1024, null);

		String output = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> shortDefault.call(
				"{\"command\":\"sleep 60 & echo $! > child.pid; wait\"}", context()));

		assertThat(output).contains("timed out after 1s");
		assertGone(Long.parseLong(Files.readString(this.work.resolve("child.pid")).strip()));
	}

	@Test
	void shellExitWithBackgroundChildThatReleasedThePipeReportsExitCodeAndLeavesItRunning() throws Exception {
		assertThat(callWhileOrphanKeepsRunning("sleep 60 >/dev/null 2>&1 & echo $! > ready.fifo; exit 0"))
			.contains("exit code 0")
			.doesNotContain("timed out")
			.doesNotContain("cleanup failed");
	}

	@Test
	void shellExitWithBackgroundChildHoldingThePipeReportsExitCodeAndLeavesItRunning() throws Exception {
		assertThat(callWhileOrphanKeepsRunning("sleep 60 & echo $! > ready.fifo; exit 0"))
			.contains("exit code 0")
			.doesNotContain("timed out")
			.doesNotContain("cleanup failed");
	}

	@Test
	void shellExitWithBackgroundChildHoldingThePipeKeepsTheOutputWrittenBeforeExit() throws Exception {
		assertThat(callWhileOrphanKeepsRunning("sleep 60 & echo $! > ready.fifo; printf done; exit 0"))
			.isEqualTo("\"done\\n(exit code 0)\"");
	}

	/**
	 * 셸이 백그라운드 sleep 을 남기고 0 으로 끝나는 호출. 타임아웃이 30초라서 8초 안에 결과가 오면
	 * 타임아웃 경로가 아니라 셸 종료 경로로 끝난 것이다. 남은 sleep 은 도구가 건드리지 않아야 한다.
	 */
	private String callWhileOrphanKeepsRunning(String command) throws Exception {
		Path fifo = this.work.resolve("ready.fifo");
		assertThat(new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor()).isZero();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		ProcessHandle orphan = null;
		try {
			Future<String> call = executor.submit(() -> this.bash.call("{\"command\":\"" + command + "\"}", context()));
			long pid = assertTimeoutPreemptively(Duration.ofSeconds(20),
					() -> Long.parseLong(Files.readString(fifo).strip()));
			orphan = ProcessHandle.of(pid).orElseThrow();

			String output = call.get(8, TimeUnit.SECONDS);

			assertThat(orphan.isAlive()).isTrue();
			return output;
		}
		finally {
			if (orphan != null) {
				orphan.destroyForcibly();
				orphan.onExit().get(20, TimeUnit.SECONDS);
			}
			executor.shutdownNow();
			assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
		}
	}

	private static void assertGone(long pid) throws Exception {
		Optional<ProcessHandle> handle = ProcessHandle.of(pid);
		if (handle.isPresent()) {
			handle.get().onExit().get(20, TimeUnit.SECONDS);
		}
		assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).isNotEqualTo(Optional.of(true));
	}

}
