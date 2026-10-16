package com.example.miniagent.plugin.skills;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.StatePaths;
import com.example.miniagent.core.SystemPromptContributor;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;

/**
 * 스킬 = 시스템 프롬프트 섹션(목록) + 도구(load_skill) + 명령(/skills, /skill).
 * 찾는 위치: {@code ~/.mini-agent/skills} 와 {@code <작업 디렉터리>/.mini-agent/skills}.
 */
@AutoConfiguration
@ConditionalOnBooleanProperty(name = "agent.plugins.skills.enabled", matchIfMissing = true)
public class SkillsPluginAutoConfiguration {

	@Bean
	SkillRegistry skillRegistry(AgentProperties properties) {
		Path projectSkills = StatePaths.projectSkills(properties.getCwd());
		Path userSkills = StatePaths.userSkills(properties.getHome());
		return new SkillRegistry(List.of(projectSkills, userSkills));
	}

	@Bean
	SystemPromptContributor skillsPromptContributor(SkillRegistry registry) {
		return () -> {
			List<SkillRegistry.Skill> skills = registry.all();
			if (skills.isEmpty()) {
				return "";
			}
			String list = skills.stream()
				.map(s -> "- " + s.name() + ": " + s.description())
				.collect(Collectors.joining("\n"));
			return """
					# Skills
					Skills are task-specific instructions. When a request matches a skill's description,
					call the load_skill tool with its name first, then follow the loaded instructions.
					%s""".formatted(list);
		};
	}

	@Bean
	ToolCallbackProvider skillToolsProvider(SkillRegistry registry) {
		return MethodToolCallbackProvider.builder().toolObjects(new SkillTools(registry)).build();
	}

	@Bean
	SlashCommand skillsCommand(SkillRegistry registry) {
		return SlashCommand.of("skills", "발견된 스킬 목록", (args, ctx) -> {
			if (registry.all().isEmpty()) {
				ctx.console().println("스킬이 없습니다. .mini-agent/skills/<이름>/SKILL.md 를 만들어 보세요.");
			}
			registry.all().forEach(s -> ctx.console().println("- %-20s %s".formatted(s.name(), s.description())));
		});
	}

	/** 명령이 에이전트를 직접 구동하는 예: 스킬 지침을 강제로 붙여 한 턴을 실행한다. */
	@Bean
	SlashCommand skillCommand(SkillRegistry registry) {
		return SlashCommand.of("skill", "스킬을 강제로 적용해 실행 (/skill <이름> <요청>)", (args, ctx) -> {
			String[] parts = args.strip().split("\\s+", 2);
			registry.find(parts[0]).ifPresentOrElse(skill -> {
				String request = (parts.length > 1) ? parts[1] : "";
				String prompt = "Follow these skill instructions:\n\n%s\n\nUser request: %s".formatted(skill.instructions(), request);
				Agent.AgentReply reply = ctx.agent().run(prompt);
				ctx.console().println(reply.text());
			}, () -> ctx.console().error("알 수 없는 스킬: " + parts[0]));
		});
	}

	@Bean
	AgentPlugin skillsPlugin(SkillRegistry registry) {
		return AgentPlugin.of("skills", registry.all().size() + "개 스킬 (SKILL.md)");
	}

}
