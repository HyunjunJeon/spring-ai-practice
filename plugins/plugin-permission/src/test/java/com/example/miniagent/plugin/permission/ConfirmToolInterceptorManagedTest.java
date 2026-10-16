package com.example.miniagent.plugin.permission;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.ToolInterceptor.ToolInvocation;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
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
import com.example.miniagent.core.run.RunWriter;
import com.example.miniagent.core.run.Stop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 관리되는 실행(run)에서는 질문을 ApprovalBroker 가 맡는다. 그 결정·중단·실패가 도구 결과와 실행 상태로 어떻게 이어지는지 확인한다. */
class ConfirmToolInterceptorManagedTest {

	private static final String INPUT = "{\"path\":\"a.txt\"}";

	@TempDir
	Path home;

	private final ByteArrayOutputStream output = new ByteArrayOutputStream();

	private final List<RunWriter> writers = new ArrayList<>();

	@AfterEach
	void closeWriters() throws Exception {
		for (RunWriter writer : this.writers) {
			writer.close();
		}
	}

	private ConfirmToolInterceptor interceptor(ApprovalBroker approvals) {
		var console = new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(this.output, true, StandardCharsets.UTF_8), false);
		return new ConfirmToolInterceptor(List.of("read_file"), List.of("bash"), console, false, approvals);
	}

	private RunContext runContext() throws Exception {
		var selection = new ModelSelection(new ModelRef("fake", "test"));
		ChatModel model = new ChatModel() {
			@Override
			public ChatOptions getOptions() {
				return ToolCallingChatOptions.builder().build();
			}

			@Override
			public ChatResponse call(Prompt prompt) {
				throw new AssertionError("The permission interceptor never calls the model");
			}
		};
		var binding = new ModelBinding(UUID.randomUUID(), selection.ref(), selection.optionsFingerprint(), "fake",
				model, ChatClient.builder(model).build());
		var spec = new RunSpec(1, RunId.random(), UUID.randomUUID().toString(), "parent", null, null, selection.ref(),
				"fake", this.home.toRealPath().toString(), RunBudget.defaults(), selection.optionsFingerprint(),
				"a".repeat(64), Instant.EPOCH);
		RunWriter writer = new FileRunStore(this.home).create(spec);
		this.writers.add(writer);
		String attempt = UUID.randomUUID().toString();
		writer.append(new RunEvent.StateChanged(Instant.EPOCH, RunState.RUNNING, RunEvent.Reason.NONE));
		writer.append(new RunEvent.AttemptStarted(Instant.EPOCH, attempt, binding.bindingId()));
		var turn = new TurnContext(spec.conversationId(), UUID.randomUUID().toString(), this.home.toRealPath(), binding,
				spec.runId().value(), attempt);
		return new RunContext(spec, attempt, writer, turn, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
	}

	private static ToolInvocation invocation(String tool, RunContext run) {
		var definition = ToolDefinition.builder().name(tool).description(tool).inputSchema("{}").build();
		return new ToolInvocation(definition, INPUT, new ToolContext(Map.of(RunContext.TOOL_CONTEXT_KEY, run)));
	}

	@Test
	void approvedDecisionRunsTheToolAfterAskingTheBrokerAboutThatExactCall() throws Exception {
		RunContext run = runContext();
		var broker = new StubBroker(CompletableFuture.completedFuture(ApprovalDecision.APPROVED));

		String result = interceptor(broker).intercept(invocation("write_file", run), call -> "ran");

		assertThat(result).isEqualTo("ran");
		assertThat(broker.requests).singleElement().satisfies(request -> {
			assertThat(request.runId()).isEqualTo(run.spec().runId());
			assertThat(request.attemptId()).isEqualTo(run.attemptId());
			assertThat(request.toolCallKey()).isEqualTo("write_file");
			assertThat(request.argsDigest()).isEqualTo(ApprovalRequest.digest(INPUT));
		});
		assertThat(this.output.toString(StandardCharsets.UTF_8)).as("the broker owns the question").isEmpty();
	}

	@Test
	void rejectedDecisionReturnsTheDenialTextWithoutRunningTheTool() throws Exception {
		var broker = new StubBroker(CompletableFuture.completedFuture(ApprovalDecision.REJECTED));

		String result = interceptor(broker).intercept(invocation("write_file", runContext()), call -> {
			throw new AssertionError("A rejected tool must not run");
		});

		assertThat(result).isEqualTo("Permission denied: 'write_file' was not executed because the user rejected it. "
				+ "Do not retry it; ask the user how to proceed.");
	}

	@Test
	void deferredDecisionStopsTheRunWithoutRunningTheTool() throws Exception {
		var broker = new StubBroker(CompletableFuture.completedFuture(ApprovalDecision.DEFERRED));
		RunContext run = runContext();

		Throwable thrown = catchThrowable(() -> interceptor(broker).intercept(invocation("write_file", run), call -> {
			throw new AssertionError("A deferred tool must not run");
		}));

		assertThat(thrown).isInstanceOfSatisfying(Stop.class,
				stop -> assertThat(stop.kind()).isEqualTo(Stop.Kind.DEFERRED));
		assertThat(broker.cancelledRuns).isEmpty();
	}

	@Test
	void cancelledDecisionStopsTheRunWithoutCancellingAgainOrTouchingTheInterruptFlag() throws Exception {
		var broker = new StubBroker(CompletableFuture.completedFuture(ApprovalDecision.CANCELLED));
		RunContext run = runContext();

		Throwable thrown = catchThrowable(() -> interceptor(broker).intercept(invocation("write_file", run), call -> {
			throw new AssertionError("A cancelled tool must not run");
		}));

		assertThat(thrown).isInstanceOfSatisfying(Stop.class,
				stop -> assertThat(stop.kind()).isEqualTo(Stop.Kind.CANCELLED));
		assertThat(broker.cancelledRuns).isEmpty();
		assertThat(Thread.currentThread().isInterrupted()).isFalse();
	}

	@Test
	void autoAllowedToolInAManagedRunNeverReachesTheBroker() throws Exception {
		var broker = new StubBroker(CompletableFuture.completedFuture(ApprovalDecision.REJECTED));

		String result = interceptor(broker).intercept(invocation("read_file", runContext()), call -> "ran");

		assertThat(result).isEqualTo("ran");
		assertThat(broker.requests).isEmpty();
	}

	@Test
	void interruptWhileWaitingCancelsThePendingApprovalRestoresTheFlagAndStopsCancelled() throws Exception {
		RunContext run = runContext();
		var broker = new StubBroker(new CompletableFuture<>());
		ConfirmToolInterceptor interceptor = interceptor(broker);
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicBoolean flagAfterwards = new AtomicBoolean();
		AtomicBoolean toolRan = new AtomicBoolean();
		Thread waiting = new Thread(() -> {
			thrown.set(catchThrowable(() -> interceptor.intercept(invocation("write_file", run), call -> {
				toolRan.set(true);
				return "ran";
			})));
			flagAfterwards.set(Thread.currentThread().isInterrupted());
		});

		waiting.start();
		assertThat(broker.asked.await(10, TimeUnit.SECONDS)).isTrue();
		waiting.interrupt();
		waiting.join(10_000);

		assertThat(waiting.isAlive()).as("the interrupted wait must end").isFalse();
		assertThat(thrown.get()).isInstanceOfSatisfying(Stop.class,
				stop -> assertThat(stop.kind()).isEqualTo(Stop.Kind.CANCELLED));
		assertThat(broker.cancelledRuns).containsExactly(run.spec().runId());
		assertThat(flagAfterwards).as("the interrupt flag is restored for the caller").isTrue();
		assertThat(toolRan).isFalse();
	}

	@Test
	void failedApprovalFutureSurfacesAsACompletionExceptionWithTheOriginalCause() throws Exception {
		var failure = new IllegalStateException("approval transport lost");
		var broker = new StubBroker(CompletableFuture.failedFuture(failure));
		RunContext run = runContext();

		assertThatThrownBy(() -> interceptor(broker).intercept(invocation("write_file", run), call -> {
			throw new AssertionError("A tool whose approval failed must not run");
		})).isInstanceOf(CompletionException.class).hasCause(failure);
		assertThat(broker.cancelledRuns).isEmpty();
		assertThat(Thread.currentThread().isInterrupted()).isFalse();
	}

	/** Hands out one prepared answer and records what the interceptor asked; every other broker call is a test bug. */
	private static final class StubBroker implements ApprovalBroker {

		final List<ApprovalRequest> requests = new CopyOnWriteArrayList<>();

		final List<RunId> cancelledRuns = new CopyOnWriteArrayList<>();

		final CountDownLatch asked = new CountDownLatch(1);

		private final CompletableFuture<ApprovalDecision> answer;

		StubBroker(CompletableFuture<ApprovalDecision> answer) {
			this.answer = answer;
		}

		@Override
		public CompletableFuture<ApprovalDecision> request(ApprovalRequest request, RunContext context) {
			this.requests.add(request);
			this.asked.countDown();
			return this.answer;
		}

		@Override
		public void cancelRun(RunId runId) {
			this.cancelledRuns.add(runId);
		}

		@Override
		public CompletableFuture<ApprovalDecision> requestChange(ApprovalRequest request,
				Consumer<ApprovalDecision> durableDecision) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean respond(ApprovalRequest request, ApprovalDecision decision) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean cancel(ApprovalRequest request) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean resolveDeferred(ApprovalRequest request, ApprovalDecision decision) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void abandonRun(RunId runId, Throwable failure) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<ApprovalRequest> pending() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void restore(RunId runId, String authoritativeEvents) {
			throw new UnsupportedOperationException();
		}

	}

}
