package com.example.miniagent.core;

import java.util.List;
import java.util.concurrent.CancellationException;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * {@link ToolCallback} 데코레이터. 실제 도구 앞에 {@link ToolInterceptor} 체인을 끼워 넣는다.
 *
 * <p>Spring AI 의 {@code DefaultToolCallingManager} 는 결국 {@code toolCallback.call(input, toolContext)}
 * 만 부른다. 그래서 이 한 메서드만 가로채면 어떤 출처(@Tool, FunctionToolCallback, MCP)의 도구든
 * 똑같이 감쌀 수 있다.
 */
public final class InterceptingToolCallback implements ToolCallback {

	private final ToolCallback delegate;

	private final List<ToolInterceptor> interceptors;

	private InterceptingToolCallback(ToolCallback delegate, List<ToolInterceptor> interceptors) {
		this.delegate = delegate;
		this.interceptors = interceptors;
	}

	public static ToolCallback wrap(ToolCallback delegate, List<ToolInterceptor> interceptors) {
		if (interceptors.isEmpty() || delegate instanceof InterceptingToolCallback) {
			return delegate;
		}
		return new InterceptingToolCallback(delegate, interceptors);
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return this.delegate.getToolDefinition();
	}

	@Override
	public ToolMetadata getToolMetadata() {
		return this.delegate.getToolMetadata();
	}

	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	@Override
	public String call(String toolInput, @Nullable ToolContext toolContext) {
		var invocation = new ToolInterceptor.ToolInvocation(getToolDefinition(), toolInput, toolContext);
		try {
			return proceed(0, invocation);
		}
		catch (RuntimeException ex) {
			// ToolExecutionException 으로 남으면 Spring AI 가 도구 결과로 바꾸고 다음 라운드를 실행한다.
			if (Thread.currentThread().isInterrupted()) {
				CancellationException cancelled = new CancellationException("Tool execution cancelled");
				cancelled.initCause(ex);
				throw cancelled;
			}
			throw ex;
		}
	}

	private String proceed(int index, ToolInterceptor.ToolInvocation invocation) {
		if (index == this.interceptors.size()) {
			return this.delegate.call(invocation.input(), invocation.context());
		}
		return this.interceptors.get(index).intercept(invocation, next -> proceed(index + 1, next));
	}

}
