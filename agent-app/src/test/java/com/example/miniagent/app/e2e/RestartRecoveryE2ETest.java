package com.example.miniagent.app.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import static com.example.miniagent.app.e2e.AppProcess.JSON;
import static com.example.miniagent.app.e2e.AppProcess.is;
import static org.assertj.core.api.Assertions.assertThat;

/** S15 and S17: durable run and goal state that a later process, not the one that created it, has to settle. */
class RestartRecoveryE2ETest {
	@TempDir Path directory;

	@AfterEach void stop() throws Exception { AppProcess.killAll(); }

	@Test void deferredApprovalIsLostWithItsProcessAndOnlyAnExplicitResumeSettlesTheRun() throws Exception {
		var layout = Layout.under(this.directory);
		Path file = layout.work().resolve("s.txt");
		Files.writeString(file, "before");

		var first = AppProcess.repl(layout, List.of());
		first.line("/run start !tool write_file {\"path\":\"s.txt\",\"content\":\"after\"}");
		var waiting = first.await(Pattern.compile("run=(\\S+) .*state=WAITING_APPROVAL"));
		String run = waiting.group(1);
		String approval = first.await(Pattern.compile("pending approval (\\S+) attempt=\\S+ tool=write_file")).group(1);
		first.closeInput();
		assertThat(first.awaitExit()).as("a piped REPL defers the approval and reports the run as not completed").isEqualTo(1);
		assertThat(Files.readString(file)).isEqualTo("before");

		var second = AppProcess.repl(layout, List.of());
		second.line("/run show " + run);
		second.await(Pattern.compile("run=" + run + " .*state=INTERRUPTED reason=PROCESS_LOST"));
		second.line("/run approve " + run + " " + approval);
		second.await(Pattern.compile("No matching pending approval"));
		second.line("/run resume " + run);
		second.await(Pattern.compile("run=" + run + " .*state=COMPLETED"));
		second.await(Pattern.compile("USER {6}Explicitly resume the interrupted user goal"));
		second.line("/changes");
		second.await(Pattern.compile("journal=CLEAN recoveryRequired=false"));
		second.closeInput();
		assertThat(second.awaitExit()).as("the refused approve keeps the piped exit code at 1").isEqualTo(1);

		assertThat(second.stdout()).doesNotContain("pending approval " + approval, "UNKNOWN attempt=")
				.as("pinned: the resumed attempt does not replay the deferred write as a new proposal")
				.doesNotContainPattern("(?m)change=\\S+ status=");
		assertThat(Files.readString(file)).isEqualTo("before");
	}

	@Test void pendingGoalSurvivesARestartAndIsCancelledWithoutEverCreatingItsRun() throws Exception {
		var layout = Layout.under(this.directory);
		Files.writeString(layout.work().resolve("answer.txt"), "READY\n");

		var first = AppProcess.rpc(layout, List.of());
		String submit = first.command("/goal submit " + readyGoal());
		assertThat(is(first.completion(submit), "status", "completed")).isTrue();
		var pending = Pattern.compile("goal=(\\S+) run=(\\S+) state=PENDING").matcher(first.outputs(submit).getFirst());
		assertThat(pending.find()).isTrue();
		String goal = pending.group(1);
		String run = pending.group(2);
		first.shutdown();
		assertThat(first.awaitExit()).isZero();

		var second = AppProcess.rpc(layout, List.of());
		assertThat(outputs(second, "/goal list")).anySatisfy(line -> assertThat(line)
				.startsWith("goal=" + goal + " run=" + run + " state=PENDING"));
		assertThat(outputs(second, "/goal cancel " + goal)).singleElement().satisfies(line -> assertThat(line)
				.contains("state=CANCELLED reason=CANCEL_REQUESTED steps=0/"));
		assertThat(outputs(second, "/goal show " + goal)).singleElement().satisfies(line -> assertThat(line)
				.contains("state=CANCELLED reason=CANCEL_REQUESTED"));
		assertThat(outputs(second, "/runs")).noneSatisfy(line -> assertThat(line).contains("run=" + run));
		second.shutdown();
		assertThat(second.awaitExit()).isZero();
		assertThat(layout.state().resolve("runs").resolve(run)).as("no gate or model ever ran for it").doesNotExist();
	}

	private static String readyGoal() {
		ObjectNode gate = JSON.createObjectNode();
		gate.putArray("argv").add("/usr/bin/grep").add("-q").add("^READY$").add("answer.txt");
		gate.put("timeoutMillis", 30000).put("maxOutputBytes", 4096);
		ObjectNode goal = JSON.createObjectNode().put("goal", "answer.txt must be the single line READY.").put("maxSteps", 2);
		goal.putArray("gates").add(gate);
		return JSON.writeValueAsString(goal);
	}

	private static List<String> outputs(AppProcess app, String command) {
		String id = app.command(command);
		assertThat(is(app.completion(id), "status", "completed")).as(command).isTrue();
		return app.outputs(id);
	}
}
