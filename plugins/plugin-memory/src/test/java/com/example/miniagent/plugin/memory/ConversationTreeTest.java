package com.example.miniagent.plugin.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.miniagent.core.model.FileSessionModelStore;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationTreeTest {

	@TempDir
	Path home;

	@Test
	void forkIsIndependentAndPersistsAncestrySummaryAndModelValues() throws Exception {
		var repository = new JsonlChatMemoryRepository(this.home.resolve("sessions"));
		var selections = new FileSessionModelStore(this.home);
		var selected = new ModelSelection(new ModelRef("fake", "one"), Map.of("temperature", "0.2"));
		List<Message> first = List.of(new UserMessage("Q1"), new AssistantMessage("A1"));
		repository.append("root", first);
		repository.appendSummary("root", new JsonlChatMemoryRepository.Summary("PREFIX", 2));
		repository.append("root", List.of(new UserMessage("Q2"), new AssistantMessage("A2")));
		var tree = new ConversationTree(repository, () -> selections);
		var branch = tree.fork("root", 1, selected);
		assertThat(repository.read(branch.id()).messages()).containsExactlyElementsOf(first);
		assertThat(repository.read(branch.id()).summary())
			.isEqualTo(new JsonlChatMemoryRepository.Summary("PREFIX", 2));
		assertThat(selections.load(branch.id())).contains(selected);
		repository.append(branch.id(), List.of(new UserMessage("BRANCH_Q"), new AssistantMessage("BRANCH_A")));
		assertThat(repository.read("root").messages()).hasSize(4);
		assertThat(repository.read("root").messages()).extracting(Message::getText)
			.doesNotContain("BRANCH_Q", "BRANCH_A");
		var reopened = new JsonlChatMemoryRepository(this.home.resolve("sessions"));
		assertThat(reopened.branch(branch.id())).contains(branch);
		assertThat(new ConversationTree(reopened, () -> {
			try { return new FileSessionModelStore(this.home); }
			catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
		}).branches())
			.containsExactly(branch);
		assertThat(reopened.completedTurns(branch.id())).isEqualTo(2);
	}

	@Test
	void forkKeepsToolPairsAndProviderStateButNeverPendingToolsOrFutureSummary() {
		var repository = new JsonlChatMemoryRepository(this.home);
		var call = AssistantMessage.builder().content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "read_file", "{}")))
			.properties(Map.of("reasoningContent", "PRIVATE_REPLAY")).build();
		var result = ToolResponseMessage.builder().responses(List.of(
				new ToolResponseMessage.ToolResponse("c1", "read_file", "OK"))).build();
		repository.append("source", List.of(new UserMessage("Q"), call, result, new AssistantMessage("A"),
				new UserMessage("PENDING"), call));
		repository.appendSummary("source", new JsonlChatMemoryRepository.Summary("FUTURE", 5));
		repository.fork("source", "fork", 1);
		assertThat(repository.read("fork").messages()).hasSize(4);
		assertThat(repository.read("fork").messages().get(1).getMetadata())
			.containsEntry("reasoningContent", "PRIVATE_REPLAY");
		assertThat(repository.read("fork").summary()).isNull();
		assertThat(repository.completedTurns("source")).isEqualTo(1);
		assertThatThrownBy(() -> repository.fork("source", "bad", 2))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void invalidBoundariesOrTargetsNeverReplaceSourceOrExistingHistory() throws Exception {
		var repository = new JsonlChatMemoryRepository(this.home);
		repository.append("source", List.of(new UserMessage("Q"), new AssistantMessage("A")));
		byte[] original = Files.readAllBytes(this.home.resolve("source.jsonl"));
		assertThatThrownBy(() -> repository.fork("source", "source", 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> repository.fork("source", "../outside", 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> repository.fork("source", "bad", -1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(Files.readAllBytes(this.home.resolve("source.jsonl"))).isEqualTo(original);
		repository.fork("source", "empty", 0);
		assertThat(repository.read("empty").messages()).isEmpty();
	}

	@Test
	void forkIgnoresIncompleteTailAndDoesNotCreateRunAuthority() throws Exception {
		var repository = new JsonlChatMemoryRepository(this.home.resolve("sessions"));
		repository.append("source", List.of(new UserMessage("Q"), new AssistantMessage("A")));
		Files.writeString(this.home.resolve("sessions/source.jsonl"), "{\"type\":",
				java.nio.file.StandardOpenOption.APPEND);
		var branch = repository.fork("source", "fork", 1);
		assertThat(repository.read(branch.id()).messages()).hasSize(2);
		assertThat(this.home.resolve("runs")).doesNotExist();
	}

	@Test
	void replacingOrForgettingMessagesPreservesBranchAncestry() {
		var repository = new JsonlChatMemoryRepository(this.home);
		repository.append("source", List.of(new UserMessage("Q"), new AssistantMessage("A")));
		var branch = repository.fork("source", "fork", 1);
		repository.saveAll("fork", List.of(new UserMessage("REPLACED"), new AssistantMessage("NEW")));
		assertThat(repository.branch("fork")).contains(branch);
		new JsonlChatMemory(repository, 100, 10000, null, text -> { }, 1000).clear("fork");
		assertThat(repository.read("fork").messages()).isEmpty();
		assertThat(repository.branch("fork")).contains(branch);
		repository.deleteByConversationId("fork");
		assertThat(repository.findConversationIds()).doesNotContain("fork");
	}
}
