package com.example.miniagent.core;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.Ordered;

/**
 * 모든 도구 실행을 감싸는 플러그인 계약. (pi 의 {@code tool_call} / {@code tool_result} 이벤트에 해당)
 *
 * <p>Spring AI 에는 "도구 호출 직전/직후" 훅이 따로 없다. 대신 {@code ToolCallback} 자체가
 * 단순한 인터페이스이므로, 코어가 모든 ToolCallback 을 데코레이터로 감싸서 이 체인을 태운다.
 * 차단하려면 {@code chain.proceed(...)} 를 부르지 말고 모델에게 돌려줄 문자열을 반환하면 된다.
 */
public interface ToolInterceptor extends Ordered {

	String intercept(ToolInvocation invocation, Chain chain);

	@Override
	default int getOrder() {
		return 0;
	}

	/** 모델이 요청한 도구 호출 1건. {@code input} 은 모델이 만든 JSON 인자 문자열이다. */
	record ToolInvocation(ToolDefinition definition, String input, @Nullable ToolContext context) {

		public String toolName() {
			return this.definition.name();
		}

		public ToolInvocation withInput(String newInput) {
			return new ToolInvocation(this.definition, newInput, this.context);
		}

	}

	@FunctionalInterface
	interface Chain {

		String proceed(ToolInvocation invocation);

	}

}
