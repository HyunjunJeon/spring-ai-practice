package com.example.miniagent.core;

import java.util.Arrays;
import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * {@link ToolCallbackProvider} 데코레이터.
 *
 * <p>Provider 를 미리 펼쳐서 ToolCallback 목록으로 바꾸지 않고 provider 그대로 감싸는 이유:
 * ChatClient 는 <b>요청마다</b> {@code provider.getToolCallbacks()} 를 다시 부른다
 * ({@code DefaultChatClientUtils.toChatClientRequest}). MCP 서버처럼 도구 목록이 실행 중에 바뀌는
 * provider 도 매 요청 최신 목록을 쓰게 하려면 감싸기만 해야 한다.
 */
public final class InterceptingToolCallbackProvider implements ToolCallbackProvider {

	private final ToolCallbackProvider delegate;

	private final List<ToolInterceptor> interceptors;

	public InterceptingToolCallbackProvider(ToolCallbackProvider delegate, List<ToolInterceptor> interceptors) {
		this.delegate = delegate;
		this.interceptors = interceptors;
	}

	@Override
	public ToolCallback[] getToolCallbacks() {
		return Arrays.stream(this.delegate.getToolCallbacks())
			.map(callback -> InterceptingToolCallback.wrap(callback, this.interceptors))
			.toArray(ToolCallback[]::new);
	}

}
