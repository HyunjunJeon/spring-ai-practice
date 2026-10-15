package com.example.miniagent.core.model.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.example.miniagent.core.model.AgentChatClientFactory;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.ModelUnavailableException;

import org.springframework.ai.chat.model.ChatModel;

/** Owns provider factories, not mutable model instances. There is no fallback path. */
public final class ModelRegistry {

	private final Map<String, ModelProvider> providers;

	private final AgentChatClientFactory clientFactory;

	public ModelRegistry(List<ModelProvider> providers, AgentChatClientFactory clientFactory) {
		this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
		Map<String, ModelProvider> indexed = new LinkedHashMap<>();
		for (ModelProvider provider : providers) {
			String id = provider.providerId();
			new ModelRef(id, "validation");
			if (indexed.putIfAbsent(id, provider) != null) {
				throw new IllegalArgumentException("Duplicate model provider: " + id);
			}
			entries(provider);
			Objects.requireNonNull(provider.auth(), "provider auth");
		}
		this.providers = java.util.Collections.unmodifiableMap(indexed);
	}

	public List<ModelEntry> models() {
		List<ModelEntry> result = new ArrayList<>();
		for (ModelProvider provider : this.providers.values()) {
			boolean authenticated = provider.auth().available();
			for (ModelEntry entry : entries(provider)) {
				result.add(new ModelEntry(entry.ref(), entry.wireFamily(),
						authenticated ? entry.unavailableCode() : ModelUnavailableException.Code.AUTH_MISSING));
			}
		}
		return List.copyOf(result);
	}

	public ModelEntry requireAvailable(ModelRef ref) {
		ModelProvider provider = provider(ref);
		ModelEntry entry = entries(provider).stream().filter(candidate -> candidate.ref().equals(ref))
			.findFirst().orElseThrow(() -> unavailable(ModelUnavailableException.Code.UNKNOWN_MODEL, ref));
		if (!provider.auth().available()) {
			throw unavailable(ModelUnavailableException.Code.AUTH_MISSING, ref);
		}
		if (entry.unavailableCode() != null) {
			throw unavailable(entry.unavailableCode(), ref);
		}
		return entry;
	}

	public ModelBinding bind(ModelSelection selection) {
		ModelEntry entry = requireAvailable(selection.ref());
		ModelProvider provider = provider(selection.ref());
		if (!provider.supportedOptions().containsAll(selection.options().keySet())) {
			throw new IllegalArgumentException("Provider does not support selected generation options");
		}
		ChatModel model = Objects.requireNonNull(provider.create(selection.ref(), selection.options()), "model");
		Set<String> privateKeys = new java.util.HashSet<>();
		this.providers.values().forEach(contributor -> privateKeys.addAll(contributor.privateMetadataKeys()));
		model = new ProvenanceChatModel(model, selection.ref(), privateKeys);
		model = Objects.requireNonNull(this.clientFactory.modelBoundary(model), "model boundary");
		return new ModelBinding(UUID.randomUUID(), selection.ref(), selection.optionsFingerprint(), entry.wireFamily(),
				model, this.clientFactory.create(model));
	}

	/** Separate client assembly; provider/auth ownership remains with this registry. */
	public ModelRegistry scoped(AgentChatClientFactory scopedFactory) {
		return new ModelRegistry(List.copyOf(this.providers.values()), scopedFactory);
	}

	public Set<String> privateMetadataKeys(ModelRef ref) {
		ModelProvider provider = provider(ref);
		if (entries(provider).stream().noneMatch(entry -> entry.ref().equals(ref))) {
			throw unavailable(ModelUnavailableException.Code.UNKNOWN_MODEL, ref);
		}
		return Set.copyOf(provider.privateMetadataKeys());
	}

	private ModelProvider provider(ModelRef ref) {
		ModelProvider provider = this.providers.get(ref.provider());
		if (provider == null) {
			throw unavailable(ModelUnavailableException.Code.UNKNOWN_MODEL, ref);
		}
		return provider;
	}

	private static List<ModelEntry> entries(ModelProvider provider) {
		List<ModelEntry> entries = List.copyOf(provider.models());
		Set<ModelRef> seen = new java.util.HashSet<>();
		for (ModelEntry entry : entries) {
			if (!entry.ref().provider().equals(provider.providerId())) {
				throw new IllegalArgumentException("Model belongs to a different provider");
			}
			if (!seen.add(entry.ref())) {
				throw new IllegalArgumentException("Duplicate model: " + entry.ref());
			}
		}
		return entries;
	}

	private static ModelUnavailableException unavailable(ModelUnavailableException.Code code, ModelRef ref) {
		return new ModelUnavailableException(code, ref);
	}

}
