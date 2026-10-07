package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunExecutor;
import com.example.miniagent.core.run.RunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = { "agent.ui=plain", "agent.interactive=false",
		"agent.plugins.permission.enabled=false", "agent.plugins.memory.enabled=false" })
@Import(AgentTestSupport.class)
class RunBudgetIntegrationTest {
	@TempDir static Path workspace;
	@TempDir static Path state;

	@DynamicPropertySource
	static void paths(DynamicPropertyRegistry properties) {
		properties.add("agent.home", () -> state.resolve("app-home").toString());
		properties.add("agent.cwd", () -> workspace.toString());
	}

	@Autowired Agent agent;
	@Autowired RunExecutor executor;

	@Test
	void zeroToolBudgetInterruptsBeforeRealFileTool() throws Exception {
		Files.writeString(workspace.resolve("sentinel.txt"), "unchanged");
		RunExecutor.Result result = execute("!tool write_file {\"path\":\"sentinel.txt\",\"content\":\"changed\"}",
				new RunBudget(Duration.ofMinutes(1), null, 0));
		assertThat(Files.readString(workspace.resolve("sentinel.txt"))).isEqualTo("unchanged");
		assertThat(result.record().state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(result.record().reason()).isEqualTo(RunEvent.Reason.BUDGET_EXHAUSTED);
		assertThat(result.record().usage().toolCalls()).isZero();
	}

	@Test
	void realToolCallIsCountedWithoutLoopAggregateDuplication() throws Exception {
		RunExecutor.Result result = execute("!tool list_dir {\"path\":\".\"}",
				new RunBudget(Duration.ofMinutes(1), null, 1));
		assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(result.record().usage().toolCalls()).isEqualTo(1);
		assertThat(result.reply()).isNotNull();
		assertThat(result.record().usage().knownTokens()).isPositive();
		assertThat(result.record().usage().tokensUnknown()).isFalse();
	}

	@Test
	void zeroTokenBudgetDoesNotCallModel() throws Exception {
		RunExecutor.Result result = execute("blocked", new RunBudget(Duration.ofMinutes(1), 0L, 50));
		assertThat(result.reply()).isNull();
		assertThat(result.record().state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(result.record().reason()).isEqualTo(RunEvent.Reason.BUDGET_EXHAUSTED);
		assertThat(result.record().usage().knownTokens()).isZero();
	}

	private RunExecutor.Result execute(String input, RunBudget budget) throws Exception {
		return executor.start(input, "parent", this.agent.selectedModel(), workspace.toRealPath(), budget);
	}
}
