package com.example.miniagent.core.model.registry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.miniagent.core.ToolContextKeys;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.TurnContext;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

/** Replay copies are sanitized; saved messages and public/tool content are not rewritten. */
public final class ProviderPrivateFieldFilter {

	private ProviderPrivateFieldFilter() {
	}

	public static List<Message> filter(List<Message> messages, ModelRef target, Set<String> privateKeys) {
		List<Message> result = new ArrayList<>(messages.size());
		for (Message message : messages) {
			if (message instanceof AssistantMessage assistant
					&& (!target.provider().equals(message.getMetadata().get(ToolContextKeys.MODEL_PROVIDER))
						|| !target.model().equals(message.getMetadata().get(ToolContextKeys.MODEL_NAME)))) {
				Map<String, Object> metadata = new HashMap<>(assistant.getMetadata());
				privateKeys.forEach(metadata::remove);
				result.add(assistant.mutate().properties(metadata).build());
			}
			else {
				result.add(message);
			}
		}
		return List.copyOf(result);
	}

	public static List<Message> forCurrentTurn(List<Message> messages) {
		return TurnContext.current()
			.filter(context -> context.binding().chatModel() instanceof ProvenanceChatModel)
			.map(context -> ((ProvenanceChatModel) context.binding().chatModel()).filter(messages))
			.orElse(messages);
	}
}
