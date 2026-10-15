package com.example.miniagent.core.model;

import java.util.Objects;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

/** Ephemeral turn binding. Never pass this object to a persistence serializer. */
public record ModelBinding(UUID bindingId, ModelRef ref, String optionsFingerprint, String wireFamily,
		ChatModel chatModel, ChatClient client) {

	public ModelBinding {
		Objects.requireNonNull(bindingId, "bindingId");
		Objects.requireNonNull(ref, "ref");
		Objects.requireNonNull(optionsFingerprint, "optionsFingerprint");
		Objects.requireNonNull(wireFamily, "wireFamily");
		Objects.requireNonNull(chatModel, "chatModel");
		Objects.requireNonNull(client, "client");
	}

}
