package com.example.miniagent.plugin.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 전체 기록은 파일에 남고, 모델에게는 한도 안의 최근 턴만 USER 경계에서 잘려 간다. */
class JsonlChatMemoryTest {

	@TempDir
	Path dir;

	@Test
	void repositoryPreservesStringProviderStateForToolReplay() {
		var repository = new JsonlChatMemoryRepository(this.dir);
		var message = AssistantMessage.builder().content("answer")
			.properties(Map.of("reasoningContent", "reasoning-sentinel",
					"claude.content", "[{\"type\":\"thinking\",\"signature\":\"signed\"}]", "index", 0))
			.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "read_file", "{\"path\":\"a\"}")))
			.build();
		repository.saveAll("state", List.of(message));

		var reread = new JsonlChatMemoryRepository(this.dir)
			.findByConversationId("state").getFirst();

		assertThat(reread.getMetadata()).containsEntry("reasoningContent", "reasoning-sentinel")
			.containsEntry("claude.content", "[{\"type\":\"thinking\",\"signature\":\"signed\"}]")
			.doesNotContainKey("index");
		assertThat(((AssistantMessage) reread).getToolCalls()).isEqualTo(message.getToolCalls());
	}

	@Test
	void exactCountAndCharacterBoundariesKeepWholeGroups() {
		List<Message> group = List.of(new UserMessage("q"), toolCall(), toolResult("abc"), new AssistantMessage("a"));
		long chars = MemoryBudget.length(group);
		assertThat(chars).isEqualTo(15); // q + list_dir + {} + abc + a
		assertThat(JsonlChatMemory.window(group, 4, chars)).containsExactlyElementsOf(group);
		assertThat(JsonlChatMemory.window(group, 4, chars - 1)).isEmpty();
		assertThat(JsonlChatMemory.window(group, 3, chars)).isEmpty();
		List<Message> history = new ArrayList<>(List.of(new UserMessage("old")));
		history.addAll(group);
		history.add(new UserMessage("latest"));
		assertThat(texts(JsonlChatMemory.window(history, 100, 14))).containsExactly("latest");
	}

	@Test
	void summaryIsCountedBoundedOnLoadAndOptionalAfterNewestRawGroup() {
		JsonlChatMemoryRepository repository = new JsonlChatMemoryRepository(this.dir);
		repository.append("s", List.of(new UserMessage("old"), new AssistantMessage("answer"), new UserMessage("new")));
		repository.appendSummary("s", new JsonlChatMemoryRepository.Summary("x".repeat(1000), 2));
		Message summary = new UserMessage(JsonlChatMemory.SUMMARY_HEADER + "\n" + "x".repeat(5));
		JsonlChatMemory memory = new JsonlChatMemory(repository, 2, MemoryBudget.length(summary) + 3,
				null, notice -> { }, 5);
		assertThat(texts(memory.get("s"))).containsExactly(summary.getText(), "new");
		assertThat(texts(new JsonlChatMemory(repository, 1, 1000, null, notice -> { }, 5).get("s")))
			.containsExactly("new");
		assertThat(texts(new JsonlChatMemory(repository, 2, MemoryBudget.length(summary) + 2,
				null, notice -> { }, 5).get("s"))).containsExactly("new");
		repository.appendSummary("s", new JsonlChatMemoryRepository.Summary("x".repeat(1000), 3));
		assertThat(texts(memory.get("s"))).containsExactly(summary.getText());
		assertThat(new JsonlChatMemory(repository, 1, 1, null, notice -> { }, 5).get("s")).isEmpty();
		assertThat(repository.read("s").summary().text()).hasSize(1000);
	}

	@Test
	void malformedCoverageIsIgnoredWithoutSplittingToolPairs() {
		JsonlChatMemoryRepository repository = new JsonlChatMemoryRepository(this.dir);
		List<Message> group = List.of(new UserMessage("q"), toolCall(), toolResult("data"));
		repository.append("s", group);
		for (int covers : List.of(-1, 1, 2, 4)) {
			repository.appendSummary("s", new JsonlChatMemoryRepository.Summary("invalid", covers));
			List<String> notices = new ArrayList<>();
			JsonlChatMemory memory = new JsonlChatMemory(repository, 10, 1000, null, notices::add);
			assertThat(memory.get("s")).hasSize(3);
			assertThat(notices).hasSize(1);
		}
	}

	@Test
	void invalidHistoricalToolGroupsAreOmittedWhole() {
		List<Message> invalid = List.of(new UserMessage("q"), toolCall(),
				ToolResponseMessage.builder().responses(List.of(
						new ToolResponseMessage.ToolResponse("wrong", "list_dir", "data"))).build());
		assertThat(JsonlChatMemory.window(invalid, 100, 1000)).isEmpty();
		assertThat(JsonlChatMemory.window(List.of(new UserMessage("q"), toolCall()), 100, 1000)).isEmpty();
		assertThat(MemoryBudget.validTools(List.of(new UserMessage("q"), toolCall(), toolResult("a"),
				toolCall(), toolResult("b")))).isFalse();
	}

	@Test
	void persistedSummaryIsCappedAndFailuresKeepPreviousBoundedSummary() {
		JsonlChatMemoryRepository repository = new JsonlChatMemoryRepository(this.dir);
		repository.append("s", List.of(new UserMessage("q1"), new AssistantMessage("a1"),
				new UserMessage("q2"), new AssistantMessage("a2"), new UserMessage("q3")));
		JsonlChatMemory memory = new JsonlChatMemory(repository, 3, 1000,
				(previous, messages) -> "  " + "x".repeat(100) + "  ", notice -> { }, 5);
		assertThat(memory.get("s")).hasSize(2);
		assertThat(repository.read("s").summary().text()).isEqualTo("xxxxx");
		repository.append("s", List.of(new AssistantMessage("a3"), new UserMessage("q4")));
		List<String> notices = new ArrayList<>();
		JsonlChatMemory failed = new JsonlChatMemory(repository, 2, 1000, (previous, messages) -> {
			throw new IllegalStateException("failure");
		}, notices::add, 5);
		assertThat(texts(failed.get("s"))).containsExactly(JsonlChatMemory.SUMMARY_HEADER + "\nxxxxx", "q4");
		assertThat(notices).hasSize(1);
	}

	@Test
	void compactionCancellationPropagatesWithoutAppendingSummary() {
		JsonlChatMemoryRepository repository = new JsonlChatMemoryRepository(this.dir);
		repository.append("s", List.of(new UserMessage("q1"), new AssistantMessage("a1"), new UserMessage("q2")));
		JsonlChatMemory memory = new JsonlChatMemory(repository, 1, 1000, (previous, messages) -> {
			throw new IllegalStateException(new InterruptedException("cancelled"));
		}, notice -> { });
		assertThatThrownBy(() -> memory.get("s")).hasCauseInstanceOf(InterruptedException.class);
		assertThat(repository.read("s").summary()).isNull();
	}

	@Test
	void interruptDuringSuccessfulSummarizationStillCancelsBeforePersistence() {
		JsonlChatMemoryRepository repository = new JsonlChatMemoryRepository(this.dir);
		repository.append("s", List.of(new UserMessage("q1"), new AssistantMessage("a1"), new UserMessage("q2")));
		JsonlChatMemory memory = new JsonlChatMemory(repository, 1, 1000, (previous, messages) -> {
			Thread.currentThread().interrupt();
			return "summary";
		}, notice -> { });
		try {
			assertThatThrownBy(() -> memory.get("s")).isInstanceOf(java.util.concurrent.CancellationException.class);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
			assertThat(repository.read("s").summary()).isNull();
		}
		finally {
			Thread.interrupted();
		}
	}

	@Test
	void fullHistoryStaysOnDiskWhileTheModelSeesRecentTurns() {
		JsonlChatMemoryRepository repository = new JsonlChatMemoryRepository(this.dir);
		JsonlChatMemory memory = new JsonlChatMemory(repository, 4, 1_000_000, null, notice -> {
		});
		for (int i = 1; i <= 5; i++) {
			memory.add("s", new UserMessage("q" + i));
			memory.add("s", new AssistantMessage("a" + i));
		}

		assertThat(repository.findByConversationId("s")).hasSize(10);
		assertThat(texts(memory.get("s"))).containsExactly("q4", "a4", "q5", "a5");
	}

	@Test
	void characterBudgetCutsAtATurnBoundary() {
		// 예산이 도구 결과에서 끊겨도 턴 중간(a1)에서 시작하지 않고 다음 USER(q2)부터 보낸다.
		List<Message> history = List.of(new UserMessage("q1"), toolCall(), toolResult("x".repeat(40)),
				new AssistantMessage("a1"), new UserMessage("q2"), new AssistantMessage("a2"));

		assertThat(texts(JsonlChatMemory.window(history, 100, 20))).containsExactly("q2", "a2");
	}

	@Test
	void oversizedLatestStoredTurnIsOmittedWholeWithoutUsingOlderTurns() {
		List<Message> history = List.of(new UserMessage("q1"), new AssistantMessage("a1"), new UserMessage("q2"),
				toolCall(), toolResult("x".repeat(100)), new AssistantMessage("done"));

		assertThat(JsonlChatMemory.window(history, 100, 10)).isEmpty();
		assertThat(JsonlChatMemory.window(history, 3, 1000)).isEmpty();
	}

	@Test
	void tornLastLineIsIgnoredAndCutOffByTheNextAppend() throws Exception {
		JsonlChatMemoryRepository repository = new JsonlChatMemoryRepository(this.dir);
		repository.append("s", List.of(new UserMessage("q1")));
		Path file = this.dir.resolve("s.jsonl");
		// 쓰다가 끊긴 줄
		Files.writeString(file, "{\"type\":\"assis", StandardOpenOption.APPEND);

		assertThat(texts(repository.findByConversationId("s"))).containsExactly("q1");

		repository.append("s", List.of(new AssistantMessage("a1")));

		assertThat(texts(repository.findByConversationId("s"))).containsExactly("q1", "a1");
		assertThat(Files.readAllLines(file)).hasSize(2);
	}

	@Test
	void cancelledTurnIsStillRecordedAndTheInterruptIsKept() {
		// TUI 의 Esc 취소는 작업 스레드를 인터럽트한다. 그 턴의 기록도 끝까지 남아야 한다.
		JsonlChatMemoryRepository repository = new JsonlChatMemoryRepository(this.dir);
		Thread.currentThread().interrupt();
		try {
			repository.append("s", List.of(new UserMessage("q1")));
			assertThat(texts(repository.findByConversationId("s"))).containsExactly("q1");
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted();
		}
	}

	@Test
	void compactionFoldsOldTurnsOnlyWhenTheRecentPartOverflows() {
		JsonlChatMemoryRepository repository = new JsonlChatMemoryRepository(this.dir);
		List<String> calls = new ArrayList<>();
		ConversationSummarizer summarizer = (previous, messages) -> {
			calls.add(previous + " + " + texts(messages));
			return "S" + calls.size();
		};
		List<String> notices = new ArrayList<>();
		JsonlChatMemory memory = new JsonlChatMemory(repository, 4, 1_000_000, summarizer, notices::add);
		for (int i = 1; i <= 5; i++) {
			// MessageChatMemoryAdvisor 처럼: 문맥을 읽고, 사용자 메시지와 답을 저장한다.
			memory.get("s");
			memory.add("s", new UserMessage("q" + i));
			memory.add("s", new AssistantMessage("a" + i));
		}
		List<Message> context = memory.get("s");

		// 한도는 4개. 4턴 직전에 앞 2턴을 접고(절반까지), 5턴 직전에는 한도 안이라 요약하지 않는다.
		// 마지막 get 에서 3~4턴을 이전 요약과 합친다.
		assertThat(calls).containsExactly("null + [q1, a1, q2, a2]", "S1 + [q3, a3, q4, a4]");
		assertThat(texts(context)).containsExactly(JsonlChatMemory.SUMMARY_HEADER + "\nS2", "q5", "a5");
		assertThat(repository.findByConversationId("s")).hasSize(10);
		assertThat(repository.read("s").summary()).isEqualTo(new JsonlChatMemoryRepository.Summary("S2", 8));
		assertThat(notices).hasSize(2);
	}

	@Test
	void failedSummaryFallsBackToTheRecentWindow() {
		List<String> notices = new ArrayList<>();
		JsonlChatMemory memory = new JsonlChatMemory(new JsonlChatMemoryRepository(this.dir), 2, 1_000_000,
				(previous, messages) -> {
					throw new IllegalStateException("model down");
				}, notices::add);
		for (int i = 1; i <= 2; i++) {
			memory.add("s", new UserMessage("q" + i));
			memory.add("s", new AssistantMessage("a" + i));
		}

		assertThat(texts(memory.get("s"))).containsExactly("q2", "a2");
		assertThat(notices).singleElement().asString().contains("model down");
	}

	private static AssistantMessage toolCall() {
		return AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "list_dir", "{}")))
			.build();
	}

	private static ToolResponseMessage toolResult(String data) {
		return ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "list_dir", data)))
			.build();
	}

	private static List<String> texts(List<Message> messages) {
		return messages.stream().map(Message::getText).toList();
	}

}
