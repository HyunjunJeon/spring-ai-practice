package com.example.miniagent.core.run;

import java.util.Objects;

/** Explicit future broker/budget signal, distinct from user cancellation and process loss. */
public final class Stop extends RuntimeException {
	public enum Kind { INTERRUPTED, BUDGET_EXHAUSTED, DEFERRED, CANCELLED }
	private final Kind kind;

	public Stop(Kind kind) {
		super(Objects.requireNonNull(kind).name());
		this.kind = kind;
	}

	public Stop(Kind kind, String message) {
		super(Objects.requireNonNull(message));
		this.kind = Objects.requireNonNull(kind);
	}

	public Kind kind() { return this.kind; }
}
