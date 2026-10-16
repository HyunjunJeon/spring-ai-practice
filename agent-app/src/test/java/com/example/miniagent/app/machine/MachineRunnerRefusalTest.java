package com.example.miniagent.app.machine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.miniagent.core.SlashCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Transport refusals: nothing refused here may reach a slash command or the model. */
@Timeout(20)
class MachineRunnerRefusalTest {
	// The README lists exactly these names for command.text.
	private static final List<String> DOCUMENTED = List.of("model", "models", "sessions", "new", "resume", "tree",
			"fork", "history", "forget", "runs", "run", "changes", "context", "goal", "lesson", "worker", "message");

	@TempDir Path home;
	private final List<String> reached = new CopyOnWriteArrayList<>();
	private final AtomicInteger modelCalls = new AtomicInteger();

	private MachineFixture fixture(List<String> commandNames) throws IOException {
		List<SlashCommand> commands = commandNames.stream()
				.map(name -> SlashCommand.of(name, name, (args, context) -> this.reached.add(name + " " + args)))
				.toList();
		return new MachineFixture(this.home, "rpc", MachineFixture.input(""),
				prompt -> this.modelCalls.incrementAndGet(), commands);
	}

	@Test void everyDocumentedCommandReachesItsHandlerWithItsArguments() throws Exception {
		try (var fixture = fixture(DOCUMENTED)) {
			for (String name : DOCUMENTED) {
				fixture.send("c-" + name, "command", Map.of("text", "/" + name + " one two"));
				assertThat(fixture.output.await("completion", "c-" + name).path("status").asString())
						.as(name).isEqualTo("completed");
			}
			assertThat(this.reached).containsExactlyElementsOf(DOCUMENTED.stream().map(name -> name + " one two").toList());
		}
	}

	@Test void commandsOutsideTheDocumentedListAreRefusedBeforeReachingTheirHandler() throws Exception {
		List<String> outside = List.of("help", "exit", "quit", "tools", "skills", "plugins", "login", "logout",
				"plan", "apply", "undo", "verify", "delegate", "steer", "follow-up");
		try (var fixture = fixture(outside)) {
			for (String name : outside)
				assertThat(fixture.refusalCode("o-" + name, "command", Map.of("text", "/" + name + " x")))
						.as(name).isEqualTo("command_not_allowed");
			assertThat(this.reached).isEmpty();
			assertThat(this.modelCalls).hasValue(0);
		}
	}

	@Test void nearMissesOfDocumentedNamesAreNotAllowed() throws Exception {
		try (var fixture = fixture(DOCUMENTED)) {
			int index = 0;
			for (String text : List.of("/modelx", "/MODEL", "/Run start", "model x", "/ model", "/runs/x"))
				assertThat(fixture.refusalCode("n" + index++, "command", Map.of("text", text)))
						.as(text).isEqualTo("command_not_allowed");
			assertThat(this.reached).isEmpty();
		}
	}

	@Test void runApprovalAndCancellationMustUseTheExactControlMethods() throws Exception {
		try (var fixture = fixture(List.of("run"))) {
			int index = 0;
			for (String text : List.of("/run approve 1234", "/run reject 1234", "/run defer 1234",
					"/run cancel 1234", "  /run   approve 1234", "/run\tdefer 1234"))
				assertThat(fixture.refusalCode("r" + index++, "command", Map.of("text", text)))
						.as(text).isEqualTo("use_exact_control_method");
			assertThat(this.reached).isEmpty();
		}
	}

	@Test void runStartResumeAndInspectionStillReachTheRunCommand() throws Exception {
		try (var fixture = fixture(List.of("run"))) {
			int index = 0;
			for (String text : List.of("/run start goal", "/run resume abc", "/run show abc", "/run")) {
				String id = "r" + index++;
				fixture.send(id, "command", Map.of("text", text));
				assertThat(fixture.output.await("completion", id).path("status").asString())
						.as(text).isEqualTo("completed");
			}
			assertThat(this.reached).containsExactly("run start goal", "run resume abc", "run show abc", "run ");
		}
	}

	@Test void slashPromptsMustUseTheCommandMethod() throws Exception {
		try (var fixture = fixture(List.of("model", "new", "run"))) {
			int index = 0;
			for (String text : List.of("/model fake/other", "   /new", "/unknown", "/run approve 1"))
				assertThat(fixture.refusalCode("p" + index++, "prompt", Map.of("text", text)))
						.as(text).isEqualTo("use_command_method");
			assertThat(this.reached).isEmpty();
			assertThat(this.modelCalls).hasValue(0);
		}
	}

	@Test void unknownMethodsAreRefusedWithoutDispatch() throws Exception {
		try (var fixture = fixture(DOCUMENTED)) {
			int index = 0;
			for (String method : List.of("frobnicate", "PROMPT", "Prompt", "", "prompt "))
				assertThat(fixture.refusalCode("m" + index++, method, Map.of("text", "hello")))
						.as("method '" + method + "'").isEqualTo("unknown_method");
			assertThat(this.reached).isEmpty();
			assertThat(this.modelCalls).hasValue(0);
		}
	}

	@Test void fieldsOutsideAMethodsSchemaAreRefusedWithoutDispatch() throws Exception {
		try (var fixture = fixture(DOCUMENTED)) {
			var refusals = new LinkedHashMap<String, Map<String, ?>>();
			refusals.put("prompt", Map.of("text", "hello", "extra", "x"));
			refusals.put("command", Map.of("text", "/model", "remember", true));
			refusals.put("steer", Map.of("targetId", "p", "text", "hello", "extra", "x"));
			refusals.put("follow_up", Map.of("targetId", "p", "text", "hello", "extra", "x"));
			refusals.put("cancel", Map.of("targetId", "p", "text", "hello"));
			refusals.put("approval", Map.of("approvalId", "x", "decision", "APPROVED", "text", "hello"));
			refusals.put("question", Map.of("questionId", "x", "text", "hello", "extra", "x"));
			refusals.put("shutdown", Map.of("text", "hello"));
			int index = 0;
			for (var refusal : refusals.entrySet())
				assertThat(fixture.refusalCode("u" + index++, refusal.getKey(), refusal.getValue()))
						.as(refusal.getKey()).isEqualTo("unknown_field");
			assertThat(this.reached).isEmpty();
			assertThat(this.modelCalls).hasValue(0);
			// The refused shutdown must not have stopped admission.
			fixture.send("after", "prompt", Map.of("text", "still serving"));
			assertThat(fixture.output.await("completion", "after").path("status").asString()).isEqualTo("completed");
		}
	}
}
