package com.example.miniagent.app.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.miniagent.app.MiniAgentApplication;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.e2efixture.FixtureLaunchPolicySource;
import com.example.miniagent.e2efixture.ScreenConsoleSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static com.example.miniagent.app.e2e.AppProcess.is;
import static org.assertj.core.api.Assertions.assertThat;

/** S5 (Tier I: staged change verified, applied, undone, then read after a restart) and S6 (Tier P refusals). */
class ChangeFlowE2ETest {
	private static final Pattern CHANGE = Pattern.compile("change=(\\S+) status=(\\S+) digest=(\\S+)");
	private static final String STAGE = "/run start !tool write_file {\"path\":\"f.txt\",\"content\":\"after\"}";

	@TempDir Path directory;

	@AfterEach void stop() throws Exception { AppProcess.killAll(); }

	@Test void stagedChangeIsVerifiedAppliedAndUndoneAndTheUndoSurvivesARestart() throws Exception {
		var layout = Layout.under(this.directory);
		Path file = layout.work().resolve("f.txt");
		Files.writeString(file, "before");
		String id;
		try (var context = start(layout)) {
			var handler = context.getBean(AgentInputHandler.class);
			var screen = context.getBean(ScreenConsoleSource.Screen.class);
			assertThat(handler.handle(STAGE)).as(screen.take()).isTrue();
			assertThat(Files.readString(file)).isEqualTo("before");
			screen.take();
			assertThat(handler.handle("/changes")).isTrue();
			var change = find(CHANGE, screen.take());
			id = change.group(1);
			String digest = change.group(3);
			assertThat(change.group(2)).isEqualTo("PENDING_APPROVAL");

			assertThat(handler.handle("/verify " + id + " [\"/usr/bin/grep\",\"-q\",\"after\",\"f.txt\"]"))
					.as(screen.take()).isTrue();
			screen.take();
			assertThat(handler.handle("/changes " + id)).isTrue();
			assertThat(screen.take()).contains("argv=[/usr/bin/grep, -q, after, f.txt]", " exit=0 ",
					"sourceCwd=" + layout.work());
			assertThat(Files.readString(file)).as("verification ran in a private clone").isEqualTo("before");

			screen.answer("exclusive " + id + " " + digest);
			var worker = Executors.newSingleThreadExecutor();
			try {
				var apply = worker.submit(() -> handler.handle("/apply " + id));
				var request = screen.awaitApproval();
				assertThat(request.diffDigest()).isEqualTo(digest);
				assertThat(context.getBean(ApprovalBroker.class).respond(request, ApprovalDecision.APPROVED)).isTrue();
				boolean applied = apply.get(AppProcess.FRAME_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
				String printed = screen.take();
				assertThat(applied).as(printed).isTrue();
				assertThat(last(CHANGE, printed).group(2)).isEqualTo("APPLIED");
			}
			finally {
				worker.shutdownNow();
				assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
			}
			assertThat(Files.readString(file)).isEqualTo("after");

			screen.answer("exclusive " + id + " " + digest);
			assertThat(handler.handle("/undo " + id)).as(screen.take()).isTrue();
			assertThat(Files.readString(file)).isEqualTo("before");
		}
		try (var context = start(layout)) {
			var screen = context.getBean(ScreenConsoleSource.Screen.class);
			assertThat(context.getBean(AgentInputHandler.class).handle("/changes " + id)).isTrue();
			assertThat(find(CHANGE, screen.take()).group(2)).as("UNDONE is read back from the journal").isEqualTo("UNDONE");
		}
		assertThat(Files.readString(file)).isEqualTo("before");
	}

	@Test void unattendedApplyIsRefusedByThePipedReplAndByRpc() throws Exception {
		var layout = Layout.under(this.directory);
		Path file = layout.work().resolve("f.txt");
		Files.writeString(file, "before");
		var autoWrite = List.of("--agent.plugins.permission.auto-allow-tools=write_file");

		var repl = AppProcess.repl(layout, autoWrite);
		repl.line(STAGE);
		repl.await(Pattern.compile("run=\\S+ .*state=COMPLETED"));
		repl.line("/changes");
		var change = repl.await(CHANGE);
		String id = change.group(1);
		assertThat(change.group(2)).isEqualTo("PENDING_APPROVAL");
		repl.line("/apply " + id);
		repl.await(Pattern.compile("approval=DEFERRED"));
		repl.line("/changes");
		assertThat(repl.await(CHANGE).group()).startsWith("change=" + id + " status=PENDING_APPROVAL");
		repl.closeInput();
		assertThat(repl.awaitExit()).isEqualTo(1);
		assertThat(Files.readString(file)).isEqualTo("before");

		var rpc = AppProcess.rpc(layout, autoWrite);
		String apply = rpc.command("/apply " + id);
		rpc.awaitFrame(frame -> is(frame, "requestId", apply) && is(frame, "code", "command_not_allowed"));
		String changes = rpc.command("/changes");
		assertThat(is(rpc.completion(changes), "status", "completed")).isTrue();
		assertThat(rpc.outputs(changes)).anySatisfy(line -> assertThat(line).startsWith("change=" + id + " status=PENDING_APPROVAL"));
		rpc.shutdown();
		assertThat(rpc.awaitExit()).isZero();
		assertThat(Files.readString(file)).isEqualTo("before");
	}

	/** Tier I restart: a new context with the same arguments against the same workspace and home. */
	private static ConfigurableApplicationContext start(Layout layout) {
		return new SpringApplicationBuilder(MiniAgentApplication.class)
				.sources(ScreenConsoleSource.class, FixtureLaunchPolicySource.class)
				.run("--agent.ui=plain", "--agent.repl.enabled=false", "--agent.cwd=" + layout.work(),
						"--agent.home=" + layout.state(), "--agent.plugins.trace.level=off",
						"--agent.plugins.memory.compaction=false", "--agent.plugins.bash.enabled=false",
						"--agent.plugins.permission.auto-allow-tools=write_file",
						"--agent.codex.codex-home=" + layout.state().resolve("codex"),
						"--agent.claude.auth-store=" + layout.state().resolve("auth/claude.json"));
	}

	private static MatchResult find(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		assertThat(matcher.find()).as(text).isTrue();
		return matcher.toMatchResult();
	}

	private static MatchResult last(Pattern pattern, String text) {
		var matches = pattern.matcher(text).results().toList();
		assertThat(matches).as(text).isNotEmpty();
		return matches.getLast();
	}
}
