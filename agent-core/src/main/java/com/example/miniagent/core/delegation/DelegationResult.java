package com.example.miniagent.core.delegation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.miniagent.core.run.RunUsage;

/** Only aggregation can construct a summary; callers cannot supply a fabricated COMPLETE. */
public final class DelegationResult {
	public enum Status { COMPLETE, PARTIAL, FAILED, CANCELLED }

	private final String planId;
	private final Status status;
	private final List<ChildResult> children;
	private final RunUsage usage;

	private DelegationResult(String planId, Status status, List<ChildResult> children, RunUsage usage) {
		this.planId = Objects.requireNonNull(planId);
		this.status = Objects.requireNonNull(status);
		this.children = List.copyOf(children);
		this.usage = Objects.requireNonNull(usage);
	}

	public String planId() { return this.planId; }
	public Status status() { return this.status; }
	public List<ChildResult> children() { return this.children; }
	public RunUsage usage() { return this.usage; }

	public static DelegationResult aggregate(DelegationPlan plan, List<ChildResult> results,
			boolean parentCancelled, String revision, String configurationDigest) {
		return aggregateChecked(plan, results, parentCancelled,
				(spec, result) -> result.verifiedSuccess(spec.role(), revision, configurationDigest));
	}

	static DelegationResult aggregateChecked(DelegationPlan plan, List<ChildResult> results,
			boolean parentCancelled, java.util.function.BiPredicate<ChildSpec, ChildResult> proof) {
		var specs = plan.children().stream().collect(Collectors.toMap(ChildSpec::childId, Function.identity()));
		var seen = new HashSet<String>();
		var identities = new HashSet<String>();
		RunUsage usage = RunUsage.empty();
		int successes = 0;
		boolean requiredSucceeded = true;
		for (ChildResult result : results) {
			ChildSpec spec = specs.get(result.childId());
			if (spec == null || !seen.add(result.childId()) || !result.status().terminal()) {
				throw new IllegalArgumentException("Foreign, duplicate or nonterminal child result");
			}
			if (!identities.add(result.conversationId().toString()) || !identities.add(result.runId().value())
					|| !identities.add(result.attemptId().toString())) {
				throw new IllegalArgumentException("Shared child execution identity");
			}
			boolean verified = proof.test(spec, result);
			if (verified) successes++;
			if (spec.required() && !verified) requiredSucceeded = false;
			if (spec.model() != null && !spec.model().equals(result.model())) {
				throw new IllegalArgumentException("Explicit child model mismatch");
			}
			usage = usage.plus(result.usage());
		}
		if (!seen.equals(specs.keySet())) throw new IllegalArgumentException("Missing child results");
		Status status = parentCancelled ? Status.CANCELLED
				: successes == 0 ? Status.FAILED : requiredSucceeded ? Status.COMPLETE : Status.PARTIAL;
		return new DelegationResult(plan.planId(), status, results, usage);
	}
}
