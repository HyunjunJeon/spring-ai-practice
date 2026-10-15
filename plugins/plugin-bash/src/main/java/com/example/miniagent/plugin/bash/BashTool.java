package com.example.miniagent.plugin.bash;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.example.miniagent.core.change.StagedWorkspace;
import com.example.miniagent.core.model.ToolContextKeys;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * 프로그래밍 방식 도구: {@link FunctionToolCallback} 빌더로 함수 하나를 도구로 만든다.
 *
 * <p>{@code @Tool} 과의 차이: 스키마는 입력 타입({@link Input} 레코드)에서 만들어지고,
 * 이름과 설명을 런타임에 정할 수 있다(설정값으로 타임아웃을 바꾸는 식).
 *
 * <p>시간 초과로 끝나든 인터럽트(TUI 의 Esc 취소, 종료)로 끝나든 셸과 그 자식 프로세스를 함께 정리한다.
 * {@code nohup}, {@code setsid} 처럼 스스로 떨어져 나간 프로세스는 여기서 잡을 수 없다.
 *
 * <p>{@link BashSandbox} 를 주면 macOS 샌드박스 안에서 실행해 작업 디렉터리 밖 쓰기를 OS 수준에서 막는다.
 */
public final class BashTool {

	private BashTool() {
	}

	/** 모델이 채울 인자. 레코드 컴포넌트가 JSON 스키마의 속성이 된다. */
	public record Input(@ToolParam(description = "Shell command to run with bash -lc") String command,
			@ToolParam(description = "Timeout in seconds", required = false) @Nullable Integer timeoutSeconds) {
	}

	/** @param sandbox null 이면 샌드박스 없이 실행한다. */
	public static ToolCallback create(Duration defaultTimeout, Duration maxTimeout, int maxOutputChars,
			int maxOutputBytes, @Nullable BashSandbox sandbox) {
		return create(defaultTimeout, maxTimeout, maxOutputChars, maxOutputBytes, sandbox,
				() -> java.nio.file.Files.isExecutable(BashSandbox.SANDBOX_EXEC));
	}

	static ToolCallback create(Duration defaultTimeout, Duration maxTimeout, int maxOutputChars,
			int maxOutputBytes, @Nullable BashSandbox sandbox, java.util.function.BooleanSupplier managedSupported) {
		return create(defaultTimeout, maxTimeout, maxOutputChars, maxOutputBytes, sandbox, managedSupported, List.of());
	}

	static ToolCallback create(Duration defaultTimeout, Duration maxTimeout, int maxOutputChars,
			int maxOutputBytes, @Nullable BashSandbox sandbox, java.util.function.BooleanSupplier managedSupported,
			List<java.nio.file.Path> managedCacheRoots) {
		var caches = List.copyOf(managedCacheRoots);
		int defaultSeconds = seconds(defaultTimeout);
		int maxSeconds = seconds(maxTimeout);
		if (defaultSeconds > maxSeconds || maxOutputChars <= 0 || maxOutputBytes <= 0) {
			throw new IllegalArgumentException("default timeout must not exceed max timeout; output limits must be positive");
		}
		String sandboxNote = (sandbox == null) ? ""
				: " File writes outside the working directory and temp directories are blocked by a sandbox "
						+ "(Operation not permitted); do not try to work around it.";
		return FunctionToolCallback.<Input, String>builder("bash",
				(input, toolContext) -> run(input, toolContext, defaultSeconds, maxSeconds, maxOutputChars,
						maxOutputBytes, sandbox, managedSupported, caches))
			.description("Run a shell command in the working directory and return its combined stdout/stderr "
					+ "and exit code. Default timeout " + defaultSeconds + "s, maximum " + maxSeconds
					+ "s. Output byte limit " + maxOutputBytes + "." + sandboxNote)
			.inputType(Input.class)
			.build();
	}

	private static int seconds(Duration duration) {
		if (duration.getNano() != 0 || duration.getSeconds() <= 0 || duration.getSeconds() > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("bash timeout must be positive whole seconds representable by int");
		}
		return (int) duration.getSeconds();
	}

	private static String run(@Nullable Input input, @Nullable ToolContext toolContext, int defaultTimeout,
			int maxTimeout, int maxOutputChars, int maxOutputBytes, @Nullable BashSandbox sandbox,
			java.util.function.BooleanSupplier managedSupported, List<java.nio.file.Path> caches) {
		if (input == null || input.command() == null || input.command().isBlank()) {
			throw new IllegalArgumentException("command is required");
		}
		int timeout = (input.timeoutSeconds() != null) ? input.timeoutSeconds() : defaultTimeout;
		if (timeout <= 0 || timeout > maxTimeout) {
			throw new IllegalArgumentException("timeoutSeconds must be between 1 and " + maxTimeout);
		}
		StagedWorkspace stage = StagedWorkspace.from(toolContext).orElse(null);
		File cwd = stage == null ? workingDirectory(toolContext) : stage.root().toFile();
		if (stage != null) {
			if (!managedSupported.getAsBoolean()) throw new BashSandbox.Refusal(BashSandbox.Code.UNSUPPORTED_SANDBOX, null);
			BashSandbox.verify(stage, caches);
		}
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
		Process process;
		try {
			List<String> command = stage != null ? BashSandbox.managedCommand(input.command(), stage, caches)
					: (sandbox != null) ? sandbox.command(input.command(), cwd)
					: List.of("bash", "-lc", input.command());
			var builder = new ProcessBuilder(command).directory(cwd).redirectErrorStream(true);
			if (stage != null) {
				builder.environment().clear();
				builder.environment().putAll(BashSandbox.managedEnvironment(stage));
			}
			process = builder.start();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot run bash: " + ex.getMessage(), ex);
		}
		Capture capture = new Capture(maxOutputChars, maxOutputBytes);
		CompletableFuture<Void> completed = new CompletableFuture<>();
		Thread reader = Thread.ofVirtual().name("bash-output").start(() -> {
			try {
				byte[] buffer = new byte[8192];
				int count;
				while ((count = process.getInputStream().read(buffer)) != -1) {
					if (!capture.append(buffer, count)) {
						completed.complete(null);
						return;
					}
				}
				// EOF alone is not completion: a command may close stdout and keep running.
				process.onExit().whenComplete((exited, failure) -> {
					if (failure == null) {
						completed.complete(null);
					}
					else {
						completed.completeExceptionally(failure);
					}
				});
			}
			catch (IOException ex) {
				completed.completeExceptionally(ex);
			}
		});
		String status;
		RuntimeException failure = null;
		boolean interrupted = false;
		boolean terminate = false;
		try {
			completed.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			terminate = capture.overflow();
			status = terminate ? "output limit exceeded (" + maxOutputBytes + " bytes)" : "exit code " + process.exitValue();
		}
		catch (TimeoutException ex) {
			terminate = true;
			status = "timed out after " + timeout + "s";
		}
		catch (InterruptedException ex) {
			interrupted = true;
			terminate = true;
			status = "interrupted";
			failure = new IllegalStateException("Interrupted", ex);
		}
		catch (ExecutionException ex) {
			terminate = true;
			status = "output reader failed";
			failure = new IllegalStateException("Cannot read bash output", ex.getCause());
		}
		String cleanupFailure = cleanup(process, reader, terminate);
		if (stage != null) {
			boolean pendingInterrupt = Thread.interrupted();
			try { stage.record(); }
			catch (IOException ex) {
				if (failure != null) failure.addSuppressed(ex);
				else failure = new UncheckedIOException(ex);
			}
			finally { if (pendingInterrupt) Thread.currentThread().interrupt(); }
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
		if (failure != null) {
			if (!cleanupFailure.isEmpty()) {
				failure.addSuppressed(new IllegalStateException(cleanupFailure));
			}
			throw failure;
		}
		if (!cleanupFailure.isEmpty()) {
			status += "; " + cleanupFailure;
		}
		String output = capture.output();
		return output.isBlank() ? "(no output, " + status + ")" : output + "\n(" + status + ")";
	}

	/** 하나의 5초 정리 예산. 자식을 깊이 순서로 종료한 뒤 셸을 종료한다. */
	private static String cleanup(Process process, Thread reader, boolean terminate) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		boolean interrupted = Thread.interrupted();
		List<ProcessHandle> descendants = terminate ? process.descendants()
			.sorted(Comparator.comparingInt(BashTool::depth).reversed()).toList() : List.of();
		boolean failed = false;
		try {
			for (ProcessHandle child : descendants) {
				child.destroyForcibly();
			}
			for (ProcessHandle child : descendants) {
				try {
					child.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
				}
				catch (InterruptedException ex) {
					interrupted = true;
				}
				catch (ExecutionException | TimeoutException ex) {
					failed = true;
				}
			}
			if (terminate) {
				process.destroyForcibly();
			}
			// Process pipe close can block behind a read held by a reparented descendant.
			CompletableFuture<Void> closed = new CompletableFuture<>();
			Thread.ofVirtual().name("bash-stream-close").start(() -> {
				try {
					process.getInputStream().close();
					process.getErrorStream().close();
					process.getOutputStream().close();
					closed.complete(null);
				}
				catch (IOException ex) {
					closed.completeExceptionally(ex);
				}
			});
			try {
				process.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
				closed.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
				reader.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
			}
			catch (InterruptedException ex) {
				interrupted = true;
				failed = true;
			}
			catch (ExecutionException | TimeoutException ex) {
				failed = true;
			}
			failed |= reader.isAlive() || process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive);
		}
		finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
		return failed ? "cleanup failed within 5s" : "";
	}

	private static int depth(ProcessHandle process) {
		int depth = 0;
		var parent = process.parent();
		while (parent.isPresent()) {
			depth++;
			parent = parent.get().parent();
		}
		return depth;
	}

	/** 원시 바이트는 모두 센다. 보관은 UTF-8 접두부에 필요한 유한한 양만 한다. */
	private static final class Capture {
		private final int maxChars;
		private final int maxBytes;
		private final int retainedLimit;
		private final ByteArrayOutputStream prefix = new ByteArrayOutputStream();
		private long total;

		Capture(int maxChars, int maxBytes) {
			this.maxChars = maxChars;
			this.maxBytes = maxBytes;
			this.retainedLimit = (int) Math.min(maxBytes, 4L * maxChars + 4);
		}

		synchronized boolean append(byte[] buffer, int count) {
			int accepted = (int) Math.min(count, Math.max(0, this.maxBytes - this.total));
			int retained = Math.min(accepted, this.retainedLimit - this.prefix.size());
			this.prefix.write(buffer, 0, retained);
			this.total += count;
			return !overflow();
		}

		synchronized boolean overflow() {
			return this.total > this.maxBytes;
		}

		synchronized String output() {
			String decoded = this.prefix.toString(StandardCharsets.UTF_8);
			int end = Math.min(this.maxChars, decoded.length());
			if (end > 0 && Character.isHighSurrogate(decoded.charAt(end - 1))) {
				end--;
			}
			boolean truncated = decoded.length() > end || this.total > this.prefix.size();
			return decoded.substring(0, end) + (truncated
					? "\n... (truncated, " + (overflow() ? "output limit exceeded" : this.total + " bytes total") + ")" : "");
		}
	}

	private static File workingDirectory(@Nullable ToolContext toolContext) {
		Object cwd = (toolContext != null) ? toolContext.getContext().get(ToolContextKeys.CWD) : null;
		return new File((cwd != null) ? cwd.toString() : System.getProperty("user.dir"));
	}

}
