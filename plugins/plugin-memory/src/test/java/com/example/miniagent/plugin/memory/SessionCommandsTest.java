package com.example.miniagent.plugin.memory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 세션 슬래시 명령({@code /sessions /history /forget /new /resume})이 화면과 저장된 기록에 남기는 결과. */
class SessionCommandsTest {

	private static final String RED = "\u001B[31m";

	private static final String RESET = "\u001B[0m";

	@TempDir
	Path home;

	private final ByteArrayOutputStream output = new ByteArrayOutputStream();

	private final AgentConsole console = new AgentConsole(new BufferedReader(new StringReader("")),
			new PrintStream(this.output, true, StandardCharsets.UTF_8), false);

	private final AgentSession session = new AgentSession();

	@Test
	void sessionsListsStoredIdsAndMarksTheCurrentOne() {
		repository().append("alpha", List.of(new UserMessage("a")));
		repository().append("beta", List.of(new UserMessage("b")));
		this.session.switchTo("beta");

		run("sessions", "");

		assertThat(screen()).containsExactly("  alpha", "* beta");
	}

	@Test
	void sessionsWithoutStoredSessionsSaysSo() {
		run("sessions", "");

		assertThat(screen()).containsExactly("저장된 세션이 없습니다.");
	}

	@Test
	void historyShowsRoleAndOneLineTextAndCutsLongRowsAtOneHundredCharacters() {
		String exactly100 = "a".repeat(100);
		String over100 = "b".repeat(101);
		repository().append("chat", List.of(new UserMessage("first line\n\n   second\tline"),
				new AssistantMessage(exactly100), new UserMessage(over100)));
		this.session.switchTo("chat");

		run("history", "");

		assertThat(screen()).containsExactly("USER       first line second line",
				"ASSISTANT  " + exactly100, "USER       " + "b".repeat(100) + "…");
	}

	@Test
	void historyShowsARowWithoutTextForAnEmptyMessage() {
		repository().append("chat", List.of(new UserMessage("question"), new AssistantMessage("")));
		this.session.switchTo("chat");

		run("history", "");

		assertThat(screen()).containsExactly("USER       question", "ASSISTANT  ");
	}

	@Test
	void historyOfAnEmptySessionSaysSo() {
		this.session.switchTo("nothing-yet");

		run("history", "");

		assertThat(screen()).containsExactly("(비어 있음)");
	}

	@Test
	void historyKeepsAToolRoundAsRoleRowsInOrder() {
		var call = new AssistantMessage.ToolCall("call-1", "function", "read_file", "{\"path\":\"a\"}");
		var response = new ToolResponseMessage.ToolResponse("call-1", "read_file", "{\"ok\":true}");
		repository().append("chat", List.of(new UserMessage("q"),
				AssistantMessage.builder().content("").toolCalls(List.of(call)).build(),
				ToolResponseMessage.builder().responses(List.of(response)).build(), new AssistantMessage("done")));
		this.session.switchTo("chat");

		run("history", "");

		assertThat(screen()).containsExactly("USER       q", "ASSISTANT  ", "TOOL       ", "ASSISTANT  done");
	}

	@Test
	void forgetDeletesOnlyTheCurrentSessionFile() throws Exception {
		repository().append("keep", List.of(new UserMessage("kept")));
		repository().append("gone", List.of(new UserMessage("forgotten")));
		byte[] kept = Files.readAllBytes(sessionFile("keep"));
		this.session.switchTo("gone");

		run("forget", "");

		assertThat(sessionFile("gone")).doesNotExist();
		assertThat(Files.readAllBytes(sessionFile("keep"))).isEqualTo(kept);
		assertThat(screen()).containsExactly("지웠습니다: gone");
	}

	@Test
	void forgetOnAForkBranchDropsItsMessagesButKeepsItsAncestry() {
		repository().append("root", List.of(new UserMessage("q"), new AssistantMessage("a")));
		repository().fork("root", "child", 1);
		this.session.switchTo("child");

		run("forget", "");

		assertThat(repository().findByConversationId("child")).isEmpty();
		assertThat(repository().branch("child")).hasValueSatisfying(branch -> {
			assertThat(branch.parentId()).isEqualTo("root");
			assertThat(branch.forkTurns()).isEqualTo(1);
		});
		assertThat(repository().findByConversationId("root")).hasSize(2);
	}

	@Test
	void resumeSwitchesToTheNamedSessionAndReportsItsMessageCount() {
		repository().append("target", List.of(new UserMessage("q"), new AssistantMessage("a")));

		run("resume", "target");

		assertThat(this.session.id()).isEqualTo("target");
		assertThat(screen()).containsExactly("세션 전환: target (전체 기록 2개 메시지)");
	}

	@Test
	void resumeOfAnUnknownIdReportsAnErrorAndKeepsTheCurrentSession() {
		repository().append("real", List.of(new UserMessage("q")));
		this.session.switchTo("current");

		run("resume", "ghost");

		assertThat(this.session.id()).isEqualTo("current");
		assertThat(screen()).containsExactly(RED + "세션을 찾을 수 없습니다: ghost" + RESET);
	}

	@Test
	void resumeWithoutAnIdAndWithoutStoredSessionsReportsAnErrorAndKeepsTheCurrentSession() {
		this.session.switchTo("current");

		run("resume", "");

		assertThat(this.session.id()).isEqualTo("current");
		assertThat(screen()).containsExactly(RED + "세션을 찾을 수 없습니다: " + RESET);
	}

	@Test
	void newStartsAFreshSessionWithoutWritingAFile() {
		String before = this.session.id();

		run("new", "");

		assertThat(this.session.id()).isNotEqualTo(before).matches("\\d{8}-\\d{6}-[0-9a-f]+");
		assertThat(screen()).containsExactly("새 세션: " + this.session.id());
		assertThat(repository().findConversationIds()).isEmpty();
	}

	private JsonlChatMemoryRepository repository() {
		return new JsonlChatMemoryRepository(this.home.resolve("sessions"));
	}

	private Path sessionFile(String id) {
		return this.home.resolve("sessions").resolve(id + ".jsonl");
	}

	private void run(String command, String args) {
		var properties = new AgentProperties();
		properties.setHome(this.home);
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MemoryPluginAutoConfiguration.class))
			.withBean(AgentProperties.class, () -> properties)
			.withBean(AgentConsole.class, () -> this.console)
			.withPropertyValues("agent.plugins.memory.compaction=false")
			.run(context -> context.getBeansOfType(SlashCommand.class).values().stream()
				.filter(candidate -> candidate.name().equals(command)).findFirst().orElseThrow()
				.execute(args, new SlashCommand.CommandContext(null, this.session, this.console)));
	}

	private List<String> screen() {
		return this.output.toString(StandardCharsets.UTF_8).lines().toList();
	}

}
