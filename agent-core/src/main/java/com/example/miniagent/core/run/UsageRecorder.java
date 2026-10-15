package com.example.miniagent.core.run;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.metadata.EmptyUsage;

/** One run's synchronized meter. In-flight token overshoot is observed, never hidden. */
public final class UsageRecorder {
	private final RunBudget budget;
	private final Supplier<Instant> clock;
	private long tokens;
	private boolean unknown;
	private long tools;
	private long priorActive;
	private long elapsed;
	private long emittedActive;
	private Instant interval;
	private int pauses;

	public UsageRecorder(RunBudget budget, Supplier<Instant> clock, RunUsage prior) {
		this.budget = Objects.requireNonNull(budget);
		this.clock = Objects.requireNonNull(clock);
		this.tokens = prior.knownTokens();
		this.unknown = prior.tokensUnknown();
		this.tools = prior.toolCalls();
		this.priorActive = prior.activeMillis();
		this.interval = clock.get();
	}

	public synchronized void beforeModel() {
		checkTime();
		if (this.budget.tokenCap() != null
				&& (this.unknown || this.tokens >= this.budget.tokenCap())) {
			exhausted(this.unknown ? "Token usage is unknown" : "Token budget exhausted");
		}
	}

	public synchronized RunUsage model(@Nullable Usage usage) {
		long previousOvershoot = overshoot();
		Integer total = usage == null || usage instanceof EmptyUsage ? null : usage.getTotalTokens();
		boolean missing = total == null;
		long measured = missing ? 0 : total.longValue();
		if (measured < 0) {
			throw new IllegalArgumentException("Negative token usage");
		}
		this.tokens = Math.addExact(this.tokens, measured);
		this.unknown |= missing;
		return new RunUsage(measured, missing, 0, 0, overshoot() - previousOvershoot);
	}

	/** Counts model-requested tool attempts, including denied attempts. */
	public synchronized RunUsage toolAttempt() {
		beforeModel();
		if (this.tools >= this.budget.toolCallCap()) {
			exhausted("Tool-call budget exhausted");
		}
		this.tools = Math.addExact(this.tools, 1);
		return new RunUsage(0, false, 1, 0, 0);
	}

	public synchronized void pause() {
		if (this.pauses++ == 0) {
			this.elapsed = Math.addExact(this.elapsed, intervalMillis());
		}
	}

	public synchronized void resume() {
		if (this.pauses == 0) {
			throw new IllegalStateException("Budget clock is not paused");
		}
		if (--this.pauses == 0) {
			this.interval = this.clock.get();
		}
	}

	public synchronized RunUsage activeDelta() {
		long current = active();
		long delta = current - this.emittedActive;
		this.emittedActive = current;
		return new RunUsage(0, false, 0, delta, 0);
	}

	public synchronized RunUsage total() {
		return new RunUsage(this.tokens, this.unknown, this.tools,
				Math.addExact(this.priorActive, active()), overshoot());
	}

	/** Translate a child's overshoot into this parent's cap, without mutating before fsync. */
	synchronized RunUsage importedDelta(RunUsage delta) {
		long nextTokens = Math.addExact(this.tokens, delta.knownTokens());
		Math.addExact(this.tools, delta.toolCalls());
		Math.addExact(this.priorActive, delta.activeMillis());
		long nextOvershoot = this.budget.tokenCap() == null ? 0
				: Math.max(0, nextTokens - this.budget.tokenCap());
		return new RunUsage(delta.knownTokens(), delta.tokensUnknown(), delta.toolCalls(),
				delta.activeMillis(), nextOvershoot - overshoot());
	}

	/** Only an acknowledged imported delta enters live enforcement; activeDelta remains parent-only. */
	synchronized void importAcknowledged(RunUsage delta) {
		this.tokens = Math.addExact(this.tokens, delta.knownTokens());
		this.unknown |= delta.tokensUnknown();
		this.tools = Math.addExact(this.tools, delta.toolCalls());
		this.priorActive = Math.addExact(this.priorActive, delta.activeMillis());
	}

	private long active() {
		return Math.addExact(this.elapsed, this.pauses == 0 ? intervalMillis() : 0);
	}

	private long intervalMillis() {
		long value = Duration.between(this.interval, this.clock.get()).toMillis();
		if (value < 0) {
			throw new IllegalStateException("Run clock moved backwards");
		}
		return value;
	}

	private void checkTime() {
		if (Math.addExact(this.priorActive, active()) >= this.budget.wall().toMillis()) {
			exhausted("Active time budget exhausted");
		}
	}

	private long overshoot() {
		return this.budget.tokenCap() == null ? 0 : Math.max(0, this.tokens - this.budget.tokenCap());
	}

	private static void exhausted(String reason) {
		throw new Stop(Stop.Kind.BUDGET_EXHAUSTED, reason);
	}
}
