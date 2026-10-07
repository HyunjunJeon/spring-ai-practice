package com.example.miniagent.plugin.codex;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code agent.codex.*} 속성. */
@ConfigurationProperties("agent.codex")
public class CodexProperties {

	/** ChatGPT 구독으로 쓸 수 있는 모델 slug. {@code /codex models} 로 목록을 본다. */
	private String model = "gpt-6-luna";

	/** low | medium | high | xhigh | max … 모델마다 지원 범위가 다르다. {@code /codex effort} 로 확인. */
	private String reasoningEffort = "xhigh";

	private String baseUrl = "https://chatgpt.com/backend-api/codex";

	/** 요청을 보내는 클라이언트 이름. 다른 클라이언트(codex_cli_rs 등)를 사칭하지 않는다. */
	private String originator = "mini-agent";

	/**
	 * 토큰 출처.
	 * <ul>
	 *   <li>auto: {@code /login} 으로 받은 자체 토큰이 있으면 그것, 없으면 Codex CLI 토큰(읽기 전용)</li>
	 *   <li>mini-agent: 자체 토큰만</li>
	 *   <li>codex-cli: Codex CLI 토큰만 (읽기 전용, 절대 갱신하지 않음)</li>
	 * </ul>
	 */
	private String auth = "auto";

	/** /login 때 기본 브라우저를 자동으로 열지. 끄면 URL 만 출력한다(원격 터미널 등). */
	private boolean openBrowser = true;

	/** Codex CLI 홈 디렉터리. auth.json, models_cache.json 을 읽는다. */
	private Path codexHome = Path.of(System.getProperty("user.home"), ".codex");

	/**
	 * 응답(헤더나 SSE 줄)이 이 시간 동안 하나도 오지 않으면 호출을 끊는다. 추론을 오래 하는 모델이 중간에 아무것도
	 * 보내지 않을 수 있으므로 넉넉히 잡는다.
	 */
	private Duration idleTimeout = Duration.ofMinutes(5);

	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getReasoningEffort() {
		return this.reasoningEffort;
	}

	public void setReasoningEffort(String reasoningEffort) {
		this.reasoningEffort = reasoningEffort;
	}

	public String getBaseUrl() {
		return this.baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getOriginator() {
		return this.originator;
	}

	public void setOriginator(String originator) {
		this.originator = originator;
	}

	public String getAuth() {
		return this.auth;
	}

	public void setAuth(String auth) {
		this.auth = auth;
	}

	public boolean isOpenBrowser() {
		return this.openBrowser;
	}

	public void setOpenBrowser(boolean openBrowser) {
		this.openBrowser = openBrowser;
	}

	public Path getCodexHome() {
		return this.codexHome;
	}

	public void setCodexHome(Path codexHome) {
		this.codexHome = codexHome;
	}

	public Duration getIdleTimeout() {
		return this.idleTimeout;
	}

	public void setIdleTimeout(Duration idleTimeout) {
		this.idleTimeout = idleTimeout;
	}

}
