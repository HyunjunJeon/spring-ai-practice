package com.example.miniagent.plugin.skills;

import java.util.Objects;

import com.example.miniagent.core.SkillUseListener;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 점진적 공개(progressive disclosure): 시스템 프롬프트에는 스킬의 이름/설명만 넣고,
 * 전체 지침은 모델이 이 도구를 불렀을 때만 컨텍스트에 들어온다.
 */
public class SkillTools {

	private final SkillRegistry registry;

	private final SkillUseListener listener;

	public SkillTools(SkillRegistry registry) {
		this(registry, SkillUseListener.NONE);
	}

	/** 아래의 기록 실패 처리가 null 리스너의 예외까지 삼키지 않도록, 리스너가 없으면 만들 때 거부한다. */
	public SkillTools(SkillRegistry registry, SkillUseListener listener) {
		this.registry = registry;
		this.listener = Objects.requireNonNull(listener, "listener");
	}

	@Tool(name = "load_skill", description = "Load the full instructions of a skill listed in the system prompt. "
			+ "Call this before doing a task that matches the skill's description.")
	public String loadSkill(@ToolParam(description = "Skill name exactly as listed") String name) {
		SkillRegistry.Skill skill = this.registry.find(name)
			.orElseThrow(() -> new IllegalArgumentException("Unknown skill: " + name));
		try {
			this.listener.loaded(skill.directory(), SkillUseListener.Kind.CHOSEN);
		}
		catch (RuntimeException ex) {
			// 사용 기록은 best-effort 다. 리스너가 실패해도 모델은 지침을 그대로 받는다.
		}
		return "Skill directory: %s (resolve relative paths against it)%n%n%s".formatted(skill.directory(),
				skill.instructions());
	}

}
