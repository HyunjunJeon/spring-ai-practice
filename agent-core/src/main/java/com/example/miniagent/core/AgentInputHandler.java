package com.example.miniagent.core;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.ai.chat.metadata.Usage;

/**
 * 사용자 입력 한 줄을 처리한다: {@code /명령} 이면 명령 실행, 아니면 에이전트 한 턴.
 *
 * <p>REPL 과 TUI 가 같은 규칙으로 동작하도록 화면과 분리해 둔다. 출력은 모두 {@link AgentConsole} 로 간다.
 */
public class AgentInputHandler {

	private final Agent agent;

	private final AgentSession session;

	private final AgentConsole console;

	private final Map<String, SlashCommand> commands;
	private final InputQueue queue = new InputQueue();
	private InputQueue.Target executing;

	public AgentInputHandler(Agent agent, AgentSession session, AgentConsole console, List<SlashCommand> commands) {
		this.agent = agent;
		this.session = session;
		this.console = console;
		this.commands = commands.stream().collect(Collectors.toMap(SlashCommand::name, Function.identity()));
	}

	/**
	 * 입력 한 줄을 처리한다. 오류는 화면에 보여주고 삼킨다(대화형 화면이 계속 돌도록).
	 * @return 성공하면 true. 예외나 알 수 없는 명령이면 false. 한 번 실행 모드는 이 값으로 종료 코드를 정한다.
	 */
	public boolean handle(String input) {
		var result = new java.util.concurrent.atomic.AtomicBoolean(true);
		return submit(input, Runnable::run, result::set) && result.get();
	}

	public InputQueue inputQueue() { return this.queue; }

	/** Registered commands for frontend completion; this does not execute or grant them. */
	public List<String> commandNames() {
		return this.commands.keySet().stream().map(name -> "/" + name).sorted().toList();
	}

	/** Reserve on the caller thread before scheduling: idle transitions cannot drop input. */
	public boolean submit(String input, java.util.function.Consumer<Runnable> executor) {
		return submit(input, executor, result -> {});
	}

	private boolean submit(String input, java.util.function.Consumer<Runnable> executor,
			java.util.function.Consumer<Boolean> completed) {
		if (isControlInput(input)) return command(input);
		InputQueue.Target target;
		synchronized (this.queue) {
			var active = this.queue.target();
			if (active.isPresent()) return enqueue(active.get(), input);
			if (isQueueInput(input)) {
				this.console.error("QUEUE_IDLE: start a conversation first");
				return false;
			}
			target = this.queue.begin(this.session.id(), !input.startsWith("/"));
		}
		try { executor.accept(() -> completed.accept(execute(input, target))); }
		catch (RuntimeException ex) { this.queue.end(target); throw ex; }
		return true;
	}

	/** RPC callers should carry this target, not look up a possibly newer active turn. */
	public boolean enqueue(InputQueue.Target target, String input) {
		try {
			String[] parts = input.strip().split("\\s+", 2);
			InputQueue.Kind kind = InputQueue.Kind.FOLLOW_UP;
			String text = input;
			if (isQueueInput(input)) {
				if (parts.length < 2) throw new IllegalArgumentException("Usage: " + parts[0] + " <text>");
				kind = parts[0].equals("/steer") ? InputQueue.Kind.STEER : InputQueue.Kind.FOLLOW_UP;
				text = parts[1];
			}
			else if (input.startsWith("/")) throw new IllegalStateException("SESSION_BUSY: slash mutation refused");
			this.queue.offer(target, this.session.id(), kind, text);
			this.console.info("Queued " + kind);
			return true;
		}
		catch (RuntimeException ex) { this.console.error(ex.getMessage()); return false; }
	}

	public void cancelPending() {
		synchronized (this.queue) {
			this.queue.target().ifPresent(target -> {
				this.queue.cancel(target);
				if (!target.equals(this.executing)) this.queue.end(target);
			});
		}
	}

	public static boolean isQueueInput(String input) {
		String name = input.strip().split("\\s+", 2)[0];
		return name.equals("/steer") || name.equals("/follow-up");
	}

	private boolean command(String input) {
		try { return runCommand(input.substring(1)); }
		catch (RuntimeException ex) { this.console.error("오류: " + ex.getMessage()); return false; }
	}

	private boolean execute(String input, InputQueue.Target target) {
		synchronized (this.queue) {
			if (!this.queue.target().filter(target::equals).isPresent()) return false;
			this.executing = target;
		}
		try {
			if (input.startsWith("/")) {
				return runCommand(input.substring(1));
			}
			com.example.miniagent.core.model.TurnContext captured = null;
			String text = input;
			while (true) {
				if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
				var scope = new SteeringAdvisor.Scope(this.queue, target, this.session::id,
						java.util.UUID.randomUUID().toString(), captured);
				Agent.AgentReply reply;
				try {
					reply = this.agent.streamQueued(text, this.console::appendReply, scope);
				}
				finally { this.console.endReply(); }
				captured = scope.captured();
				if (reply != null) printUsage(reply.usage());
				if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
				InputQueue.Entry next = this.queue.nextOrEnd(target, this.session.id());
				if (next == null) break;
				text = next.text(); // Never dispatch queued text through the slash router.
			}
			return true;
		}
		catch (RuntimeException ex) {
			// Esc 취소는 작업 스레드의 인터럽트로 전달된다. 일반 오류로 표시하지 않는다.
			if (Thread.currentThread().isInterrupted()) {
				return false;
			}
			if (ex instanceof java.util.concurrent.CancellationException) return false;
			this.console.error("오류: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
			return false;
		}
		finally {
			synchronized (this.queue) {
				if (target.equals(this.executing)) this.executing = null;
				this.queue.end(target);
			}
		}
	}

	public static boolean isControlInput(String input) {
		return ControlInput.isRunControl(input)
				|| ControlInput.isGoalControl(input)
				|| ControlInput.isLessonControl(input)
				|| ControlInput.isWorkerControl(input)
				|| input.strip().matches("/message\\s+(read|archive)\\s+.+")
				|| ControlInput.isDelegateControl(input)
				|| input.strip().split("\\s+", 2)[0].equals("/changes");
	}

	private boolean runCommand(String commandLine) {
		String[] parts = commandLine.split("\\s+", 2);
		SlashCommand command = this.commands.get(parts[0]);
		if (command == null) {
			this.console.error("알 수 없는 명령: /" + parts[0] + "  (/help 로 목록 보기)");
			return false;
		}
		return command.executeWithStatus((parts.length > 1) ? parts[1] : "",
				new SlashCommand.CommandContext(this.agent, this.session, this.console));
	}

	private void printUsage(Usage usage) {
		TraceEvent.Tokens tokens = TraceEvent.Tokens.from(usage);
		if (!tokens.isEmpty()) {
			this.console.info("  tokens: " + tokens.format());
		}
	}

}
