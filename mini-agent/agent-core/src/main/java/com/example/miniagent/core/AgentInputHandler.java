package com.example.miniagent.core;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import com.example.miniagent.core.run.RunCommand;

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
	private final AtomicBoolean busy = new AtomicBoolean();

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
		boolean control = isControlInput(input);
		if (!control && !this.busy.compareAndSet(false, true)) {
			this.console.error("SESSION_BUSY");
			return false;
		}
		try {
			if (input.startsWith("/")) {
				return runCommand(input.substring(1));
			}
			Agent.AgentReply reply = null;
			boolean completed = false;
			try {
				reply = this.agent.stream(input, this.console::appendReply);
				completed = true;
				return true;
			}
			finally {
				this.console.endReply();
				if (completed && reply != null) {
					printUsage(reply.usage());
				}
			}
		}
		catch (RuntimeException ex) {
			// Esc 취소는 작업 스레드의 인터럽트로 전달된다. 일반 오류로 표시하지 않는다.
			if (Thread.currentThread().isInterrupted()) {
				return false;
			}
			this.console.error("오류: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
			return false;
		}
		finally {
			if (!control) this.busy.set(false);
		}
	}

	public static boolean isControlInput(String input) {
		return RunCommand.isControlInput(input)
				|| com.example.miniagent.core.delegation.DelegateCommand.isControlInput(input)
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
