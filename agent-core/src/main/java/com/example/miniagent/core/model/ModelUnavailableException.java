package com.example.miniagent.core.model;

public final class ModelUnavailableException extends IllegalStateException {

	public enum Code {
		AUTH_MISSING, UNKNOWN_MODEL
	}

	private final Code code;

	private final ModelRef ref;

	public ModelUnavailableException(Code code, ModelRef ref) {
		super(code + ": " + ref.provider() + "/" + ref.model());
		this.code = java.util.Objects.requireNonNull(code, "code");
		this.ref = java.util.Objects.requireNonNull(ref, "ref");
	}

	public Code code() {
		return this.code;
	}

	public ModelRef ref() {
		return this.ref;
	}

}
