package com.example.miniagent.app.machine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.approval.ConsoleApprovalBroker;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.model.registry.ModelRegistry;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.DefaultApplicationArguments;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(20)
class MachineRunnerTest {
	@TempDir Path home;

	@Test void oneShotStreamsCorrelatedParseableFramesWithRealScriptedProvider() throws Exception {
		try (var fixture = new Fixture("json", "", prompt -> {}, List.of())) {
			fixture.runner.run(new DefaultApplicationArguments("hello"));
			var completion = fixture.output.await("completion", "one-shot");
			assertThat(completion.path("status").asString()).isEqualTo("completed");
			assertThat(completion.path("turnId").asString()).isNotBlank();
			var deltas = fixture.output.frames.stream().filter(f -> f.path("type").asString().equals("delta")).toList();
			assertThat(deltas).isNotEmpty().allSatisfy(delta -> {
				assertThat(delta.path("executionId")).isEqualTo(completion.path("executionId"));
				assertThat(delta.path("sessionId")).isEqualTo(completion.path("sessionId"));
				assertThat(delta.path("turnId")).isEqualTo(completion.path("turnId"));
			});
			assertThat(fixture.runner.getExitCode()).isZero();
			fixture.output.bytes.toString(StandardCharsets.UTF_8).lines()
					.forEach(line -> assertThat(fixture.output.json.readTree(line).isObject()).isTrue());
		}
	}

	@Test void refusesConcurrentTurnsMutationsAndWrongCancelUntilActualWorkerExit() throws Exception {
		var entered = new CountDownLatch(1);
		var interrupted = new CountDownLatch(1);
		var release = new CompletableFuture<Void>();
		try (var fixture = new Fixture("rpc", "", prompt -> {
			entered.countDown();
			try { new CountDownLatch(1).await(); }
			catch (InterruptedException ex) {
				interrupted.countDown();
				release.join(); // Deliberately keep provider alive after cancel acknowledgement.
				Thread.currentThread().interrupt();
			}
		}, List.of())) {
			try {
				fixture.send("p", "prompt", Map.of("text", "hello"));
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				fixture.send("wrong", "cancel", Map.of("targetId", "other"));
				assertThat(fixture.output.await("error", "wrong").path("code").asString()).isEqualTo("not_active");
				assertThat(interrupted.getCount()).isEqualTo(1);
				fixture.send("model", "command", Map.of("text", "/model fake/other"));
				assertThat(fixture.output.await("error", "model").path("code").asString()).isEqualTo("busy");
				fixture.send("cancel", "cancel", Map.of("targetId", "p"));
				assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
				assertThat(fixture.output.await("response", "cancel").path("status").asString()).isEqualTo("cancelling");
				fixture.send("next", "prompt", Map.of("text", "no overlap"));
				assertThat(fixture.output.await("error", "next").path("code").asString()).isEqualTo("busy");
			}
			finally { release.complete(null); }
			assertThat(fixture.output.await("completion", "p").path("status").asString()).isEqualTo("cancelled");
			assertThat(fixture.output.frames.stream().filter(f -> f.path("type").asString().equals("completion")).count())
					.isEqualTo(1);
		}
	}

	@Test void queueAckReferencesParentAndCancellationDropsFollowUps() throws Exception {
		var entered = new CountDownLatch(1);
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		try (var fixture = new Fixture("rpc", "", prompt -> {
			calls.incrementAndGet();
			entered.countDown();
			try { new CountDownLatch(1).await(); }
			catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
		}, List.of())) {
			fixture.send("p", "prompt", Map.of("text", "hello"));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			var parent = fixture.output.await("response", "p");
			fixture.send("queue", "follow_up", Map.of("targetId", "p", "text", "/new is literal queued text"));
			var queued = fixture.output.await("response", "queue");
			assertThat(queued.path("executionId")).isEqualTo(parent.path("executionId"));
			fixture.send("cancel", "cancel", Map.of("targetId", "p"));
			fixture.output.await("completion", "p");
			assertThat(calls.get()).isEqualTo(1);
		}
	}

	@Test void queuedTurnsKeepParentExecutionButPublishDistinctActualTurnIds() throws Exception {
		var entered = new LinkedBlockingQueue<String>();
		var first = new CompletableFuture<Void>();
		var second = new CompletableFuture<Void>();
		try (var fixture = new Fixture("rpc", "", prompt -> {
			String text = prompt.getInstructions().getLast().getText();
			entered.add(text);
			(text.equals("first") ? first : second).join();
		}, List.of())) {
			try {
				fixture.send("p", "prompt", Map.of("text", "first"));
				assertThat(entered.poll(5, TimeUnit.SECONDS)).isEqualTo("first");
				fixture.send("q", "follow_up", Map.of("targetId", "p", "text", "second"));
				var queued = fixture.output.await("response", "q");
				first.complete(null);
				assertThat(entered.poll(5, TimeUnit.SECONDS)).isEqualTo("second");
				second.complete(null);
				var completion = fixture.output.await("completion", "p");
				assertThat(completion.path("executionId")).isEqualTo(queued.path("executionId"));
				var turns = fixture.output.frames.stream()
						.filter(f -> f.path("event").asString("").equals("ModelBound")).toList();
				assertThat(turns).hasSize(2);
				assertThat(turns.get(0).path("turnId")).isNotEqualTo(turns.get(1).path("turnId"));
				assertThat(turns).allSatisfy(frame -> {
					assertThat(frame.path("requestId").asString()).isEqualTo("p");
					assertThat(frame.path("executionId")).isEqualTo(completion.path("executionId"));
				});
			}
			finally { first.complete(null); second.complete(null); }
		}
	}

	@Test void lateQueueTargetCannotSteerNewerExecution() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CompletableFuture<Void>();
		try (var fixture = new Fixture("rpc", "", prompt -> {
			if (prompt.getInstructions().getLast().getText().equals("second")) {
				entered.countDown(); release.join();
			}
		}, List.of())) {
			try {
				fixture.send("old", "prompt", Map.of("text", "first"));
				fixture.output.await("completion", "old");
				fixture.send("new", "prompt", Map.of("text", "second"));
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				fixture.send("late", "steer", Map.of("targetId", "old", "text", "must not enter new turn"));
				assertThat(fixture.output.await("error", "late").path("code").asString()).isEqualTo("not_active");
			}
			finally { release.complete(null); }
			assertThat(fixture.output.await("completion", "new").path("status").asString()).isEqualTo("completed");
		}
	}

	@Test void shutdownCancelsQueuedWorkAndLegacyQuestions() throws Exception {
		RpcConsole[] console = new RpcConsole[1];
		try (var fixture = new Fixture("rpc", "", prompt -> console[0].readLine("confirm"), List.of())) {
			console[0] = fixture.console;
			fixture.send("p", "prompt", Map.of("text", "hello"));
			var question = fixture.output.awaitEvent("question");
			fixture.send("q", "follow_up", Map.of("targetId", "p", "text", "must not run"));
			fixture.send("stop", "shutdown", Map.of());
			assertThat(fixture.output.await("completion", "p").path("status").asString()).isEqualTo("cancelled");
			fixture.send("late", "question", Map.of("questionId", question.path("questionId").asString(), "text", "yes"));
			assertThat(fixture.output.await("error", "late").path("code").asString()).isEqualTo("shutting_down");
			assertThat(fixture.output.frames.stream()
					.filter(f -> f.path("event").asString("").equals("ModelBound")).count()).isEqualTo(1);
		}
	}

	@Test void approvalCallbackDoesNotHoldConsoleMonitor() throws Exception {
		try (var fixture = new Fixture("rpc", "", prompt -> {}, List.of())) {
			var entered = new CountDownLatch(1);
			var release = new CompletableFuture<Void>();
			var request = approval();
			fixture.console.requestApproval(request, answer -> { entered.countDown(); release.join(); });
			var dispatched = CompletableFuture.runAsync(() ->
					fixture.console.answerApproval(request.requestId(), ApprovalDecision.REJECTED, false));
			try {
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				var dismissed = CompletableFuture.runAsync(() -> fixture.console.dismissApproval(request.requestId()));
				dismissed.get(5, TimeUnit.SECONDS);
			}
			finally { release.complete(null); dispatched.get(5, TimeUnit.SECONDS); }
		}
	}

	@Test void eofCompletesActiveWorkAndClosesLegacyQuestionWithoutReadingStdinTwice() throws Exception {
		RpcConsole[] console = new RpcConsole[1];
		try (var fixture = new Fixture("rpc",
				"{\"v\":1,\"id\":\"p\",\"method\":\"prompt\",\"text\":\"hello\"}\n",
				prompt -> assertThat(console[0].readLine("tool confirmation")).isNull(), List.of())) {
			console[0] = fixture.console;
			fixture.runner.run(new DefaultApplicationArguments());
			assertThat(fixture.output.await("completion", "p").path("status").asString()).isEqualTo("completed");
			assertThat(fixture.console.readLine("after EOF")).isNull();
		}
	}

	@Test void exactManagedCallbackPersistsDecisionAndRejectsStaleApproval() throws Exception {
		try (var fixture = new Fixture("rpc", "", prompt -> {}, List.of())) {
			var broker = new ConsoleApprovalBroker(fixture.console);
			var decisions = new ArrayList<ApprovalDecision>();
			ApprovalRequest first = approval();
			var result = broker.requestChange(first, decisions::add);
			fixture.send("answer", "approval", Map.of("approvalId", first.requestId().toString(),
					"decision", "REJECTED"));
			assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.REJECTED);
			ApprovalRequest second = approval();
			var next = broker.requestChange(second, decisions::add);
			fixture.send("stale", "approval", Map.of("approvalId", first.requestId().toString(),
					"decision", "APPROVED"));
			assertThat(fixture.output.await("error", "stale").path("code").asString()).isEqualTo("stale_approval");
			assertThat(next).isNotDone();
			fixture.console.clearQuestions(true);
			assertThat(next.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.CANCELLED);
			assertThat(decisions).containsExactly(ApprovalDecision.REJECTED, ApprovalDecision.CANCELLED);
			assertThat(broker.pending()).isEmpty();
		}
	}

	@Test void legacyQuestionsRequireOneTimeUuidAndNeverConsumeRpcInput() throws Exception {
		try (var fixture = new Fixture("rpc", "", prompt -> {}, List.of())) {
			var answer = new CompletableFuture<String>();
			Thread worker = Thread.startVirtualThread(() -> answer.complete(fixture.console.readLine("confirm")));
			try {
				var question = fixture.output.awaitEvent("question");
				String id = question.path("questionId").asString();
				fixture.send("wrong", "question", Map.of("questionId", UUID.randomUUID().toString(), "text", "yes"));
				assertThat(fixture.output.await("error", "wrong").path("code").asString()).isEqualTo("stale_question");
				fixture.send("correct", "question", Map.of("questionId", id, "text", "yes"));
				assertThat(answer.get(5, TimeUnit.SECONDS)).isEqualTo("yes");
				fixture.send("stale", "question", Map.of("questionId", id, "text", "yes"));
				assertThat(fixture.output.await("error", "stale").path("code").asString()).isEqualTo("stale_question");
			}
			finally { fixture.console.clearQuestions(true); worker.join(); }
		}
	}

	@Test void eofDefersManagedCallbackAndErrorsNeverReflectSecrets() throws Exception {
		try (var fixture = new Fixture("rpc", "", prompt -> {}, List.of())) {
			var broker = new ConsoleApprovalBroker(fixture.console);
			var result = broker.requestChange(approval(), decision -> {});
			fixture.console.clearQuestions(false);
			assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.DEFERRED);
			fixture.console.error("java.lang.Exception: credential=secret");
			assertThat(fixture.output.bytes.toString(StandardCharsets.UTF_8)).doesNotContain("credential", "secret", "Exception");
		}
	}

	private ApprovalRequest approval() {
		return new ApprovalRequest(UUID.randomUUID(), new RunId(UUID.randomUUID().toString()),
				"attempt", null, null, "tool", ApprovalRequest.digest("args"), ApprovalRequest.digest("diff"));
	}

	private final class Fixture implements AutoCloseable {
		final Output output = new Output();
		final MachineProtocol protocol = new MachineProtocol(this.output);
		final RpcConsole console = new RpcConsole(this.protocol);
		final AgentSession session = new AgentSession();
		final MachineRunner runner;
		Fixture(String mode, String input, Consumer<Prompt> before, List<SlashCommand> commands)
				throws java.io.IOException {
			var ref = new ModelRef("fake", "machine");
			ModelProvider provider = new ModelProvider() {
				@Override public String providerId() { return "fake"; }
				@Override public List<ModelEntry> models() { return List.of(new ModelEntry(ref, "fake", null)); }
				@Override public ProviderAuth auth() { return () -> true; }
				@Override public ChatModel create(ModelRef model, Map<String, String> options) {
					return new ScriptedChatModel() {
						@Override public Flux<ChatResponse> stream(Prompt prompt) {
							before.accept(prompt);
							return super.stream(prompt);
						}
					};
				}
			};
			var registry = new ModelRegistry(List.of(provider), model -> ChatClient.builder(model).build());
			var agent = new Agent(registry, new FileSessionModelStore(home), new ModelSelection(ref, Map.of()),
					this.session, home, "", List.of(), List.of(), List.of(), List.of(), 0, this.console::onTrace);
			var handler = new AgentInputHandler(agent, this.session, this.console, commands);
			this.runner = new MachineRunner(handler, this.session, this.console, this.protocol,
					new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), mode);
		}
		void send(String id, String method, Map<String, ?> payload) {
			var fields = new java.util.LinkedHashMap<String, Object>(payload);
			fields.put("v", 1); fields.put("id", id); fields.put("method", method);
			this.runner.accept(this.output.json.writeValueAsString(fields));
		}
		@Override public void close() { this.runner.close(); }
	}

	/** A subscription to actual flushed frames, not timing or file polling. */
	private static final class Output extends PrintStream {
		final ByteArrayOutputStream bytes;
		final JsonMapper json = JsonMapper.builder().build();
		final List<JsonNode> frames = new java.util.concurrent.CopyOnWriteArrayList<>();
		final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
		final List<JsonNode> unmatched = new ArrayList<>();
		Output() { this(new ByteArrayOutputStream()); }
		private Output(ByteArrayOutputStream bytes) {
			super(bytes, true, StandardCharsets.UTF_8); this.bytes = bytes;
		}
		@Override public synchronized void println(String line) {
			super.println(line);
			JsonNode frame = this.json.readTree(line);
			this.frames.add(frame);
			this.events.add(frame);
		}
		JsonNode await(String type, String id) {
			return awaitFrame(frame -> frame.path("type").asString().equals(type)
					&& frame.path("requestId").asString("").equals(id));
		}
		JsonNode awaitEvent(String event) {
			return awaitFrame(frame -> frame.path("event").asString("").equals(event));
		}
		private JsonNode awaitFrame(java.util.function.Predicate<JsonNode> predicate) {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			try {
				var iterator = this.unmatched.iterator();
				while (iterator.hasNext()) {
					JsonNode frame = iterator.next();
					if (predicate.test(frame)) { iterator.remove(); return frame; }
				}
				while (true) {
					JsonNode frame = this.events.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
					assertThat(frame).as("expected frame before deadline").isNotNull();
					if (predicate.test(frame)) return frame;
					this.unmatched.add(frame);
				}
			}
			catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
		}
	}
}
