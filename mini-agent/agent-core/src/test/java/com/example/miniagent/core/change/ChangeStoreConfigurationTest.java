package com.example.miniagent.core.change;

import java.nio.file.Files;
import java.nio.file.Path;
import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ChangeStoreConfigurationTest {
	@TempDir Path temp;

	@Test void configuredStoreIsLazyWorkspaceScopedAndUsesBoundLimits() throws Exception {
		Path workspace = Files.createDirectory(this.temp.resolve("workspace"));
		Path home = this.temp.resolve("state");
		new ApplicationContextRunner().withUserConfiguration(AgentAutoConfiguration.class)
				.withBean(Agent.class, () -> mock(Agent.class))
				.withPropertyValues("agent.cwd=" + workspace, "agent.home=" + home,
						"agent.changes.max-blob-bytes=32")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.containsBeanDefinition("changeStore")).isTrue();
					assertThat(context.getBean(ChangeProperties.class))
							.isEqualTo(new ChangeProperties(32, 4194304, 67108864));
					assertThat(home.resolve("changes")).doesNotExist();
					ChangeStore store = context.getBean(ChangeStore.class);
					assertThat(context).hasSingleBean(ChangeStore.class);
					assertThat(store.workspace()).isEqualTo(workspace.toRealPath());
					assertThat(store.directory()).startsWith(home.toRealPath().resolve("changes"));
					try (var lease = store.acquire()) {
						assertThat(lease.read().journalState()).isEqualTo(ChangeJournal.State.CLEAN);
						assertThat(lease.read().changes()).isEmpty();
					}
				});
	}
}
