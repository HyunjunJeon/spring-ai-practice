package com.example.miniagent.core.run;

import java.io.IOException;

public final class RunStoreException extends IOException {
	public enum Code {
		IO, RUN_LOCKED, CORRUPT, FUTURE_SCHEMA, INVALID_TRANSITION, TERMINAL, CLOSED,
		ALREADY_EXISTS, NOT_FOUND, UNSUPPORTED_FILESYSTEM
	}

	private final Code code;

	public RunStoreException(Code code, String message) {
		super(message);
		this.code = code;
	}

	public RunStoreException(Code code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public Code code() {
		return this.code;
	}
}
