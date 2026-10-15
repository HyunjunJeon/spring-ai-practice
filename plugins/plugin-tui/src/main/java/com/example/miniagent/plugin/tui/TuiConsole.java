package com.example.miniagent.plugin.tui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.approval.ApprovalAnswer;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import org.jspecify.annotations.Nullable;

/**
 * TUI 가 켜져 있으면 출력은 대화 패널로, 입력은 화면 아래 입력창으로 보내는 {@link AgentConsole}.
 *
 * <p>플러그인(권한 확인, /login 등)은 화면이 바뀐 줄 모르고 {@code console.readLine(...)} 을 그대로 부른다.
 * 이 호출은 에이전트 작업 스레드에서 일어나므로, 사용자가 입력창에 답을 칠 때까지 큐에서 기다리면 된다.
 * TUI 가 꺼져 있으면(한 번 실행 모드, 파이프 입력) 부모 클래스의 표준 입출력으로 동작한다.
 */
public class TuiConsole extends AgentConsole {

	private final ChatLog log;

	private @Nullable CompletableFuture<String> lineAnswer;

	private @Nullable Consumer<ApprovalAnswer> approvalAnswer;

	private @Nullable UUID questionId;

	private volatile boolean active;

	private volatile @Nullable String pendingQuestion;

	private volatile Runnable redraw = () -> {
	};

	public TuiConsole(ChatLog log) {
		super(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out, true);
		this.log = log;
	}

	void activate(Runnable redraw) {
		this.redraw = redraw;
		this.active = true;
	}

	void deactivate() {
		cancelQuestion();
		this.active = false;
	}

	/** 지금 플러그인이 답을 기다리는 질문. 없으면 null. */
	@Nullable String pendingQuestion() {
		return this.pendingQuestion;
	}

	/** UI 스레드가 입력창의 답을 넘긴다. */
	synchronized @Nullable UUID pendingQuestionId() {
		return this.questionId;
	}

	void answer(String text) {
		answer(pendingQuestionId(), text);
	}

	boolean answer(@Nullable UUID id, String text) {
		Consumer<ApprovalAnswer> response;
		ApprovalAnswer parsed;
		synchronized (this) {
			if (id == null || !id.equals(this.questionId)) return false;
			response = this.approvalAnswer;
			if (response == null) {
				if (this.lineAnswer == null || this.lineAnswer.isDone()) return false;
				this.log.add(ChatLog.Role.USER, text);
				return this.lineAnswer.complete(text);
			}
			parsed = ApprovalAnswer.parse(text);
		}
		if (parsed == null || !id.equals(parsed.requestId())) {
			error("Use the displayed approval UUID followed by y/n/a/defer/cancel.");
			return false;
		}
		this.log.add(ChatLog.Role.USER, text);
		response.accept(parsed);
		return true;
	}

	void cancelQuestion() {
		Consumer<ApprovalAnswer> response;
		UUID id;
		synchronized (this) {
			response = this.approvalAnswer;
			id = this.questionId;
			if (this.lineAnswer != null) this.lineAnswer.cancel(false);
		}
		if (response != null) response.accept(new ApprovalAnswer(id, ApprovalDecision.CANCELLED, false));
	}

	@Override
	public synchronized void requestApproval(ApprovalRequest request, Consumer<ApprovalAnswer> response) {
		if (!this.active) {
			super.requestApproval(request, response);
			return;
		}
		if (this.questionId != null) throw new IllegalStateException("Console already has a reader");
		this.questionId = request.requestId();
		this.approvalAnswer = response;
		this.pendingQuestion = approvalPrompt(request).strip();
		this.log.add(ChatLog.Role.QUESTION, this.pendingQuestion);
		this.redraw.run();
	}

	@Override
	public synchronized void dismissApproval(UUID id) {
		super.dismissApproval(id);
		if (!id.equals(this.questionId)) return;
		this.questionId = null;
		this.approvalAnswer = null;
		this.pendingQuestion = null;
		this.redraw.run();
	}

	@Override
	public @Nullable String readLine(String prompt) {
		if (!this.active) {
			return super.readLine(prompt);
		}
		UUID id = UUID.randomUUID();
		CompletableFuture<String> answer = new CompletableFuture<>();
		synchronized (this) {
			if (this.questionId != null) throw new IllegalStateException("Console already has a reader");
			this.questionId = id;
			this.lineAnswer = answer;
			this.pendingQuestion = prompt.strip();
			this.log.add(ChatLog.Role.QUESTION, prompt.strip());
			this.redraw.run();
		}
		try {
			return answer.get();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new CancellationException("Input cancelled");
		}
		catch (java.util.concurrent.ExecutionException ex) {
			throw new IllegalStateException(ex.getCause());
		}
		finally {
			synchronized (this) {
				if (id.equals(this.questionId)) {
					this.questionId = null;
					this.lineAnswer = null;
					this.pendingQuestion = null;
					this.redraw.run();
				}
			}
		}
	}

	/** TUI 가 켜져 있으면 트레이스는 사이드바가 보여주므로 대화에 텍스트로 섞지 않는다. */
	@Override
	public boolean hasTraceView() {
		return this.active;
	}

	@Override
	public void println(String text) {
		add(ChatLog.Role.TEXT, text, () -> super.println(text));
	}

	@Override
	public void reply(String text) {
		add(ChatLog.Role.AGENT, text, () -> super.reply(text));
	}

	@Override
	public void appendReply(String delta) {
		if (delta == null || delta.isEmpty()) {
			return;
		}
		if (!this.active) {
			super.appendReply(delta);
			return;
		}
		this.log.append(ChatLog.Role.AGENT, delta);
		this.redraw.run();
	}

	@Override
	public void endReply() {
		if (!this.active) {
			super.endReply();
			return;
		}
		this.redraw.run();
	}

	@Override
	public void info(String text) {
		add(ChatLog.Role.INFO, text, () -> super.info(text));
	}

	@Override
	public void error(String text) {
		add(ChatLog.Role.ERROR, text, () -> super.error(text));
	}

	private void add(ChatLog.Role role, String text, Runnable fallback) {
		if (!this.active) {
			fallback.run();
			return;
		}
		this.log.add(role, text.strip());
		this.redraw.run();
	}

}
