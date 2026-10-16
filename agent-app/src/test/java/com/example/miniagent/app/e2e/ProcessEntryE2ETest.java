package com.example.miniagent.app.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** S1, S2, S4: the real main() exit codes, piped REPL behaviour and the one-shot permission boundary. */
class ProcessEntryE2ETest {
	/** DEC private-mode sequences (alternate screen, cursor visibility) appear only when a full-screen TUI runs. */
	private static final String PRIVATE_MODE_ESCAPE = "\u001B[?";

	@TempDir Path directory;

	@AfterEach void stop() throws Exception { AppProcess.killAll(); }

	@Test void oneShotReplAndJsonExitCodesComeFromTheRealMain() throws Exception {
		var layout = Layout.under(this.directory);

		var hello = AppProcess.oneShot(layout, "repl", "hello", List.of());
		assertThat(hello.exit()).as(hello.stderr()).isZero();
		assertThat(hello.stdout()).contains("[fake] 모델이 받은 요청", "USER      hello");

		var unknown = AppProcess.oneShot(layout, "repl", "/no-such-command", List.of());
		assertThat(unknown.exit()).isEqualTo(1);
		assertThat(unknown.stdout()).contains("알 수 없는 명령: /no-such-command");

		var json = AppProcess.oneShot(layout, "json", "hello", List.of());
		assertThat(json.exit()).as(json.stderr()).isZero();
		assertThat(json.frames()).allSatisfy(frame -> assertThat(frame.path("v").asInt()).isEqualTo(1))
				.anySatisfy(frame -> {
					assertThat(AppProcess.is(frame, "type", "completion")).isTrue();
					assertThat(AppProcess.is(frame, "status", "completed")).isTrue();
				});

		var refused = AppProcess.oneShot(layout, "json", "/apply x", List.of());
		assertThat(refused.exit()).isEqualTo(1);
		var frames = refused.frames();
		assertThat(frames).first().satisfies(frame -> assertThat(AppProcess.is(frame, "type", "ready")).isTrue());
		assertThat(frames).anySatisfy(frame -> {
			assertThat(AppProcess.is(frame, "type", "error")).isTrue();
			assertThat(AppProcess.is(frame, "code", "command_not_allowed")).isTrue();
		}).noneSatisfy(frame -> assertThat(AppProcess.is(frame, "type", "completion")).isTrue());
	}

	@Test void pipedReplKeepsAnEarlierFailureInItsExitCodeAndNeverStartsTheTui() throws Exception {
		var layout = Layout.under(this.directory);
		Files.writeString(layout.work().resolve("seed-listing.txt"), "seed");
		var app = AppProcess.repl(layout, List.of());
		var banner = app.await(Pattern.compile("agent-forge {2}\\(.*session: (\\S+)\\)"));
		app.line("hello");
		app.await(Pattern.compile("USER {6}hello"));
		app.line("!tool list_dir {\"path\":\".\"}");
		app.await(Pattern.compile("\\[fake] 도구 'list_dir' 결과를 받았습니다"));
		app.await(Pattern.compile("seed-listing\\.txt"));
		app.line("/no-such-command");
		app.await(Pattern.compile("알 수 없는 명령: /no-such-command"));
		app.line("hello");
		app.await(Pattern.compile("USER {6}hello"));
		app.closeInput();

		assertThat(app.awaitExit()).as("a failed line is not lost at EOF or after a later success").isEqualTo(1);
		assertThat(banner.group(1)).isNotBlank();
		assertThat(app.stdout()).doesNotContain(PRIVATE_MODE_ESCAPE);
	}

	@Test void oneShotLegacyToolsAreDeniedUnlessNonInteractiveUseIsAllowed() throws Exception {
		var layout = Layout.under(this.directory);
		String write = "!tool write_file {\"path\":\"x.txt\",\"content\":\"x\"}";
		// The shell computes 42; the literal input never contains it, so "42" proves the command ran.
		String bash = "!tool bash {\"command\":\"echo E2E_BASH_$((6*7))\"}";
		String allow = "--agent.plugins.permission.allow-non-interactive=true";

		var deniedWrite = AppProcess.oneShot(layout, "repl", write, List.of());
		var deniedBash = AppProcess.oneShot(layout, "repl", bash, List.of());
		assertThat(layout.work().resolve("x.txt")).doesNotExist();
		assertThat(deniedWrite.stdout()).contains("non-interactive mode");
		assertThat(deniedBash.stdout()).contains("non-interactive mode").doesNotContain("E2E_BASH_42");
		// Pinned: the denial is a tool result the fake model answers, so the turn itself succeeds.
		assertThat(List.of(deniedWrite.exit(), deniedBash.exit())).containsExactly(0, 0);

		var allowedWrite = AppProcess.oneShot(layout, "repl", write, List.of(allow));
		var allowedBash = AppProcess.oneShot(layout, "repl", bash, List.of(allow));
		assertThat(Files.readString(layout.work().resolve("x.txt"))).isEqualTo("x");
		assertThat(allowedBash.stdout()).contains("E2E_BASH_42");
		assertThat(List.of(allowedWrite.exit(), allowedBash.exit())).containsExactly(0, 0);
	}
}
