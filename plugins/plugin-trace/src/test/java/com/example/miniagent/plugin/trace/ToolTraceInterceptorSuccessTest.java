package com.example.miniagent.plugin.trace;

import java.util.ArrayList;
import java.util.List;

import com.example.miniagent.core.ToolInterceptor.ToolInvocation;
import com.example.miniagent.core.TraceEvent;
import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 정상 종료한 도구는 {@code ToolExecuted} 와 턴 요약에 남고, 인터셉터는 결과를 그대로 돌려준다. */
class ToolTraceInterceptorSuccessTest {

	private final List<Object> events = new ArrayList<>();

	private final TraceSettings settings = new TraceSettings("summary");

	private final ToolTraceInterceptor interceptor = new ToolTraceInterceptor(this.events::add, this.settings);

	@Test
	void successfulToolIsPublishedWithItsResultLengthAndListedInTheTurn() {
		TurnRecord turn = new TurnRecord(2);
		this.settings.setCurrentTurn(turn);

		String result = this.interceptor.intercept(invocation("read_file"), call -> "abc");

		assertThat(result).isEqualTo("abc");
		assertThat(this.events).singleElement().isInstanceOfSatisfying(TraceEvent.ToolExecuted.class, e -> {
			assertThat(e.name()).isEqualTo("read_file");
			assertThat(e.resultChars()).isEqualTo(3);
			assertThat(e.durationNanos()).isNotNegative();
		});
		assertThat(turn.finish().toolSummary()).isEqualTo("read_file");
		assertThat(turn.finish().toolCount()).isEqualTo(1);
	}

	@Test
	void interceptorPassesTheInvocationOnUnchanged() {
		ToolInvocation invocation = invocation("read_file");
		List<ToolInvocation> proceeded = new ArrayList<>();

		this.interceptor.intercept(invocation, call -> {
			proceeded.add(call);
			return "";
		});

		assertThat(proceeded).containsExactly(invocation);
		assertThat(this.events).singleElement()
			.isInstanceOfSatisfying(TraceEvent.ToolExecuted.class, e -> assertThat(e.resultChars()).isZero());
	}

	@Test
	void successWithoutACurrentTurnStillPublishesTheEvent() {
		assertThat(this.settings.currentTurn()).isNull();

		String result = this.interceptor.intercept(invocation("list_dir"), call -> "a\nb");

		assertThat(result).isEqualTo("a\nb");
		assertThat(this.events).singleElement()
			.isInstanceOfSatisfying(TraceEvent.ToolExecuted.class, e -> assertThat(e.resultChars()).isEqualTo(3));
	}

	@Test
	void failureWithoutACurrentTurnStillPublishesTheEventAndRethrows() {
		IllegalStateException failure = new IllegalStateException("boom");

		assertThatThrownBy(() -> this.interceptor.intercept(invocation("bash"), call -> {
			throw failure;
		})).isSameAs(failure);

		assertThat(this.events).singleElement()
			.isInstanceOfSatisfying(TraceEvent.ToolFailed.class, e -> assertThat(e.error()).isEqualTo("boom"));
	}

	@Test
	void failureWithoutAMessageReportsTheExceptionClassName() {
		assertThatThrownBy(() -> this.interceptor.intercept(invocation("bash"), call -> {
			throw new IllegalStateException();
		})).isInstanceOf(IllegalStateException.class);

		assertThat(this.events).singleElement()
			.isInstanceOfSatisfying(TraceEvent.ToolFailed.class,
					e -> assertThat(e.error()).isEqualTo("IllegalStateException"));
	}

	@Test
	void failureReportsTheClassNameOfAnInnermostCauseWithoutAMessage() {
		assertThatThrownBy(() -> this.interceptor.intercept(invocation("bash"), call -> {
			throw new IllegalStateException("wrapped", new UnsupportedOperationException());
		})).hasMessage("wrapped");

		assertThat(this.events).singleElement()
			.isInstanceOfSatisfying(TraceEvent.ToolFailed.class,
					e -> assertThat(e.error()).isEqualTo("UnsupportedOperationException"));
	}

	@Test
	void interceptorRunsInnermostSoOnlyCallsThatPassedEarlierInterceptorsAreRecorded() {
		assertThat(this.interceptor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
	}

	private static ToolInvocation invocation(String tool) {
		return new ToolInvocation(ToolDefinition.builder().name(tool).description(tool).inputSchema("{}").build(), "{}",
				null);
	}

}
