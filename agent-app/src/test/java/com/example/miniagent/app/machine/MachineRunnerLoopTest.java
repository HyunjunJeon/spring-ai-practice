package com.example.miniagent.app.machine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** The rpc stdin loop in MachineRunner.run(): what the process does with bytes it did not expect. */
@Timeout(20)
class MachineRunnerLoopTest {
	private static final String PROMPT = "{\"v\":1,\"id\":\"p\",\"method\":\"prompt\",\"text\":\"hello\"}\n";
	private static final String SHUTDOWN = "{\"v\":1,\"id\":\"s\",\"method\":\"shutdown\"}\n";

	@TempDir Path home;

	private MachineFixture rpc(InputStream input, Consumer<Prompt> before) throws IOException {
		return new MachineFixture(this.home, "rpc", input, before, List.of());
	}

	private static byte[] bytes(String... parts) {
		return String.join("", parts).getBytes(StandardCharsets.UTF_8);
	}

	@Test void readyFrameAnnouncesTheModeAndTheWireLimits() throws Exception {
		try (var fixture = rpc(MachineFixture.input(""), prompt -> {})) {
			fixture.runner.run(new DefaultApplicationArguments());
			assertThat(fixture.output.frames).hasSize(1);
			JsonNode ready = fixture.output.frames.getFirst();
			assertThat(ready.path("v").asInt()).isEqualTo(1);
			assertThat(ready.path("type").asString()).isEqualTo("ready");
			assertThat(ready.path("mode").asString()).isEqualTo("rpc");
			assertThat(ready.path("maxLineBytes").asInt()).isEqualTo(65536);
			assertThat(ready.path("maxTextBytes").asInt()).isEqualTo(32768);
			assertThat(ready.path("maxRequests").asInt()).isEqualTo(4096);
			for (String key : List.of("requestId", "executionId", "sessionId", "turnId"))
				assertThat(ready.get(key).isNull()).as(key).isTrue();
			assertThat(fixture.runner.getExitCode()).isZero();
		}
	}

	@Test void jsonModeAnnouncesItsOwnModeInTheReadyFrame() throws Exception {
		try (var fixture = new MachineFixture(this.home, "json", MachineFixture.input(""), prompt -> {}, List.of())) {
			fixture.runner.run(new DefaultApplicationArguments("hello"));
			assertThat(fixture.output.frames.getFirst().path("type").asString()).isEqualTo("ready");
			assertThat(fixture.output.frames.getFirst().path("mode").asString()).isEqualTo("json");
		}
	}

	@Test void shutdownWhileIdleEndsTheLoopWithoutReadingFurtherLines() throws Exception {
		var input = MachineFixture.ScriptedInput.idleAfter(bytes(SHUTDOWN, PROMPT));
		try (var fixture = rpc(input, prompt -> {})) {
			fixture.runner.run(new DefaultApplicationArguments());
			var acknowledgement = fixture.output.await("response", "s");
			assertThat(acknowledgement.path("status").asString()).isEqualTo("shutting_down");
			assertThat(input.unread()).as("the line after shutdown stays unread").isEqualTo(bytes(PROMPT).length);
			assertThat(fixture.output.frames.stream().map(frame -> frame.path("requestId").asString("")))
					.doesNotContain("p");
			assertThat(fixture.runner.getExitCode()).isZero();
		}
	}

	@Test void oversizeAndInvalidUtf8LinesEmitErrorsAndTheLoopContinuesWithTheNextLine() throws Exception {
		var script = new ByteArrayOutputStream();
		script.writeBytes(bytes("a".repeat(MachineProtocol.MAX_LINE_BYTES + 1), "\n"));
		script.writeBytes(new byte[] {(byte) 0xff, '\n'});
		script.writeBytes(bytes("{not json\n", PROMPT));
		try (var fixture = rpc(new ByteArrayInputStream(script.toByteArray()), prompt -> {})) {
			fixture.runner.run(new DefaultApplicationArguments());
			assertThat(fixture.output.errorCodes()).containsExactly("line_too_large", "invalid_utf8", "malformed_json");
			assertThat(fixture.output.ofType("error")).allSatisfy(error -> assertThat(error.path("requestId").isNull()).isTrue());
			assertThat(fixture.output.frames.stream().limit(5).map(frame -> frame.path("type").asString()).toList())
					.containsExactly("ready", "error", "error", "error", "response");
			assertThat(fixture.output.await("completion", "p").path("status").asString()).isEqualTo("completed");
			assertThat(fixture.runner.getExitCode()).isZero();
		}
	}

	@Test void stdinFailureWithoutActiveWorkEmitsInputFailedAndExitsOne() throws Exception {
		var input = MachineFixture.ScriptedInput.failingAfter(new byte[0], new CountDownLatch(0));
		try (var fixture = rpc(input, prompt -> {})) {
			fixture.runner.run(new DefaultApplicationArguments());
			assertThat(fixture.output.errorCodes()).containsExactly("input_failed");
			assertThat(fixture.output.ofType("error").getFirst().path("requestId").isNull()).isTrue();
			assertThat(fixture.output.ofType("completion")).isEmpty();
			assertThat(fixture.runner.getExitCode()).isEqualTo(1);
		}
	}

	@Test void stdinFailureCancelsTheActiveTurnAndExitsOne() throws Exception {
		var entered = new CountDownLatch(1);
		var input = MachineFixture.ScriptedInput.failingAfter(bytes(PROMPT), entered);
		try (var fixture = rpc(input, prompt -> {
			entered.countDown();
			try { new CountDownLatch(1).await(); }
			catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
		})) {
			fixture.runner.run(new DefaultApplicationArguments());
			assertThat(fixture.output.errorCodes()).containsExactly("input_failed");
			assertThat(fixture.output.await("completion", "p").path("status").asString()).isEqualTo("cancelled");
			assertThat(fixture.runner.getExitCode()).isEqualTo(1);
		}
	}
}
