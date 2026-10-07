package com.example.miniagent.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * 가짜 모델(spring.ai.model.chat=fake)로 에이전트 루프 전체를 검증한다. API 키가 필요 없다.
 */
@SpringBootTest(properties = { "agent.repl.enabled=false", "agent.plugins.trace.level=off" })
@Import(AgentTestSupport.class)
class AgentLoopTest {

	@TempDir
	static Path workDir;

	@DynamicPropertySource
	static void paths(DynamicPropertyRegistry registry) {
		registry.add("agent.cwd", () -> workDir.toString());
		registry.add("agent.home", () -> workDir.resolve(".home").toString());
	}

	@Autowired
	Agent agent;

	@Autowired
	AgentSession session;

	@Autowired
	ChatMemory chatMemory;

	@BeforeEach
	void newSession() throws IOException {
		this.session.startNew();
		Files.writeString(workDir.resolve("hello.txt"), "hi");
	}

	@Test
	void toolCallIsExecutedByToolCallingAdvisorAndResultReachesModel() {
		Agent.AgentReply reply = this.agent.run("!tool list_dir {\"path\":\".\"}");

		// 라운드 1: 모델이 list_dir 호출 요청 → ToolCallingAdvisor 가 실행 → 라운드 2: 결과를 받은 모델이 답변
		assertThat(reply.text()).contains("list_dir").contains("hello.txt");
		assertThat(reply.usage()).isNotNull();
	}

	@Test
	void mutatingToolIsDeniedWhenNobodyCanConfirm() {
		Agent.AgentReply reply = this.agent.run("!tool write_file {\"path\":\"out.txt\",\"content\":\"x\"}");

		assertThat(reply.text()).contains("Permission denied");
		assertThat(workDir.resolve("out.txt")).doesNotExist();
	}

	@Test
	void memoryOutsideTheLoopKeepsOnlyUserInputAndFinalAnswer() {
		this.agent.run("!tool list_dir {\"path\":\".\"}");

		List<MessageType> types = this.chatMemory.get(this.session.id()).stream().map(Message::getMessageType).toList();
		// 도구 호출/결과 메시지는 ToolCallingAdvisor 안에서만 오가고 메모리(HIGHEST+200)에는 안 남는다.
		assertThat(types).containsExactly(MessageType.USER, MessageType.ASSISTANT);
		assertThat(workDir.resolve(".home/sessions/" + this.session.id() + ".jsonl")).exists();
	}

	@Test
	void secondTurnSeesFirstTurnThroughMemory() {
		this.agent.run("첫 번째 질문");
		Agent.AgentReply second = this.agent.run("두 번째 질문");

		// 가짜 모델은 받은 메시지를 그대로 설명한다: system + (user, assistant) + user = 4개
		assertThat(second.text()).contains("메시지 4개").contains("첫 번째 질문");
	}

	@Test
	void streamMatchesCallAndEmitsTextPieces() {
		Agent.AgentReply called = this.agent.run("안녕");
		this.session.startNew();
		List<String> pieces = new ArrayList<>();
		Agent.AgentReply streamed = this.agent.stream("안녕", pieces::add);

		assertThat(streamed.text()).isEqualTo(called.text());
		assertThat(pieces.size()).isGreaterThan(1);
		assertThat(String.join("", pieces)).isEqualTo(streamed.text());
		assertThat(streamed.usage()).isNotNull();
		assertThat(streamed.usage().getPromptTokens()).isEqualTo(called.usage().getPromptTokens());
		assertThat(streamed.usage().getCompletionTokens()).isEqualTo(called.usage().getCompletionTokens());
	}

	@Test
	void streamStillExecutesToolsAndRemembersTheTurn() {
		List<String> pieces = new ArrayList<>();
		Agent.AgentReply streamed = this.agent.stream("!tool list_dir {\"path\":\".\"}", pieces::add);

		assertThat(streamed.text()).contains("list_dir").contains("hello.txt");
		assertThat(String.join("", pieces)).isEqualTo(streamed.text());
		Agent.AgentReply second = this.agent.run("두 번째 질문");
		assertThat(second.text()).contains("메시지 4개").contains("!tool list_dir");
	}

	@Test
	void pluginToolsAreAttachedToTheChatClient() {
		List<String> names = this.agent.toolDefinitions().stream().map(d -> d.name()).toList();

		assertThat(names).contains("read_file", "write_file", "edit_file", "list_dir", "bash", "load_skill");
	}

}
