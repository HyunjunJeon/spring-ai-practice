package com.example.miniagent.plugin.bash;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.miniagent.core.ToolContextKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** bash 도구가 끝날 때 프로세스를 남기지 않는지, 큰 출력을 앞부분만 돌려주는지. */
class BashToolTest {

	@TempDir
	Path work;

	@TempDir
	Path outside;

	private final ToolCallback bash = BashTool.create(Duration.ofSeconds(30), Duration.ofSeconds(300), 100, 1048576, null);

	private ToolContext context() {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString()));
	}

	@Test
	void invalidTimeoutOverridesNeverLaunch() {
		for (int timeout : new int[] { 0, -1, 301 }) {
			assertThatThrownBy(() -> this.bash.call(
					"{\"command\":\"touch launched\",\"timeoutSeconds\":" + timeout + "}", context()))
				.isInstanceOf(ToolExecutionException.class)
				.hasCauseInstanceOf(IllegalArgumentException.class);
			assertThat(this.work.resolve("launched")).doesNotExist();
		}
	}

	@Test
	void configurationRejectsInvalidLimits() {
		for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(1500),
				Duration.ofSeconds((long) Integer.MAX_VALUE + 1))) {
			assertThatThrownBy(() -> BashTool.create(invalid, Duration.ofSeconds(300), 100, 100, null))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> BashTool.create(Duration.ofSeconds(1), invalid, 100, 100, null))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> BashTool.create(Duration.ofSeconds(301), Duration.ofSeconds(300), 100, 100, null))
			.isInstanceOf(IllegalArgumentException.class);
		for (int invalid : new int[] { 0, -1 }) {
			assertThatThrownBy(() -> BashTool.create(Duration.ofSeconds(1), Duration.ofSeconds(1), 100, invalid, null))
				.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> BashTool.create(Duration.ofSeconds(1), Duration.ofSeconds(1), invalid, 100, null))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(this.work.resolve("launched")).doesNotExist();
		assertThatThrownBy(() -> new BashPluginAutoConfiguration().bashTool(Duration.ofSeconds(30),
				Duration.ofSeconds(300), 100, 0, "off", List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void exactMaximumTimeoutIsAccepted() {
		assertThat(this.bash.call("{\"command\":\"printf ok\",\"timeoutSeconds\":300}", context()))
			.contains("ok", "exit code 0");
	}

	@Test
	void rawByteLimitCountsMergedStderrAndUtf8() {
		ToolCallback limited = BashTool.create(Duration.ofSeconds(30), Duration.ofSeconds(300), 100, 8, null);
		assertThat(limited.call("{\"command\":\"printf 1234567 >&2\"}", context())).contains("exit code 0");
		assertThat(limited.call("{\"command\":\"printf '😀😀'\"}", context())).contains("😀😀", "exit code 0");
		assertThat(limited.call("{\"command\":\"printf '😀😀'; printf x >&2\"}", context()))
			.contains("output limit exceeded", "8 bytes");
	}

	@Test
	void characterLimitKeepsPrefixWithoutSplittingSurrogates() {
		ToolCallback limited = BashTool.create(Duration.ofSeconds(30), Duration.ofSeconds(300), 3, 100, null);
		assertThat(limited.call("{\"command\":\"printf ab\"}", context())).isEqualTo("\"ab\\n(exit code 0)\"");
		assertThat(limited.call("{\"command\":\"printf abc\"}", context())).isEqualTo("\"abc\\n(exit code 0)\"");
		assertThat(limited.call("{\"command\":\"printf abcd\"}", context())).contains("abc", "truncated");
		assertThat(limited.call("{\"command\":\"printf 'ab😀'\"}", context()))
			.isEqualTo("\"ab\\n... (truncated, 6 bytes total)\\n(exit code 0)\"");
	}

	@Test
	void floodBeyondPipeCapacityStopsAndLeavesNoSpool() throws Exception {
		ToolCallback limited = BashTool.create(Duration.ofSeconds(30), Duration.ofSeconds(300), 100, 131072, null);
		String output = limited.call("{\"command\":\"yes x >&2\"}", context());
		assertThat(output).contains("output limit exceeded", "131072 bytes").doesNotContain("cleanup failed");
		assertThat(output.length()).isLessThan(400);
		try (var files = Files.list(this.work)) {
			assertThat(files.toList()).isEmpty();
		}
	}

	@Test
	void outputIsAPipeRatherThanADiskSpool() {
		assertThat(this.bash.call("{\"command\":\"test -p /dev/fd/1 && printf pipe\"}", context()))
			.isEqualTo("\"pipe\\n(exit code 0)\"");
	}

	@Test
	void outputOverflowKillsReachableDescendants() throws Exception {
		ToolCallback limited = BashTool.create(Duration.ofSeconds(30), Duration.ofSeconds(300), 100, 1024, null);
		assertThat(limited.call(
				"{\"command\":\"sleep 60 & echo $! > child.pid; yes x; wait\"}", context()))
			.contains("output limit exceeded");
		assertGone(Long.parseLong(Files.readString(this.work.resolve("child.pid")).strip()));
	}

	@Test
	void inheritedPipeAfterShellExitCannotHang() throws Exception {
		Path fifo = this.work.resolve("ready.fifo");
		assertThat(new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor()).isZero();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		ProcessHandle child = null;
		try {
			ToolCallback limited = BashTool.create(Duration.ofSeconds(1), Duration.ofSeconds(300), 100, 1024, null);
			Future<String> call = executor.submit(() -> limited.call(
					"{\"command\":\"sleep 60 & echo $! > ready.fifo; exit 0\"}", context()));
			long pid = assertTimeoutPreemptively(Duration.ofSeconds(20),
					() -> Long.parseLong(Files.readString(fifo).strip()));
			child = ProcessHandle.of(pid).orElseThrow();
			assertThat(call.get(8, TimeUnit.SECONDS)).containsAnyOf("exit code 0", "timed out after 1s");
		}
		finally {
			if (child != null) {
				child.destroyForcibly();
				child.onExit().get(20, TimeUnit.SECONDS);
			}
			executor.shutdownNow();
			assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void timeoutKillsChildProcesses() throws Exception {
		String output = this.bash.call("{\"command\":\"sleep 60 & echo $! > child.pid; wait\",\"timeoutSeconds\":1}",
				context());

		assertThat(output).contains("timed out after 1s");
		assertGone(Long.parseLong(Files.readString(this.work.resolve("child.pid")).strip()));
	}

	@Test
	void interruptKillsTheShellAndItsChildren() throws Exception {
		Path fifo = this.work.resolve("child.fifo");
		assertThat(new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor()).isZero();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		AtomicBoolean interruptPreserved = new AtomicBoolean();
		try {
			Future<String> call = executor.submit(() -> {
				try {
					return this.bash.call("{\"command\":\"sleep 60 & echo $! > child.fifo; wait\"}", context());
				}
				finally {
					interruptPreserved.set(Thread.currentThread().isInterrupted());
				}
			});
			// FIFO 읽기는 셸이 PID 를 쓸 때까지 막힌다. 그 뒤에 인터럽트하면 sleep 이 확실히 떠 있다.
			long pid = assertTimeoutPreemptively(Duration.ofSeconds(20),
					() -> Long.parseLong(Files.readString(fifo).strip()));

			call.cancel(true);
			executor.shutdown();
			assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
			assertGone(pid);
			assertThat(interruptPreserved).isTrue();
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void longOutputIsCutToTheHead() {
		String output = this.bash.call("{\"command\":\"yes x | head -n 3000\"}", context());

		// 도구 결과는 DefaultToolCallResultConverter 가 JSON 문자열로 직렬화한다(README "2.0.1에서 직접 부딪힌 동작" 6번).
		// 앞부분 100자 = "x\n" 50번, 그 뒤에 전체 크기 안내가 붙는다.
		assertThat(output).isEqualTo("\"" + "x\\n".repeat(50) + "\\n... (truncated, 6000 bytes total)\\n(exit code 0)\"");
	}

	@Test
	void sandboxBlocksWritesOutsideTheWorkspace() throws Exception {
		assumeTrue(Files.isExecutable(BashSandbox.SANDBOX_EXEC));
		ToolCallback sandboxed = BashTool.create(Duration.ofSeconds(30), Duration.ofSeconds(300), 2000, 1048576,
				BashSandbox.workspaceWrite(List.of()));
		Path escape = this.outside.resolve("escape.txt");

		String output = sandboxed.call("{\"command\":\"echo in > inside.txt; echo out > " + escape + "\"}", context());

		assertThat(Files.readString(this.work.resolve("inside.txt"))).isEqualTo("in\n");
		assertThat(escape).doesNotExist();
		assertThat(output).contains("Operation not permitted");
	}

	@Test
	void sandboxAllowsConfiguredWritableRoots() throws Exception {
		assumeTrue(Files.isExecutable(BashSandbox.SANDBOX_EXEC));
		ToolCallback sandboxed = BashTool.create(Duration.ofSeconds(30), Duration.ofSeconds(300), 2000, 1048576,
				BashSandbox.workspaceWrite(List.of(this.outside)));
		Path allowed = this.outside.resolve("allowed.txt");

		sandboxed.call("{\"command\":\"echo ok > " + allowed + "\"}", context());

		assertThat(Files.readString(allowed)).isEqualTo("ok\n");
	}

	private static void assertGone(long pid) throws Exception {
		Optional<ProcessHandle> handle = ProcessHandle.of(pid);
		if (handle.isPresent()) {
			handle.get().onExit().get(20, TimeUnit.SECONDS);
		}
		assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).isNotEqualTo(Optional.of(true));
	}

}
