package com.example.miniagent.plugin.claude;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OAuth 와 모델 호출을 Java 가 직접 처리한다. */
@ConfigurationProperties("agent.claude")
public class ClaudeProperties {

	private String model = "claude-sonnet-5-5";

	private String baseUrl = "https://api.anthropic.com";

	private int maxTokens = 4096;

	private Path authStore = Path.of(System.getProperty("user.home"), ".mini-agent", "auth", "claude.json");

	private Duration timeout = Duration.ofSeconds(120);

	private Duration oauthTimeout = Duration.ofSeconds(30);

	private int maxResponseBytes = 8 * 1024 * 1024;

	/** 구독 OAuth 프로토콜의 CLI 버전 식별자. 거부되면 현재 지원 버전으로 명시적으로 갱신한다. */
	private String cliVersion = "2.1.289";

	public String getModel() {
		return this.model;
	}

	public String getBaseUrl() {
		return this.baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public int getMaxTokens() {
		return this.maxTokens;
	}

	public void setMaxTokens(int maxTokens) {
		this.maxTokens = maxTokens;
	}

	public Path getAuthStore() {
		return this.authStore;
	}

	public void setAuthStore(Path authStore) {
		this.authStore = authStore;
	}

	public Duration getTimeout() {
		return this.timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public Duration getOauthTimeout() {
		return this.oauthTimeout;
	}

	public void setOauthTimeout(Duration oauthTimeout) {
		this.oauthTimeout = oauthTimeout;
	}

	public int getMaxResponseBytes() {
		return this.maxResponseBytes;
	}

	public void setMaxResponseBytes(int maxResponseBytes) {
		this.maxResponseBytes = maxResponseBytes;
	}

	public String getCliVersion() {
		return this.cliVersion;
	}

	public void setCliVersion(String cliVersion) {
		this.cliVersion = cliVersion;
	}

}
