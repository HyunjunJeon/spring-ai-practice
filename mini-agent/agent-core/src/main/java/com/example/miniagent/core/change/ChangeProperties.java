package com.example.miniagent.core.change;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Storage limits; process execution and staged-mode policy belong to later tasks. */
@ConfigurationProperties("agent.changes")
public record ChangeProperties(@DefaultValue("16777216") int maxBlobBytes,
		@DefaultValue("4194304") int maxManifestBytes, @DefaultValue("67108864") int maxJournalBytes) {
	public ChangeProperties {
		if (maxBlobBytes < 1 || maxManifestBytes < 1 || maxJournalBytes < maxManifestBytes
				|| maxBlobBytes == Integer.MAX_VALUE || maxJournalBytes == Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Invalid change storage limits");
		}
	}
	public static ChangeProperties defaults() { return new ChangeProperties(16777216, 4194304, 67108864); }
}
