package com.example.miniagent.plugin.memory;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import com.example.miniagent.core.model.TurnContext;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

/** No child advisor may route reads, appends, compaction or clear to a parent ID. */
final class ScopeMemory implements ChatMemory {
	private final ChatMemory delegate;
	private final Supplier<TurnContext> authority;
	ScopeMemory(ChatMemory delegate, Supplier<TurnContext> authority) {
		this.delegate = Objects.requireNonNull(delegate);
		this.authority = Objects.requireNonNull(authority);
	}
	private void require(String conversationId) {
		TurnContext captured = this.authority.get();
		if (!captured.sessionId().equals(conversationId)
				|| TurnContext.current().orElse(null) != captured) {
			throw new IllegalArgumentException("Memory requires captured child conversation");
		}
	}
	@Override public void add(String conversationId, List<Message> messages) {
		require(conversationId);
		this.delegate.add(conversationId, messages);
	}
	@Override public List<Message> get(String conversationId) {
		require(conversationId);
		return this.delegate.get(conversationId);
	}
	@Override public void clear(String conversationId) {
		require(conversationId);
		this.delegate.clear(conversationId);
	}
}
