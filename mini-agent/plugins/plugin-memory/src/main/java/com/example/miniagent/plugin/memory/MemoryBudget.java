package com.example.miniagent.plugin.memory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/** UTF-16 content accounting; tool schemas and transport overhead are not included. */
final class MemoryBudget {

	static void positive(String property, long value) {
		if (value <= 0) {
			throw new IllegalArgumentException("agent.plugins.memory." + property + " must be positive");
		}
	}

	static long length(Message message) {
		long size = length(message.getText());
		if (message instanceof AssistantMessage assistant) {
			for (var call : assistant.getToolCalls()) {
				size += length(call.name()) + length(call.arguments());
			}
		}
		if (message instanceof ToolResponseMessage tool) {
			for (var response : tool.getResponses()) {
				size += length(response.responseData());
			}
		}
		return size;
	}

	static long length(List<Message> messages) {
		return messages.stream().mapToLong(MemoryBudget::length).sum();
	}

	private static int length(@Nullable String text) {
		return text == null ? 0 : text.length();
	}

	static String prefix(String text, int limit) {
		int end = Math.min(text.length(), Math.max(0, limit));
		if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
				&& Character.isLowSurrogate(text.charAt(end))) {
			end--;
		}
		return text.substring(0, end);
	}

	/** Each call ID is nonblank, unique within the USER group, and answered exactly once. */
	static boolean validTools(List<Message> group) {
		Map<String, String> pending = new HashMap<>();
		Set<String> seen = new HashSet<>();
		for (Message message : group) {
			if (message instanceof AssistantMessage assistant) {
				if (!pending.isEmpty()) {
					return false;
				}
				for (var call : assistant.getToolCalls()) {
					if (call.id() == null || call.id().isBlank() || !seen.add(call.id())) {
						return false;
					}
					pending.put(call.id(), call.name());
				}
			}
			else if (message instanceof ToolResponseMessage tool) {
				for (var response : tool.getResponses()) {
					if (response.id() == null || !pending.containsKey(response.id())
							|| !java.util.Objects.equals(pending.remove(response.id()), response.name())) {
						return false;
					}
				}
			}
		}
		return pending.isEmpty();
	}

	private MemoryBudget() {
	}

}
