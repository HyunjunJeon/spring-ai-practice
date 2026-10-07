package com.example.miniagent.core.delegation;

import java.util.Objects;

/** Recovery interruption and uncertain effects must survive projection, never become success. */
public record ChildFailure(Code code, String detail, boolean retryable) {
	public enum Code {
		MODEL_UNAVAILABLE, SCOPE_DENIED, BUDGET_EXHAUSTED, DEPENDENCY_FAILED, TOOL_FAILED,
		VERIFICATION_FAILED, QUEUE_REJECTED, CANCELLED, TIMED_OUT, INTERRUPTED, UNKNOWN_EFFECT
	}

	public ChildFailure {
		Objects.requireNonNull(code);
		Objects.requireNonNull(detail);
		if (retryable && (code == Code.UNKNOWN_EFFECT || code == Code.INTERRUPTED
				|| code == Code.CANCELLED || code == Code.DEPENDENCY_FAILED)) {
			throw new IllegalArgumentException("Failure requires explicit recovery, not automatic retry");
		}
	}
}
