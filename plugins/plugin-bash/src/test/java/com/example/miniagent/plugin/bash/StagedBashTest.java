package com.example.miniagent.plugin.bash;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.miniagent.core.ToolContextKeys;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import static org.assertj.core.api.Assertions.*;

class StagedBashTest {
	@TempDir Path work;
	@TempDir Path state;
	@TempDir Path cache;
	private StagedWorkspace stage() throws Exception {
		ChatModel model = prompt -> new ChatResponse(List.of());
		var binding = new ModelBinding(UUID.randomUUID(), new ModelRef("fake", "test"), "a".repeat(64),
				"fake", model, ChatClient.builder(model).build());
		return StagedWorkspace.open(new FileChangeStore(this.state, this.work),
				new TurnContext("session", "turn", this.work.toRealPath(), binding, "run", "attempt"), Clock.systemUTC());
	}
	private ToolContext context(StagedWorkspace stage) {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString(), ToolContextKeys.STAGED_WORKSPACE, stage));
	}
	private ToolCallback bash(boolean supported) {
		return BashTool.create(Duration.ofSeconds(10), Duration.ofSeconds(10), 10000, 1048576, null, () -> supported);
	}
	private static String quote(Path path) { return "'" + path.toString().replace("'", "'\\''") + "'"; }
	private void refusal(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
		assertThatThrownBy(action).satisfies(ex -> {
			Throwable cause = ex;
			while (cause.getCause() != null) cause = cause.getCause();
			assertThat(cause).isInstanceOfSatisfying(BashSandbox.Refusal.class,
					r -> assertThat(r.code()).isEqualTo(BashSandbox.Code.UNSUPPORTED_SANDBOX));
		});
	}

	@Test void unsupportedSandboxRejectsBeforeRequestedCommandLaunch() throws Exception {
		try (var stage = stage()) {
			refusal(() -> bash(false).call("{\"command\":\"printf launched > launched\"}", context(stage)));
			assertThat(stage.root().resolve("launched")).doesNotExist();
			assertThat(this.work.resolve("launched")).doesNotExist();
		}
	}

	@Test void onlyExplicitCacheRootsReceiveManagedWriteAuthority() throws Exception {
		try (var stage = stage()) {
			if (!Files.isExecutable(BashSandbox.SANDBOX_EXEC)) {
				refusal(() -> bash(true).call("{\"command\":\"printf launched > launched\"}", context(stage)));
				return;
			}
			var tool = BashTool.create(Duration.ofSeconds(10), Duration.ofSeconds(10), 10000, 1048576, null,
					() -> true, List.of(this.cache));
			String args = tools.jackson.databind.json.JsonMapper.builder().build()
					.writeValueAsString(Map.of("command", "printf cache > " + quote(this.cache.resolve("artifact"))));
			String result = tool.call(args, context(stage));
			assertThat(this.cache.resolve("artifact")).as("Shell result: %s", result).exists();
			assertThat(Files.readString(this.cache.resolve("artifact"))).isEqualTo("cache");
			assertThatThrownBy(() -> BashSandbox.managedCommand("true", stage, List.of(this.work)))
					.isInstanceOfSatisfying(BashSandbox.Refusal.class,
							ex -> assertThat(ex.code()).isEqualTo(BashSandbox.Code.INVALID_CACHE_ROOT));
			assertThatThrownBy(() -> BashSandbox.managedCommand("true", stage, List.of(this.state)))
					.isInstanceOfSatisfying(BashSandbox.Refusal.class,
							ex -> assertThat(ex.code()).isEqualTo(BashSandbox.Code.INVALID_CACHE_ROOT));
		}
	}

	@Test void realSandboxConfinesStageTempAndFiltersEnvironmentWithoutStartupScripts() throws Exception {
		Path live = Files.writeString(this.work.resolve("source"), "live");
		Path outside = this.state.resolve("external-write");
		try (var stage = stage()) {
			if (!Files.isExecutable(BashSandbox.SANDBOX_EXEC)) {
				refusal(() -> bash(true).call("{\"command\":\"printf launched > launched\"}", context(stage)));
				assertThat(stage.root().resolve("launched")).doesNotExist();
				return;
			}
			Path startup = stage.root().resolve("startup");
			Files.writeString(stage.tempDirectory().resolve(".bash_profile"), "printf bad > " + quote(startup));
			Files.writeString(stage.tempDirectory().resolve(".bashrc"), "printf bad > " + quote(startup));
			String shell = "printf staged > source; printf temporary > \"$TMPDIR/scratch\"; "
					+ "(printf escaped > " + quote(live) + ") 2>/dev/null; "
					+ "(printf escaped > " + quote(outside) + ") 2>/dev/null; "
					+ "(printf secret > .env) 2>/dev/null; /usr/bin/env > environment.txt";
			String input = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("command", shell));
			bash(true).call(input, context(stage));
			assertThat(Files.readString(live)).isEqualTo("live");
			assertThat(outside).doesNotExist();
			assertThat(Files.readString(stage.root().resolve("source"))).isEqualTo("staged");
			assertThat(Files.readString(stage.tempDirectory().resolve("scratch"))).isEqualTo("temporary");
			assertThat(stage.root().resolve(".env")).doesNotExist();
			assertThat(startup).doesNotExist();
			var env = Files.readAllLines(stage.root().resolve("environment.txt")).stream()
					.map(line -> line.substring(0, line.indexOf('='))).toList();
			assertThat(env).contains("HOME", "TMPDIR", "PATH", "LANG", "LC_ALL")
					.allMatch(name -> List.of("HOME", "TMPDIR", "PATH", "LANG", "LC_ALL", "PWD", "SHLVL", "_").contains(name));
			assertThat(BashSandbox.managedEnvironment(stage)).containsOnlyKeys("HOME", "TMPDIR", "PATH", "LANG", "LC_ALL");
			assertThat(new FileChangeStore(this.state, this.work).read().changes()).hasSize(1);
		}
	}

	@Test void cancellationKillsManagedDescendantAndRetainsPartialStageProposal() throws Exception {
		try (var stage = stage()) {
			if (!Files.isExecutable(BashSandbox.SANDBOX_EXEC)) {
				refusal(() -> bash(true).call("{\"command\":\"printf launched > launched\"}", context(stage)));
				assertThat(stage.root().resolve("launched")).doesNotExist();
				return;
			}
			Path fifo = stage.tempDirectory().resolve("child.fifo");
			Process mkfifo = new ProcessBuilder("/usr/bin/mkfifo", fifo.toString()).start();
			assertThat(mkfifo.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS).exitValue()).isZero();
			var signal = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
				try { return Long.parseLong(Files.readString(fifo).strip()); }
				catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
			});
			var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
			var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
			try {
				String shell = "/bin/sleep 60 & printf retained > partial; echo $! > \"$TMPDIR/child.fifo\"; wait";
				String input = tools.jackson.databind.json.JsonMapper.builder().build()
						.writeValueAsString(Map.of("command", shell));
				var call = worker.submit(() -> {
					try { return bash(true).call(input, context(stage)); }
					finally { interrupted.set(Thread.currentThread().isInterrupted()); }
				});
				long pid = signal.get(10, java.util.concurrent.TimeUnit.SECONDS);
				call.cancel(true);
				worker.shutdown();
				assertThat(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
				var child = ProcessHandle.of(pid);
				if (child.isPresent()) child.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
				assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive)).isNotEqualTo(java.util.Optional.of(true));
				assertThat(interrupted).isTrue();
				assertThat(Files.readString(stage.root().resolve("partial"))).isEqualTo("retained");
				assertThat(this.work.resolve("partial")).doesNotExist();
				assertThat(new FileChangeStore(this.state, this.work).read().changes()).hasSize(1);
			}
			finally {
				worker.shutdownNow();
				assertThat(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
			}
		}
	}
}
