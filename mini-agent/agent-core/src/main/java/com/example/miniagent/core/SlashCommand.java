package com.example.miniagent.core;

import java.util.function.BiConsumer;

/**
 * REPL 의 {@code /명령}. (pi 의 {@code registerCommand} 에 해당)
 *
 * <p>Spring AI 개념이 아니라 이 프로젝트의 REPL 개념이다. 모델을 거치지 않고 바로 실행된다.
 */
public interface SlashCommand {

	/** 슬래시 없이 쓴 명령 이름. 예: {@code "tools"} */
	String name();

	String description();

	void execute(String args, CommandContext context);

	/** One-shot launchers need a real outcome for long-running control commands. */
	default boolean executeWithStatus(String args, CommandContext context) {
		execute(args, context);
		return true;
	}

	static SlashCommand of(String name, String description, BiConsumer<String, CommandContext> action) {
		return new SlashCommand() {

			@Override
			public String name() {
				return name;
			}

			@Override
			public String description() {
				return description;
			}

			@Override
			public void execute(String args, CommandContext context) {
				action.accept(args, context);
			}

		};
	}

	record CommandContext(Agent agent, AgentSession session, AgentConsole console) {
	}

}
