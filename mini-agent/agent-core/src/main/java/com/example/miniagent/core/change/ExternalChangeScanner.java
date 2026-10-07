package com.example.miniagent.core.change;

import java.io.IOException;
import java.util.Map;

/** Live-source drift is never confused with edits in an owner stage. */
public final class ExternalChangeScanner {
	private final Fingerprinter source;
	private final Map<String, Fingerprint> baseline;

	public ExternalChangeScanner(PathPolicy source, Map<String, Fingerprint> baseline) {
		this.source = new Fingerprinter(source);
		this.baseline = Map.copyOf(baseline);
	}

	public boolean changed() throws IOException { return !this.baseline.equals(this.source.snapshot()); }
}
