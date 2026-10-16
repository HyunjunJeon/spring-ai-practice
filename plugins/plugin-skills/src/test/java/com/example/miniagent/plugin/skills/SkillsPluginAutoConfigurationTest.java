package com.example.miniagent.plugin.skills;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.SystemPromptContributor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 스킬 플러그인 배선: 프롬프트 섹션, /skills, /skill, 도구, 활성화 토글. */
class SkillsPluginAutoConfigurationTest {

	@TempDir
	Path cwd;

	@TempDir
	Path home;

	private final ByteArrayOutputStream output = new ByteArrayOutputStream();

	private final List<Prompt> prompts = new ArrayList<>();

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(SkillsPluginAutoConfiguration.class))
			.withBean(AgentProperties.class, () -> {
				AgentProperties properties = new AgentProperties();
				properties.setCwd(this.cwd);
				properties.setHome(this.home);
				return properties;
			});
	}

	private static void skill(Path skillsRoot, String dir, String name, String description, String body)
			throws Exception {
		Path folder = Files.createDirectories(skillsRoot.resolve(dir));
		Files.writeString(folder.resolve("SKILL.md"),
				"---\nname: " + name + "\ndescription: " + description + "\n---\n" + body + "\n");
	}

	private Path projectSkills() {
		return this.cwd.resolve(".mini-agent").resolve("skills");
	}

	private Path userSkills() {
		return this.home.resolve("skills");
	}

	private static SlashCommand command(AssertableApplicationContext context, String name) {
		return context.getBeansOfType(SlashCommand.class)
			.values()
			.stream()
			.filter(command -> command.name().equals(name))
			.findFirst()
			.orElseThrow();
	}

	private SlashCommand.CommandContext commandContext(String modelReply) {
		ChatModel model = new ChatModel() {
			@Override
			public ChatOptions getOptions() {
				return ToolCallingChatOptions.builder().build();
			}

			@Override
			public ChatResponse call(Prompt prompt) {
				SkillsPluginAutoConfigurationTest.this.prompts.add(prompt);
				return new ChatResponse(List.of(new Generation(new AssistantMessage(modelReply))));
			}
		};
		AgentSession session = new AgentSession();
		Agent agent = new Agent(ChatClient.create(model), session, this.cwd, "system", List.of(), List.of(),
				List.of(), List.of(), ToolCallingAdvisor.DEFAULT_ORDER);
		AgentConsole console = new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(this.output, true, StandardCharsets.UTF_8), false);
		return new SlashCommand.CommandContext(agent, session, console);
	}

	private List<String> printedLines() {
		return this.output.toString(StandardCharsets.UTF_8).lines().toList();
	}

	@Test
	void promptSectionIsEmptyWhenThereAreNoSkills() {
		runner().run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(SystemPromptContributor.class).contribute()).isEmpty();
		});
	}

	@Test
	void promptSectionListsOneLinePerSkillWithoutTheInstructions() throws Exception {
		skill(projectSkills(), "alpha-dir", "alpha", "first skill", "SECRET ALPHA STEPS");
		skill(userSkills(), "beta-dir", "beta", "second skill", "SECRET BETA STEPS");

		runner().run(context -> {
			String section = context.getBean(SystemPromptContributor.class).contribute();

			assertThat(section).startsWith("# Skills\n")
				.contains("load_skill")
				.endsWith("- alpha: first skill\n- beta: second skill");
			assertThat(section).doesNotContain("SECRET");
		});
	}

	@Test
	void skillsAreDiscoveredInTheProjectFolderBeforeTheUserFolder() throws Exception {
		skill(userSkills(), "user-dir", "shared", "from user", "user body");
		skill(projectSkills(), "project-dir", "shared", "from project", "project body");
		skill(userSkills(), "only-dir", "only-user", "user only", "other body");

		runner().run(context -> {
			SkillRegistry registry = context.getBean(SkillRegistry.class);

			assertThat(registry.find("shared").orElseThrow().description()).isEqualTo("from project");
			assertThat(registry.all()).extracting(SkillRegistry.Skill::name).containsExactly("shared", "only-user");
		});
	}

	@Test
	void skillsCommandPadsNamesToTwentyColumns() throws Exception {
		skill(projectSkills(), "alpha-dir", "alpha", "first skill", "body");
		skill(projectSkills(), "long-dir", "a-skill-name-over-twenty", "long name", "body");

		runner().run(context -> {
			command(context, "skills").execute("", commandContext("unused"));

			assertThat(printedLines()).containsExactly("- alpha" + " ".repeat(16) + "first skill",
					"- a-skill-name-over-twenty long name");
		});
	}

	@Test
	void skillsCommandPrintsAHintWhenThereAreNoSkills() {
		runner().run(context -> {
			command(context, "skills").execute("", commandContext("unused"));

			assertThat(printedLines()).containsExactly("스킬이 없습니다. .mini-agent/skills/<이름>/SKILL.md 를 만들어 보세요.");
		});
	}

	@Test
	void skillCommandRunsOneTurnWithTheSkillInstructionsAndPrintsTheReply() throws Exception {
		skill(projectSkills(), "release-dir", "release", "cut a release", "Step one.\n\nStep two.");

		runner().run(context -> {
			command(context, "skill").execute("release  ship version 2 today", commandContext("release shipped"));

			assertThat(this.prompts).singleElement()
				.extracting(prompt -> prompt.getUserMessage().getText())
				.isEqualTo("Follow these skill instructions:\n\nStep one.\n\nStep two.\n\nUser request: ship version 2 today");
			assertThat(printedLines()).containsExactly("release shipped");
		});
	}

	@Test
	void skillCommandWithoutARequestSendsAnEmptyRequest() throws Exception {
		skill(projectSkills(), "release-dir", "release", "cut a release", "Step one.");

		runner().run(context -> {
			command(context, "skill").execute("release", commandContext("done"));

			assertThat(this.prompts).singleElement()
				.extracting(prompt -> prompt.getUserMessage().getText())
				.isEqualTo("Follow these skill instructions:\n\nStep one.\n\nUser request: ");
		});
	}

	@Test
	void skillCommandWithAnUnknownNameReportsItAndRunsNoTurn() throws Exception {
		skill(projectSkills(), "release-dir", "release", "cut a release", "Step one.");

		runner().run(context -> {
			command(context, "skill").execute("nope do something", commandContext("must not be asked"));

			assertThat(this.prompts).isEmpty();
			assertThat(this.output.toString(StandardCharsets.UTF_8)).contains("알 수 없는 스킬: nope")
				.doesNotContain("must not be asked");
		});
	}

	@Test
	void pluginRegistersLoadSkillSlashCommandsAndADescriptor() throws Exception {
		skill(projectSkills(), "alpha-dir", "alpha", "first skill", "body");
		skill(userSkills(), "beta-dir", "beta", "second skill", "body");

		runner().run(context -> {
			assertThat(context.getBeansOfType(SlashCommand.class).values()).extracting(SlashCommand::name)
				.containsExactlyInAnyOrder("skills", "skill");
			assertThat(Arrays.stream(context.getBean(ToolCallbackProvider.class).getToolCallbacks())
				.map(callback -> callback.getToolDefinition().name())).containsExactly("load_skill");
			assertThat(context.getBean(AgentPlugin.class)).satisfies(plugin -> {
				assertThat(plugin.id()).isEqualTo("skills");
				assertThat(plugin.description()).isEqualTo("2개 스킬 (SKILL.md)");
			});
		});
	}

	@Test
	void disabledPropertyRemovesEveryContribution() throws Exception {
		skill(projectSkills(), "alpha-dir", "alpha", "first skill", "body");

		runner().withPropertyValues("agent.plugins.skills.enabled=false").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(SkillRegistry.class)
				.doesNotHaveBean(SystemPromptContributor.class)
				.doesNotHaveBean(ToolCallbackProvider.class)
				.doesNotHaveBean(SlashCommand.class)
				.doesNotHaveBean(AgentPlugin.class);
		});
	}

	@Test
	void enabledPropertyKeepsTheContributions() throws Exception {
		skill(projectSkills(), "alpha-dir", "alpha", "first skill", "body");

		runner().withPropertyValues("agent.plugins.skills.enabled=true").run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(SkillRegistry.class);
			assertThat(context.getBeansOfType(SlashCommand.class)).hasSize(2);
		});
	}

}
