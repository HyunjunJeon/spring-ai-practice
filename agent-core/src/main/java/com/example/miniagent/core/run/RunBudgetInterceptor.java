package com.example.miniagent.core.run;

import com.example.miniagent.core.ToolInterceptor;
import org.springframework.core.Ordered;

/** Gate before permission requests; no change to ordinary un-managed turns. */
public final class RunBudgetInterceptor implements ToolInterceptor {
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 10;
	}

	@Override
	public String intercept(ToolInvocation invocation, Chain chain) {
		if (invocation.context() == null) {
			return chain.proceed(invocation);
		}
		var captured = RunContext.fromToolContext(invocation.context());
		if (captured.isEmpty()) {
			return chain.proceed(invocation);
		}
		RunContext context = captured.get();
		context.toolAttempt();
		try (var scope = context.open(); var turn = context.turnContext().open()) {
			return chain.proceed(invocation);
		}
	}
}
