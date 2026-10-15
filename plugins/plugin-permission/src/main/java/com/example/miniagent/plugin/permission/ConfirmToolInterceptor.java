package com.example.miniagent.plugin.permission;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.ToolInterceptor;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.approval.ConsoleApprovalBroker;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.Stop;

import org.springframework.core.Ordered;

/**
 * 도구를 실행하기 전에 사용자에게 묻는다. (pi 예제 permission-gate.ts 와 같은 역할)
 *
 * <p>허용 목록 방식이다. {@code autoAllowPatterns} 에 있는 도구(읽기 전용)만 묻지 않고, 나머지는 모두 묻는다.
 * 새 플러그인이나 MCP 서버가 도구를 추가해도 따로 설정하지 않으면 확인 대상이 된다.
 *
 * <p>거부할 때 예외를 던지지 않고 문자열을 돌려주는 것이 요점이다. 이 문자열이 도구 결과로 모델에게
 * 전달되므로, 모델은 "사용자가 거부했다"는 사실을 알고 다른 방법을 찾거나 사용자에게 묻는다.
 */
public class ConfirmToolInterceptor implements ToolInterceptor {

	private final List<String> autoAllowPatterns;

	/** [a]lways 를 골랐을 때 도구 전체가 아니라 같은 입력에만 적용할 도구. bash 는 명령마다 위험이 다르다. */
	private final Set<String> rememberExactInput;

	private final AgentConsole console;

	private final boolean allowWhenNonInteractive;

	private final Set<String> alwaysAllowed = ConcurrentHashMap.newKeySet();

	private final ApprovalBroker approvals;

	public ConfirmToolInterceptor(List<String> autoAllowPatterns, Collection<String> rememberExactInput,
			AgentConsole console, boolean allowWhenNonInteractive) {
		this(autoAllowPatterns, rememberExactInput, console, allowWhenNonInteractive,
				new ConsoleApprovalBroker(console));
	}

	public ConfirmToolInterceptor(List<String> autoAllowPatterns, Collection<String> rememberExactInput,
			AgentConsole console, boolean allowWhenNonInteractive, ApprovalBroker approvals) {
		this.autoAllowPatterns = List.copyOf(autoAllowPatterns);
		this.rememberExactInput = Set.copyOf(rememberExactInput);
		this.console = console;
		this.allowWhenNonInteractive = allowWhenNonInteractive;
		this.approvals = approvals;
	}

	@Override
	public String intercept(ToolInvocation invocation, Chain chain) {
		String tool = invocation.toolName();
		var managed = invocation.context() == null ? java.util.Optional.<RunContext>empty()
				: RunContext.fromToolContext(invocation.context());
		if (managed.isPresent()) {
			// Outer role/path and budget gates have already run. Legacy /always must not leak into runs.
			if (isAutoAllowed(tool)) return chain.proceed(invocation);
			RunContext context = managed.get();
			ApprovalRequest request = ApprovalRequest.tool(context, tool, invocation.input());
			try {
				ApprovalDecision decision = this.approvals.request(request, context).get();
				return switch (decision) {
					case APPROVED -> chain.proceed(invocation);
					case REJECTED -> denied(tool, "the user rejected it");
					case DEFERRED -> throw new Stop(Stop.Kind.DEFERRED);
					case CANCELLED -> throw new Stop(Stop.Kind.CANCELLED);
				};
			}
			catch (InterruptedException ex) {
				this.approvals.cancelRun(context.spec().runId());
				Thread.currentThread().interrupt();
				throw new Stop(Stop.Kind.CANCELLED);
			}
			catch (java.util.concurrent.ExecutionException ex) {
				throw new java.util.concurrent.CompletionException(ex.getCause());
			}
		}
		String alwaysKey = alwaysKey(invocation);
		if (isAutoAllowed(tool) || this.alwaysAllowed.contains(alwaysKey)) {
			return chain.proceed(invocation);
		}

		if (!this.console.isInteractive()) {
			return this.allowWhenNonInteractive ? chain.proceed(invocation)
					: denied(tool, "non-interactive mode (set agent.plugins.permission.allow-non-interactive=true)");
		}

		boolean exact = this.rememberExactInput.contains(tool);
		this.console.println("\n[permission] %s %s".formatted(tool, abbreviate(invocation.input(), 300)));
		String answer = this.console.readLine("  허용할까요? [y]es / [n]o / [a]lways(%s): "
			.formatted(exact ? "이 세션 동안 같은 입력만" : "이 세션 동안"));
		answer = (answer != null) ? answer.strip().toLowerCase() : "n";
		if (answer.startsWith("a")) {
			this.alwaysAllowed.add(alwaysKey);
			return chain.proceed(invocation);
		}
		if (answer.startsWith("y")) {
			return chain.proceed(invocation);
		}
		return denied(tool, "the user rejected it");
	}

	/** 가장 바깥(가장 먼저) 실행. 거부되면 안쪽 인터셉터와 실제 도구는 실행되지 않는다. */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 20;
	}

	private boolean isAutoAllowed(String tool) {
		return this.autoAllowPatterns.stream()
			.anyMatch(p -> p.endsWith("*") ? tool.startsWith(p.substring(0, p.length() - 1)) : tool.equals(p));
	}

	private String alwaysKey(ToolInvocation invocation) {
		String tool = invocation.toolName();
		return this.rememberExactInput.contains(tool) ? tool + "\n" + invocation.input() : tool;
	}

	private static String denied(String tool, String reason) {
		return "Permission denied: '%s' was not executed because %s. Do not retry it; ask the user how to proceed."
			.formatted(tool, reason);
	}

	private static String abbreviate(String text, int max) {
		String oneLine = text.replaceAll("\\s+", " ");
		return (oneLine.length() <= max) ? oneLine : oneLine.substring(0, max) + "…";
	}

}
