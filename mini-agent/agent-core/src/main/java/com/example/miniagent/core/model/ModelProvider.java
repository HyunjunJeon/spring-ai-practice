package com.example.miniagent.core.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.chat.model.ChatModel;

/** A provider factory; create must return a fresh independent model for each turn. */
public interface ModelProvider {

	String providerId();

	List<ModelEntry> models();

	ProviderAuth auth();

	/** Apply the immutable options to a new model, never to a shared model bean. */
	ChatModel create(ModelRef ref, Map<String, String> options);

	default Set<String> supportedOptions() {
		return Set.of();
	}

	default Set<String> privateMetadataKeys() {
		return Set.of();
	}

}
