package com.example.miniagent.core.delegation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunUsage;

/** Reservations use the existing budget units; observed usage remains authoritative. */
public final class BudgetLedger {
	public enum Code { DUPLICATE_CHILD, NOT_RESERVED, SETTLED, RESERVATION_EXCEEDED, UNKNOWN_USAGE, EXHAUSTED }
	public static final class Refusal extends IllegalStateException {
		private final Code code;
		public Refusal(Code code) { super(code.name()); this.code = code; }
		public Code code() { return this.code; }
	}
	private static final class Entry {
		final RunBudget budget;
		RunUsage usage = RunUsage.empty();
		boolean settled;
		Entry(RunBudget budget) { this.budget = budget; }
	}
	private final RunBudget parent;
	private final Supplier<RunUsage> parentUsage;
	private final boolean parentIncludesChildren;
	private final Map<String, Entry> children = new LinkedHashMap<>();

	public BudgetLedger(RunBudget parent, Supplier<RunUsage> parentUsage) {
		this(parent, parentUsage, false);
	}

	public BudgetLedger(RunBudget parent, Supplier<RunUsage> parentUsage, boolean parentIncludesChildren) {
		this.parent = Objects.requireNonNull(parent);
		this.parentUsage = Objects.requireNonNull(parentUsage);
		this.parentIncludesChildren = parentIncludesChildren;
	}

	/** A whole batch is admitted or none is; retries keep the original reservation. */
	public synchronized void reserve(Map<String, RunBudget> reservations) {
		long wall = 0, tools = 0, tokens = 0;
		boolean uncapped = false;
		RunUsage spent = this.parentUsage.get();
		for (Entry entry : this.children.values()) {
			if (entry.settled) {
				if (!this.parentIncludesChildren) spent = spent.plus(entry.usage);
			}
			else if (this.parentIncludesChildren) {
				wall = Math.addExact(wall, Math.max(0, entry.budget.wall().toMillis() - entry.usage.activeMillis()));
				tools = Math.addExact(tools, Math.max(0, entry.budget.toolCallCap() - entry.usage.toolCalls()));
				if (entry.budget.tokenCap() == null) uncapped = true;
				else tokens = Math.addExact(tokens, Math.max(0, entry.budget.tokenCap() - entry.usage.knownTokens()));
			}
			else {
				wall = Math.addExact(wall, Math.max(entry.budget.wall().toMillis(), entry.usage.activeMillis()));
				tools = Math.addExact(tools, Math.max(entry.budget.toolCallCap(), entry.usage.toolCalls()));
				if (entry.budget.tokenCap() == null) uncapped = true;
				else tokens = Math.addExact(tokens, Math.max(entry.budget.tokenCap(), entry.usage.knownTokens()));
			}
		}
		for (var reservation : reservations.entrySet()) {
			if (this.children.containsKey(reservation.getKey())) throw new Refusal(Code.DUPLICATE_CHILD);
			if (reservation.getKey() == null || reservation.getKey().isBlank())
				throw new IllegalArgumentException("Missing child ID");
			var budget = Objects.requireNonNull(reservation.getValue());
			if (budget.wall().toMillis() == 0) throw new Refusal(Code.RESERVATION_EXCEEDED);
			wall = Math.addExact(wall, budget.wall().toMillis());
			tools = Math.addExact(tools, budget.toolCallCap());
			if (budget.tokenCap() == null) uncapped = true;
			else tokens = Math.addExact(tokens, budget.tokenCap());
		}
		if (this.parent.tokenCap() != null && usage().tokensUnknown()) throw new Refusal(Code.UNKNOWN_USAGE);
		if (wall > this.parent.wall().toMillis() - spent.activeMillis()
				|| tools > this.parent.toolCallCap() - spent.toolCalls()
				|| this.parent.tokenCap() != null && (uncapped || tokens > this.parent.tokenCap() - spent.knownTokens()))
			throw new Refusal(Code.RESERVATION_EXCEEDED);
		reservations.forEach((id, budget) -> this.children.put(id, new Entry(budget)));
	}

	public synchronized void beforeModel(String childId) { before(childId, false); }
	public synchronized void beforeTool(String childId) { before(childId, true); }

	private void before(String childId, boolean tool) {
		var entry = active(childId);
		var all = usage();
		if ((this.parent.tokenCap() != null || entry.budget.tokenCap() != null) && all.tokensUnknown())
			throw new Refusal(Code.UNKNOWN_USAGE);
		if (all.activeMillis() >= this.parent.wall().toMillis()
				|| entry.usage.activeMillis() >= entry.budget.wall().toMillis()
				|| this.parent.tokenCap() != null && all.knownTokens() >= this.parent.tokenCap()
				|| entry.budget.tokenCap() != null && entry.usage.knownTokens() >= entry.budget.tokenCap()
				|| tool && (all.toolCalls() >= this.parent.toolCallCap()
						|| entry.usage.toolCalls() >= entry.budget.toolCallCap()))
			throw new Refusal(Code.EXHAUSTED);
	}

	/** Called once per durable delta, not with loop totals or the final cumulative record. */
	public synchronized void observe(String childId, RunUsage delta) {
		var entry = active(childId);
		entry.usage = entry.usage.plus(delta);
	}

	public synchronized RunUsage settle(String childId) {
		var entry = active(childId);
		entry.settled = true;
		return entry.usage;
	}

	public synchronized RunBudget remaining(String childId) {
		var entry = active(childId);
		if (entry.budget.tokenCap() != null && entry.usage.tokensUnknown()) throw new Refusal(Code.UNKNOWN_USAGE);
		long millis = entry.budget.wall().toMillis() - entry.usage.activeMillis();
		if (millis <= 0) throw new Refusal(Code.EXHAUSTED);
		return new RunBudget(java.time.Duration.ofMillis(millis),
				entry.budget.tokenCap() == null ? null : Math.max(0, entry.budget.tokenCap() - entry.usage.knownTokens()),
				(int) Math.max(0, entry.budget.toolCallCap() - entry.usage.toolCalls()));
	}

	public synchronized RunUsage usage() {
		RunUsage usage = this.parentUsage.get();
		if (!this.parentIncludesChildren)
			for (Entry entry : this.children.values()) usage = usage.plus(entry.usage);
		return usage;
	}

	private Entry active(String childId) {
		var entry = this.children.get(childId);
		if (entry == null) throw new Refusal(Code.NOT_RESERVED);
		if (entry.settled) throw new Refusal(Code.SETTLED);
		return entry;
	}
}
