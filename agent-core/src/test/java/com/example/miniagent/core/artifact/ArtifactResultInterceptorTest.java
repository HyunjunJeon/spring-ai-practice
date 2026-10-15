package com.example.miniagent.core.artifact;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.InterceptingToolCallback;
import com.example.miniagent.core.ToolContextKeys;
import com.example.miniagent.core.ToolInterceptor;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.SideEffectClass;
import com.example.miniagent.core.run.ToolJournalInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ArtifactResultInterceptorTest {
	@TempDir Path temp;

	private TurnContext turn() throws Exception {
		var binding = new ModelBinding(UUID.randomUUID(), new ModelRef("fixture", "unused"), "fixture", "fixture",
				mock(ChatModel.class), mock(ChatClient.class));
		return new TurnContext("session", "turn", this.temp.toRealPath(), binding);
	}
	private ToolContext context(TurnContext turn) {
		return new ToolContext(Map.of(ToolContextKeys.TURN_CONTEXT, turn,
				ToolContextKeys.SESSION_ID, "forged", ToolContextKeys.CWD, "/untrusted"));
	}
	private ToolDefinition definition(String name) {
		return ToolDefinition.builder().name(name).description("fixture").inputSchema("{}").build();
	}

	@Test void originalCallbackAuthorityDefinitionAndExactJsonEncodedTextArePreserved() throws Exception {
		var properties = new ArtifactProperties();
		properties.setThresholdChars(8);
		var store = new ArtifactStore(this.temp.toRealPath().resolve("home"), properties);
		var interceptor = new ArtifactResultInterceptor(store, properties, Map.of());
		var turn = turn();
		var context = context(turn);
		var definition = definition("original");
		String raw = "\"complete\\ncallback\\\"result\"";
		ToolCallback callback = new ToolCallback() {
			@Override public ToolDefinition getToolDefinition() { return definition; }
			@Override public String call(String input) { throw new AssertionError("authority missing"); }
			@Override public String call(String input, ToolContext captured) {
				assertThat(input).isEqualTo("{\"input\":1}");
				assertThat(captured).isSameAs(context);
				return raw;
			}
		};
		var wrapped = InterceptingToolCallback.wrap(callback, List.of(interceptor));
		assertThat(wrapped.getToolDefinition()).isSameAs(definition);
		String result = wrapped.call("{\"input\":1}", context);
		var descriptor = ArtifactStore.JSON.readValue(result, ArtifactStore.Descriptor.class);
		assertThat(store.read(turn, descriptor.artifactId(), 0, 100).content()).isEqualTo(raw);
		assertThat(descriptor.bytes()).isEqualTo(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
		assertThat(new ToolJournalInterceptor().getOrder()).isLessThan(interceptor.getOrder());
	}

	@Test void thresholdLegacyContextAndThreadLocalNeverCreateImplicitOwnership() throws Exception {
		var properties = new ArtifactProperties();
		properties.setThresholdChars(8);
		var store = new ArtifactStore(this.temp.toRealPath().resolve("home"), properties);
		var interceptor = new ArtifactResultInterceptor(store, properties, Map.of());
		var invocation = new ToolInterceptor.ToolInvocation(definition("original"), "{}", context(turn()));
		assertThat(interceptor.intercept(invocation, ignored -> "12345678")).isEqualTo("12345678");
		try (var scope = turn().open()) {
			var legacy = new ToolInterceptor.ToolInvocation(definition("original"), "{}",
					new ToolContext(Map.of(ToolContextKeys.SESSION_ID, "session", ToolContextKeys.CWD, this.temp)));
			assertThat(interceptor.intercept(legacy, ignored -> "x".repeat(9000))).hasSize(9000);
			assertThat(interceptor.intercept(new ToolInterceptor.ToolInvocation(definition("original"), "{}", null),
					ignored -> "x".repeat(9000))).hasSize(9000);
			assertThatThrownBy(() -> new ArtifactTools(store).readArtifact("unused", null, null, legacy.context()))
					.isInstanceOfSatisfying(ArtifactStore.Failure.class,
							ex -> assertThat(ex.code()).isEqualTo(ArtifactStore.Code.AUTHORITY_REQUIRED));
		}
	}

	@Test void stableRetrievalDefinitionsAreReadOnlyAndExcludedByDigestNotName() throws Exception {
		var properties = new ArtifactProperties();
		properties.setThresholdChars(1);
		var store = new ArtifactStore(this.temp.toRealPath().resolve("home"), properties);
		var configuration = new ArtifactAutoConfiguration();
		var tools = configuration.artifactTools(store);
		var provider = configuration.artifactToolsProvider(tools);
		var callbacks = provider.getToolCallbacks();
		var definitions = configuration.artifactReadPolicy(provider).readOnlyDefinitions();
		assertThat(provider.getToolCallbacks()[0]).isSameAs(callbacks[0]);
		assertThat(definitions.keySet()).containsExactlyInAnyOrder("read_artifact", "search_artifact");
		var interceptor = configuration.artifactResultInterceptor(store, properties, () -> definitions);
		var authority = turn();
		var captured = context(authority);
		var saved = store.put(authority, "a".repeat(3000));
		var read = java.util.Arrays.stream(callbacks)
				.filter(callback -> callback.getToolDefinition().name().equals("read_artifact")).findFirst().orElseThrow();
		assertThat(definitions.get("read_artifact")).isEqualTo(SideEffectClass.definitionDigest(read.getToolDefinition()));
		String result = InterceptingToolCallback.wrap(read, List.of(interceptor))
				.call("{\"artifactId\":\"" + saved.artifactId() + "\",\"offset\":0,\"limit\":1024}", captured);
		var page = ArtifactStore.JSON.readValue(result, ArtifactStore.Page.class);
		assertThat(page.content()).hasSize(1024);
		assertThat(page.truncated()).isTrue();
		assertThat(tools.readArtifact(saved.artifactId(), null, null, captured).content()).hasSize(1024);
		assertThat(tools.searchArtifact(saved.artifactId(), "a", null, null, captured).matches()).hasSize(10);
		var impostor = new ToolInterceptor.ToolInvocation(definition("read_artifact"), "{}", captured);
		assertThat(ArtifactStore.JSON.readValue(interceptor.intercept(impostor, ignored -> "different"),
				ArtifactStore.Descriptor.class).artifactId()).isNotBlank();
	}

	@Test void publicationFailureDoesNotReturnRawOutputOrAcknowledgeOuterJournal() throws Exception {
		var properties = new ArtifactProperties();
		properties.setThresholdChars(1);
		properties.setMaxArtifactBytes(2);
		var store = new ArtifactStore(this.temp.toRealPath().resolve("home"), properties);
		var interceptor = new ArtifactResultInterceptor(store, properties, Map.of());
		var invocation = new ToolInterceptor.ToolInvocation(definition("mutating"), "{}", context(turn()));
		AtomicReference<String> acknowledged = new AtomicReference<>();
		ToolInterceptor outer = (captured, chain) -> {
			String result = chain.proceed(captured);
			acknowledged.set(result);
			return result;
		};
		assertThatThrownBy(() -> outer.intercept(invocation,
				captured -> interceptor.intercept(captured, ignored -> "completed effect result")))
				.isInstanceOfSatisfying(ArtifactStore.Failure.class,
						ex -> assertThat(ex.code()).isEqualTo(ArtifactStore.Code.TOO_LARGE));
		assertThat(acknowledged.get()).isNull();
	}
}
