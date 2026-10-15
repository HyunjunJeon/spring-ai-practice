package com.example.miniagent.core.artifact;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Storage is always below agent.home/artifacts; no model-controlled root exists. */
@ConfigurationProperties("agent.artifacts")
public class ArtifactProperties {
	private int thresholdChars = 8192;
	private int previewChars = 256;
	private int maxArtifactBytes = 4 * 1024 * 1024;
	private long maxScopeBytes = 64L * 1024 * 1024;
	private int maxArtifacts = 128;

	public int getThresholdChars() { return this.thresholdChars; }
	public void setThresholdChars(int value) { this.thresholdChars = value; }
	public int getPreviewChars() { return this.previewChars; }
	public void setPreviewChars(int value) { this.previewChars = value; }
	public int getMaxArtifactBytes() { return this.maxArtifactBytes; }
	public void setMaxArtifactBytes(int value) { this.maxArtifactBytes = value; }
	public long getMaxScopeBytes() { return this.maxScopeBytes; }
	public void setMaxScopeBytes(long value) { this.maxScopeBytes = value; }
	public int getMaxArtifacts() { return this.maxArtifacts; }
	public void setMaxArtifacts(int value) { this.maxArtifacts = value; }

	void validate() {
		if (this.thresholdChars < 1 || this.thresholdChars > 1024 * 1024
				|| this.previewChars < 0 || this.previewChars > 512
				|| this.maxArtifactBytes < 1 || this.maxArtifactBytes > 16 * 1024 * 1024
				|| this.maxScopeBytes < 1 || this.maxScopeBytes > 1024L * 1024 * 1024
				|| this.maxArtifacts < 1 || this.maxArtifacts > 1024)
			throw new IllegalArgumentException("Invalid agent.artifacts limits");
	}
}
