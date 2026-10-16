package com.example.miniagent.plugin.trace;

import java.util.List;

import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.TraceEvent.Tokens;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 턴 요약 한 줄과 TUI 합계가 읽는 {@link TurnRecord} 집계. */
class TurnRecordTest {

	@Test
	void roundsAddUpTheirTokensAndKeepTheLastReportedModelAndEffort() {
		TurnRecord turn = new TurnRecord(1);
		turn.roundFinished(round(List.of("read_file {}"), null, new Tokens(100, 10, 40), "model-a", "low"));
		turn.roundFinished(round(List.of(), null, new Tokens(250, 30, 100), "model-b", "high"));

		TraceEvent.TurnFinished finished = turn.finish();

		assertThat(finished.tokens()).isEqualTo(new Tokens(350, 40, 140));
		assertThat(finished.model()).isEqualTo("model-b");
		assertThat(finished.reasoningEffort()).isEqualTo("high");
	}

	@Test
	void aLaterRoundWithoutModelOrEffortKeepsTheEarlierOnes() {
		TurnRecord turn = new TurnRecord(1);
		turn.roundFinished(round(List.of("read_file {}"), null, new Tokens(5, 1, 0), "model-a", "medium"));
		turn.roundFinished(round(List.of(), null, new Tokens(7, 2, 0), null, null));

		TraceEvent.TurnFinished finished = turn.finish();

		assertThat(finished.model()).isEqualTo("model-a");
		assertThat(finished.reasoningEffort()).isEqualTo("medium");
		assertThat(finished.tokens()).isEqualTo(new Tokens(12, 3, 0));
	}

	@Test
	void aTurnWithoutFinishedRoundsHasNoUsageNoModelAndIsNotAnswered() {
		TraceEvent.TurnFinished finished = new TurnRecord(3).finish();

		assertThat(finished.tokens()).isEqualTo(Tokens.ZERO);
		assertThat(finished.model()).isNull();
		assertThat(finished.reasoningEffort()).isNull();
		assertThat(finished.answered()).isFalse();
		assertThat(finished.rounds()).isZero();
		assertThat(finished.toolCount()).isZero();
		assertThat(finished.toolSummary()).isEmpty();
	}

	@Test
	void turnIsAnsweredOnlyOnceTheLastRoundGivesAnAnswerWithoutToolCalls() {
		TurnRecord turn = new TurnRecord(1);
		turn.roundFinished(round(List.of("read_file {}"), null, Tokens.ZERO, null, null));
		assertThat(turn.finish().answered()).isFalse();

		turn.roundFinished(round(List.of(), null, Tokens.ZERO, null, null));
		assertThat(turn.finish().answered()).isTrue();
	}

	@Test
	void aFinalRoundCutOffByTheProviderIsNotAnswered() {
		TurnRecord turn = new TurnRecord(1);
		turn.roundFinished(round(List.of(), "LENGTH", Tokens.ZERO, null, null));

		assertThat(turn.finish().answered()).isFalse();
	}

	@Test
	void finishReportsTheRoundCounterTheToolCountAndANonNegativeDuration() {
		TurnRecord turn = new TurnRecord(1);
		turn.rounds.incrementAndGet();
		turn.rounds.incrementAndGet();
		turn.toolExecuted("read_file");

		TraceEvent.TurnFinished finished = turn.finish();

		assertThat(finished.rounds()).isEqualTo(2);
		assertThat(finished.toolCount()).isEqualTo(1);
		assertThat(finished.durationNanos()).isNotNegative();
	}

	@Test
	void toolSummaryCountsRepeatsPerNameInFirstSeenOrder() {
		TurnRecord turn = new TurnRecord(1);
		turn.toolExecuted("read_file");
		turn.toolExecuted("list_dir");
		turn.toolExecuted("read_file");

		TraceEvent.TurnFinished finished = turn.finish();

		assertThat(finished.toolSummary()).isEqualTo("read_file ×2, list_dir");
		assertThat(finished.toolCount()).isEqualTo(3);
	}

	@Test
	void singleToolRunIsListedWithoutACount() {
		TurnRecord turn = new TurnRecord(1);
		turn.toolExecuted("read_file");

		assertThat(turn.finish().toolSummary()).isEqualTo("read_file");
	}

	@Test
	void failedAndCancelledToolsAreSummarisedSeparatelyFromSuccessfulRuns() {
		TurnRecord turn = new TurnRecord(1);
		turn.toolExecuted("bash");
		turn.toolFailed("bash", false);
		turn.toolFailed("bash", false);
		turn.toolFailed("bash", true);

		TraceEvent.TurnFinished finished = turn.finish();

		assertThat(finished.toolSummary()).isEqualTo("bash, bash (오류) ×2, bash (취소)");
		assertThat(finished.toolCount()).isEqualTo(4);
	}

	private static TraceEvent.RoundFinished round(List<String> toolCalls, @Nullable String abnormalFinish, Tokens tokens,
			@Nullable String model, @Nullable String reasoningEffort) {
		return new TraceEvent.RoundFinished(1, toolCalls, 0, abnormalFinish, 0L, tokens, model, reasoningEffort);
	}

}
