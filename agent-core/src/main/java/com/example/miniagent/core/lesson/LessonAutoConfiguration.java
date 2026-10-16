package com.example.miniagent.core.lesson;

import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.autoconfigure.AgentAutoConfiguration;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.goal.GoalService;
import com.example.miniagent.core.run.RunStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Parent registers this configuration. Construction does not touch stores or bind/authenticate models. */
@AutoConfiguration(after = AgentAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "agent.lessons.enabled", matchIfMissing = true)
public class LessonAutoConfiguration {
	@Bean @ConditionalOnMissingBean
	LessonStore lessonStore(AgentProperties properties) throws java.io.IOException {
		return new LessonStore(com.example.miniagent.core.StatePaths.home(properties.getHome()));
	}
	@Bean @ConditionalOnMissingBean
	LessonService lessonService(LessonStore store, ObjectProvider<GoalService> goals, ObjectProvider<RunStore> runs,
			ObjectProvider<VerificationRunner> verifier, ObjectProvider<ChangeStore> changes) {
		return new LessonService(store, goals::getObject, runs::getObject, verifier::getObject, changes::getObject);
	}
	@Bean @ConditionalOnMissingBean
	LessonAdvisor lessonAdvisor(LessonService lessons) { return new LessonAdvisor(lessons); }
	@Bean
	com.example.miniagent.core.delegation.runtime.ChildScopeFactory.AdvisorContribution childLessonContribution(
			LessonService lessons) {
		// LessonAdvisor derives the actor from the child's captured request. Never borrow console session state.
		return (model, authority) -> java.util.List.of(new LessonAdvisor(lessons));
	}
	@Bean @ConditionalOnMissingBean
	LessonRefinementAdvisor.EffectGuard lessonRefinementEffectGuard() {
		return new LessonRefinementAdvisor.EffectGuard();
	}
	@Bean @ConditionalOnMissingBean
	LessonSkillExport lessonSkillExport(LessonService lessons, AgentProperties properties) throws java.io.IOException {
		return new LessonSkillExport(lessons, com.example.miniagent.core.StatePaths.home(properties.getHome()));
	}
	@Bean @ConditionalOnMissingBean
	LessonCommand lessonCommand(LessonService lessons, LessonSkillExport export, AgentProperties properties) {
		return new LessonCommand(lessons, export, properties.getCwd());
	}
}
