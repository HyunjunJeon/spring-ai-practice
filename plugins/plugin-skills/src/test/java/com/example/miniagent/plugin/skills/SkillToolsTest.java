package com.example.miniagent.plugin.skills;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** load_skill: 시스템 프롬프트에는 이름/설명만 있고, 전체 지침은 이 도구로만 모델에게 간다. */
class SkillToolsTest {

	@TempDir
	Path root;

	private SkillTools tools() throws Exception {
		Path dir = Files.createDirectories(this.root.resolve("release"));
		Files.writeString(dir.resolve("SKILL.md"),
				"---\nname: release\ndescription: cut a release\n---\nStep one.\n\nStep two.\n");
		return new SkillTools(new SkillRegistry(List.of(this.root)));
	}

	@Test
	void loadSkillReturnsTheDirectoryLineThenTheInstructions() throws Exception {
		String text = tools().loadSkill("release");

		assertThat(text).isEqualTo("Skill directory: " + this.root.resolve("release")
				+ " (resolve relative paths against it)" + System.lineSeparator() + System.lineSeparator()
				+ "Step one.\n\nStep two.");
	}

	@Test
	void unknownSkillNameIsRejectedWithItsName() throws Exception {
		SkillTools tools = tools();

		assertThatThrownBy(() -> tools.loadSkill("missing")).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Unknown skill: missing");
	}

	@Test
	void theModelSeesOneLoadSkillToolTakingAName() throws Exception {
		ToolCallback[] callbacks = provider(tools()).getToolCallbacks();

		assertThat(callbacks).singleElement().satisfies(callback -> {
			assertThat(callback.getToolDefinition().name()).isEqualTo("load_skill");
			assertThat(callback.getToolDefinition().inputSchema()).contains("\"name\"");
		});
	}

	@Test
	void loadSkillThroughTheToolCallbackReturnsAJsonStringHoldingTheInstructions() throws Exception {
		ToolCallback loadSkill = loadSkillCallback(provider(tools()));

		String json = loadSkill.call("{\"name\":\"release\"}");

		assertThat(json).startsWith("\"Skill directory: ").contains("Step one.\\n\\nStep two.\"");
	}

	@Test
	void unknownSkillThroughTheToolCallbackFailsTheToolCall() throws Exception {
		ToolCallback loadSkill = loadSkillCallback(provider(tools()));

		assertThatThrownBy(() -> loadSkill.call("{\"name\":\"missing\"}")).isInstanceOf(ToolExecutionException.class)
			.hasRootCauseInstanceOf(IllegalArgumentException.class)
			.hasRootCauseMessage("Unknown skill: missing");
	}

	private static ToolCallbackProvider provider(SkillTools tools) {
		return MethodToolCallbackProvider.builder().toolObjects(tools).build();
	}

	private static ToolCallback loadSkillCallback(ToolCallbackProvider provider) {
		return Arrays.stream(provider.getToolCallbacks())
			.filter(callback -> callback.getToolDefinition().name().equals("load_skill"))
			.findFirst()
			.orElseThrow();
	}

}
