package com.example.miniagent.plugin.claude;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.SlashCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeAuthCommandsTest {

	@TempDir
	Path dir;

	@Test
	void loginPrintsUrlBeforeReadingAndNeverPrintsTokens() {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		AgentConsole console = console("pasted#state\n", output, true);
		ClaudeOAuth oauth = new ClaudeOAuth(Duration.ofSeconds(5)) {
			@Override
			public Authorization authorization() {
				return new Authorization(URI.create("https://claude.ai/oauth/authorize?state=state"), "verifier",
						"state");
			}

			@Override
			public ClaudeCredentials exchange(String pasted, Authorization authorization) {
				assertThat(output.toString(StandardCharsets.UTF_8))
					.contains("https://claude.ai/oauth/authorize?state=state");
				assertThat(pasted).isEqualTo("pasted#state");
				return new ClaudeCredentials("secret-access", "secret-refresh", 4_102_444_800_000L);
			}
		};
		ClaudeAuth auth = new ClaudeAuth(this.dir.resolve("claude.json"), oauth);

		command(new ClaudeAuthCommands(auth, console).commands(), "login").execute("", null);

		assertThat(auth.read()).contains(new ClaudeCredentials("secret-access", "secret-refresh", 4_102_444_800_000L));
		assertThat(output.toString(StandardCharsets.UTF_8))
			.doesNotContain("secret-access")
			.doesNotContain("secret-refresh");
	}

	@Test
	void loginRejectsNoninteractiveConsoleBeforeStartingOAuth() {
		AtomicBoolean called = new AtomicBoolean();
		ClaudeOAuth oauth = new ClaudeOAuth(Duration.ofSeconds(5)) {
			@Override
			public Authorization authorization() {
				called.set(true);
				return super.authorization();
			}
		};
		AgentConsole console = console("", new ByteArrayOutputStream(), false);
		ClaudeAuthCommands commands = new ClaudeAuthCommands(new ClaudeAuth(this.dir.resolve("claude.json"), oauth),
				console);

		assertThatThrownBy(() -> command(commands.commands(), "login").execute("", null))
			.isInstanceOf(IllegalStateException.class);
		assertThat(called).isFalse();
	}

	@Test
	void logoutDeletesOnlyClaudeStore() throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		AgentConsole console = console("", output, true);
		Path store = this.dir.resolve("claude.json");
		Path unrelated = Files.writeString(this.dir.resolve("other.json"), "keep");
		ClaudeAuth auth = new ClaudeAuth(store, new ClaudeOAuth(Duration.ofSeconds(5)));
		auth.save(new ClaudeCredentials("access", "refresh", 4_102_444_800_000L));

		command(new ClaudeAuthCommands(auth, console).commands(), "logout").execute("", null);

		assertThat(store).doesNotExist();
		assertThat(unrelated).exists().hasContent("keep");
		assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("access").doesNotContain("refresh");
	}

	private static AgentConsole console(String input, ByteArrayOutputStream output, boolean interactive) {
		return new AgentConsole(new BufferedReader(new StringReader(input)),
				new PrintStream(output, true, StandardCharsets.UTF_8), interactive);
	}

	private static SlashCommand command(List<SlashCommand> commands, String name) {
		assertThat(commands).extracting(SlashCommand::name).containsExactly("login", "logout");
		return commands.stream().filter(command -> command.name().equals(name)).findFirst().orElseThrow();
	}

}
