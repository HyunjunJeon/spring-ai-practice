package com.example.miniagent.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

import com.example.miniagent.core.approval.ApprovalAnswer;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;

import org.jspecify.annotations.Nullable;

/**
 * 터미널 입출력. REPL 과 플러그인(예: 권한 확인)이 같은 입력 스트림을 공유하기 위해 빈으로 둔다.
 *
 * <p>모델 호출은 REPL 스레드에서 동기({@code .call()})로 일어나므로, 도구 실행 중에 권한 플러그인이
 * {@link #readLine(String)} 을 불러도 REPL 과 입력을 다투지 않는다.
 *
 * <p>TUI 같은 다른 화면은 이 클래스를 상속해 출력/입력 메서드를 바꾼다. 플러그인은 화면이 무엇이든
 * 이 API 만 쓴다.
 */
public class AgentConsole {

	private static final String DIM = "\u001B[2m";

	private static final String RED = "\u001B[31m";

	private static final String RESET = "\u001B[0m";

	private final BufferedReader in;

	private final PrintStream out;

	private volatile boolean interactive;

	private volatile UUID approvalId;

	private volatile boolean replyOpen;

	public AgentConsole(BufferedReader in, PrintStream out, boolean interactive) {
		this.in = in;
		this.out = out;
		this.interactive = interactive;
	}

	public static AgentConsole system() {
		var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		return new AgentConsole(in, System.out, true);
	}

	/** 출력 모드(한 번 실행하고 종료)에서는 false. 사용자에게 물어볼 수 없다는 뜻이다. */
	public boolean isInteractive() {
		return this.interactive;
	}

	public void setInteractive(boolean interactive) {
		this.interactive = interactive;
	}

	public synchronized @Nullable String readLine(String prompt) {
		this.out.print(prompt);
		this.out.flush();
		try {
			return this.in.readLine();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** One asynchronous transport reader; all plain REPL readers share readLine's monitor. */
	public void requestApproval(ApprovalRequest request, Consumer<ApprovalAnswer> response) {
		this.approvalId = request.requestId();
		Thread.startVirtualThread(() -> {
			while (request.requestId().equals(this.approvalId)) {
				String text = readLine(approvalPrompt(request));
				if (!request.requestId().equals(this.approvalId)) return;
				ApprovalAnswer answer = text == null
						? new ApprovalAnswer(request.requestId(), ApprovalDecision.DEFERRED, false)
						: ApprovalAnswer.parse(text);
				if (answer != null && request.requestId().equals(answer.requestId())) {
					response.accept(answer);
					return;
				}
				error("Approval answer requires the displayed request UUID and y/n/a/defer/cancel.");
			}
		});
	}

	public void dismissApproval(UUID requestId) {
		if (requestId.equals(this.approvalId)) this.approvalId = null;
	}

	protected static String approvalPrompt(ApprovalRequest request) {
		return "[approval " + request.requestId() + " run=" + request.runId().value()
				+ " attempt=" + request.attemptId() + " child=" + request.childId()
				+ " tool=" + request.toolCallKey() + " args=" + request.argsDigest()
				+ " diff=" + request.diffDigest() + "]\n"
				+ "  <UUID> y / n / a(exact scope) / defer / cancel: ";
	}

	public void println(String text) {
		this.out.println(text);
	}

	/** 에이전트의 최종 답변. REPL 에서는 그냥 한 줄이지만, TUI 는 말풍선처럼 따로 그린다. */
	public void reply(String text) {
		println(text);
	}

	/** 스트리밍 답의 다음 조각. 빈 조각은 무시한다. */
	public void appendReply(String delta) {
		if (delta == null || delta.isEmpty()) {
			return;
		}
		this.out.print(delta);
		this.out.flush();
		this.replyOpen = true;
	}

	/** 스트리밍 답을 줄바꿈으로 닫는다. 조각이 없었으면 빈 줄을 만들지 않는다. */
	public void endReply() {
		if (this.replyOpen) {
			this.out.println();
			this.replyOpen = false;
		}
	}

	/** 화면이 트레이스를 따로(예: 사이드바) 보여주는지. true 면 텍스트 트레이스를 대화에 섞지 않는다. */
	public boolean hasTraceView() {
		return false;
	}

	/** 흐리게 출력. 트레이스 같은 부가 정보용. */
	public void info(String text) {
		this.out.println(DIM + text + RESET);
	}

	public void error(String text) {
		this.out.println(RED + text + RESET);
	}

}
