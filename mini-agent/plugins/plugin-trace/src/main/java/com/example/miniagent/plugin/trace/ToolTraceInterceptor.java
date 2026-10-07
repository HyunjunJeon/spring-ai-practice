package com.example.miniagent.plugin.trace;

import com.example.miniagent.core.ToolInterceptor;
import com.example.miniagent.core.TraceEvent;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;

/**
 * 실제 도구 실행을 기록한다. 가장 안쪽(LOWEST)이라 권한 확인을 통과한 호출만 기록된다.
 *
 * <p>정상 종료는 {@link TraceEvent.ToolExecuted}, 예외(오류·취소)는 {@link TraceEvent.ToolFailed} 로 남긴다.
 * 예외를 기록하지 않으면 화면은 "실행 기록이 없는 도구"를 권한 거부와 구분할 수 없다.
 */
public class ToolTraceInterceptor implements ToolInterceptor {

	private final ApplicationEventPublisher events;

	private final TraceSettings settings;

	public ToolTraceInterceptor(ApplicationEventPublisher events, TraceSettings settings) {
		this.events = events;
		this.settings = settings;
	}

	@Override
	public String intercept(ToolInvocation invocation, Chain chain) {
		long start = System.nanoTime();
		String result;
		try {
			result = chain.proceed(invocation);
		}
		catch (RuntimeException ex) {
			boolean cancelled = Thread.currentThread().isInterrupted() || causedByInterrupt(ex);
			TurnRecord turn = this.settings.currentTurn();
			if (turn != null) {
				turn.toolFailed(invocation.toolName(), cancelled);
			}
			this.events.publishEvent(new TraceEvent.ToolFailed(invocation.toolName(), rootMessage(ex), cancelled,
					System.nanoTime() - start));
			throw ex;
		}
		TurnRecord turn = this.settings.currentTurn();
		if (turn != null) {
			turn.toolExecuted(invocation.toolName());
		}
		this.events.publishEvent(new TraceEvent.ToolExecuted(invocation.toolName(), result.length(), System.nanoTime() - start));
		return result;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	private static boolean causedByInterrupt(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof InterruptedException) {
				return true;
			}
		}
		return false;
	}

	/** Spring AI 가 도구 예외를 ToolExecutionException 으로 감싸므로, 가장 안쪽 원인의 메시지를 쓴다. */
	private static String rootMessage(Throwable ex) {
		Throwable root = ex;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return (root.getMessage() != null) ? root.getMessage() : root.getClass().getSimpleName();
	}

}
