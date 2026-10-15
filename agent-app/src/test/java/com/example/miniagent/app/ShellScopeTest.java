package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import com.example.miniagent.core.Agent;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.plugins.memory.enabled=false", "agent.plugins.permission.enabled=false",
		"agent.plugins.trace.level=off", "agent.plugins.bash.sandbox=off" })
@Import(AgentTestSupport.class)
class ShellScopeTest {
	@TempDir static Path workspace;
	@TempDir static Path state;
	@DynamicPropertySource static void paths(DynamicPropertyRegistry p) {
		p.add("agent.home", () -> state.resolve("home").toString());
		p.add("agent.cwd", () -> workspace.toString());
	}
	@Autowired Agent agent;
	@Autowired RunExecutor executor;
	@Autowired ChangeStore changes;

	@Test void argvOutsideWorkspaceRejected() throws Exception {
		Path live = Files.writeString(workspace.resolve("shell-source"), "original");
		Path outside = state.resolve("outside");
		String shell = "printf staged > shell-source; (printf escaped > '" + live
				+ "') 2>/dev/null; (printf escaped > '" + outside + "') 2>/dev/null; true";
		String args = tools.jackson.databind.json.JsonMapper.builder().build()
				.writeValueAsString(java.util.Map.of("command", shell));
		if (!Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) {
			assertThatThrownBy(() -> executor.start("!tool bash " + args, "parent", agent.selectedModel(),
					workspace, RunBudget.defaults())).isInstanceOf(RuntimeException.class);
			assertThat(Files.readString(live)).isEqualTo("original");
			assertThat(outside).doesNotExist();
			return;
		}
		var result = executor.start("!tool bash " + args, "parent", agent.selectedModel(),
				workspace, RunBudget.defaults());
		assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(Files.readString(live)).isEqualTo("original");
		assertThat(outside).doesNotExist();
		var set = changes.read().changes().stream().map(ChangeStore.Snapshot::changeSet)
				.filter(c -> c.owner().runId().equals(result.record().spec().runId())).findFirst().orElseThrow();
		Path stage;
		try (var lease = changes.acquire()) { stage = lease.stagingDirectory(set.owner()); }
		assertThat(Files.readString(stage.resolve("shell-source"))).isEqualTo("staged");
	}
}
