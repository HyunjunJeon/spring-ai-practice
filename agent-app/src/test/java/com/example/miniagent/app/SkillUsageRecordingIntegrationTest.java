package com.example.miniagent.app;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SkillUseListener;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.StatePaths;
import com.example.miniagent.core.lesson.LessonRecord;
import com.example.miniagent.core.lesson.SkillUsage;
import com.example.miniagent.core.lesson.SkillUsageStore;
import com.example.miniagent.core.run.WorkspaceFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Loads of a lesson-linked PROJECT skill, counted by the assembled app in the home and never in the workspace. */
class SkillUsageRecordingIntegrationTest {

	private static final String SKILL = "release-notes";

	private static final String BODY = "Write the release notes from the merged changes.";

	private static final String LOAD = "!tool load_skill {\"name\":\"" + SKILL + "\"}";

	@TempDir
	Path root;

	private Path work;

	private Path home;

	/** The skill folder exists before startup, because the skills plugin scans its roots once. */
	@BeforeEach
	void linkedProjectSkill() throws Exception {
		this.work = Files.createDirectory(this.root.resolve("workspace")).toRealPath();
		this.home = Files.createDirectory(this.root.resolve("home")).toRealPath();
		Path folder = Files.createDirectories(StatePaths.projectSkills(this.work).resolve(SKILL));
		byte[] skill = ("---\nname: " + SKILL + "\ndescription: Draft release notes\n---\n\n" + BODY + "\n")
			.getBytes(StandardCharsets.UTF_8);
		Files.write(folder.resolve("SKILL.md"), skill);
		store().link(new SkillUsage(LessonRecord.Scope.PROJECT, this.work.toString(), SKILL, "lesson-1", 3,
				Instant.parse("2026-10-01T00:00:00Z"),
				HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(skill)), 0, 0, null, null, null,
				null));
	}

	private SkillUsageStore store() {
		return new SkillUsageStore(this.home, Clock.systemUTC());
	}

	private ConfigurableApplicationContext start(String... extra) {
		return ProviderProfileMigrationTest.start(this.home, "",
				Stream.concat(Stream.of("--agent.cwd=" + this.work), Stream.of(extra)).toArray(String[]::new));
	}

	private static SlashCommand.CommandContext operator(ConfigurableApplicationContext context) {
		AgentConsole console = new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), false);
		return new SlashCommand.CommandContext(context.getBean(Agent.class), context.getBean(AgentSession.class),
				console);
	}

	private static SlashCommand skillCommand(ConfigurableApplicationContext context) {
		return context.getBeansOfType(SlashCommand.class)
			.values()
			.stream()
			.filter(command -> command.name().equals("skill"))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void twoLoadSkillCallsAndOneSkillCommandAreCountedInTheHomeOnly() throws Exception {
		Instant started = Instant.now();
		try (var context = start()) {
			Agent agent = context.getBean(Agent.class);
			context.getBean(AgentSession.class).startNew();
			var before = WorkspaceFingerprint.capture(this.work, this.home);

			assertThat(agent.run(LOAD).text()).contains(BODY);
			assertThat(agent.run(LOAD).text()).contains(BODY);
			skillCommand(context).execute(SKILL + " for version 2", operator(context));

			assertThat(WorkspaceFingerprint.capture(this.work, this.home)).isEqualTo(before);
		}
		assertThat(store().read().entries()).singleElement().satisfies(entry -> {
			assertThat(List.of(entry.chosenLoads(), entry.forcedLoads())).containsExactly(2L, 1L);
			assertThat(entry.lastLoadedAt()).isAfterOrEqualTo(started);
		});
		assertThat(this.work.resolve(".mini-agent").resolve("skill-usage")).doesNotExist();
	}

	@Test
	void withLessonsDisabledLoadSkillStillReturnsTheBodyAndNothingIsCounted() throws Exception {
		var linked = store().read();
		try (var context = start("--agent.lessons.enabled=false")) {
			assertThat(context.getBeansOfType(SkillUseListener.class)).isEmpty();
			context.getBean(AgentSession.class).startNew();

			assertThat(context.getBean(Agent.class).run(LOAD).text()).contains(BODY);
		}
		assertThat(store().read()).isEqualTo(linked);
	}

}
