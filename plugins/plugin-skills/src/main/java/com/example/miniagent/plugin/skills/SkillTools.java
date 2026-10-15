package com.example.miniagent.plugin.skills;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 점진적 공개(progressive disclosure): 시스템 프롬프트에는 스킬의 이름/설명만 넣고,
 * 전체 지침은 모델이 이 도구를 불렀을 때만 컨텍스트에 들어온다.
 */
public class SkillTools {

	private final SkillRegistry registry;

	public SkillTools(SkillRegistry registry) {
		this.registry = registry;
	}

	@Tool(name = "load_skill", description = "Load the full instructions of a skill listed in the system prompt. "
			+ "Call this before doing a task that matches the skill's description.")
	public String loadSkill(@ToolParam(description = "Skill name exactly as listed") String name) {
		return this.registry.find(name)
			.map(skill -> "Skill directory: %s (resolve relative paths against it)%n%n%s".formatted(skill.directory(),
					skill.instructions()))
			.orElseThrow(() -> new IllegalArgumentException("Unknown skill: " + name));
	}

}
