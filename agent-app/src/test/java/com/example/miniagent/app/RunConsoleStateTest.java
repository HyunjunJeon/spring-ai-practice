package com.example.miniagent.app;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import com.example.miniagent.core.*;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.run.*;
import com.example.miniagent.core.run.command.RunCommand;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/run-fixture", "agent.plugins.memory.enabled=false",
		"agent.plugins.permission.allow-non-interactive=true", "agent.plugins.trace.level=off" })
@Import({ RunCommandTest.Fixture.class, RunConsoleStateTest.Fixture.class })
class RunConsoleStateTest {
	@TempDir static Path workspace;
	@TempDir static Path state;
	@DynamicPropertySource static void paths(DynamicPropertyRegistry p) {
		p.add("agent.home", () -> state.resolve(".home").toString());
		p.add("agent.cwd", () -> workspace.toString());
	}
	@Autowired Agent agent;
	@Autowired AgentSession session;
	@Autowired AgentInputHandler handler;
	@Autowired RunCommandTest.TestConsole console;
	@Autowired RunStore runs;
	@Autowired RunExecutor executor;
	@Autowired ObjectProvider<InteractiveFrontEnd> frontEnd;
	@Autowired Environment environment;
	@Autowired Observations observed;
	@BeforeEach void reset() { this.observed.states.clear(); this.console.setInteractive(false); }
	@AfterEach void cleanup() throws Exception {
		for (RunId id : this.runs.list()) if (!this.runs.read(id).state().terminal()) this.executor.cancel(id);
	}
	@Test void commandPublishesPersistedDispatchAndCompletionWithSnapshot() throws Exception {
		assertThat(this.handler.handle("/run start hello")).isTrue();
		var events = new ArrayList<>(this.observed.states);
		assertThat(events).hasSize(2);
		assertThat(events.getFirst().state()).isEqualTo(RunState.RUNNING);
		assertThat(events.getLast().state()).isEqualTo(RunState.COMPLETED);
		for (var event : events) {
			var record = this.runs.read(new RunId(event.runId()));
			assertThat(event.conversationId()).isEqualTo(record.spec().conversationId());
			assertThat(event.attemptId()).isEqualTo(record.attempts().getLast().attemptId());
			assertThat(event.ref()).isEqualTo(record.spec().model());
		}
	}
	@Test void oneShotPendingIsNonzeroAndCancelUpdatesObservedState() throws Exception {
		AgentRepl repl = new AgentRepl(this.agent, this.session, this.console, this.handler, this.frontEnd, this.environment);
		repl.run(new DefaultApplicationArguments("/run start !tool write_file {\"path\":\"pending.txt\",\"content\":\"no\"}"));
		assertThat(repl.getExitCode()).isEqualTo(1);
		var waiting = this.observed.states.stream().filter(e -> e.state() == RunState.WAITING_APPROVAL).findFirst().orElseThrow();
		assertThat(workspace.resolve("pending.txt")).doesNotExist();
		assertThat(this.handler.handle("/run cancel " + waiting.runId())).isTrue();
		assertThat(this.observed.states.stream().toList().getLast().state()).isEqualTo(RunState.CANCELLED);
	}
	@Test void oneShotInterruptedIsNonzeroAndCompletedIsZero() {
		AgentRepl repl = new AgentRepl(this.agent, this.session, this.console, this.handler, this.frontEnd, this.environment);
		repl.run(new DefaultApplicationArguments("/run start !interrupt"));
		assertThat(repl.getExitCode()).isEqualTo(1);
		repl.run(new DefaultApplicationArguments("/run start hello"));
		assertThat(repl.getExitCode()).isZero();
	}
	@Test void controlsCannotAuthorizeNewWork() {
		for (String input : List.of("/run cancel x", "/run show x", "/runs", "/run approve x y"))
			assertThat(RunCommand.isControlInput(input)).isTrue();
		for (String input : List.of("/run start hi", "/run resume x", "/apply", "/model fake/x"))
			assertThat(RunCommand.isControlInput(input)).isFalse();
	}
	@TestConfiguration static class Fixture {
		@Bean Observations observations() { return new Observations(); }
	}
	static class Observations {
		final LinkedBlockingQueue<TraceEvent.RunStateChanged> states = new LinkedBlockingQueue<>();
		@EventListener public void on(TraceEvent.RunStateChanged event) { this.states.add(event); }
	}
}
