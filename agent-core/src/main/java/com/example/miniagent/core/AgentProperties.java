package com.example.miniagent.core;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code agent.*} 속성. 플러그인 on/off 는 각 플러그인이 {@code agent.plugins.<id>.enabled} 로 직접 읽는다.
 */
@ConfigurationProperties("agent")
public class AgentProperties {

	/** 모든 플러그인 섹션보다 앞에 오는 기본 시스템 프롬프트. */
	private String systemPrompt = """
			You are agent-forge, an agent running in the user's terminal.
			Use the available tools when they help. Keep answers short and concrete.
			Respond in the same language as the user.""";

	/** 도구가 상대 경로를 해석할 기준 디렉터리. */
	private Path cwd = Path.of(System.getProperty("user.dir"));

	/** 세션, 스킬 등 사용자 데이터가 저장되는 위치. */
	private Path home = Path.of(System.getProperty("user.home"), ".mini-agent");

	public String getSystemPrompt() {
		return this.systemPrompt;
	}

	public void setSystemPrompt(String systemPrompt) {
		this.systemPrompt = systemPrompt;
	}

	public Path getCwd() {
		return this.cwd;
	}

	public void setCwd(Path cwd) {
		this.cwd = cwd;
	}

	public Path getHome() {
		return this.home;
	}

	public void setHome(Path home) {
		this.home = home;
	}

}
