package com.example.miniagent.core.run;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunBudgetTest {
	@Test
	void unknownUsageWithTokenCapInterrupts() {
		var meter = meter(new RunBudget(Duration.ofMinutes(1), 100L, 50));
		assertThat(meter.model(new EmptyUsage()).tokensUnknown()).isTrue();
		assertThatThrownBy(meter::beforeModel).isInstanceOf(RunExecutor.Stop.class)
			.hasMessageContaining("unknown");
	}

	@Test
	void noToolStartsAfterCap() {
		var meter = meter(new RunBudget(Duration.ofMinutes(1), null, 1));
		assertThat(meter.toolAttempt().toolCalls()).isEqualTo(1);
		assertThatThrownBy(meter::toolAttempt).isInstanceOf(RunExecutor.Stop.class);
		assertThat(meter.total().toolCalls()).isEqualTo(1);
	}

	@Test
	void overshootIsRecordedAndPreventsNextModel() {
		var meter = meter(new RunBudget(Duration.ofMinutes(1), 10L, 50));
		meter.beforeModel();
		assertThat(meter.model(new DefaultUsage(7, 8)).overshootTokens()).isEqualTo(5);
		assertThat(meter.total().knownTokens()).isEqualTo(15);
		assertThatThrownBy(meter::beforeModel).isInstanceOf(RunExecutor.Stop.class);
	}

	@Test
	void unknownUsageWithoutTokenCapDoesNotBlockNextModel() {
		var meter = meter(RunBudget.defaults());
		meter.model(null);
		meter.beforeModel();
		assertThat(meter.total().tokensUnknown()).isTrue();
	}

	@Test
	void approvalWaitIsExcludedAndNestedPausesRestoreTime() {
		AtomicReference<Instant> clock = new AtomicReference<>(Instant.EPOCH);
		var meter = new UsageRecorder(new RunBudget(Duration.ofSeconds(10), null, 50),
				clock::get, RunUsage.empty());
		clock.set(Instant.EPOCH.plusSeconds(2));
		meter.pause();
		meter.pause();
		clock.set(Instant.EPOCH.plusSeconds(100));
		meter.resume();
		assertThat(meter.total().activeMillis()).isEqualTo(2000);
		meter.resume();
		clock.set(Instant.EPOCH.plusSeconds(103));
		assertThat(meter.activeDelta().activeMillis()).isEqualTo(5000);
		assertThat(meter.activeDelta().activeMillis()).isZero();
		clock.set(Instant.EPOCH.plusSeconds(108));
		assertThatThrownBy(meter::beforeModel).isInstanceOf(RunExecutor.Stop.class)
			.hasMessageContaining("time");
	}

	@Test
	void resumedRunSharesPreviouslyConsumedLimits() {
		var meter = new UsageRecorder(new RunBudget(Duration.ofSeconds(10), 10L, 1),
				() -> Instant.EPOCH, new RunUsage(10, false, 1, 10000, 0));
		assertThatThrownBy(meter::beforeModel).isInstanceOf(RunExecutor.Stop.class);
		assertThatThrownBy(meter::toolAttempt).isInstanceOf(RunExecutor.Stop.class);
		assertThat(meter.total().activeMillis()).isEqualTo(10000);
	}

	@Test
	void zeroCapAllowsNoModelAndMeasuredZeroIsNotUnknown() {
		assertThatThrownBy(meter(new RunBudget(Duration.ofMinutes(1), 0L, 50))::beforeModel)
			.isInstanceOf(RunExecutor.Stop.class);
		assertThat(meter(RunBudget.defaults()).model(new DefaultUsage(0, 0)).tokensUnknown()).isFalse();
	}

	private static UsageRecorder meter(RunBudget budget) {
		return new UsageRecorder(budget, () -> Instant.EPOCH, RunUsage.empty());
	}
}
