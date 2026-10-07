package com.example.miniagent.core.model;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/** Explicit turn snapshot. Async callers must carry and open this on their own thread. */
public record TurnContext(String sessionId, String turnId, Path cwd, ModelBinding binding,
		@Nullable String runId, @Nullable String attemptId) {

	private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

	public TurnContext {
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(turnId, "turnId");
		Objects.requireNonNull(cwd, "cwd");
		Objects.requireNonNull(binding, "binding");
		cwd = cwd.toAbsolutePath().normalize();
	}

	public TurnContext(String sessionId, String turnId, Path cwd, ModelBinding binding) {
		this(sessionId, turnId, cwd, binding, null, null);
	}

	public Scope open() {
		Scope scope = new Scope(this, CURRENT.get());
		CURRENT.set(scope);
		return scope;
	}

	public static Optional<TurnContext> current() {
		Scope scope = CURRENT.get();
		return scope == null ? Optional.empty() : Optional.of(scope.context);
	}

	public static final class Scope implements AutoCloseable {

		private final TurnContext context;

		private final @Nullable Scope previous;

		private final Thread owner = Thread.currentThread();

		private boolean closed;

		private Scope(TurnContext context, @Nullable Scope previous) {
			this.context = context;
			this.previous = previous;
		}

		@Override
		public void close() {
			if (Thread.currentThread() != this.owner || this.closed || CURRENT.get() != this) {
				throw new IllegalStateException("Turn scopes must close once, in order, on their owner thread");
			}
			if (this.previous == null) {
				CURRENT.remove();
			}
			else {
				CURRENT.set(this.previous);
			}
			this.closed = true;
		}

	}

}
