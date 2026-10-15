package com.example.miniagent.plugin.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.SessionModelStore;

/** Conversation ancestry copies values, never run journals, approvals or live bindings. */
public final class ConversationTree {

	private final JsonlChatMemoryRepository repository;
	private final java.util.function.Supplier<SessionModelStore> selections;

	public ConversationTree(JsonlChatMemoryRepository repository,
			java.util.function.Supplier<SessionModelStore> selections) {
		this.repository = repository;
		this.selections = selections;
	}

	public JsonlChatMemoryRepository.Branch fork(String sourceId, int turns, ModelSelection selection) {
		if (turns < 0 || turns > this.repository.completedTurns(sourceId)) {
			throw new IllegalArgumentException("Invalid completed turn boundary");
		}
		String id = "branch-" + UUID.randomUUID();
		try {
			// Persist the value before publishing the branch. A crash may leave an unused
			// selection sidecar, but never a visible branch with the wrong inherited model.
			this.selections.get().save(id, selection);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot save fork model selection", ex);
		}
		return this.repository.fork(sourceId, id, turns);
	}

	public List<JsonlChatMemoryRepository.Branch> branches() {
		return this.repository.findConversationIds().stream()
			.flatMap(id -> this.repository.branch(id).stream()).toList();
	}
}
