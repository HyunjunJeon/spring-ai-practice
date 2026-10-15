package com.example.miniagent.core.goal;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentAutoConfiguration;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.run.RunProperties;
import com.example.miniagent.core.run.RunStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Parent registers this configuration; command construction neither binds models nor opens stores. */
@AutoConfiguration(after = AgentAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "agent.goals.enabled", matchIfMissing = true)
public class GoalAutoConfiguration {
	@Bean @ConditionalOnMissingBean
	GoalStore goalStore(AgentProperties properties) throws java.io.IOException {
		return new GoalStore(com.example.miniagent.core.StatePaths.home(properties.getHome()));
	}

	@Bean @ConditionalOnMissingBean
	GoalService goalService(GoalStore goals, ObjectProvider<Agent> agent, ObjectProvider<RunExecutor> executor,
			ObjectProvider<RunStore> runs, ObjectProvider<ChangeStore> changes,
			ObjectProvider<VerificationRunner> verifier) {
		return new GoalService(goals, agent::getObject, executor::getObject, runs::getObject,
				changes::getObject, verifier::getObject);
	}
	@Bean @ConditionalOnMissingBean(GoalCommand.class)
	GoalCommand goalCommand(GoalService goals, AgentProperties properties, RunProperties runs) {
		return new GoalCommand(goals, properties.getCwd(), runs.budget());
	}
}
