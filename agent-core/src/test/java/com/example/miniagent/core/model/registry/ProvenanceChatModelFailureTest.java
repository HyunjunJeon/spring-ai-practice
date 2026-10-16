package com.example.miniagent.core.model.registry;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunSpec;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunUsage;
import com.example.miniagent.core.run.RunWriter;
import com.example.miniagent.core.run.Stop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A failed provider call must leave the run's token usage unknown, never a measured zero. */
class ProvenanceChatModelFailureTest {
	private static final ModelRef REF = new ModelRef("fake", "test");
	private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");

	@TempDir Path home;
	private RunWriter writer;
	private FileRunStore store;

	@AfterEach void close() throws Exception {
		if (this.writer != null) this.writer.close();
	}

	private RunContext run(long tokenCap) throws Exception {
		ModelSelection selection = new ModelSelection(REF);
		ChatModel unused = new ScriptedModel();
		ModelBinding binding = new ModelBinding(UUID.randomUUID(), REF, selection.optionsFingerprint(), "fake",
				unused, ChatClient.builder(unused).build());
		RunSpec spec = new RunSpec(1, RunId.random(), UUID.randomUUID().toString(), "parent", null, null, REF,
				"fake", this.home.toRealPath().toString(), new RunBudget(Duration.ofMinutes(30), tokenCap, 50),
				selection.optionsFingerprint(), "a".repeat(64), AT);
		this.store = new FileRunStore(this.home);
		this.writer = this.store.create(spec);
		String attempt = UUID.randomUUID().toString();
		this.writer.append(new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE));
		this.writer.append(new RunEvent.AttemptStarted(AT, attempt, binding.bindingId()));
		return new RunContext(spec, attempt, this.writer, new TurnContext(spec.conversationId(),
				UUID.randomUUID().toString(), this.home.toRealPath(), binding, spec.runId().value(), attempt),
				Clock.fixed(AT, ZoneOffset.UTC));
	}

	private List<RunUsage> modelRounds(RunContext run) throws Exception {
		return FileRunStore.events(this.store, run.spec().runId()).stream()
				.filter(RunEvent.UsageRecorded.class::isInstance).map(RunEvent.UsageRecorded.class::cast)
				.filter(recorded -> recorded.kind() == RunEvent.UsageKind.MODEL_ROUND)
				.map(RunEvent.UsageRecorded::usage).toList();
	}

	private static ChatResponse reply(int promptTokens, int completionTokens) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage("reply"))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(promptTokens, completionTokens)).build());
	}

	@Test void providerFailureIsRethrownAndRecordedOnceAsUnknownUsage() throws Exception {
		RunContext run = run(1000);
		IllegalStateException failure = new IllegalStateException("provider down");
		var model = new ProvenanceChatModel(new ScriptedModel().failing(failure), REF, Set.of());

		try (var scope = run.open()) {
			assertThatThrownBy(() -> model.call(new Prompt("hello"))).isSameAs(failure);
		}

		assertThat(modelRounds(run)).containsExactly(new RunUsage(0, true, 0, 0, 0));
		RunUsage usage = run.usage();
		assertThat(usage.tokensUnknown()).isTrue();
		assertThat(usage.knownTokens()).isZero();
		assertThat(usage.overshootTokens()).isZero();
		assertThat(this.writer.record().usage().tokensUnknown()).isTrue();
	}

	@Test void unknownUsageStopsTheNextModelCallOfACappedRun() throws Exception {
		RunContext run = run(1000);
		ScriptedModel provider = new ScriptedModel().failing(new IllegalStateException("provider down"));
		var model = new ProvenanceChatModel(provider, REF, Set.of());

		try (var scope = run.open()) {
			assertThatThrownBy(() -> model.call(new Prompt("hello"))).isInstanceOf(IllegalStateException.class);
			assertThatThrownBy(() -> model.call(new Prompt("again")))
					.isInstanceOfSatisfying(Stop.class, stop -> {
						assertThat(stop.kind()).isEqualTo(Stop.Kind.BUDGET_EXHAUSTED);
						assertThat(stop).hasMessage("Token usage is unknown");
					});
		}

		assertThat(provider.calls.get()).isEqualTo(1);
		assertThat(modelRounds(run)).hasSize(1);
	}

	@Test void failureAfterMeasuredRoundKeepsTheMeasuredTokensAndTurnsUnknown() throws Exception {
		RunContext run = run(1000);
		ScriptedModel provider = new ScriptedModel().replying(reply(3, 4));
		var model = new ProvenanceChatModel(provider, REF, Set.of());

		try (var scope = run.open()) {
			model.call(new Prompt("first"));
			assertThat(run.usage()).isEqualTo(new RunUsage(7, false, 0, 0, 0));
			provider.failing(new IllegalStateException("provider down"));
			assertThatThrownBy(() -> model.call(new Prompt("second"))).isInstanceOf(IllegalStateException.class);
		}

		assertThat(modelRounds(run)).containsExactly(new RunUsage(7, false, 0, 0, 0), new RunUsage(0, true, 0, 0, 0));
		assertThat(run.usage().knownTokens()).isEqualTo(7);
		assertThat(run.usage().tokensUnknown()).isTrue();
	}

	@Test void failureOutsideAnyRunContextJustPropagates() {
		IllegalStateException failure = new IllegalStateException("provider down");
		var model = new ProvenanceChatModel(new ScriptedModel().failing(failure), REF, Set.of());

		assertThatThrownBy(() -> model.call(new Prompt("hello"))).isSameAs(failure);
	}

	@Test void exhaustedBudgetStopsBeforeTheProviderAndIsNotRecordedAsFailure() throws Exception {
		RunContext run = run(10);
		ScriptedModel provider = new ScriptedModel().replying(reply(5, 5));
		var model = new ProvenanceChatModel(provider, REF, Set.of());

		try (var scope = run.open()) {
			model.call(new Prompt("spends the cap"));
			assertThatThrownBy(() -> model.call(new Prompt("over the cap")))
					.isInstanceOfSatisfying(Stop.class, stop -> assertThat(stop).hasMessage("Token budget exhausted"));
		}

		assertThat(provider.calls.get()).isEqualTo(1);
		assertThat(modelRounds(run)).containsExactly(new RunUsage(10, false, 0, 0, 0));
		assertThat(run.usage().tokensUnknown()).isFalse();
	}

	/** Counts calls and either replies with canned usage or throws. */
	private static final class ScriptedModel implements ChatModel {
		private final AtomicInteger calls = new AtomicInteger();

		private ChatResponse response = reply(0, 0);

		private RuntimeException failure;

		private ScriptedModel replying(ChatResponse response) {
			this.response = response;
			return this;
		}

		private ScriptedModel failing(RuntimeException failure) {
			this.failure = failure;
			return this;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			this.calls.incrementAndGet();
			if (this.failure != null) throw this.failure;
			return this.response;
		}

		@Override
		public ChatOptions getOptions() {
			return ToolCallingChatOptions.builder().build();
		}
	}
}
