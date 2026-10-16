package com.example.miniagent.app.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** S19, S3, S20: the JSONL protocol, legacy permission questions over RPC and session continuity across a restart. */
class RpcSessionE2ETest {
	@TempDir Path directory;

	@AfterEach void stop() throws Exception { AppProcess.killAll(); }

	@Test void protocolSessionCorrelatesFramesAndRefusesBadRequestsWithoutStoppingTheLane() throws Exception {
		var layout = Layout.under(this.directory);
		var app = AppProcess.rpc(layout, List.of());
		var ready = app.frames().getFirst();
		assertThat(AppProcess.is(ready, "mode", "rpc")).isTrue();
		assertThat(ready.path("maxLineBytes").asInt()).isEqualTo(65536);

		String hello = app.prompt("hello");
		var completion = app.completion(hello);
		assertThat(AppProcess.is(completion, "status", "completed")).isTrue();
		String session = completion.path("sessionAfter").asString();
		String execution = completion.path("executionId").asString();
		var correlated = app.frames(hello);
		assertThat(correlated).allSatisfy(frame -> assertThat(AppProcess.is(frame, "executionId", execution)).isTrue());
		assertThat(correlated).anySatisfy(frame -> assertThat(AppProcess.is(frame, "status", "accepted")).isTrue())
				.anySatisfy(frame -> {
					assertThat(AppProcess.is(frame, "event", "ModelBound")).isTrue();
					assertThat(AppProcess.is(frame, "provider", "fake")).isTrue();
					assertThat(AppProcess.is(frame, "eventSessionId", session)).isTrue();
				})
				.anySatisfy(frame -> assertThat(AppProcess.is(frame, "event", "TurnFinished")).isTrue());
		assertThat(app.streamed(hello)).contains("[fake] 모델이 받은 요청", "USER      hello");

		app.raw(AppProcess.JSON.writeValueAsString(AppProcess.JSON.createObjectNode()
				.put("v", 1).put("id", hello).put("method", "prompt").put("text", "again")));
		app.awaitFrame(frame -> AppProcess.is(frame, "code", "duplicate_id"));
		assertThat(error(app, app.prompt("/runs"))).isEqualTo("use_command_method");
		assertThat(error(app, app.command("/apply x"))).isEqualTo("command_not_allowed");
		app.raw("{not json");
		app.awaitFrame(frame -> AppProcess.is(frame, "code", "malformed_json"));
		String runs = app.command("/runs");
		assertThat(AppProcess.is(app.completion(runs), "status", "completed")).isTrue();
		assertThat(AppProcess.is(app.shutdown(), "status", "shutting_down")).isTrue();

		assertThat(app.awaitExit()).as(app.stderr()).isZero();
		// Every stdout line is a frame; refusals carried their code and never produced a completion.
		assertThat(app.frames()).allSatisfy(frame -> assertThat(frame.path("v").asInt()).isEqualTo(1));
		assertThat(app.frames()).filteredOn(frame -> AppProcess.is(frame, "type", "completion")).hasSize(2);
	}

	@Test void legacyToolQuestionsAreAnsweredByUuidAndDecideWhatRuns() throws Exception {
		var layout = Layout.under(this.directory);
		var app = AppProcess.rpc(layout, List.of());

		String write = app.prompt("!tool write_file {\"path\":\"notes.txt\",\"content\":\"alpha\"}");
		var question = app.question(write);
		assertThat(UUID.fromString(question.path("questionId").asString())).isNotNull();
		assertThat(question.path("prompt").asString()).contains("허용할까요");
		assertThat(app.outputs(write)).anySatisfy(line -> assertThat(line).contains("[permission] write_file"));
		assertThat(AppProcess.is(app.answerQuestion(question.path("questionId").asString(), "y"), "status", "answered")).isTrue();
		assertThat(AppProcess.is(app.completion(write), "status", "completed")).isTrue();
		assertThat(Files.readString(layout.work().resolve("notes.txt"))).isEqualTo("alpha");
		assertThat(executed(app, write)).containsExactly("write_file");

		String read = app.prompt("!tool read_file {\"path\":\"notes.txt\"}");
		assertThat(AppProcess.is(app.completion(read), "status", "completed")).isTrue();
		assertThat(app.frames(read)).noneSatisfy(frame -> assertThat(AppProcess.is(frame, "event", "question")).isTrue());
		assertThat(app.streamed(read)).contains("alpha");
		assertThat(executed(app, read)).containsExactly("read_file");

		String bash = "!tool bash {\"command\":\"cat notes.txt\"}";
		String denied = app.prompt(bash);
		app.answerQuestion(app.question(denied).path("questionId").asString(), "n");
		assertThat(AppProcess.is(app.completion(denied), "status", "completed")).isTrue();
		assertThat(app.streamed(denied)).contains("the user rejected it").doesNotContain("alpha");
		assertThat(executed(app, denied)).as("the permission gate runs before the tool and its trace").isEmpty();

		String approved = app.prompt(bash);
		app.answerQuestion(app.question(approved).path("questionId").asString(), "y");
		assertThat(AppProcess.is(app.completion(approved), "status", "completed")).isTrue();
		assertThat(app.streamed(approved)).contains("alpha");
		assertThat(executed(app, approved)).containsExactly("bash");

		String glob = app.prompt("!tool glob_paths {\"path\":\".\",\"pattern\":\"**/*.txt\",\"limit\":10}");
		assertThat(AppProcess.is(app.completion(glob), "status", "completed")).isTrue();
		assertThat(app.streamed(glob)).contains("notes.txt");
		assertThat(executed(app, glob)).containsExactly("glob_paths");
		app.shutdown();
		assertThat(app.awaitExit()).isZero();
	}

	@Test void sessionHistoryAndForkSurviveARestart() throws Exception {
		var layout = Layout.under(this.directory);
		var first = AppProcess.rpc(layout, List.of());
		String parent = first.completion(first.prompt("PARENT_ONE")).path("sessionAfter").asString();
		first.shutdown();
		assertThat(first.awaitExit()).isZero();

		var second = AppProcess.rpc(layout, List.of());
		assertThat(second.outputs(completed(second, "/sessions"))).anySatisfy(line -> assertThat(line).contains(parent));
		assertThat(second.outputs(completed(second, "/resume " + parent)))
				.anySatisfy(line -> assertThat(line).startsWith("세션 전환: " + parent));
		assertThat(second.outputs(completed(second, "/history")))
				.anySatisfy(line -> assertThat(line).isEqualTo("USER       PARENT_ONE"));
		String fork = completed(second, "/fork 1");
		String branch = second.completion(fork).path("sessionAfter").asString();
		assertThat(branch).isNotEqualTo(parent);
		assertThat(second.outputs(fork)).containsExactly("세션 분기: " + branch + " <- " + parent + " @ 1");
		String branchOnly = second.prompt("BRANCH_ONLY");
		assertThat(AppProcess.is(second.completion(branchOnly), "sessionAfter", branch)).isTrue();
		assertThat(second.outputs(completed(second, "/tree " + parent)))
				.contains("* " + parent, "  " + branch + " <- " + parent + " @ 1");
		assertThat(second.outputs(completed(second, "/history")))
				.contains("USER       PARENT_ONE").noneSatisfy(line -> assertThat(line).contains("BRANCH_ONLY"));
		second.shutdown();
		assertThat(second.awaitExit()).isZero();

		try (var files = Files.list(layout.state().resolve("sessions"))) {
			assertThat(files.map(path -> path.getFileName().toString()).toList())
					.anySatisfy(name -> assertThat(name).startsWith(parent))
					.anySatisfy(name -> assertThat(name).startsWith(branch));
		}
	}

	/** Sends a command and requires that it completed; returns its request id. */
	private static String completed(AppProcess app, String command) {
		String id = app.command(command);
		assertThat(AppProcess.is(app.completion(id), "status", "completed")).as(command).isTrue();
		return id;
	}

	private static String error(AppProcess app, String requestId) {
		return app.awaitFrame(frame -> AppProcess.is(frame, "requestId", requestId)
				&& AppProcess.is(frame, "type", "error")).path("code").asString();
	}

	private static List<String> executed(AppProcess app, String requestId) {
		return app.frames(requestId).stream().filter(frame -> AppProcess.is(frame, "event", "ToolExecuted"))
				.map(frame -> frame.path("tool").asString()).toList();
	}
}
