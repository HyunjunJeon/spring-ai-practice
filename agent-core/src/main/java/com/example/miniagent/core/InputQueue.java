package com.example.miniagent.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/** Console-owned, bounded mailbox. Its monitor also owns the idle transition. */
public final class InputQueue {
	public enum Kind { STEER, FOLLOW_UP }
	public record Target(String sessionId, UUID generation) {}
	public record Entry(long sequence, Kind kind, String text) {}

	private final int maxCount;
	private final int maxBytes;
	private final ArrayDeque<Entry> steering = new ArrayDeque<>();
	private final ArrayDeque<Entry> followUps = new ArrayDeque<>();
	private Target target;
	private boolean interactive;
	private boolean cancelled;
	private int count;
	private int bytes;
	private long sequence;

	public InputQueue() { this(32, 64 * 1024); }

	public InputQueue(int maxCount, int maxBytes) {
		if (maxCount < 1 || maxBytes < 1) throw new IllegalArgumentException("Queue limits must be positive");
		this.maxCount = maxCount;
		this.maxBytes = maxBytes;
	}

	public synchronized Target begin(String sessionId, boolean interactive) {
		if (this.target != null) throw new IllegalStateException("SESSION_BUSY");
		this.target = new Target(sessionId, UUID.randomUUID());
		this.interactive = interactive;
		this.cancelled = false;
		this.count = 0;
		this.bytes = 0;
		return this.target;
	}

	public synchronized java.util.Optional<Target> target() {
		return java.util.Optional.ofNullable(this.target);
	}

	public synchronized void offer(Target expected, String currentSession, Kind kind, String text) {
		require(expected, currentSession);
		if (!this.interactive) throw new IllegalStateException("QUEUE_MANAGED_COMMAND");
		if (this.cancelled) throw new IllegalStateException("QUEUE_CANCELLED");
		if (text.isBlank()) throw new IllegalArgumentException("Queue text must not be blank");
		int size = text.getBytes(StandardCharsets.UTF_8).length;
		// Charge all accepted entries for the batch, including consumed steering.
		if (this.count == this.maxCount || size > this.maxBytes - this.bytes)
			throw new IllegalStateException("QUEUE_FULL");
		var entry = new Entry(++this.sequence, kind, text);
		(kind == Kind.STEER ? this.steering : this.followUps).addLast(entry);
		this.count++;
		this.bytes += size;
	}

	synchronized List<Entry> drainSteering(Target expected, String currentSession) {
		require(expected, currentSession);
		if (this.cancelled) throw new java.util.concurrent.CancellationException("Queue cancelled");
		List<Entry> result = List.copyOf(this.steering);
		this.steering.clear();
		return result;
	}

	/** Late steering is promoted to a literal next turn, ahead of follow-ups. */
	public synchronized Entry nextOrEnd(Target expected, String currentSession) {
		require(expected, currentSession);
		if (this.cancelled) { end(expected); return null; }
		Entry next = this.steering.pollFirst();
		if (next == null) next = this.followUps.pollFirst();
		if (next == null) end(expected);
		return next;
	}

	public synchronized void cancel(Target expected) {
		if (!expected.equals(this.target)) return;
		this.cancelled = true;
		this.steering.clear();
		this.followUps.clear();
	}

	public synchronized void end(Target expected) {
		if (!expected.equals(this.target)) return;
		this.steering.clear();
		this.followUps.clear();
		this.target = null;
	}

	private void require(Target expected, String currentSession) {
		if (!expected.equals(this.target) || !expected.sessionId().equals(currentSession))
			throw new IllegalStateException("QUEUE_STALE_TARGET");
	}
}
