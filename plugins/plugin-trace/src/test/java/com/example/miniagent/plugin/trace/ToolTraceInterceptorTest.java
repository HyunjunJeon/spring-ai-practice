package com.example.miniagent.plugin.trace;

import java.util.ArrayList;
import java.util.List;

import com.example.miniagent.core.ToolInterceptor.ToolInvocation;
import com.example.miniagent.core.TraceEvent;
import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 도구가 예외로 끝나도 기록이 남아야 화면이 권한 거부와 구분할 수 있다. */
class ToolTraceInterceptorTest {

	private final List<Object> events = new ArrayList<>();

	private final TraceSettings settings = new TraceSettings("summary");

	private final ToolTraceInterceptor interceptor = new ToolTraceInterceptor(this.events::add, this.settings);

	@Test
	void toolErrorIsRecordedAsFailureAndRethrown() {
		TurnRecord turn = new TurnRecord(2);
		this.settings.setCurrentTurn(turn);

		assertThatThrownBy(() -> this.interceptor.intercept(invocation("read_file"), call -> {
			throw new IllegalStateException("wrapped", new IllegalArgumentException("Access denied: /x"));
		})).hasMessage("wrapped");

		assertThat(this.events).singleElement().isInstanceOfSatisfying(TraceEvent.ToolFailed.class, e -> {
			assertThat(e.error()).isEqualTo("Access denied: /x");
			assertThat(e.cancelled()).isFalse();
		});
		assertThat(turn.finish().toolSummary()).isEqualTo("read_file (오류)");
	}

	@Test
	void interruptedToolIsRecordedAsCancelled() {
		Thread.currentThread().interrupt();
		try {
			assertThatThrownBy(() -> this.interceptor.intercept(invocation("bash"), call -> {
				throw new IllegalStateException("Interrupted", new InterruptedException());
			})).hasMessage("Interrupted");
		}
		finally {
			Thread.interrupted();
		}

		assertThat(this.events).singleElement()
			.isInstanceOfSatisfying(TraceEvent.ToolFailed.class, e -> assertThat(e.cancelled()).isTrue());
	}

	private static ToolInvocation invocation(String tool) {
		return new ToolInvocation(ToolDefinition.builder().name(tool).description(tool).inputSchema("{}").build(), "{}",
				null);
	}

}
