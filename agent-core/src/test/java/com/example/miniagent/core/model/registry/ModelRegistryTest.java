package com.example.miniagent.core.model.registry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.ModelUnavailableException;
import com.example.miniagent.core.model.ProviderAuth;
import com.example.miniagent.core.model.TurnContext;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRegistryTest {

	@Test
	void bindUnknownFailsWithUnknownModel() {
		TestProvider provider = new TestProvider("openai", "shared");
		ModelRegistry registry = registry(provider);
		for (ModelRef ref : List.of(new ModelRef("unknown", "shared"), new ModelRef("openai", "unknown"))) {
			ModelUnavailableException error = assertThrows(ModelUnavailableException.class,
					() -> registry.bind(new ModelSelection(ref)));
			assertThat(error.code()).isEqualTo(ModelUnavailableException.Code.UNKNOWN_MODEL);
			assertThat(error.ref()).isEqualTo(ref);
		}
		assertThat(provider.created).isEmpty();
	}

	@Test
	void missingAuthStaysListedAndFailsBeforeCreation() {
		TestProvider provider = new TestProvider("openai", "shared");
		provider.available.set(false);
		ModelRegistry registry = registry(provider);
		assertThat(registry.models()).containsExactly(new ModelEntry(provider.ref, "openai",
				ModelUnavailableException.Code.AUTH_MISSING));
		ModelUnavailableException error = assertThrows(ModelUnavailableException.class,
				() -> registry.bind(new ModelSelection(provider.ref)));
		assertThat(error.code()).isEqualTo(ModelUnavailableException.Code.AUTH_MISSING);
		assertThat(provider.created).isEmpty();
		provider.available.set(true);
		assertThat(registry.requireAvailable(provider.ref).unavailableCode()).isNull();
		assertThat(registry.bind(new ModelSelection(provider.ref)).ref()).isEqualTo(provider.ref);
	}

	@Test
	void duplicateProviderAndDuplicateOrForeignModelsAreRejected() {
		assertThatThrownBy(() -> registry(new TestProvider("zai", "a"), new TestProvider("zai", "b")))
			.isInstanceOf(IllegalArgumentException.class);
		TestProvider duplicate = new TestProvider("zai", "a");
		duplicate.entries = List.of(duplicate.entries.getFirst(), duplicate.entries.getFirst());
		assertThatThrownBy(() -> registry(duplicate)).isInstanceOf(IllegalArgumentException.class);
		TestProvider foreign = new TestProvider("zai", "a");
		foreign.entries = List.of(new ModelEntry(new ModelRef("openai", "a"), "openai", null));
		assertThatThrownBy(() -> registry(foreign)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void compatibleProvidersAndModelsHaveSeparateIdentityAndRuntime() {
		TestProvider openai = new TestProvider("openai", "shared");
		TestProvider zai = new TestProvider("zai", "shared");
		zai.entries = List.of(zai.entries.getFirst(), new ModelEntry(new ModelRef("zai", "other"), "openai", null));
		ModelRegistry registry = registry(openai, zai);
		ModelBinding a = registry.bind(new ModelSelection(openai.ref));
		ModelBinding b = registry.bind(new ModelSelection(zai.ref));
		ModelBinding c = registry.bind(new ModelSelection(new ModelRef("zai", "other")));
		assertThat(a.client().prompt("hello").call().content()).isEqualTo("openai/shared:{}");
		assertThat(b.client().prompt("hello").call().content()).isEqualTo("zai/shared:{}");
		assertThat(c.chatModel().call("summary")).isEqualTo("zai/other:{}");
		assertThat(a.chatModel()).isNotSameAs(b.chatModel());
		assertThat(registry.models()).hasSize(3);
	}

	@Test
	void identicalOptionsCreateFreshModelsClientsAndBindingIds() {
		TestProvider provider = new TestProvider("codex", "a");
		ModelRegistry registry = registry(provider);
		Map<String, String> source = new HashMap<>(Map.of("effort", "high"));
		ModelSelection selection = new ModelSelection(provider.ref, source);
		ModelBinding first = registry.bind(selection);
		source.put("effort", "low");
		ModelBinding second = registry.bind(selection);
		assertThat(first.bindingId()).isNotEqualTo(second.bindingId());
		assertThat(first.chatModel()).isNotSameAs(second.chatModel());
		assertThat(first.client()).isNotSameAs(second.client());
		assertThat(first.optionsFingerprint()).isEqualTo(second.optionsFingerprint());
		assertThat(first.client().prompt("hello").call().content()).isEqualTo("codex/a:{effort=high}");
		assertThat(second.chatModel().call("summary")).isEqualTo("codex/a:{effort=high}");
		assertThat(provider.created).hasSize(2);
		assertThatThrownBy(() -> selection.options().put("effort", "low"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void fingerprintIsOrderIndependentAndSensitiveToChanges() {
		ModelRef ref = new ModelRef("codex", "a");
		ModelSelection a = new ModelSelection(ref, Map.of("effort", "high", "temperature", "0.1"));
		Map<String, String> reversed = new java.util.LinkedHashMap<>();
		reversed.put("temperature", "0.1");
		reversed.put("effort", "high");
		assertThat(a.optionsFingerprint()).isEqualTo(new ModelSelection(ref, reversed).optionsFingerprint())
			.hasSize(64);
		assertThat(a.optionsFingerprint()).isNotEqualTo(
				new ModelSelection(ref, Map.of("effort", "low", "temperature", "0.1")).optionsFingerprint());
	}

	@Test
	void generationOptionsMustBeExplicitlySupportedAndNeverCredentialFields() {
		TestProvider provider = new TestProvider("codex", "a");
		assertThatThrownBy(() -> registry(provider).bind(
				new ModelSelection(provider.ref, Map.of("temperature", "0.1"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(provider.created).isEmpty();
		for (String key : List.of("apiKey", "authorization", "headers", "token", "password", "baseUrl")) {
			assertThatThrownBy(() -> new ModelSelection(provider.ref, Map.of(key, "synthetic-secret")))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void providerFailureNeverFallsBackOrInvokesAnotherProvider() {
		TestProvider failed = new TestProvider("openai", "a");
		TestProvider fallback = new TestProvider("zai", "a");
		failed.failure = new IllegalStateException("provider failure");
		assertThatThrownBy(() -> registry(failed, fallback).bind(new ModelSelection(failed.ref)))
			.isSameAs(failed.failure);
		assertThat(fallback.created).isEmpty();
	}

	@Test
	void providerUnavailableEntryAndPrivateKeysArePreserved() {
		TestProvider provider = new TestProvider("openai", "a");
		provider.entries = List.of(new ModelEntry(provider.ref, "openai", ModelUnavailableException.Code.UNKNOWN_MODEL));
		ModelRegistry registry = registry(provider);
		assertThat(registry.models()).isEqualTo(provider.entries);
		assertThatThrownBy(() -> registry.bind(new ModelSelection(provider.ref)))
			.isInstanceOf(ModelUnavailableException.class);
		assertThat(registry.privateMetadataKeys(provider.ref)).containsExactly("reasoning_content");
	}

	@Test
	void applicationFactoryReceivesExactModelAndConfiguresOfficialClient() {
		TestProvider provider = new TestProvider("fake", "a");
		List<ChatModel> assembled = new ArrayList<>();
		ModelRegistry registry = new ModelRegistry(List.of(provider), model -> {
			assembled.add(model);
			return ChatClient.builder(model).defaultSystem("system-sentinel").build();
		});
		ModelBinding binding = registry.bind(new ModelSelection(provider.ref));
		assertThat(assembled).containsExactly(binding.chatModel());
		assertThat(binding.client().prompt("hello").call().content()).isEqualTo("fake/a:{}");
		assertThat(binding.wireFamily()).isEqualTo("openai");
	}

	@Test
	void turnScopeUsesExactBindingRestoresNestingAndCleansUpOnFailure() {
		TestProvider provider = new TestProvider("fake", "a");
		ModelRegistry registry = registry(provider);
		TurnContext outer = new TurnContext("session", "turn", Path.of("."), registry.bind(new ModelSelection(provider.ref)));
		TurnContext inner = new TurnContext("child", "child-turn", Path.of("."),
				registry.bind(new ModelSelection(provider.ref)), "run", "attempt");
		assertThat(TurnContext.current()).isEmpty();
		try (TurnContext.Scope scope = outer.open()) {
			assertThat(TurnContext.current()).contains(outer);
			assertThat(TurnContext.current().orElseThrow().binding().chatModel()).isSameAs(outer.binding().chatModel());
			assertThatThrownBy(() -> {
				try (TurnContext.Scope nested = inner.open()) {
					assertThat(TurnContext.current()).contains(inner);
					throw new IllegalStateException("failure");
				}
			}).isInstanceOf(IllegalStateException.class);
			assertThat(TurnContext.current()).contains(outer);
		}
		assertThat(TurnContext.current()).isEmpty();
	}

	@Test
	void turnScopesRejectOutOfOrderAndCrossThreadCloseWithoutLosingContext() throws Exception {
		TestProvider provider = new TestProvider("fake", "a");
		TurnContext context = new TurnContext("session", "turn", Path.of("."),
				registry(provider).bind(new ModelSelection(provider.ref)));
		try (TurnContext.Scope outer = context.open()) {
			try (TurnContext.Scope inner = context.open()) {
				assertThatThrownBy(outer::close).isInstanceOf(IllegalStateException.class);
				try (var executor = Executors.newSingleThreadExecutor()) {
					executor.submit(() -> {
						assertThat(TurnContext.current()).isEmpty();
						assertThatThrownBy(inner::close).isInstanceOf(IllegalStateException.class);
						try (var local = context.open()) {
							assertThat(TurnContext.current()).contains(context);
						}
						assertThat(TurnContext.current()).isEmpty();
					}).get(5, TimeUnit.SECONDS);
				}
				assertThat(TurnContext.current()).contains(context);
			}
		}
		assertThat(TurnContext.current()).isEmpty();
	}

	private static ModelRegistry registry(TestProvider... providers) {
		return new ModelRegistry(List.of(providers), model -> ChatClient.builder(model).build());
	}

	private static final class TestProvider implements ModelProvider {

		private final ModelRef ref;

		private final AtomicBoolean available = new AtomicBoolean(true);

		private List<ModelEntry> entries;

		private final List<ChatModel> created = new ArrayList<>();

		private RuntimeException failure;

		private TestProvider(String provider, String model) {
			this.ref = new ModelRef(provider, model);
			this.entries = List.of(new ModelEntry(this.ref, "openai", null));
		}

		@Override
		public String providerId() {
			return this.ref.provider();
		}

		@Override
		public List<ModelEntry> models() {
			return this.entries;
		}

		@Override
		public ProviderAuth auth() {
			return this.available::get;
		}

		@Override
		public ChatModel create(ModelRef ref, Map<String, String> options) {
			if (this.failure != null) {
				throw this.failure;
			}
			ChatModel model = prompt -> new ChatResponse(List.of(new Generation(
					new AssistantMessage(ref.provider() + "/" + ref.model() + ":" + options))));
			this.created.add(model);
			return model;
		}

		@Override
		public Set<String> supportedOptions() {
			return Set.of("effort");
		}

		@Override
		public Set<String> privateMetadataKeys() {
			return Set.of("reasoning_content");
		}

	}

}
