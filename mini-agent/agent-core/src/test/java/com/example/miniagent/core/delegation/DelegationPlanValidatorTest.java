package com.example.miniagent.core.delegation;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.miniagent.core.change.ChangeOwner;
import com.example.miniagent.core.change.VerificationEvidence;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunUsage;
import com.example.miniagent.core.run.SideEffectClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DelegationPlanValidatorTest {
	@TempDir Path root;
	private final DelegationPlanValidator validator = new DelegationPlanValidator();
	private final ModelRef model = new ModelRef("fake", "test");
	private final String revision = "a".repeat(64);
	private final String configuration = "b".repeat(64);

	private ToolScope parent() {
		return new ToolScope(Set.of("read"), Set.of(this.root), Set.of(), Set.of(), false, false, true, false);
	}

	private ToolScope childScope() {
		return new ToolScope(Set.of("read"), Set.of(this.root), Set.of(), Set.of(), false, false, false, false);
	}

	private ChildSpec child(String id, Set<String> dependencies, RunBudget budget) {
		return new ChildSpec(id, "Inspect source", ChildRole.RESEARCH, null, dependencies, childScope(), budget, 2, true);
	}

	private RunBudget reservation() { return new RunBudget(Duration.ofMinutes(1), 10L, 1); }
	private DelegationPlan plan(ChildSpec... children) { return new DelegationPlan("plan", List.of(children), 1); }

	private DelegationPlanValidator.ValidatedPlan validate(DelegationPlan plan) {
		return this.validator.validate(plan, parent(), RunBudget.defaults(), RunUsage.empty(),
				DelegationProperties.defaults());
	}

	private void rejects(DelegationPlan plan, DelegationPlanValidator.Code code) {
		var error = assertThrows(DelegationPlanValidator.Rejection.class, () -> validate(plan));
		assertThat(error.code()).isEqualTo(code);
	}

	@Test void cycleRejectsBeforeAnyStart() {
		var cyclic = plan(child("a", Set.of("b"), reservation()), child("b", Set.of("a"), reservation()));
		var starts = new AtomicInteger();
		var error = assertThrows(DelegationPlanValidator.Rejection.class, () -> {
			var validated = validate(cyclic);
			validated.topologicalOrder().forEach(ignored -> starts.incrementAndGet());
		});
		assertThat(error.code()).isEqualTo(DelegationPlanValidator.Code.CYCLE);
		assertThat(starts).hasValue(0);
	}

	@Test void validatedOrderRespectsDependenciesNotInputOrderOrNestingDepth() {
		var a = child("a", Set.of(), reservation());
		var b = child("b", Set.of("a"), reservation());
		var c = child("c", Set.of("b"), reservation());
		var input = plan(c, b, a);
		var result = validate(input);
		assertThat(result.plan()).isSameAs(input);
		assertThat(result.topologicalOrder()).containsExactly(a, b, c);
		assertThatThrownBy(() -> result.topologicalOrder().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test void invalidIdsDuplicateChildrenMissingEdgesSelfEdgesAndEmptyPlansReject() {
		rejects(plan(child("../a", Set.of(), reservation())), DelegationPlanValidator.Code.INVALID_ID);
		rejects(new DelegationPlan("../plan", List.of(child("a", Set.of(), reservation())), 1),
				DelegationPlanValidator.Code.INVALID_ID);
		rejects(plan(child("a", Set.of(), reservation()), child("a", Set.of(), reservation())),
				DelegationPlanValidator.Code.DUPLICATE_CHILD);
		rejects(plan(child("a", Set.of("missing"), reservation())), DelegationPlanValidator.Code.INVALID_EDGE);
		rejects(plan(child("a", Set.of("a"), reservation())), DelegationPlanValidator.Code.INVALID_EDGE);
		rejects(plan(child("a", Set.of("../edge"), reservation())), DelegationPlanValidator.Code.INVALID_ID);
		rejects(plan(), DelegationPlanValidator.Code.EMPTY_PLAN);
	}

	@Test void depthAndAttemptsAreFiniteAndBounded() {
		var a = child("a", Set.of(), reservation());
		rejects(new DelegationPlan("plan", List.of(a), 0), DelegationPlanValidator.Code.DEPTH_EXCEEDED);
		rejects(new DelegationPlan("plan", List.of(a), 2), DelegationPlanValidator.Code.DEPTH_EXCEEDED);
		for (int attempts : List.of(0, 3)) {
			rejects(plan(new ChildSpec("a", "Inspect", ChildRole.RESEARCH, null, Set.of(), childScope(),
					reservation(), attempts, true)), DelegationPlanValidator.Code.INVALID_ATTEMPTS);
		}
	}

	@Test void defaultsShareRunBudgetAndDoNotMultiplyReservationsByAttempts() {
		var properties = DelegationProperties.defaults();
		assertThat(properties.concurrency()).isEqualTo(2);
		assertThat(properties.queueCapacity()).isEqualTo(8);
		assertThat(properties.maxDepth()).isEqualTo(1);
		assertThat(properties.maxAttempts()).isEqualTo(2);
		assertThat(properties.budget()).isEqualTo(RunBudget.defaults());
		var full = child("a", Set.of(), RunBudget.defaults());
		assertThat(validate(plan(full)).topologicalOrder()).containsExactly(full);
	}

	@Test void aggregateReservationsExceedingRemainingParentAreRejectedInEveryUnit() {
		var pair = plan(child("a", Set.of(), reservation()), child("b", Set.of(), reservation()));
		List<RunBudget> budgets = List.of(new RunBudget(Duration.ofMinutes(1), 100L, 50),
				new RunBudget(Duration.ofMinutes(30), 19L, 50),
				new RunBudget(Duration.ofMinutes(30), 100L, 1));
		for (RunBudget budget : budgets) {
			var error = assertThrows(DelegationPlanValidator.Rejection.class, () -> this.validator.validate(
					pair, parent(), budget, RunUsage.empty(), DelegationProperties.defaults()));
			assertThat(error.code()).isEqualTo(DelegationPlanValidator.Code.RESERVATION_EXCEEDED);
		}
		var budget = new RunBudget(Duration.ofMinutes(2), 20L, 2);
		this.validator.validate(pair, parent(), budget, RunUsage.empty(), DelegationProperties.defaults());
		for (RunUsage usage : List.of(new RunUsage(1, false, 0, 0, 0), new RunUsage(0, false, 1, 0, 0),
				new RunUsage(0, false, 0, 1, 0))) {
			var error = assertThrows(DelegationPlanValidator.Rejection.class, () -> this.validator.validate(
					pair, parent(), budget, usage, DelegationProperties.defaults()));
			assertThat(error.code()).isEqualTo(DelegationPlanValidator.Code.RESERVATION_EXCEEDED);
		}
	}

	@Test void unknownUsageAndUncappedChildNeverBypassFiniteParentTokenLimit() {
		var finite = new RunBudget(Duration.ofMinutes(30), 100L, 50);
		var unknown = new RunUsage(0, true, 0, 0, 0);
		var bounded = plan(child("a", Set.of(), reservation()));
		var error = assertThrows(DelegationPlanValidator.Rejection.class, () -> this.validator.validate(
				bounded, parent(), finite, unknown, DelegationProperties.defaults()));
		assertThat(error.code()).isEqualTo(DelegationPlanValidator.Code.UNKNOWN_USAGE);
		var uncapped = plan(child("a", Set.of(), new RunBudget(Duration.ofMinutes(1), null, 1)));
		error = assertThrows(DelegationPlanValidator.Rejection.class, () -> this.validator.validate(
				uncapped, parent(), finite, RunUsage.empty(), DelegationProperties.defaults()));
		assertThat(error.code()).isEqualTo(DelegationPlanValidator.Code.RESERVATION_EXCEEDED);
		assertThat(this.validator.validate(uncapped, parent(), RunBudget.defaults(), unknown,
				DelegationProperties.defaults()).plan()).isSameAs(uncapped);
	}

	@Test void invalidReservationsAndArithmeticOverflowRejectWithoutStartingChildren() {
		assertThatThrownBy(() -> new RunBudget(Duration.ZERO, null, 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunBudget(Duration.ofSeconds(1), -1L, 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunBudget(Duration.ofSeconds(1), null, -1))
				.isInstanceOf(IllegalArgumentException.class);
		rejects(plan(child("a", Set.of(), new RunBudget(Duration.ofNanos(1), null, 0))),
				DelegationPlanValidator.Code.INVALID_RESERVATION);
		rejects(plan(child("a", Set.of(), new RunBudget(Duration.ofMinutes(31), null, 0))),
				DelegationPlanValidator.Code.RESERVATION_EXCEEDED);
		var huge = new RunBudget(Duration.ofMinutes(1), Long.MAX_VALUE, 0);
		rejects(plan(child("a", Set.of(), huge), child("b", Set.of(), huge)),
				DelegationPlanValidator.Code.INVALID_RESERVATION);
	}

	@Test void parentScopeWideningAndParentWithoutDelegationArePlanFailures() {
		var wider = new ToolScope(Set.of("write"), Set.of(this.root), Set.of(), Set.of(), false, false, false, false);
		rejects(plan(new ChildSpec("a", "Inspect", ChildRole.RESEARCH, null, Set.of(), wider, reservation(), 1, true)),
				DelegationPlanValidator.Code.SCOPE_DENIED);
		var input = plan(child("a", Set.of(), reservation()));
		var error = assertThrows(DelegationPlanValidator.Rejection.class, () -> this.validator.validate(
				input, childScope(), RunBudget.defaults(), RunUsage.empty(), DelegationProperties.defaults()));
		assertThat(error.code()).isEqualTo(DelegationPlanValidator.Code.SCOPE_DENIED);
	}

	@Test void immutableSnapshotsAndExplicitOrInheritedModelDoNotResolveRuntimeAvailability() {
		var deps = new HashSet<String>();
		var a = new ChildSpec("a", "Inspect", ChildRole.RESEARCH, null, deps, childScope(), reservation(), 1, true);
		deps.add("missing");
		var children = new ArrayList<>(List.of(a));
		var input = new DelegationPlan("plan", children, 1);
		children.clear();
		assertThat(validate(input).topologicalOrder()).containsExactly(a);
		assertThat(a.effectiveModel(this.model)).isEqualTo(this.model);
		var unavailable = new ModelRef("unregistered", "not-installed");
		var explicit = new ChildSpec("b", "Inspect", ChildRole.RESEARCH, unavailable, Set.of(), childScope(),
				reservation(), 1, true);
		assertThat(explicit.effectiveModel(this.model)).isSameAs(unavailable);
		assertThat(validate(plan(explicit)).topologicalOrder()).containsExactly(explicit);
	}

	private ChildResult result(String id, boolean proof, ChildFailure failure, RunUsage usage) {
		UUID conversation = UUID.randomUUID();
		RunId run = RunId.random();
		UUID attempt = UUID.randomUUID();
		List<ChildEvidence> evidence = new ArrayList<>();
		evidence.add(new ChildEvidence.ModelNarrative("Test passed"));
		if (proof) {
			var owner = new ChangeOwner(conversation.toString(), run, attempt.toString(), "turn");
			evidence.add(new ChildEvidence.Verification(com.example.miniagent.core.change.VerificationRunner.Code.SUCCEEDED,
					new VerificationEvidence(UUID.randomUUID(), UUID.randomUUID(),
					owner, List.of("test"), this.root.toString(), this.revision, this.configuration,
					"c".repeat(64), 0, false, Instant.EPOCH, Instant.EPOCH)));
		}
		return new ChildResult(id, conversation, run, attempt, this.model,
				failure == null ? ChildStatus.SUCCEEDED : ChildStatus.FAILED, failure, evidence, usage);
	}

	@Test void modelNarrativeCannotProveSuccessAndStaleVerificationCannotCompletePlan() {
		var input = plan(child("a", Set.of(), reservation()));
		var narrative = result("a", false, null, RunUsage.empty());
		assertThat(narrative.verifiedSuccess(this.revision, this.configuration)).isFalse();
		assertThat(DelegationResult.aggregate(input, List.of(narrative), false, this.revision, this.configuration).status())
				.isEqualTo(DelegationResult.Status.FAILED);
		var proven = result("a", true, null, RunUsage.empty());
		assertThat(proven.verifiedSuccess(this.revision, this.configuration)).isTrue();
		assertThat(DelegationResult.aggregate(input, List.of(proven), false, "d".repeat(64), this.configuration).status())
				.isEqualTo(DelegationResult.Status.FAILED);
	}

	@Test void staleOutcomeWithHonestZeroExitCannotCompletePlan() {
		var proven = result("a", true, null, RunUsage.empty());
		var verification = (ChildEvidence.Verification) proven.evidence().getLast();
		assertThat(verification.evidence().exitCode()).isZero();
		var stale = new ChildResult("a", proven.conversationId(), proven.runId(), proven.attemptId(),
				proven.model(), proven.status(), null,
				List.of(new ChildEvidence.Verification(com.example.miniagent.core.change.VerificationRunner.Code.STALE,
						verification.evidence())), proven.usage());
		assertThat(stale.verifiedSuccess(this.revision, this.configuration)).isFalse();
		assertThat(DelegationResult.aggregate(plan(child("a", Set.of(), reservation())),
				List.of(stale), false, this.revision, this.configuration).status())
				.isEqualTo(DelegationResult.Status.FAILED);
	}

	@Test void aggregationReportsCompletePartialFailedAndParentCancellationWithHonestUsage() {
		var input = plan(child("a", Set.of(), reservation()), child("b", Set.of(), reservation()));
		var a = result("a", true, null, new RunUsage(7, false, 2, 3, 1));
		var b = result("b", true, null, new RunUsage(0, true, 1, 4, 2));
		var complete = DelegationResult.aggregate(input, List.of(a, b), false, this.revision, this.configuration);
		assertThat(complete.status()).isEqualTo(DelegationResult.Status.COMPLETE);
		assertThat(complete.usage()).isEqualTo(new RunUsage(7, true, 3, 7, 3));
		assertThatThrownBy(() -> complete.children().clear()).isInstanceOf(UnsupportedOperationException.class);
		var interrupted = new ChildFailure(ChildFailure.Code.INTERRUPTED, "Process lost", false);
		var failed = result("b", false, interrupted, RunUsage.empty());
		assertThat(DelegationResult.aggregate(input, List.of(a, failed), false, this.revision, this.configuration).status())
				.isEqualTo(DelegationResult.Status.PARTIAL);
		assertThat(DelegationResult.aggregate(input, List.of(a, b), true, this.revision, this.configuration).status())
				.isEqualTo(DelegationResult.Status.CANCELLED);
		assertThatThrownBy(() -> new ChildFailure(ChildFailure.Code.INTERRUPTED, "Lost", true))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(failed.failure().code()).isEqualTo(ChildFailure.Code.INTERRUPTED);
	}

	@Test void unknownToolEffectsCannotBeVerifiedAndForeignOrDuplicateResultsReject() {
		var input = plan(child("a", Set.of(), reservation()));
		var proven = result("a", true, null, RunUsage.empty());
		var evidence = new ArrayList<>(proven.evidence());
		evidence.add(new ChildEvidence.ToolResult("call", "read", "c".repeat(64),
				SideEffectClass.MUTATING, ChildEvidence.ToolResult.Outcome.UNKNOWN));
		var unknown = new ChildResult("a", proven.conversationId(), proven.runId(), proven.attemptId(),
				proven.model(), proven.status(), null, evidence, proven.usage());
		assertThat(unknown.verifiedSuccess(this.revision, this.configuration)).isFalse();
		for (List<ChildResult> results : List.of(List.<ChildResult>of(), List.of(proven, proven),
				List.of(result("foreign", true, null, RunUsage.empty())))) {
			assertThatThrownBy(() -> DelegationResult.aggregate(input, results, false, this.revision, this.configuration))
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> new ChildResult("a", UUID.randomUUID(), proven.runId(), proven.attemptId(),
				proven.model(), proven.status(), null, proven.evidence(), proven.usage()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test void nonterminalResultsSharedIdentitiesAndFalseFailureStatusesCannotBeAggregated() {
		var a = result("a", true, null, RunUsage.empty());
		var input = plan(child("a", Set.of(), reservation()));
		var running = new ChildResult("a", a.conversationId(), a.runId(), a.attemptId(), a.model(),
				ChildStatus.RUNNING, null, List.of(), RunUsage.empty());
		assertThatThrownBy(() -> DelegationResult.aggregate(input, List.of(running), false,
				this.revision, this.configuration)).isInstanceOf(IllegalArgumentException.class);
		var b = new ChildResult("b", a.conversationId(), a.runId(), a.attemptId(), a.model(),
				ChildStatus.SUCCEEDED, null, a.evidence(), RunUsage.empty());
		var pair = plan(child("a", Set.of(), reservation()), child("b", Set.of(), reservation()));
		assertThatThrownBy(() -> DelegationResult.aggregate(pair, List.of(a, b), false,
				this.revision, this.configuration)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ChildResult("a", a.conversationId(), a.runId(), a.attemptId(), a.model(),
				ChildStatus.SUCCEEDED, new ChildFailure(ChildFailure.Code.INTERRUPTED, "Lost", false),
				List.of(), RunUsage.empty())).isInstanceOf(IllegalArgumentException.class);
		for (ChildStatus status : List.of(ChildStatus.SKIPPED, ChildStatus.CANCELLED, ChildStatus.TIMED_OUT)) {
			assertThatThrownBy(() -> new ChildResult("a", a.conversationId(), a.runId(), a.attemptId(), a.model(),
					status, new ChildFailure(ChildFailure.Code.TOOL_FAILED, "Wrong reason", false),
					List.of(), RunUsage.empty())).isInstanceOf(IllegalArgumentException.class);
		}
	}
}
