package com.example.miniagent.app.worker;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.goal.GoalService;
import com.example.miniagent.core.goal.GoalStore;
import com.example.miniagent.core.model.ModelSelection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Component-scanned app assembly. Only the worker mode runner opens stores or executes goals. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "agent.goals.enabled", matchIfMissing = true)
public class WorkerConfiguration {
	@Bean
	WorkerController workerController(AgentProperties properties, ObjectProvider<Agent> agent, Environment env)
			throws java.io.IOException {
		return new WorkerController(properties.getCwd(), com.example.miniagent.core.StatePaths.home(properties.getHome()), () -> {
			var selected = agent.getObject().selectedModel();
			// Preserve the chosen spend route. WorkerLaunch explicitly refuses unsupported providers.
			if (!selected.options().containsKey("effort") && !selected.options().containsKey("reasoningEffort"))
				selected = new ModelSelection(selected.ref(), Map.of("reasoningEffort",
						env.getProperty("agent.codex.reasoning-effort", "high")));
			return new WorkerLaunch(selected, env.getProperty("agent.codex.auth", "codex-cli"),
					env.getProperty("agent.codex.base-url", "https://chatgpt.com/backend-api/codex"),
					env.getProperty("agent.codex.codex-home", Path.class,
							Path.of(System.getProperty("user.home"), ".codex")),
					Path.of(System.getProperty("user.home")),
					env.getProperty("agent.codex.idle-timeout", Duration.class, Duration.ofMinutes(5)));
		});
	}
	@Bean
	@ConditionalOnBooleanProperty(name = "agent.worker.mode", havingValue = false, matchIfMissing = true)
	WorkerCommand workerCommand(WorkerController controller) { return new WorkerCommand(controller); }

	@Bean
	@ConditionalOnBooleanProperty(name = "agent.worker.mode")
	AgentConsole workerConsole() {
		return new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(OutputStream.nullOutputStream()), false);
	}
	@Bean
	@ConditionalOnBooleanProperty(name = "agent.worker.mode")
	ApplicationRunner workerRunner(AgentProperties properties, GoalStore store, GoalService goals, Environment env) {
		return args -> new WorkerRuntime(properties.getCwd(), properties.getHome(),
				env.getProperty("agent.worker.instance", UUID.randomUUID().toString()), store, goals).run();
	}
}
