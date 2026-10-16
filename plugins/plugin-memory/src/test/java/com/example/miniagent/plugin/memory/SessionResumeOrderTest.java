package com.example.miniagent.plugin.memory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** id 없는 {@code /resume} 은 이름순 마지막이 아니라 가장 최근에 수정된 세션을 연다. */
class SessionResumeOrderTest {

	private static final String OLDER = "20260101-000000-aaaa";

	private static final String NEWER = "20260102-000000-bbbb";

	/** 'b' 가 숫자보다 뒤에 정렬되므로, 분기 세션은 시각과 상관없이 이름순 마지막이다. */
	private static final String BRANCH = "branch-5b0f2c8e-1111-4222-8333-944455556666";

	@TempDir
	Path home;

	private final ByteArrayOutputStream output = new ByteArrayOutputStream();

	private final AgentConsole console = new AgentConsole(new BufferedReader(new StringReader("")),
			new PrintStream(this.output, true, StandardCharsets.UTF_8), false);

	private final AgentSession session = new AgentSession();

	@Test
	void resumeWithoutIdOpensTheNewestSessionEvenWhenAForkBranchSortsLater() throws Exception {
		createSessions("2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z", "2026-02-01T00:00:00Z");

		resume("");

		assertThat(this.session.id()).isEqualTo(NEWER);
		assertThat(screen()).isEqualTo("세션 전환: " + NEWER + " (전체 기록 2개 메시지)");
	}

	@Test
	void resumeWithoutIdOpensAForkBranchWhenItWasModifiedLast() throws Exception {
		createSessions("2026-03-01T00:00:00Z", "2026-02-01T00:00:00Z", "2026-04-01T00:00:00Z");

		resume("");

		assertThat(this.session.id()).isEqualTo(BRANCH);
	}

	@Test
	void sessionsModifiedAtTheSameInstantFallBackToTheLastId() throws Exception {
		createSessions("2026-03-01T00:00:00Z", "2026-03-01T00:00:00Z", "2026-03-01T00:00:00Z");

		resume("");

		assertThat(this.session.id()).isEqualTo(BRANCH);
	}

	@Test
	void resumeWithAnIdIgnoresRecency() throws Exception {
		createSessions("2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z", "2026-02-01T00:00:00Z");

		resume("  " + OLDER + " ");

		assertThat(this.session.id()).isEqualTo(OLDER);
		assertThat(screen()).isEqualTo("세션 전환: " + OLDER + " (전체 기록 2개 메시지)");
	}

	/** 같은 세션 두 개와 그 첫 턴에서 갈라진 분기 하나를 만들고 파일 수정 시각을 고정한다. */
	private void createSessions(String olderModified, String newerModified, String branchModified) throws Exception {
		var repository = new JsonlChatMemoryRepository(this.home.resolve("sessions"));
		for (String id : List.of(OLDER, NEWER)) {
			repository.append(id, List.of(new UserMessage("question in " + id), new AssistantMessage("answer")));
		}
		repository.fork(OLDER, BRANCH, 1);
		touch(OLDER, olderModified);
		touch(NEWER, newerModified);
		touch(BRANCH, branchModified);
	}

	private void touch(String id, String modified) throws Exception {
		Files.setLastModifiedTime(this.home.resolve("sessions").resolve(id + ".jsonl"),
				FileTime.from(Instant.parse(modified)));
	}

	private void resume(String args) {
		var properties = new AgentProperties();
		properties.setHome(this.home);
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(MemoryPluginAutoConfiguration.class))
			.withBean(AgentProperties.class, () -> properties)
			.withBean(AgentConsole.class, () -> this.console)
			.withPropertyValues("agent.plugins.memory.compaction=false")
			.run(context -> context.getBean("resumeCommand", SlashCommand.class)
				.execute(args, new SlashCommand.CommandContext(null, this.session, this.console)));
	}

	private String screen() {
		return this.output.toString(StandardCharsets.UTF_8).strip();
	}

}
