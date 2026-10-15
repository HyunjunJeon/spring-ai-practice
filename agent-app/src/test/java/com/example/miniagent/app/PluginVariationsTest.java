package com.example.miniagent.app;

import java.nio.file.Path;
import java.util.List;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.AgentSession;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 설정만 바꿔서 플러그인 구성을 바꿨을 때의 동작. */
class PluginVariationsTest {

	@TempDir
	static Path workDir;

	@DynamicPropertySource
	static void paths(DynamicPropertyRegistry registry) {
		registry.add("agent.cwd", () -> workDir.toString());
		registry.add("agent.home", () -> workDir.resolve(".home").toString());
	}

	@Nested
	@SpringBootTest(properties = { "agent.repl.enabled=false", "agent.plugins.trace.level=off",
			"agent.plugins.fs.enabled=false", "agent.plugins.bash.enabled=false" })
	@Import(AgentTestSupport.class)
	class ToolPluginsDisabled {

		@Autowired
		Agent agent;

		@Test
		void onlyRemainingPluginsContributeTools() {
			List<String> tools = this.agent.toolDefinitions().stream().map(d -> d.name()).toList();
			List<String> plugins = this.agent.plugins().stream().map(AgentPlugin::id).toList();

			assertThat(tools).containsExactlyInAnyOrder("load_skill", "delegate", "apply_staged", "verify_staged",
					"read_artifact", "search_artifact", "send_message", "read_messages");
			assertThat(plugins).doesNotContain("fs", "bash").contains("memory", "skills");
		}

	}

	@Nested
	@SpringBootTest(properties = { "agent.repl.enabled=false", "agent.plugins.trace.level=off",
			"agent.plugins.memory.inside-loop=true" })
	@Import(AgentTestSupport.class)
	class MemoryInsideTheLoop {

		@Autowired
		Agent agent;

		@Autowired
		AgentSession session;

		@Autowired
		ChatMemory chatMemory;

		@Test
		void toolCallAndToolResultAreRemembered() {
			this.session.startNew();
			this.agent.run("!tool list_dir {\"path\":\".\"}");

			List<MessageType> types = this.chatMemory.get(this.session.id()).stream().map(Message::getMessageType).toList();
			// 메모리가 루프 안쪽(HIGHEST+350)에 있으면 라운드마다 저장되므로 도구 메시지도 남는다.
			assertThat(types).containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL,
					MessageType.ASSISTANT);
		}

	}

}
