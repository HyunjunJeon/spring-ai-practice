package com.example.miniagent.core.model.registry;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.ModelUnavailableException;
import com.example.miniagent.core.model.ProviderAuth;
import com.example.miniagent.core.model.ToolContextKeys;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A binding strips the private fields of every registered provider, not only its own. */
class ModelRegistryPrivateKeysTest {
	private static final Set<String> ALPHA_KEYS = Set.of("alpha.signature");
	private static final Set<String> BETA_KEYS = Set.of("beta.reasoning", "beta.items");

	private final Probe alpha = new Probe("alpha", "a-model", new HashSet<>(ALPHA_KEYS));
	private final Probe beta = new Probe("beta", "b-model", new HashSet<>(BETA_KEYS));
	private final ModelRegistry registry = new ModelRegistry(List.of(this.alpha, this.beta),
			model -> ChatClient.builder(model).build());

	@Test
	void bindingBetaStripsAlphaAndBetaFieldsFromAnAlphaStampedMessage() {
		ModelBinding binding = this.registry.bind(new ModelSelection(this.beta.ref));
		AssistantMessage fromAlpha = assistant(this.alpha.ref, ALPHA_KEYS, BETA_KEYS);

		binding.chatModel().call(new Prompt(List.of(fromAlpha)));

		Message sent = this.beta.received.get().getInstructions().getFirst();
		assertThat(sent.getMetadata()).doesNotContainKeys("alpha.signature", "beta.reasoning", "beta.items")
				.containsEntry("public", "value");
		assertThat(sent.getText()).isEqualTo("answer");
		assertThat(fromAlpha.getMetadata()).containsKeys("alpha.signature", "beta.reasoning", "beta.items");
	}

	@Test
	void bindingAlphaStripsTheSameUnionFromABetaStampedMessage() {
		ModelBinding binding = this.registry.bind(new ModelSelection(this.alpha.ref));

		binding.chatModel().call(new Prompt(List.of(assistant(this.beta.ref, ALPHA_KEYS, BETA_KEYS))));

		assertThat(this.alpha.received.get().getInstructions().getFirst().getMetadata())
				.doesNotContainKeys("alpha.signature", "beta.reasoning", "beta.items")
				.containsEntry("public", "value");
	}

	@Test
	void messageStampedByTheBoundModelKeepsEveryPrivateField() {
		ModelBinding binding = this.registry.bind(new ModelSelection(this.beta.ref));
		AssistantMessage own = assistant(this.beta.ref, ALPHA_KEYS, BETA_KEYS);

		binding.chatModel().call(new Prompt(List.of(own)));

		assertThat(this.beta.received.get().getInstructions().getFirst()).isSameAs(own);
	}

	@Test
	void sameProviderOtherModelCountsAsForeign() {
		ModelBinding binding = this.registry.bind(new ModelSelection(this.beta.ref));

		binding.chatModel().call(new Prompt(List.of(assistant(new ModelRef("beta", "older-model"), ALPHA_KEYS, BETA_KEYS))));

		assertThat(this.beta.received.get().getInstructions().getFirst().getMetadata())
				.doesNotContainKeys("alpha.signature", "beta.reasoning", "beta.items");
	}

	@Test
	void keysTheRegistryDoesNotKnowStayInPlace() {
		ModelBinding binding = this.registry.bind(new ModelSelection(this.beta.ref));
		AssistantMessage unknownKey = AssistantMessage.builder().content("answer")
				.properties(Map.of(ToolContextKeys.MODEL_PROVIDER, "alpha", ToolContextKeys.MODEL_NAME, "a-model",
						"gamma.opaque", "kept", "alpha.signature", "signed")).build();

		binding.chatModel().call(new Prompt(List.of(unknownKey)));

		assertThat(this.beta.received.get().getInstructions().getFirst().getMetadata())
				.containsEntry("gamma.opaque", "kept").doesNotContainKey("alpha.signature");
	}

	@Test
	void privateKeysOfOneModelAreItsProvidersKeysOnly() {
		assertThat(this.registry.privateMetadataKeys(this.alpha.ref)).containsExactlyInAnyOrderElementsOf(ALPHA_KEYS);
		assertThat(this.registry.privateMetadataKeys(this.beta.ref)).containsExactlyInAnyOrderElementsOf(BETA_KEYS);
	}

	@Test
	void returnedKeysAreASnapshotThatNeitherCallerNorProviderCanChange() {
		Set<String> keys = this.registry.privateMetadataKeys(this.beta.ref);

		assertThatThrownBy(() -> keys.add("injected")).isInstanceOf(UnsupportedOperationException.class);
		this.beta.keys.add("beta.added-later");
		assertThat(keys).containsExactlyInAnyOrderElementsOf(BETA_KEYS);
		assertThat(this.registry.privateMetadataKeys(this.beta.ref)).contains("beta.added-later");
	}

	@Test
	void unknownProviderOrModelHasNoKeysToReport() {
		for (ModelRef unknown : List.of(new ModelRef("gamma", "a-model"), new ModelRef("alpha", "no-such-model"))) {
			assertThatThrownBy(() -> this.registry.privateMetadataKeys(unknown))
					.isInstanceOfSatisfying(ModelUnavailableException.class, error -> {
						assertThat(error.code()).isEqualTo(ModelUnavailableException.Code.UNKNOWN_MODEL);
						assertThat(error.ref()).isEqualTo(unknown);
					});
		}
	}

	private static AssistantMessage assistant(ModelRef stampedBy, Set<String> first, Set<String> second) {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put(ToolContextKeys.MODEL_PROVIDER, stampedBy.provider());
		metadata.put(ToolContextKeys.MODEL_NAME, stampedBy.model());
		metadata.put("public", "value");
		first.forEach(key -> metadata.put(key, "private"));
		second.forEach(key -> metadata.put(key, "private"));
		return AssistantMessage.builder().content("answer").properties(metadata).build();
	}

	/** A provider whose created models remember the last prompt they were sent. */
	private static final class Probe implements ModelProvider {
		private final ModelRef ref;

		private final Set<String> keys;

		private final AtomicReference<Prompt> received = new AtomicReference<>();

		private Probe(String provider, String model, Set<String> keys) {
			this.ref = new ModelRef(provider, model);
			this.keys = keys;
		}

		@Override
		public String providerId() {
			return this.ref.provider();
		}

		@Override
		public List<ModelEntry> models() {
			return List.of(new ModelEntry(this.ref, "openai", null));
		}

		@Override
		public ProviderAuth auth() {
			return () -> true;
		}

		@Override
		public ChatModel create(ModelRef ref, Map<String, String> options) {
			return new ChatModel() {
				@Override
				public ChatResponse call(Prompt prompt) {
					Probe.this.received.set(prompt);
					return new ChatResponse(List.of(new Generation(new AssistantMessage("reply"))));
				}

				@Override
				public ChatOptions getOptions() {
					return ToolCallingChatOptions.builder().build();
				}
			};
		}

		@Override
		public Set<String> privateMetadataKeys() {
			return this.keys;
		}
	}
}
