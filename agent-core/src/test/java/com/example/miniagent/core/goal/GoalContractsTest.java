package com.example.miniagent.core.goal;

import java.time.Duration;
import java.util.List;
import com.example.miniagent.core.run.RunBudget;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GoalContractsTest {
	private final RunBudget defaults = new RunBudget(Duration.ofSeconds(40), 200L, 7);

	@Test void operatorJsonCapturesBoundedDefaultsAndRejectsAuthorityInjection() throws Exception {
		var request = GoalCommand.parse("""
				{"goal":"repair","gates":[{"argv":["/usr/bin/true"],"timeoutMillis":1000,"maxOutputBytes":1024}]}
				""", defaults);
		assertThat(request.maxSteps()).isEqualTo(6);
		assertThat(request.wallMillis()).isEqualTo(40000);
		assertThat(request.tokenCap()).isEqualTo(200);
		assertThat(request.toolCallCap()).isEqualTo(7);
		assertThat(request.gates().getFirst().argv()).containsExactly("/usr/bin/true");
		for (String extra : List.of("\"credentials\":\"private\"", "\"selection\":{}", "\"workspace\":\"/tmp\"",
				"\"parentConversationId\":\"foreign\"", "\"runId\":\"foreign\"")) {
			String input = "{\"goal\":\"repair\",\"gates\":[{\"argv\":[\"/usr/bin/true\"],"
					+ "\"timeoutMillis\":1000,\"maxOutputBytes\":1024}], " + extra + "}";
			assertThatThrownBy(() -> GoalCommand.parse(input, defaults)).isInstanceOf(Exception.class);
		}
		assertThatThrownBy(() -> GoalCommand.parse("{\"goal\":\"one\",\"goal\":\"two\","
				+ "\"gates\":[{\"argv\":[\"/usr/bin/true\"],\"timeoutMillis\":1000,\"maxOutputBytes\":1024}]}", defaults))
				.isInstanceOf(Exception.class);
	}

	@Test void executableControlsNeverEnterTheBusySidechannel() {
		for (String command : List.of("/goal show abc", "/goal list", "/goal cancel abc"))
			assertThat(GoalCommand.isControlInput(command)).isTrue();
		for (String command : List.of("/goal start {}", "/goal submit {}", "/goal resume abc", "/goal", "/goals list"))
			assertThat(GoalCommand.isControlInput(command)).isFalse();
	}

	@Test void allExecutionDimensionsHaveFiniteOperatorBounds() {
		assertThatThrownBy(() -> new GoalRequest.Gate(List.of("a".repeat(4097)), 1000, 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GoalRequest.Gate(List.of("a"), 0, 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GoalRequest.Gate(List.of("a"), 1000, 65537))
				.isInstanceOf(IllegalArgumentException.class);
		var gate = new GoalRequest.Gate(List.of("a"), 1000, 1);
		assertThatThrownBy(() -> new GoalRequest("goal", List.of(gate), 33, 1000, null, 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GoalRequest("goal", List.of(), 1, 1000, null, 1))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
