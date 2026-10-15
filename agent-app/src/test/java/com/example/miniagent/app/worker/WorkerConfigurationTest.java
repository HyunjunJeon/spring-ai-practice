package com.example.miniagent.app.worker;

import java.util.Map;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerConfigurationTest {
	@Test
	void emptySelectionOptionsPreserveConfiguredProviderEffort() throws java.io.IOException {
		var agent = mock(Agent.class);
		when(agent.selectedModel()).thenReturn(new ModelSelection(new ModelRef("codex", "gpt-6.1-sol")));
		var provider = provider(agent);
		var environment = new MockEnvironment().withProperty("agent.codex.auth", "codex-cli")
				.withProperty("agent.codex.reasoning-effort", "high");
		var controller = new WorkerConfiguration().workerController(new AgentProperties(), provider, environment);
		assertThat(controller.launchConfiguration().selection().options()).containsEntry("reasoningEffort", "high");
		assertThat(controller.launchConfiguration().selection().ref().model()).isEqualTo("gpt-6.1-sol");
	}

	@Test
	void unsupportedSelectionIsNeverChangedToCodex() throws java.io.IOException {
		var agent = mock(Agent.class);
		when(agent.selectedModel()).thenReturn(new ModelSelection(new ModelRef("fake", "fake-scripted")));
		var controller = new WorkerConfiguration().workerController(new AgentProperties(), provider(agent),
				new MockEnvironment().withProperty("agent.codex.auth", "codex-cli"));
		assertThatThrownBy(controller::launchConfiguration).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void selectedEffortOverridesProviderDefaultAndConsoleCannotGrant() throws java.io.IOException {
		var agent = mock(Agent.class);
		when(agent.selectedModel()).thenReturn(new ModelSelection(new ModelRef("codex", "gpt-6.1-sol"),
				Map.of("effort", "high")));
		var configuration = new WorkerConfiguration();
		var controller = configuration.workerController(new AgentProperties(), provider(agent),
				new MockEnvironment().withProperty("agent.codex.auth", "codex-cli")
						.withProperty("agent.codex.reasoning-effort", "xhigh"));
		assertThat(controller.launchConfiguration().selection().options()).containsExactlyEntriesOf(Map.of("effort", "high"));
		var console = configuration.workerConsole();
		assertThat(console.isInteractive()).isFalse();
		assertThat(console.readLine("")).isNull();
	}

	private static ObjectProvider<Agent> provider(Agent agent) {
		var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
		beans.addBean("agent", agent);
		return beans.getBeanProvider(Agent.class);
	}
}
