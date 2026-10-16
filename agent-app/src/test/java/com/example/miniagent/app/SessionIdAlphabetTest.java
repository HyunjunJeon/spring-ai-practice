package com.example.miniagent.app;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** 메모리 저장소가 받는 세션 ID 로 /resume 한 뒤에도 턴과 /model 이 동작해야 한다. */
class SessionIdAlphabetTest {

	@TempDir
	Path home;

	private final ByteArrayOutputStream output = new ByteArrayOutputStream();

	private final AgentConsole console = new AgentConsole(new BufferedReader(new StringReader("")),
			new PrintStream(this.output, true, StandardCharsets.UTF_8), false);

	private AgentInputHandler handler(ConfigurableApplicationContext context) {
		return new AgentInputHandler(context.getBean(Agent.class), context.getBean(AgentSession.class), this.console,
				List.copyOf(context.getBeansOfType(SlashCommand.class).values()));
	}

	private static void seed(ConfigurableApplicationContext context, String sessionId) {
		context.getBean(ChatMemoryRepository.class).saveAll(sessionId,
				List.of(new UserMessage("seed question"), new AssistantMessage("seed answer")));
	}

	@Test
	void memoryListsSessionsWithADotOrALeadingDash() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "")) {
			seed(context, "a.b");
			seed(context, "-x");

			assertThat(context.getBean(ChatMemoryRepository.class).findConversationIds()).contains("a.b", "-x");
			assertThat(this.home.resolve("sessions/a.b.jsonl")).isRegularFile();
			assertThat(this.home.resolve("sessions/-x.jsonl")).isRegularFile();
		}
	}

	@Test
	void resumedSessionInTheSharedAlphabetRunsTurnsAndShowsItsModel() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "")) {
			seed(context, "work_1-a");
			AgentInputHandler handler = handler(context);

			assertThat(handler.handle("/resume work_1-a")).isTrue();
			assertThat(context.getBean(AgentSession.class).id()).isEqualTo("work_1-a");
			assertThat(context.getBean(Agent.class).run("again").text()).contains("[fake]");
			assertThat(handler.handle("/model show")).isTrue();
			assertThat(this.output.toString(StandardCharsets.UTF_8)).contains("fake/fake-scripted");
		}
	}

	@Disabled("SUSPECT model:G4: memory accepts session ids with a dot or a leading dash (a.b, -x) but"
			+ " FileSessionModelStore rejects them with IllegalArgumentException 'Invalid session ID',"
			+ " so after /resume every turn and /model show fail")
	@ParameterizedTest
	@ValueSource(strings = { "a.b", "-x" })
	void resumedSessionTheMemoryAcceptsStillRunsTurnsAndShowsItsModel(String sessionId) {
		try (var context = ProviderProfileMigrationTest.start(this.home, "")) {
			seed(context, sessionId);
			AgentInputHandler handler = handler(context);

			assertThat(handler.handle("/resume " + sessionId)).isTrue();
			assertThat(context.getBean(Agent.class).run("again").text()).contains("[fake]");
			assertThat(handler.handle("/model show")).isTrue();
			assertThat(this.output.toString(StandardCharsets.UTF_8)).contains("fake/fake-scripted");
		}
	}
}
