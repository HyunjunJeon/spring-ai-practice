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

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.StatePaths;
import com.example.miniagent.core.lesson.LessonRecord;
import com.example.miniagent.core.lesson.SkillUsage;
import com.example.miniagent.core.lesson.SkillUsageStore;
import com.example.miniagent.plugin.skills.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assembled app's /lesson unexport and /lesson reinstate, checked with the skills plugin's own registry over the
 * roots it scans at startup.
 */
class SkillRetirementIntegrationTest {

	private static final String SKILL = "release-notes";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@TempDir
	Path root;

	private Path work;

	private Path home;

	/** A PROJECT skill linked as an export links it, before startup, because the plugin scans its roots once. */
	@BeforeEach
	void linkedProjectSkill() throws Exception {
		this.work = Files.createDirectory(this.root.resolve("workspace")).toRealPath();
		this.home = Files.createDirectory(this.root.resolve("home")).toRealPath();
		Path folder = Files.createDirectories(StatePaths.projectSkills(this.work).resolve(SKILL));
		byte[] skill = ("---\nname: " + SKILL + "\ndescription: Draft release notes\n---\n\nWrite the notes.\n")
			.getBytes(StandardCharsets.UTF_8);
		Files.write(folder.resolve("SKILL.md"), skill);
		new SkillUsageStore(this.home, Clock.systemUTC())
			.link(new SkillUsage(LessonRecord.Scope.PROJECT, this.work.toString(), SKILL, "lesson-1", 3,
					Instant.parse("2026-10-01T00:00:00Z"),
					HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(skill)), 0, 0, null, null,
					null, null));
	}

	/** What a fresh start of the skills plugin would find in the project and user roots. */
	private List<String> nextStart() {
		return new SkillRegistry(List.of(StatePaths.projectSkills(this.work), StatePaths.userSkills(this.home))).all()
			.stream()
			.map(SkillRegistry.Skill::name)
			.toList();
	}

	/** Runs one /lesson command of the assembled app and returns the JSON it printed. */
	private static JsonNode lesson(ConfigurableApplicationContext context, String args) {
		var out = new ByteArrayOutputStream();
		var console = new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(out, true, StandardCharsets.UTF_8), false);
		SlashCommand command = context.getBeansOfType(SlashCommand.class)
			.values()
			.stream()
			.filter(candidate -> candidate.name().equals("lesson"))
			.findFirst()
			.orElseThrow();
		assertThat(command.executeWithStatus(args,
				new SlashCommand.CommandContext(context.getBean(Agent.class), context.getBean(AgentSession.class),
						console)))
			.as(out.toString())
			.isTrue();
		return JSON.readTree(out.toString(StandardCharsets.UTF_8));
	}

	@Test
	void unexportTakesTheSkillOutOfTheNextStartAndReinstateBringsItBack() throws Exception {
		assertThat(nextStart()).containsExactly(SKILL);
		try (var context = ProviderProfileMigrationTest.start(this.home, "", "--agent.cwd=" + this.work)) {
			context.getBean(AgentSession.class).startNew();
			JsonNode row = lesson(context, "skills").path("skills").get(0);
			lesson(context, "unexport {\"name\":\"" + SKILL + "\",\"scope\":\"PROJECT\",\"expectedLoads\":"
					+ row.path("expectedLoads").asLong() + ",\"expectedDigest\":\""
					+ row.path("expectedDigest").asString() + "\"}");

			assertThat(nextStart()).doesNotContain(SKILL);
			// The running process scanned its roots at startup and keeps the skill until it restarts.
			assertThat(context.getBean(SkillRegistry.class).find(SKILL)).isPresent();
			assertThat(StatePaths.projectRetiredSkills(this.work).resolve(SKILL).resolve("SKILL.md")).isRegularFile();

			JsonNode reinstated = lesson(context, "reinstate {\"name\":\"" + SKILL + "\",\"scope\":\"PROJECT\"}");
			assertThat(reinstated.path("to").asString())
				.isEqualTo(StatePaths.projectSkills(this.work).resolve(SKILL).toString());
		}
		assertThat(nextStart()).containsExactly(SKILL);
	}

}
