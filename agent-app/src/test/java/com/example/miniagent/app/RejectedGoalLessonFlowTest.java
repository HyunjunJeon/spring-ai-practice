package com.example.miniagent.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.goal.GoalRecord;
import com.example.miniagent.core.goal.GoalRequest;
import com.example.miniagent.core.goal.GoalService;
import com.example.miniagent.core.lesson.LessonCommand;
import com.example.miniagent.core.lesson.LessonRecord;
import com.example.miniagent.core.lesson.LessonService;
import com.example.miniagent.core.model.TurnContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.example.miniagent.app.GoalLoopFakeModelTest.GATE_TIMEOUT_MILLIS;
import static com.example.miniagent.app.GoalLoopFakeModelTest.MAX_OUTPUT_BYTES;
import static com.example.miniagent.app.GoalLoopFakeModelTest.WALL_MILLIS;
import static com.example.miniagent.app.GoalLoopFakeModelTest.gate;
import static com.example.miniagent.app.GoalLoopFakeModelTest.request;
import static org.assertj.core.api.Assertions.assertThat;

/** Goals that GoalService really rejected, read back through the production lesson ProofReader and /lesson. */
@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.plugins.bash.enabled=false", "agent.plugins.memory.enabled=false",
		"agent.plugins.trace.level=off" })
@Import({ GoalLoopFakeModelTest.GoalFixture.class, RejectedGoalLessonFlowTest.Operator.class })
class RejectedGoalLessonFlowTest {
	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String LESSON_DATA = "Untrusted advisory lesson data";
	@TempDir static Path root;
	static Path work;

	@DynamicPropertySource static void paths(DynamicPropertyRegistry properties) throws IOException {
		work = Files.createDirectory(root.resolve("workspace")).toRealPath();
		Path home = Files.createDirectory(root.resolve("state")).toRealPath();
		properties.add("agent.cwd", () -> work.toString());
		properties.add("agent.home", () -> home.toString());
	}

	@Autowired AgentInputHandler handler;
	@Autowired GoalService goals;
	@Autowired LessonService lessons;
	@Autowired Agent agent;
	@Autowired AgentSession session;
	@Autowired OperatorScreen screen;
	@Autowired GoalLoopFakeModelTest.PromptLog prompts;

	@BeforeEach void freshSession() {
		this.session.startNew();
		this.screen.lines.clear();
		this.screen.errors.clear();
	}

	static Stream<Arguments> rejections() {
		return Stream.of(
				Arguments.of(gate("/usr/bin/false"), 2, GoalRecord.State.FAILED,
						GoalRecord.Reason.STEP_LIMIT, VerificationRunner.Code.NON_ZERO_EXIT,
						"[이전 작업 실패에서 배운 주의점] gate 0 `/usr/bin/false` 종료 코드 1 (목표 중단 사유: STEP_LIMIT)"),
				Arguments.of(gate("/bin/sh", "-c", "exit 3"), 3, GoalRecord.State.BLOCKED,
						GoalRecord.Reason.NO_PROGRESS, VerificationRunner.Code.NON_ZERO_EXIT,
						"[이전 작업 실패에서 배운 주의점] gate 0 `/bin/sh -c exit 3` 종료 코드 3 (목표 중단 사유: NO_PROGRESS)"),
				Arguments.of(new GoalRequest.Gate(List.of("/bin/sleep", "10"), 300, MAX_OUTPUT_BYTES), 2,
						GoalRecord.State.BLOCKED,
						GoalRecord.Reason.GATE_FAILED, VerificationRunner.Code.TIMED_OUT,
						"[이전 작업 실패에서 배운 주의점] gate 0 `/bin/sleep 10` 시간 초과 (목표 중단 사유: GATE_FAILED)"),
				Arguments.of(new GoalRequest.Gate(List.of("/usr/bin/yes"), GATE_TIMEOUT_MILLIS, 64), 2,
						GoalRecord.State.BLOCKED,
						GoalRecord.Reason.GATE_FAILED, VerificationRunner.Code.OUTPUT_LIMIT,
						"[이전 작업 실패에서 배운 주의점] gate 0 `/usr/bin/yes` 출력 한도 초과 (목표 중단 사유: GATE_FAILED)"));
	}

	@ParameterizedTest(name = "{3} {4}") @MethodSource("rejections")
	void everyGateRejectionGroundsAMarkedCandidateThroughTheProductionProofReader(GoalRequest.Gate gate, int maxSteps,
			GoalRecord.State state, GoalRecord.Reason reason, VerificationRunner.Code code, String marker) throws Exception {
		var goal = execute(gate, maxSteps, null);
		assertThat(goal.state()).isEqualTo(state);
		assertThat(goal.reason()).isEqualTo(reason);
		assertThat(goal.results().getLast().code()).isEqualTo(code);

		var candidate = lesson("propose", new LessonCommand.Proposal(goal.id(), "Gate pitfall",
				"Check the gate before finishing.", List.of("gate")));

		assertThat(candidate.path("state").asString()).isEqualTo("CANDIDATE");
		assertThat(candidate.path("scope").asString()).isEqualTo("SESSION");
		assertThat(candidate.path("content").path("content").asString())
				.isEqualTo(marker + "\n\nCheck the gate before finishing.");
		var proof = candidate.path("proof");
		assertThat(proof.path("goalId").asString()).isEqualTo(goal.id());
		assertThat(proof.path("runId").asString()).isEqualTo(goal.spec().runId().value());
		assertThat(proof.path("readOnly").asBoolean()).isTrue();
		assertThat(proof.path("evidenceIds").values()).extracting(JsonNode::asString)
				.containsExactly(goal.results().getLast().evidence().evidenceId().toString());
		var stored = this.lessons.lookup(candidate.path("id").asString(), actor());
		assertThat(stored.state()).isEqualTo(LessonRecord.State.CANDIDATE);
		assertThat(stored.content().content()).isEqualTo(marker + "\n\nCheck the gate before finishing.");
	}

	@Test void acceptedFailureLessonIsInjectedUntilRetiredAndAgainAfterRollback() throws Exception {
		var goal = execute(gate("/usr/bin/false"), 2, null);
		var candidate = lesson("propose", new LessonCommand.Proposal(goal.id(), "Readiness probe pitfall",
				"Run the readiness probe before reporting completion.", List.of("readiness")));
		String id = candidate.path("id").asString();
		String question = "How should the readiness probe be handled?";
		assertThat(lessonData(question)).isEmpty();

		var accepted = lesson("accept", new LessonCommand.Acceptance(id, 1, LessonRecord.Scope.SESSION));
		assertThat(accepted.path("state").asString()).isEqualTo("ACCEPTED");
		assertThat(accepted.path("version").asInt()).isEqualTo(2);
		assertThat(lessonData(question)).singleElement().asString()
				.contains("\"id\":\"" + id + "\"", "\"version\":2", LessonService.FAILURE_MARKER + " gate 0 `/usr/bin/false`");

		var retired = lesson("retire", new LessonCommand.Retirement(id, 2));
		assertThat(retired.path("state").asString()).isEqualTo("RETIRED");
		assertThat(lessonData(question)).isEmpty();

		var restored = lesson("rollback", new LessonCommand.Rollback(id, 3, 2));
		assertThat(restored.path("state").asString()).isEqualTo("ACCEPTED");
		assertThat(restored.path("rollbackOf").asInt()).isEqualTo(2);
		assertThat(restored.path("content")).isEqualTo(accepted.path("content"));
		assertThat(lessonData(question)).singleElement().asString()
				.contains("\"id\":\"" + id + "\"", "\"version\":4");
		assertThat(this.lessons.history(id, actor())).extracting(LessonRecord::operation)
				.containsExactly("propose", "accept", "retire", "rollback");
	}

	@Test void goalStoppedByItsBudgetRatherThanAGateGroundsNoLesson() throws Exception {
		var goal = execute(gate("/usr/bin/false"), 2, 1L);
		assertThat(goal.reason()).isEqualTo(GoalRecord.Reason.BUDGET_LIMIT);

		assertThat(propose(goal.id())).isFalse();

		assertThat(this.screen.errors).singleElement().asString()
				.contains("Lesson requires this session's verified goal or a goal its gate rejected");
		assertThat(this.lessons.list(actor())).isEmpty();
	}

	@Test void rejectedGoalWhoseSourceChangedGroundsNoLesson() throws Exception {
		var goal = execute(gate("/usr/bin/false"), 2, null);
		assertThat(goal.reason()).isEqualTo(GoalRecord.Reason.STEP_LIMIT);
		Files.writeString(work.resolve("operator-edit-" + goal.id() + ".txt"), "changed after the rejection\n");

		assertThat(propose(goal.id())).isFalse();

		assertThat(this.screen.errors).singleElement().asString().contains("Lesson source is stale");
		assertThat(this.lessons.list(actor())).isEmpty();
	}

	private GoalRecord execute(GoalRequest.Gate gate, int maxSteps, Long tokenCap) throws IOException {
		var request = request(gate, maxSteps, WALL_MILLIS, tokenCap);
		return this.goals.execute(this.goals.submit(request, this.agent.selectedModel(), this.session.id(), work).id());
	}

	private boolean propose(String goalId) {
		return this.handler.handle("/lesson propose " + JSON.writeValueAsString(new LessonCommand.Proposal(goalId,
				"Must not exist", "No proof grounds this lesson.", List.of())));
	}

	/** Runs one operator command with its JSON argument record and returns the lesson it printed. */
	private JsonNode lesson(String operation, Record arguments) {
		assertThat(this.handler.handle("/lesson " + operation + " " + JSON.writeValueAsString(arguments)))
				.as("console errors: %s", this.screen.errors).isTrue();
		return JSON.readTree(this.screen.lines.getLast());
	}

	/** Lesson payloads that LessonAdvisor put in front of one ordinary turn's model request. */
	private List<String> lessonData(String question) {
		this.prompts.prompts.clear();
		var turn = new TurnContext(this.session.id(), UUID.randomUUID().toString(), work,
				this.agent.modelRegistry().bind(GoalLoopFakeModelTest.GoalFixture.model("witness")));
		assertThat(this.agent.run(question, turn).text()).isEqualTo("noted");
		return this.prompts.prompts.stream().flatMap(prompt -> prompt.getInstructions().stream())
				.filter(message -> message.getMessageType() == MessageType.SYSTEM)
				.map(Message::getText).filter(text -> text.startsWith(LESSON_DATA)).toList();
	}

	private LessonRecord.Actor actor() throws IOException { return LessonRecord.Actor.capture(this.session.id(), work); }

	@TestConfiguration static class Operator {
		@Bean OperatorScreen lessonOperatorScreen() { return new OperatorScreen(); }
	}

	/** Keeps what /lesson printed so the test can read the record back. */
	static final class OperatorScreen extends AgentConsole {
		final List<String> lines = new CopyOnWriteArrayList<>();
		final List<String> errors = new CopyOnWriteArrayList<>();

		OperatorScreen() {
			super(new BufferedReader(new StringReader("")), new PrintStream(OutputStream.nullOutputStream()), false);
		}

		@Override public void println(String text) { this.lines.add(text); }

		@Override public void error(String text) { this.errors.add(text); }
	}
}
