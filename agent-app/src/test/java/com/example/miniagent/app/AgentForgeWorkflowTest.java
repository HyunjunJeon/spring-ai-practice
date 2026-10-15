package com.example.miniagent.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.ProjectInstructions;
import com.example.miniagent.core.model.SessionModelStore;
import com.example.miniagent.plugin.memory.JsonlChatMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = { "agent.repl.enabled=false", "agent.ui=repl",
		"agent.plugins.trace.level=off", "agent.plugins.memory.compaction=false" })
@Import(AgentTestSupport.class)
class AgentForgeWorkflowTest {

	@TempDir
	static Path work;

	@DynamicPropertySource
	static void paths(DynamicPropertyRegistry properties) throws IOException {
		Files.writeString(work.resolve("AGENTS.md"), "PROJECT_INSTRUCTION_SENTINEL");
		properties.add("agent.cwd", () -> work.toString());
		properties.add("agent.home", () -> work.resolve(".mini-agent").toString());
	}

	@Autowired Agent agent;
	@Autowired AgentSession session;
	@Autowired AgentInputHandler inputs;
	@Autowired ProjectInstructions instructions;
	@Autowired JsonlChatMemoryRepository history;
	@Autowired SessionModelStore selections;

	@BeforeEach
	void startSession() throws IOException {
		this.session.startNew();
		Files.writeString(work.resolve("Service.java"), "SEARCH_SENTINEL");
	}

	@Test
	void projectInstructionsAndContextCommandAreWiredIntoTheApplication() throws Exception {
		assertThat(this.instructions.sources()).extracting(ProjectInstructions.Source::path)
			.containsExactly(work.resolve("AGENTS.md").toRealPath());
		assertThat(this.agent.systemPrompt()).contains("PROJECT_INSTRUCTION_SENTINEL");
		assertThat(this.inputs.handle("/context")).isTrue();
	}

	@Test
	void searchRunsThroughTheRealAdvisorPermissionAndToolChain() {
		var content = this.agent.run("!tool search_content {\"path\":\".\",\"query\":\"SEARCH_SENTINEL\",\"limit\":10}");
		assertThat(content.text()).contains("Service.java", "SEARCH_SENTINEL");
		var paths = this.agent.run("!tool glob_paths {\"path\":\".\",\"pattern\":\"**/*.java\",\"limit\":10}");
		assertThat(paths.text()).contains("Service.java");
	}

	@Test
	void forkCommandsCopyCompletedHistoryAndModelThenNavigateWithoutMixingBranches() throws Exception {
		String parent = this.session.id();
		this.agent.selectModel(this.agent.selectedModel());
		this.agent.run("PARENT_ONE");
		this.agent.run("PARENT_TWO");
		assertThat(this.inputs.handle("/fork 1")).isTrue();
		String branch = this.session.id();
		assertThat(branch).isNotEqualTo(parent);
		assertThat(this.history.read(branch).messages()).hasSize(2);
		assertThat(this.selections.load(branch)).isEqualTo(this.selections.load(parent));
		assertThat(this.history.branch(branch).orElseThrow().parentId()).isEqualTo(parent);
		this.agent.run("BRANCH_ONLY");
		assertThat(this.history.read(parent).messages()).hasSize(4);
		assertThat(this.inputs.handle("/tree " + parent)).isTrue();
		assertThat(this.session.id()).isEqualTo(parent);
		assertThat(this.history.read(parent).messages()).extracting(message -> message.getText())
			.doesNotContain("BRANCH_ONLY");
		assertThat(new JsonlChatMemoryRepository(work.resolve(".mini-agent/sessions")).branch(branch))
			.isPresent();
	}

	@Test
	void invalidForkAndNavigationLeaveTheActiveSessionUnchanged() {
		String current = this.session.id();
		assertThat(this.inputs.handle("/fork 99")).isFalse();
		assertThat(this.inputs.handle("/tree absent")).isFalse();
		assertThat(this.session.id()).isEqualTo(current);
	}
}
