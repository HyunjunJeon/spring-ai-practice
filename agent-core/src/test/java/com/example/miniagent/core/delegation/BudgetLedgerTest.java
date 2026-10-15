package com.example.miniagent.core.delegation;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetLedgerTest {
	private final RunBudget parent = new RunBudget(Duration.ofSeconds(10), 10L, 10);
	private final RunBudget child = new RunBudget(Duration.ofSeconds(4), 4L, 4);
	private final AtomicReference<RunUsage> used = new AtomicReference<>(RunUsage.empty());
	private final BudgetLedger ledger = new BudgetLedger(this.parent, this.used::get);
	private void refuses(BudgetLedger.Code code, Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BudgetLedger.Refusal.class,
				ex -> assertThat(ex.code()).isEqualTo(code));
	}
	@Test void batchReservationCannotPartiallyConsumeParentCapacity() {
		refuses(BudgetLedger.Code.RESERVATION_EXCEEDED,
				() -> this.ledger.reserve(Map.of("a", this.child, "b", this.parent)));
		this.ledger.reserve(Map.of("a", this.child, "b", this.child));
		refuses(BudgetLedger.Code.DUPLICATE_CHILD, () -> this.ledger.reserve(Map.of("a", this.child)));
		assertThat(this.ledger.usage()).isEqualTo(RunUsage.empty());
	}
	@Test void retryUsesRemainingOriginalReservationAndCountsNoDeltaTwice() {
		this.ledger.reserve(Map.of("a", this.child));
		this.ledger.observe("a", new RunUsage(2, false, 1, 1000, 0));
		assertThat(this.ledger.remaining("a")).isEqualTo(new RunBudget(Duration.ofSeconds(3), 2L, 3));
		this.ledger.observe("a", new RunUsage(1, false, 2, 2000, 0));
		assertThat(this.ledger.settle("a")).isEqualTo(new RunUsage(3, false, 3, 3000, 0));
		assertThat(this.ledger.usage()).isEqualTo(new RunUsage(3, false, 3, 3000, 0));
		refuses(BudgetLedger.Code.SETTLED, () -> this.ledger.settle("a"));
	}
	@Test void unknownUsageInOneChildStopsNewSiblingCallsAndReservations() {
		this.ledger.reserve(Map.of("a", this.child, "b", this.child));
		this.ledger.observe("a", new RunUsage(0, true, 0, 0, 0));
		refuses(BudgetLedger.Code.UNKNOWN_USAGE, () -> this.ledger.beforeModel("b"));
		refuses(BudgetLedger.Code.UNKNOWN_USAGE, () -> this.ledger.beforeTool("b"));
		refuses(BudgetLedger.Code.UNKNOWN_USAGE, () -> this.ledger.reserve(Map.of("c", this.child)));
		assertThat(this.ledger.usage().tokensUnknown()).isTrue();
	}
	@Test void parentConsumptionStillTakesPrecedenceAndOvershootIsPreserved() {
		this.ledger.reserve(Map.of("a", this.child));
		this.ledger.observe("a", new RunUsage(6, false, 1, 10, 2));
		assertThat(this.ledger.usage().overshootTokens()).isEqualTo(2);
		refuses(BudgetLedger.Code.EXHAUSTED, () -> this.ledger.beforeModel("a"));
		this.used.set(new RunUsage(4, false, 9, 0, 0));
		refuses(BudgetLedger.Code.EXHAUSTED, () -> this.ledger.beforeTool("a"));
	}
	@Test void settledReservationsReleaseOnlyUnspentCapacity() {
		this.ledger.reserve(Map.of("a", this.child));
		this.ledger.observe("a", new RunUsage(1, false, 1, 1000, 0));
		this.ledger.settle("a");
		this.ledger.reserve(Map.of("b", new RunBudget(Duration.ofSeconds(9), 9L, 9)));
		assertThat(this.ledger.usage()).isEqualTo(new RunUsage(1, false, 1, 1000, 0));
	}
	@Test void inFlightOvershootReducesNewReservationCapacityBeforeSettlement() {
		this.ledger.reserve(Map.of("a", this.child));
		this.ledger.observe("a", new RunUsage(6, false, 0, 0, 2));
		refuses(BudgetLedger.Code.RESERVATION_EXCEEDED,
				() -> this.ledger.reserve(Map.of("b", new RunBudget(Duration.ofSeconds(1), 5L, 1))));
		assertThat(this.ledger.usage().knownTokens()).isEqualTo(6);
		assertThat(this.ledger.usage().overshootTokens()).isEqualTo(2);
	}
	@Test void followUpsRetainReservationUsageAndUnknownFlags() {
		this.ledger.reserve(Map.of("a", this.child));
		this.ledger.observe("a", new RunUsage(1, false, 1, 100, 0));
		this.ledger.settle("a");
		this.ledger.continueChild("a");
		assertThat(this.ledger.remaining("a")).isEqualTo(new RunBudget(Duration.ofMillis(3900), 3L, 3));
		this.ledger.observe("a", new RunUsage(2, false, 2, 200, 0));
		this.ledger.settle("a");
		this.ledger.continueChild("a");
		assertThat(this.ledger.remaining("a")).isEqualTo(new RunBudget(Duration.ofMillis(3700), 1L, 1));
		this.ledger.observe("a", new RunUsage(0, true, 0, 0, 0));
		this.ledger.settle("a");
		refuses(BudgetLedger.Code.UNKNOWN_USAGE, () -> this.ledger.continueChild("a"));
		assertThat(this.ledger.usageFor("a").knownTokens()).isEqualTo(3);
		assertThat(this.ledger.usageFor("a").tokensUnknown()).isTrue();
	}
	@Test void settledChildCannotResetExhaustedToolCap() {
		this.ledger.reserve(Map.of("a", this.child));
		this.ledger.observe("a", new RunUsage(0, false, 4, 0, 0));
		this.ledger.settle("a");
		refuses(BudgetLedger.Code.EXHAUSTED, () -> this.ledger.continueChild("a"));
		assertThat(this.ledger.usageFor("a").toolCalls()).isEqualTo(4);
	}
}
