package com.example.miniagent.core.delegation;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunUsage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Managed parent (parentIncludesChildren): the parent run record already holds every child delta. */
class BudgetLedgerManagedParentTest {
	private final RunBudget parent = new RunBudget(Duration.ofSeconds(10), 10L, 10);
	private final RunBudget child = new RunBudget(Duration.ofSeconds(4), 4L, 4);
	private final AtomicReference<RunUsage> parentRecord = new AtomicReference<>(RunUsage.empty());

	private BudgetLedger managed() {
		return new BudgetLedger(this.parent, this.parentRecord::get, true);
	}

	private void refuses(BudgetLedger.Code code, Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BudgetLedger.Refusal.class,
				ex -> assertThat(ex.code()).isEqualTo(code));
	}

	/** Child "a" spent 1 token, 3 tools and 1s of its 4/4/4s reservation; the parent record shows the same. */
	private BudgetLedger withChildInFlight() {
		var ledger = managed();
		ledger.reserve(Map.of("a", this.child));
		var delta = new RunUsage(1, false, 3, 1000, 0);
		ledger.observe("a", delta);
		this.parentRecord.set(delta);
		return ledger;
	}

	@Test void inFlightChildHoldsOnlyItsUnspentRemainder() {
		// 10 - 1 spent - 3 unspent remainder of "a" = 6 in every unit; counting "a" twice would leave 5.
		withChildInFlight().reserve(Map.of("b", new RunBudget(Duration.ofSeconds(6), 6L, 6)));
		for (var over : List.of(new RunBudget(Duration.ofMillis(6001), 6L, 6),
				new RunBudget(Duration.ofSeconds(6), 7L, 6), new RunBudget(Duration.ofSeconds(6), 6L, 7))) {
			refuses(BudgetLedger.Code.RESERVATION_EXCEEDED, () -> withChildInFlight().reserve(Map.of("b", over)));
		}
	}

	@Test void overspentChildRemainderIsZeroNotNegative() {
		var ledger = managed();
		ledger.reserve(Map.of("a", this.child));
		var overshoot = new RunUsage(0, false, 6, 0, 0);
		ledger.observe("a", overshoot);
		this.parentRecord.set(overshoot);
		refuses(BudgetLedger.Code.RESERVATION_EXCEEDED,
				() -> ledger.reserve(Map.of("b", new RunBudget(Duration.ofSeconds(1), 1L, 5))));
		ledger.reserve(Map.of("b", new RunBudget(Duration.ofSeconds(1), 1L, 4)));
	}

	@Test void settledChildIsChargedOnlyThroughTheParentRecord() {
		var ledger = managed();
		ledger.reserve(Map.of("a", this.child));
		var delta = new RunUsage(2, false, 2, 2000, 0);
		ledger.observe("a", delta);
		this.parentRecord.set(delta);
		assertThat(ledger.settle("a")).isEqualTo(delta);
		refuses(BudgetLedger.Code.RESERVATION_EXCEEDED,
				() -> ledger.reserve(Map.of("b", new RunBudget(Duration.ofMillis(8001), 8L, 8))));
		ledger.reserve(Map.of("b", new RunBudget(Duration.ofSeconds(8), 8L, 8)));
	}

	@Test void usageIsTheParentRecordWhileChildDeltasStayPerChild() {
		var ledger = managed();
		ledger.reserve(Map.of("a", this.child));
		var delta = new RunUsage(3, false, 1, 500, 0);
		ledger.observe("a", delta);
		assertThat(ledger.usage()).isEqualTo(RunUsage.empty());
		assertThat(ledger.usageFor("a")).isEqualTo(delta);
		this.parentRecord.set(delta);
		assertThat(ledger.usage()).isEqualTo(delta);
	}

	@Test void childWallIsExhaustedAtItsReservation() {
		var ledger = managed();
		ledger.reserve(Map.of("a", this.child));
		ledger.observe("a", new RunUsage(0, false, 0, 3999, 0));
		ledger.beforeModel("a");
		assertThat(ledger.remaining("a")).isEqualTo(new RunBudget(Duration.ofMillis(1), 4L, 4));
		ledger.observe("a", new RunUsage(0, false, 0, 1, 0));
		refuses(BudgetLedger.Code.EXHAUSTED, () -> ledger.beforeModel("a"));
		refuses(BudgetLedger.Code.EXHAUSTED, () -> ledger.beforeTool("a"));
		refuses(BudgetLedger.Code.EXHAUSTED, () -> ledger.remaining("a"));
	}

	@Test void childToolCallCapStopsTheSecondToolButNotTheNextModelCall() {
		var ledger = managed();
		ledger.reserve(Map.of("a", new RunBudget(Duration.ofSeconds(4), 4L, 1)));
		ledger.beforeTool("a");
		var oneTool = new RunUsage(0, false, 1, 0, 0);
		ledger.observe("a", oneTool);
		this.parentRecord.set(oneTool);
		refuses(BudgetLedger.Code.EXHAUSTED, () -> ledger.beforeTool("a"));
		ledger.beforeModel("a");
		assertThat(ledger.remaining("a")).isEqualTo(new RunBudget(Duration.ofSeconds(4), 4L, 0));
	}

	@Test void parentRecordAloneDecidesParentExhaustion() {
		var ledger = managed();
		ledger.reserve(Map.of("a", this.child));
		this.parentRecord.set(new RunUsage(0, false, 10, 0, 0));
		refuses(BudgetLedger.Code.EXHAUSTED, () -> ledger.beforeTool("a"));
		ledger.beforeModel("a");
		this.parentRecord.set(new RunUsage(0, false, 0, 10_000, 0));
		refuses(BudgetLedger.Code.EXHAUSTED, () -> ledger.beforeModel("a"));
	}

	@Test void remainingRefusesUnknownTokensOnlyForATokenCappedChild() {
		var ledger = managed();
		ledger.reserve(Map.of("a", this.child));
		ledger.observe("a", new RunUsage(0, true, 0, 0, 0));
		refuses(BudgetLedger.Code.UNKNOWN_USAGE, () -> ledger.remaining("a"));
		var uncappedParent = new BudgetLedger(new RunBudget(Duration.ofSeconds(10), null, 10), this.parentRecord::get, true);
		uncappedParent.reserve(Map.of("u", new RunBudget(Duration.ofSeconds(4), null, 4)));
		uncappedParent.observe("u", new RunUsage(0, true, 1, 1000, 0));
		assertThat(uncappedParent.remaining("u")).isEqualTo(new RunBudget(Duration.ofSeconds(3), null, 3));
	}

	@Test void unknownOrSettledChildIdsAreRefusedWithoutTouchingTheLedger() {
		var ledger = managed();
		ledger.reserve(Map.of("a", this.child));
		refuses(BudgetLedger.Code.NOT_RESERVED, () -> ledger.remaining("ghost"));
		refuses(BudgetLedger.Code.NOT_RESERVED, () -> ledger.observe("ghost", new RunUsage(1, false, 1, 1, 0)));
		refuses(BudgetLedger.Code.NOT_RESERVED, () -> ledger.settle("ghost"));
		refuses(BudgetLedger.Code.NOT_RESERVED, () -> ledger.beforeModel("ghost"));
		refuses(BudgetLedger.Code.NOT_RESERVED, () -> ledger.beforeTool("ghost"));
		refuses(BudgetLedger.Code.NOT_RESERVED, () -> ledger.usageFor("ghost"));
		refuses(BudgetLedger.Code.NOT_RESERVED, () -> ledger.continueChild("ghost"));
		assertThatThrownBy(() -> ledger.continueChild("a")).isInstanceOf(IllegalStateException.class)
				.isNotInstanceOf(BudgetLedger.Refusal.class).hasMessage("Child is busy");
		ledger.settle("a");
		refuses(BudgetLedger.Code.SETTLED, () -> ledger.remaining("a"));
		refuses(BudgetLedger.Code.SETTLED, () -> ledger.observe("a", new RunUsage(1, false, 1, 1, 0)));
		assertThat(ledger.usageFor("a")).isEqualTo(RunUsage.empty());
	}

	@Test void blankIdOrSubMillisecondWallAdmitsNoChildOfTheBatch() {
		var ledger = managed();
		assertThatThrownBy(() -> ledger.reserve(Map.of(" ", this.child)))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Missing child ID");
		refuses(BudgetLedger.Code.RESERVATION_EXCEEDED, () -> ledger.reserve(Map.of("ok", this.child,
				"tiny", new RunBudget(Duration.ofNanos(1), 1L, 1))));
		refuses(BudgetLedger.Code.NOT_RESERVED, () -> ledger.usageFor("ok"));
		refuses(BudgetLedger.Code.NOT_RESERVED, () -> ledger.usageFor("tiny"));
	}
}
