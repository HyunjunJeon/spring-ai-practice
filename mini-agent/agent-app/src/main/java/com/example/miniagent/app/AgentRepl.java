package com.example.miniagent.app;

import java.util.stream.Collectors;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.InteractiveFrontEnd;
import com.example.miniagent.core.model.ModelDisplay;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 세 가지 모드 (pi 의 interactive / print 모드에 해당).
 * <ul>
 *   <li>인자 있음: 인자를 프롬프트 한 건으로 실행하고 종료. 예: {@code ./agent.sh "README 요약해줘"}</li>
 *   <li>인자 없음 + {@link InteractiveFrontEnd} 빈 있음: 그 화면(예: TUI)에 맡긴다.</li>
 *   <li>인자 없음: 줄 단위 REPL.</li>
 * </ul>
 *
 * <p>한 번 실행 모드에서 실패하면 종료 코드 1로 끝난다. {@code MiniAgentApplication} 이
 * {@code SpringApplication.exit(...)} 로 {@link ExitCodeGenerator} 빈의 값을 모아 {@code System.exit} 에 넘긴다.
 * 스크립트나 CI 는 화면 문구가 아니라 이 값으로 성공 여부를 판단한다.
 */
@Component
@ConditionalOnBooleanProperty(name = "agent.repl.enabled", matchIfMissing = true)
class AgentRepl implements ApplicationRunner, ExitCodeGenerator {

	private final Agent agent;

	private final AgentSession session;

	private final AgentConsole console;

	private final AgentInputHandler inputHandler;

	private final ObjectProvider<InteractiveFrontEnd> frontEnd;

	private volatile int exitCode;

	AgentRepl(Agent agent, AgentSession session, AgentConsole console, AgentInputHandler inputHandler,
			ObjectProvider<InteractiveFrontEnd> frontEnd, Environment environment) {
		this.agent = agent;
		this.session = session;
		this.console = console;
		this.inputHandler = inputHandler;
		this.frontEnd = frontEnd;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!args.getNonOptionArgs().isEmpty()) {
			this.console.setInteractive(false);
			this.exitCode = this.inputHandler.handle(String.join(" ", args.getNonOptionArgs())) ? 0 : 1;
			return;
		}
		InteractiveFrontEnd custom = this.frontEnd.getIfAvailable();
		if (custom != null && custom.isAvailable()) {
			custom.run();
			return;
		}

		this.console.setInteractive(System.console() != null);
		printBanner();
		while (true) {
			String line = this.console.readLine("\n› ");
			if (line == null || line.strip().equals("/exit") || line.strip().equals("/quit")) {
				return;
			}
			if (!line.isBlank()) {
				boolean handled = this.inputHandler.handle(line.strip());
				if (!handled && !this.console.isInteractive()) {
					this.exitCode = 1;
				}
			}
		}
	}

	@Override
	public int getExitCode() {
		return this.exitCode;
	}

	private void printBanner() {
		String model = ModelDisplay.label(this.agent);
		String plugins = this.agent.plugins().stream().map(AgentPlugin::id).collect(Collectors.joining(", "));
		this.console.println("mini-agent  (%s, session: %s)".formatted(model, this.session.id()));
		this.console.info("plugins: " + (plugins.isEmpty() ? "(없음)" : plugins));
		this.console.info("/help 명령 목록, /exit 종료");
	}

}
