package com.example.miniagent.app;

import java.nio.file.Path;
import java.util.List;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.InteractiveFrontEnd;
import com.example.miniagent.core.SlashCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 한 번 실행 모드(./agent.sh "질문")의 종료 코드. 스크립트가 실패를 알 수 있어야 한다. */
@SpringBootTest(properties = { "agent.repl.enabled=false", "agent.plugins.trace.level=off" })
@Import(AgentTestSupport.class)
class OneShotExitCodeTest {

	@TempDir
	static Path workDir;

	@DynamicPropertySource
	static void paths(DynamicPropertyRegistry registry) {
		registry.add("agent.cwd", () -> workDir.toString());
		registry.add("agent.home", () -> workDir.resolve(".home").toString());
	}

	@Autowired
	Agent agent;

	@Autowired
	AgentSession session;

	@Autowired
	AgentConsole console;

	@Autowired
	AgentInputHandler inputHandler;

	@Autowired
	ObjectProvider<InteractiveFrontEnd> frontEnd;

	@Autowired
	Environment environment;

	@Test
	void successfulTurnExitsWithZero() {
		AgentRepl repl = repl(this.inputHandler);

		repl.run(new DefaultApplicationArguments("안녕"));

		assertThat(repl.getExitCode()).isZero();
	}

	@Test
	void unknownCommandExitsWithOne() {
		AgentRepl repl = repl(this.inputHandler);

		repl.run(new DefaultApplicationArguments("/no-such-command"));

		assertThat(repl.getExitCode()).isEqualTo(1);
	}

	@Test
	void failureInsideTheTurnExitsWithOne() {
		SlashCommand failing = SlashCommand.of("boom", "항상 실패", (args, ctx) -> {
			throw new IllegalStateException("boom");
		});
		AgentRepl repl = repl(new AgentInputHandler(this.agent, this.session, this.console, List.of(failing)));

		repl.run(new DefaultApplicationArguments("/boom"));

		assertThat(repl.getExitCode()).isEqualTo(1);
	}

	private AgentRepl repl(AgentInputHandler handler) {
		return new AgentRepl(this.agent, this.session, this.console, handler, this.frontEnd, this.environment);
	}

	@Test
	void unattendedReplFailureIsNotLostAtEofOrAfterLaterSuccess() {
		var input = org.mockito.Mockito.mock(AgentConsole.class);
		var interactive = new java.util.concurrent.atomic.AtomicBoolean(true);
		org.mockito.Mockito.when(input.isInteractive()).thenAnswer(invocation -> interactive.get());
		org.mockito.Mockito.doAnswer(invocation -> {
			interactive.set(invocation.getArgument(0));
			return null;
		}).when(input).setInteractive(org.mockito.ArgumentMatchers.anyBoolean());
		org.mockito.Mockito.when(input.readLine(org.mockito.ArgumentMatchers.anyString()))
				.thenReturn("/no-such-command", "hello", null);
		var repl = new AgentRepl(this.agent, this.session, input, this.inputHandler,
				this.frontEnd, this.environment);
		repl.run(new DefaultApplicationArguments());
		assertThat(repl.getExitCode()).isEqualTo(1);
	}

}
