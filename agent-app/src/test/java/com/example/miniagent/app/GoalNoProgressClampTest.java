package com.example.miniagent.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.goal.GoalRecord;
import com.example.miniagent.core.goal.GoalRequest;
import com.example.miniagent.core.goal.GoalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.example.miniagent.app.GoalLoopFakeModelTest.MAX_OUTPUT_BYTES;
import static com.example.miniagent.app.GoalLoopFakeModelTest.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/** NO_PROGRESS compares gate configurations; the gate timeout is clamped to the remaining wall budget. */
@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.plugins.bash.enabled=false", "agent.plugins.memory.enabled=false",
		"agent.plugins.trace.level=off" })
@Import(GoalLoopFakeModelTest.GoalFixture.class)
class GoalNoProgressClampTest {
	static final long WALL_MILLIS = 20_000;
	@TempDir static Path root;
	static Path work;

	@DynamicPropertySource static void paths(DynamicPropertyRegistry properties) throws IOException {
		work = Files.createDirectory(root.resolve("workspace")).toRealPath();
		Path home = Files.createDirectory(root.resolve("state")).toRealPath();
		properties.add("agent.cwd", () -> work.toString());
		properties.add("agent.home", () -> home.toString());
	}

	@Autowired GoalService goals;
	@Autowired Agent agent;
	@Autowired AgentSession session;

	@BeforeEach void freshSession() { this.session.startNew(); }

	@Test void unchangedFailureBlocksForNoProgressWhenTheGateTimeoutFitsTheWall() throws Exception {
		var goal = executeFalseGate(WALL_MILLIS / 2);

		assertThat(goal.state()).isEqualTo(GoalRecord.State.BLOCKED);
		assertThat(goal.reason()).isEqualTo(GoalRecord.Reason.NO_PROGRESS);
		assertThat(goal.results()).hasSize(1);
	}

	@Test void unchangedFailureBlocksForNoProgressWhenTheGateTimeoutExceedsTheRemainingWall() throws Exception {
		var goal = executeFalseGate(WALL_MILLIS + 10_000);

		assertSoftly(softly -> {
			softly.assertThat(goal.state()).isEqualTo(GoalRecord.State.BLOCKED);
			softly.assertThat(goal.reason()).isEqualTo(GoalRecord.Reason.NO_PROGRESS);
			// One launch; both unchanged retries are skipped as in the control above.
			softly.assertThat(goal.results()).extracting(GoalRecord.GateResult::configuration).hasSize(1);
		});
	}

	private GoalRecord executeFalseGate(long gateTimeoutMillis) throws IOException {
		var gate = new GoalRequest.Gate(List.of("/usr/bin/false"), gateTimeoutMillis, MAX_OUTPUT_BYTES);
		var pending = this.goals.submit(request(gate, 3, WALL_MILLIS, null), this.agent.selectedModel(),
				this.session.id(), work);
		return this.goals.execute(pending.id());
	}
}
