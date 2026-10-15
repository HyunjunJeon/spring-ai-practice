package com.example.miniagent.core.delegation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.example.miniagent.core.run.RunId;

/** In-process execution ownership; durable run writers still use RunStore leases. */
public final class SessionRunLock {
	public static final class Busy extends IllegalStateException {
		private final String conversationId;
		private final RunId runId;
		private Busy(String conversationId, RunId runId) {
			super("SESSION_BUSY: " + conversationId + " run=" + runId.value());
			this.conversationId = conversationId;
			this.runId = runId;
		}
		public String conversationId() { return this.conversationId; }
		public RunId runId() { return this.runId; }
	}

	private final Map<String, Lease> owners = new HashMap<>();

	public synchronized Lease acquire(String conversationId, RunId runId) {
		if (conversationId == null || conversationId.isBlank())
			throw new IllegalArgumentException("Missing conversation ID");
		Objects.requireNonNull(runId);
		Lease existing = this.owners.get(conversationId);
		if (existing != null) throw new Busy(conversationId, existing.runId);
		var lease = new Lease(conversationId, runId);
		this.owners.put(conversationId, lease);
		return lease;
	}

	public final class Lease implements AutoCloseable {
		private final String conversationId;
		private final RunId runId;
		private boolean closed;
		private Lease(String conversationId, RunId runId) {
			this.conversationId = conversationId;
			this.runId = runId;
		}
		public String conversationId() { return this.conversationId; }
		public RunId runId() { return this.runId; }
		@Override public void close() {
			synchronized (SessionRunLock.this) {
				if (this.closed) return;
				SessionRunLock.this.owners.remove(this.conversationId, this);
				this.closed = true;
			}
		}
	}
}
