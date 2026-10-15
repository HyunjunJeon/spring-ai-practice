package com.example.miniagent.core.autoconfigure;

import java.io.IOException;
import java.time.Clock;
import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.model.SessionModelStore;
import com.example.miniagent.core.run.DriftGate;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.JournalResumeContributor;
import com.example.miniagent.core.run.RunProperties;
import com.example.miniagent.core.run.RunRequestStore;
import com.example.miniagent.core.run.RunResumeContributor;
import com.example.miniagent.core.run.RunStore;
import com.example.miniagent.core.run.ToolEvidenceStore;
import com.example.miniagent.core.run.command.RunCommand;
import com.example.miniagent.core.run.command.RunsCommand;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = RunJournalAutoConfiguration.class)
@EnableConfigurationProperties(RunProperties.class)
public class RunCommandAutoConfiguration {
	@Bean @ConditionalOnMissingBean(Clock.class)
	Clock runClock() { return Clock.systemUTC(); }

	@Bean @ConditionalOnMissingBean(RunStore.class)
	FileRunStore runStore(AgentProperties properties) throws IOException {
		return new FileRunStore(properties.getHome());
	}

	@Bean @ConditionalOnMissingBean
	RunRequestStore runRequestStore(AgentProperties properties) throws IOException {
		return new RunRequestStore(properties.getHome());
	}

	@Bean
	JournalResumeContributor journalResumeContributor(RunStore runs, ToolEvidenceStore evidence) {
		return new JournalResumeContributor(runs, evidence);
	}

	@Bean @ConditionalOnMissingBean
	RunExecutor runExecutor(Agent agent, SessionModelStore selections, RunStore runs, RunRequestStore requests,
			Clock clock, ObjectProvider<RunResumeContributor> contributors, ApprovalBroker broker, DriftGate gate,
			com.example.miniagent.core.execution.StagingToolInterceptor staging,
			ObjectProvider<com.example.miniagent.core.change.ChangeStore> changes,
			org.springframework.core.env.Environment environment) {
		gate.stagingConfiguration(() -> "staged-v1\0" + environment.getProperty("agent.changes.shell-cache-roots", ""));
		return new RunExecutor(agent, agent.modelRegistry(), selections, runs, requests, clock,
				contributors.orderedStream().toList(), broker, gate).staging(staging, changes::getObject);
	}

	@Bean
	RunCommand runCommand(RunExecutor executor, RunStore runs, RunRequestStore requests, ApprovalBroker broker,
			RunProperties properties, AgentProperties agentProperties, JournalResumeContributor evidence,
			ApplicationEventPublisher events) {
		return new RunCommand(executor, runs, requests, broker, properties, agentProperties.getCwd(), evidence,
				events);
	}

	@Bean
	RunsCommand runsCommand(RunStore runs) { return new RunsCommand(runs); }
}
