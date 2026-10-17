package com.example.miniagent.plugin.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code saveAll}, {@code clearHistory}, {@code deleteByConversationId} 가 세션 파일과 분기 머리글에 남기는 결과. */
class RepositoryDurabilityTest {

	@TempDir
	Path dir;

	@Test
	void saveAllCreatesTheDirectoryAndReplacesEarlierMessages() {
		var repository = new JsonlChatMemoryRepository(this.dir.resolve("nested/sessions"));

		repository.saveAll("chat", List.of(new UserMessage("old question"), new AssistantMessage("old answer")));
		repository.saveAll("chat", List.of(new UserMessage("new question")));

		assertThat(repository.findByConversationId("chat")).extracting(Message::getText)
			.containsExactly("new question");
	}

	@Test
	void saveAllDropsTheSummaryButKeepsTheBranchHeaderAsTheFirstLine() throws Exception {
		var repository = new JsonlChatMemoryRepository(this.dir);
		repository.append("root", List.of(new UserMessage("q1"), new AssistantMessage("a1"),
				new UserMessage("q2"), new AssistantMessage("a2")));
		var branch = repository.fork("root", "child", 2);
		repository.appendSummary("child", new JsonlChatMemoryRepository.Summary("folded", 2));
		assertThat(repository.read("child").summary()).isNotNull();

		repository.saveAll("child", List.of(new UserMessage("replacement")));

		var history = repository.read("child");
		assertThat(history.summary()).isNull();
		assertThat(history.messages()).extracting(Message::getText).containsExactly("replacement");
		assertThat(repository.branch("child")).contains(branch);
		List<String> lines = Files.readAllLines(this.dir.resolve("child.jsonl"));
		assertThat(lines).hasSize(2);
		assertThat(lines.getFirst()).contains("\"type\":\"branch\"");
	}

	@Test
	void clearHistoryLeavesOnlyTheBranchHeaderOfAForkAndDeletesAPlainSession() throws Exception {
		var repository = new JsonlChatMemoryRepository(this.dir);
		repository.append("root", List.of(new UserMessage("q"), new AssistantMessage("a")));
		var branch = repository.fork("root", "child", 1);
		repository.append("child", List.of(new UserMessage("later")));

		repository.clearHistory("child");
		repository.clearHistory("root");

		assertThat(Files.readAllLines(this.dir.resolve("child.jsonl"))).hasSize(1);
		assertThat(repository.branch("child")).contains(branch);
		assertThat(repository.findByConversationId("child")).isEmpty();
		assertThat(this.dir.resolve("root.jsonl")).doesNotExist();
		assertThat(repository.findConversationIds()).containsExactly("child");
	}

	@Test
	void deletingOrClearingAMissingSessionChangesNothing() throws Exception {
		var missingDirectory = new JsonlChatMemoryRepository(this.dir.resolve("never-created"));

		missingDirectory.deleteByConversationId("ghost");
		missingDirectory.clearHistory("ghost");

		assertThat(this.dir.resolve("never-created")).doesNotExist();

		var repository = new JsonlChatMemoryRepository(this.dir);
		repository.append("kept", List.of(new UserMessage("kept")));
		byte[] kept = Files.readAllBytes(this.dir.resolve("kept.jsonl"));

		repository.deleteByConversationId("ghost");
		repository.clearHistory("ghost");

		assertThat(repository.findConversationIds()).containsExactly("kept");
		assertThat(Files.readAllBytes(this.dir.resolve("kept.jsonl"))).isEqualTo(kept);
	}

	@Test
	void deleteRemovesOnlyTheNamedSession() {
		var repository = new JsonlChatMemoryRepository(this.dir);
		repository.append("a", List.of(new UserMessage("a")));
		repository.append("b", List.of(new UserMessage("b")));

		repository.deleteByConversationId("a");

		assertThat(repository.findConversationIds()).containsExactly("b");
		assertThat(repository.findByConversationId("b")).extracting(Message::getText).containsExactly("b");
	}

	@Test
	void appendRacingSaveAllAndClearHistoryLeavesAParseableFileWithItsBranchHeader() throws Exception {
		var repository = new JsonlChatMemoryRepository(this.dir);
		repository.append("root", List.of(new UserMessage("q"), new AssistantMessage("a")));
		var branch = repository.fork("root", "child", 1);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<?> appender = executor.submit(() -> {
				start.await();
				for (int n = 0; n < 200; n++) {
					repository.append("child", List.of(new UserMessage("n" + n)));
				}
				return null;
			});
			Future<?> rewriter = executor.submit(() -> {
				start.await();
				for (int round = 0; round < 100; round++) {
					repository.saveAll("child", List.of(new UserMessage("saved")));
					repository.clearHistory("child");
				}
				return null;
			});
			start.countDown();
			appender.get(60, TimeUnit.SECONDS);
			rewriter.get(60, TimeUnit.SECONDS);
		}

		assertThat(repository.branch("child")).contains(branch);
		List<Integer> survivors = repository.findByConversationId("child").stream()
			.map(message -> Integer.parseInt(message.getText().substring(1))).toList();
		assertThat(survivors).isSorted().doesNotHaveDuplicates();
		assertThat(Files.readString(this.dir.resolve("child.jsonl"))).endsWith("\n");
	}

	@Test
	void saveAllWaitsUntilAnotherProcessReleasesTheSessionLock() throws Exception {
		Files.createDirectories(this.dir);
		Path file = this.dir.resolve("locked.jsonl");
		var repository = new JsonlChatMemoryRepository(this.dir);
		try (var executor = Executors.newSingleThreadExecutor();
				var holder = JsonlConcurrentAppendTest.Child.launch("hold", file.toString())) {
			holder.awaitLine("LOCKED");

			Future<?> save = executor.submit(
					() -> repository.saveAll("locked", List.of(new UserMessage("after release"))));

			assertThatThrownBy(() -> save.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			holder.send("RELEASE");
			save.get(60, TimeUnit.SECONDS);
			assertThat(holder.awaitExit()).isZero();
		}

		assertThat(repository.findByConversationId("locked")).extracting(Message::getText)
			.containsExactly("after release");
	}

}
