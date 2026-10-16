package com.example.miniagent.core.messaging;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ToolContextKeys;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.delegation.runtime.DefaultDelegationRuntime;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunSpec;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The model-facing mailbox surface: only the exact captured managed actor may send or read. */
class MessageToolTest {
	static final String SEND = "{\"plan\":\"plan\",\"child\":\"child\",\"id\":\"m-0\",\"version\":0,\"body\":\"hello\"}";
	static final String READ = "{\"plan\":\"plan\",\"child\":\"child\",\"offset\":0}";
	@TempDir Path root;
	private Fixture f;

	/** Real run and turn contexts over a real mailbox; only the delegation runtime is faked. */
	static final class Fixture implements AutoCloseable {
		final Path root;
		final DefaultDelegationRuntime runtime = mock(DefaultDelegationRuntime.class);
		final MessageService service;
		final RunContext actor;
		final MessageTool send, read;
		private final FileRunStore runs;
		private final List<RunWriter> writers = new ArrayList<>();

		Fixture(Path root) throws Exception {
			this.root = root;
			runs = new FileRunStore(root);
			service = new MessageService(new MessageStore(mailbox()), runtime);
			send = new MessageTool(() -> service, true);
			read = new MessageTool(() -> service, false);
			actor = context("parent-run");
			when(runtime.messageTarget(actor, "plan", "child")).thenReturn(MessageServiceOutcomeTest.target("parent"));
		}

		Path mailbox() { return root.resolve("mail"); }

		RunContext context(String runId) throws Exception {
			var spec = new RunSpec(RunSpec.SCHEMA_VERSION, new RunId(runId), "conversation-" + runId, "original",
				null, null, new ModelRef("fake", "test"), "fake", root.toString(), RunBudget.defaults(),
				MessageServiceOutcomeTest.DIGEST, MessageServiceOutcomeTest.DIGEST, Instant.EPOCH);
			var writer = runs.create(spec);
			writers.add(writer);
			var binding = new ModelBinding(UUID.randomUUID(), spec.model(), MessageServiceOutcomeTest.DIGEST, "fake",
				mock(ChatModel.class), mock(ChatClient.class));
			var turn = new TurnContext(spec.conversationId(), "turn-" + runId, root, binding, runId, "attempt");
			return new RunContext(spec, "attempt", writer, turn, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
		}

		/** The tool context the engine captures for the given actor. */
		ToolContext capturedBy(RunContext captured) {
			return new ToolContext(Map.of(RunContext.TOOL_CONTEXT_KEY, captured,
				ToolContextKeys.TURN_CONTEXT, captured.turnContext()));
		}

		@Override public void close() throws Exception {
			for (var writer : writers) writer.close();
		}
	}

	@BeforeEach void openFixture() throws Exception { f = new Fixture(root.toRealPath()); }
	@AfterEach void closeFixture() throws Exception { f.close(); }

	@Test void toolsExposeStableNamesAndStrictSchemas() throws Exception {
		assertThat(f.send.getToolDefinition().name()).isEqualTo("send_message");
		assertThat(f.read.getToolDefinition().name()).isEqualTo("read_messages");
		var sendSchema = MessageStore.JSON.readTree(f.send.getToolDefinition().inputSchema());
		var readSchema = MessageStore.JSON.readTree(f.read.getToolDefinition().inputSchema());
		assertThat(sendSchema.get("additionalProperties").asBoolean(true)).isFalse();
		assertThat(readSchema.get("additionalProperties").asBoolean(true)).isFalse();
		assertThat(required(sendSchema)).containsExactly("plan", "child", "id", "version", "body");
		assertThat(required(readSchema)).containsExactly("plan", "child", "offset");
		assertThat(sendSchema.get("properties").get("body").get("maxLength").asInt()).isEqualTo(MessageStore.MAX_BODY);
		assertThat(sendSchema.get("properties").get("version").get("minimum").asInt()).isZero();
		assertThat(readSchema.get("properties").get("offset").get("minimum").asInt()).isZero();
	}

	private static List<String> required(JsonNode schema) {
		var names = new ArrayList<String>();
		schema.get("required").forEach(name -> names.add(name.asString()));
		return names;
	}

	@Test void callWithoutACapturedToolContextIsRefused() {
		assertThatThrownBy(() -> f.send.call(SEND)).isInstanceOf(IllegalStateException.class)
			.hasMessage("Captured managed context required");
		assertThatThrownBy(() -> f.read.call(READ)).isInstanceOf(IllegalStateException.class)
			.hasMessage("Captured managed context required");
		verifyNoInteractions(f.runtime);
	}

	@Test void sendDispatchesToTheOwnedChildAndReturnsTheReceiptAsJson() throws Exception {
		when(f.runtime.followUp(f.actor, "plan", "child", "hello"))
			.thenReturn(MessageServiceOutcomeTest.result(RunState.COMPLETED, "child says hi", 0));
		String json;
		try (var run = f.actor.open(); var turn = f.actor.turnContext().open()) {
			json = f.send.call(SEND, f.capturedBy(f.actor));
		}
		var receipt = MessageStore.JSON.readTree(json);
		assertThat(receipt.get("id").asString()).isEqualTo("m-0");
		assertThat(receipt.get("sender").asString()).isEqualTo("parent");
		assertThat(receipt.get("body").asString()).isEqualTo("hello");
		assertThat(receipt.get("state").asString()).isEqualTo("DELIVERED");
		assertThat(receipt.get("originalRun").asString()).isEqualTo("original-run");
		assertThat(receipt.get("executionRun").asString()).isEqualTo("execution-run");
		assertThat(receipt.get("response").asString()).isEqualTo("child says hi");
		assertThat(receipt.get("responseTruncated").asBoolean(true)).isFalse();
	}

	@Test void readReturnsThePageAtTheRequestedOffsetWithTheCasVersion() throws Exception {
		when(f.runtime.followUp(any(), any(), any(), any()))
			.thenReturn(MessageServiceOutcomeTest.result(RunState.COMPLETED, "ok", 0));
		for (int i = 0; i < 3; i++) f.service.send(f.actor, "plan", "child", "m-" + i, i * 2L, "body " + i);
		String json;
		try (var run = f.actor.open(); var turn = f.actor.turnContext().open()) {
			json = f.read.call("{\"plan\":\"plan\",\"child\":\"child\",\"offset\":1}", f.capturedBy(f.actor));
		}
		var page = MessageStore.JSON.readTree(json);
		assertThat(page.get("version").asLong()).isEqualTo(6);
		assertThat(page.get("nextOffset").asInt()).isEqualTo(3);
		var ids = new ArrayList<String>();
		page.get("entries").forEach(entry -> ids.add(entry.get("id").asString()));
		assertThat(ids).containsExactly("m-1", "m-2");
	}

	@Test void aServiceRefusalSurfacesAsAnUncheckedIoExceptionAndExecutesNothing() throws Exception {
		try (var run = f.actor.open(); var turn = f.actor.turnContext().open()) {
			var stale = "{\"plan\":\"plan\",\"child\":\"child\",\"id\":\"m-0\",\"version\":5,\"body\":\"hello\"}";
			assertThatThrownBy(() -> f.send.call(stale, f.capturedBy(f.actor)))
				.isInstanceOf(UncheckedIOException.class).hasMessageContaining("Stale mailbox version");
		}
		verify(f.runtime, never()).followUp(any(), any(), any(), any());
	}

	private void assertEveryCallRefusedAsForeignActor(ToolContext tools) {
		assertThatThrownBy(() -> f.send.call(SEND, tools)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Exact captured message actor required");
		assertThatThrownBy(() -> f.read.call(READ, tools)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Exact captured message actor required");
		verifyNoInteractions(f.runtime);
		assertThat(f.mailbox()).doesNotExist();
	}

	@Test void aToolContextWithoutTheTurnContextIsRefused() {
		try (var run = f.actor.open(); var turn = f.actor.turnContext().open()) {
			assertEveryCallRefusedAsForeignActor(new ToolContext(Map.of(RunContext.TOOL_CONTEXT_KEY, f.actor)));
		}
	}

	@Test void aToolContextCarryingAnotherTurnContextIsRefused() throws Exception {
		var other = f.context("other-run");
		try (var run = f.actor.open(); var turn = f.actor.turnContext().open()) {
			assertEveryCallRefusedAsForeignActor(new ToolContext(Map.of(RunContext.TOOL_CONTEXT_KEY, f.actor,
				ToolContextKeys.TURN_CONTEXT, other.turnContext())));
		}
	}

	@Test void aCapturedActorThatIsNotTheCurrentRunContextIsRefused() {
		try (var turn = f.actor.turnContext().open()) {
			assertEveryCallRefusedAsForeignActor(f.capturedBy(f.actor));
		}
	}

	@Test void aStaleCapturedActorWhileAnotherRunContextIsCurrentIsRefused() throws Exception {
		var current = f.context("current-run");
		try (var run = current.open(); var turn = f.actor.turnContext().open()) {
			assertEveryCallRefusedAsForeignActor(f.capturedBy(f.actor));
		}
	}

	@Test void aCapturedActorThatIsNotOnTheCurrentTurnIsRefused() {
		try (var run = f.actor.open()) {
			assertEveryCallRefusedAsForeignActor(f.capturedBy(f.actor));
		}
	}

	@Test void anotherTurnOnTheCurrentThreadIsRefused() throws Exception {
		var other = f.context("other-run");
		try (var run = f.actor.open(); var turn = other.turnContext().open()) {
			assertEveryCallRefusedAsForeignActor(f.capturedBy(f.actor));
		}
	}

	@Test void aToolContextWithoutACapturedRunContextIsRefusedBeforeAnyMailboxAccess() {
		var tools = new ToolContext(Map.of(ToolContextKeys.TURN_CONTEXT, f.actor.turnContext()));
		assertThatThrownBy(() -> f.send.call(SEND, tools)).isInstanceOf(NoSuchElementException.class);
		assertThatThrownBy(() -> f.read.call(READ, tools)).isInstanceOf(NoSuchElementException.class);
		verifyNoInteractions(f.runtime);
		assertThat(f.mailbox()).doesNotExist();
	}

	@Test void aCapturedValueThatIsNotARunContextIsInvalid() {
		var tools = new ToolContext(Map.of(RunContext.TOOL_CONTEXT_KEY, "not a run context"));
		assertThatThrownBy(() -> f.send.call(SEND, tools)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Invalid captured RunContext");
		verifyNoInteractions(f.runtime);
	}
}
