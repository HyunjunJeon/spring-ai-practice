package com.example.miniagent.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = { "agent.repl.enabled=false", "agent.ui=repl",
		"agent.models.default=queue/gated", "agent.plugins.trace.level=off",
		"agent.plugins.memory.compaction=false", "agent.plugins.memory.inside-loop=true" })
@Import({ AgentTestSupport.class, UserQueueMemoryIntegrationTest.QueueModels.class })
class UserQueueMemoryInsideIntegrationTest extends UserQueueMemoryIntegrationTest {

	@Override
	@Test
	void steeringAndFollowUpPersistExactlyOnceInOrderAcrossRealToolRounds() throws Exception {
		super.steeringAndFollowUpPersistExactlyOnceInOrderAcrossRealToolRounds();
	}
}
