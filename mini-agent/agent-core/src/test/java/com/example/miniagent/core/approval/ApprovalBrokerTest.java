package com.example.miniagent.core.approval;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalBrokerTest {
	@TempDir Path home;
	private final Screen screen = new Screen();
	private final ConsoleApprovalBroker broker = new ConsoleApprovalBroker(this.screen);
	private final List<RunWriter> writers = new ArrayList<>();
	private final MutableClock clock = new MutableClock();

	@AfterEach void close() throws Exception {
		for (RunWriter writer : this.writers) {
			this.broker.cancelRun(writer.record().spec().runId());
			writer.close();
		}
	}

	private RunContext context(RunId parent) throws Exception {
		ModelSelection selection = new ModelSelection(new ModelRef("fake", "test"));
		ChatModel model = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("unused"))));
		ModelBinding binding = new ModelBinding(UUID.randomUUID(), selection.ref(), selection.optionsFingerprint(),
				"fake", model, ChatClient.builder(model).build());
		RunSpec spec = new RunSpec(1, RunId.random(), UUID.randomUUID().toString(), "parent",
				parent, parent == null ? null : "child", selection.ref(), "fake", this.home.toRealPath().toString(),
				RunBudget.defaults(), selection.optionsFingerprint(), "a".repeat(64), this.clock.instant());
		RunWriter writer = new FileRunStore(this.home).create(spec);
		this.writers.add(writer);
		String attempt = UUID.randomUUID().toString();
		writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.RUNNING, RunEvent.Reason.NONE));
		writer.append(new RunEvent.AttemptStarted(this.clock.instant(), attempt, binding.bindingId()));
		return new RunContext(spec, attempt, writer, new TurnContext(spec.conversationId(),
				UUID.randomUUID().toString(), this.home.toRealPath(), binding, spec.runId().value(), attempt), this.clock);
	}

	@Test void fifoShowsSecondOnlyAfterFirstSettles() throws Exception {
		RunContext a = context(null), b = context(null);
		ApprovalRequest first = ApprovalRequest.tool(a, "write_file", "{\"path\":\"a\"}");
		ApprovalRequest second = ApprovalRequest.tool(b, "write_file", "{\"path\":\"b\"}");
		var fa = this.broker.request(first, a);
		var fb = this.broker.request(second, b);
		assertThat(this.screen.shown).containsExactly(first);
		assertThat(this.broker.respond(second, ApprovalDecision.APPROVED)).isFalse();
		assertThat(this.broker.respond(first, ApprovalDecision.REJECTED)).isTrue();
		assertThat(fa.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.REJECTED);
		assertThat(this.screen.shown).containsExactly(first, second);
		assertThat(fb).isNotDone();
		assertThat(this.broker.respond(second, ApprovalDecision.APPROVED)).isTrue();
		assertThat(fb.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.APPROVED);
	}

	@Test void changeAndRunApprovalShareQueueAndPersistBeforeCompletion() throws Exception {
		RunContext run = context(null);
		var tool = ApprovalRequest.tool(run, "write_file", "{}");
		var first = this.broker.request(tool, run);
		var change = new ApprovalRequest(UUID.randomUUID(), RunId.random(), "attempt",
				null, null, "apply", ApprovalRequest.digest("owner"), ApprovalRequest.digest("proposal"));
		List<ApprovalDecision> persisted = new ArrayList<>();
		var second = this.broker.requestChange(change, persisted::add);
		assertThat(this.screen.shown).containsExactly(tool);
		assertThat(this.broker.respond(change, ApprovalDecision.APPROVED)).isFalse();
		assertThat(persisted).isEmpty();
		this.broker.respond(tool, ApprovalDecision.REJECTED);
		assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.REJECTED);
		assertThat(this.screen.shown).containsExactly(tool, change);
		var durableCompletion = second.thenAccept(decision -> assertThat(persisted).containsExactly(decision));
		assertThat(this.broker.respond(change, ApprovalDecision.APPROVED)).isTrue();
		assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.APPROVED);
		durableCompletion.get(5, TimeUnit.SECONDS);
		assertThat(persisted).containsExactly(ApprovalDecision.APPROVED);
		assertThat(this.broker.respond(change, ApprovalDecision.REJECTED)).isFalse();
	}

	@Test void unattendedChangeDefersWithoutConstructingRunContext() throws Exception {
		this.screen.setInteractive(false);
		var request = new ApprovalRequest(UUID.randomUUID(), RunId.random(), "attempt",
				null, null, "apply", ApprovalRequest.digest("owner"), ApprovalRequest.digest("proposal"));
		List<ApprovalDecision> persisted = new ArrayList<>();
		assertThat(this.broker.requestChange(request, persisted::add).get(5, TimeUnit.SECONDS))
				.isEqualTo(ApprovalDecision.DEFERRED);
		assertThat(persisted).containsExactly(ApprovalDecision.DEFERRED);
		assertThat(this.broker.pending()).containsExactly(request);
		assertThat(this.broker.resolveDeferred(request, ApprovalDecision.REJECTED)).isTrue();
		assertThat(persisted).containsExactly(ApprovalDecision.DEFERRED, ApprovalDecision.REJECTED);
		assertThat(this.broker.pending()).isEmpty();
	}

	@Test void failedChangePersistenceNeverCompletesAsApproved() {
		var request = new ApprovalRequest(UUID.randomUUID(), RunId.random(), "attempt",
				null, null, "apply", ApprovalRequest.digest("owner"), ApprovalRequest.digest("proposal"));
		var future = this.broker.requestChange(request, decision -> {
			throw new java.io.UncheckedIOException(new java.io.IOException("store unavailable"));
		});
		assertThatThrownBy(() -> this.broker.respond(request, ApprovalDecision.APPROVED))
				.isInstanceOf(java.io.UncheckedIOException.class);
		assertThat(future).isCompletedExceptionally();
		assertThat(this.broker.pending()).isEmpty();
	}

	@Test void exactQueuedCancellationLeavesUnrelatedDisplayedApprovalAlone() throws Exception {
		RunContext run = context(null);
		var tool = ApprovalRequest.tool(run, "write_file", "{}");
		var first = this.broker.request(tool, run);
		var change = new ApprovalRequest(UUID.randomUUID(), run.spec().runId(), run.attemptId(),
				null, null, "apply", ApprovalRequest.digest("owner"), ApprovalRequest.digest("proposal"));
		List<ApprovalDecision> persisted = new ArrayList<>();
		var second = this.broker.requestChange(change, persisted::add);
		var mismatch = new ApprovalRequest(change.requestId(), change.runId(), change.attemptId(),
				null, null, change.toolCallKey(), ApprovalRequest.digest("other"), change.diffDigest());
		assertThat(this.broker.cancel(mismatch)).isFalse();
		assertThat(this.broker.cancel(change)).isTrue();
		assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.CANCELLED);
		assertThat(persisted).containsExactly(ApprovalDecision.CANCELLED);
		assertThat(first).isNotDone();
		assertThat(this.broker.pending()).containsExactly(tool);
		assertThat(this.broker.cancel(change)).isFalse();
		this.broker.respond(tool, ApprovalDecision.REJECTED);
		assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.REJECTED);
	}

	@Test void staleAnswerRejected() throws Exception {
		RunContext context = context(null);
		ApprovalRequest old = ApprovalRequest.tool(context, "write_file", "{}");
		var first = this.broker.request(old, context);
		this.broker.respond(old, ApprovalDecision.REJECTED);
		ApprovalRequest current = ApprovalRequest.tool(context, "write_file", "{\"changed\":true}");
		var next = this.broker.request(current, context);
		assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.REJECTED);
		assertThat(this.broker.respond(old, ApprovalDecision.APPROVED)).isFalse();
		ApprovalRequest mismatch = new ApprovalRequest(current.requestId(), current.runId(), current.attemptId(),
				null, null, current.toolCallKey(), old.argsDigest(), null);
		assertThat(this.broker.respond(mismatch, ApprovalDecision.APPROVED)).isFalse();
		assertThat(next).isNotDone();
		assertThatThrownBy(() -> this.broker.request(old, context)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test void unattendedDefers() throws Exception {
		this.screen.setInteractive(false);
		RunContext context = context(null);
		ApprovalRequest request = ApprovalRequest.tool(context, "write_file", "{}");
		assertThat(this.broker.request(request, context).get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.DEFERRED);
		assertThat(this.screen.shown).isEmpty();
		assertThat(this.broker.pending()).containsExactly(request);
		assertThat(this.broker.respond(request, ApprovalDecision.APPROVED)).isFalse();
		assertThat(this.broker.resolveDeferred(request, ApprovalDecision.REJECTED)).isTrue();
		assertThat(this.broker.resolveDeferred(request, ApprovalDecision.APPROVED)).isFalse();
	}

	@Test void parentCancelStopsActiveAndQueuedChildFutures() throws Exception {
		RunContext parent = context(null), child = context(parent.spec().runId());
		var a = this.broker.request(ApprovalRequest.tool(parent, "write_file", "a"), parent);
		var b = this.broker.request(ApprovalRequest.tool(child, "write_file", "b"), child);
		this.broker.cancelRun(parent.spec().runId());
		assertThat(a.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.CANCELLED);
		assertThat(b.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.CANCELLED);
		assertThat(this.screen.shown).hasSize(1);
		assertThat(this.broker.pending()).isEmpty();
	}

	@Test void concurrentProducersKeepTheirExactOwners() throws Exception {
		RunContext a = context(null), b = context(null);
		ApprovalRequest ra = ApprovalRequest.tool(a, "write_file", "a");
		ApprovalRequest rb = ApprovalRequest.tool(b, "write_file", "b");
		try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var submitA = workers.submit(() -> this.broker.request(ra, a));
			var submitB = workers.submit(() -> this.broker.request(rb, b));
			var fa = submitA.get(5, TimeUnit.SECONDS);
			var fb = submitB.get(5, TimeUnit.SECONDS);
			ApprovalRequest head = this.broker.pending().getFirst();
			this.broker.respond(head, ApprovalDecision.REJECTED);
			ApprovalRequest tail = this.broker.pending().getFirst();
			this.broker.respond(tail, ApprovalDecision.APPROVED);
			assertThat(fa.get(5, TimeUnit.SECONDS)).isEqualTo(head.equals(ra)
					? ApprovalDecision.REJECTED : ApprovalDecision.APPROVED);
			assertThat(fb.get(5, TimeUnit.SECONDS)).isEqualTo(head.equals(rb)
					? ApprovalDecision.REJECTED : ApprovalDecision.APPROVED);
		}
	}

	@Test void nestedBudgetPausesExcludeApprovalWait() throws Exception {
		RunContext context = context(null);
		this.clock.at = this.clock.at.plusMillis(10);
		context.pauseBudget();
		ApprovalRequest request = ApprovalRequest.tool(context, "write_file", "{}");
		this.broker.request(request, context);
		this.clock.at = this.clock.at.plusSeconds(100);
		this.broker.respond(request, ApprovalDecision.REJECTED);
		context.resumeBudget();
		this.clock.at = this.clock.at.plusMillis(20);
		context.finishActiveTime();
		assertThat(context.writer().record().usage().activeMillis()).isEqualTo(30);
	}

	@Test void managedAlwaysCannotWidenArgumentOrRunScope() throws Exception {
		RunContext context = context(null);
		ApprovalRequest first = ApprovalRequest.tool(context, "bash", "{\"command\":\"ls\"}");
		this.broker.request(first, context);
		this.screen.response.accept(new ApprovalAnswer(first.requestId(), ApprovalDecision.APPROVED, true));
		var same = this.broker.request(ApprovalRequest.tool(context, "bash", "{\"command\":\"ls\"}"), context);
		assertThat(same.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.APPROVED);
		var changed = this.broker.request(ApprovalRequest.tool(context, "bash", "{\"command\":\"other\"}"), context);
		assertThat(changed).isNotDone();
		RunContext other = context(null);
		var foreign = this.broker.request(ApprovalRequest.tool(other, "bash", "{\"command\":\"ls\"}"), other);
		assertThat(foreign).isNotDone();
	}

	@Test void realPlainTransportRequiresIdAndMapsEofToDeferred() throws Exception {
		RunContext context = context(null);
		ApprovalRequest request = ApprovalRequest.tool(context, "write_file", "{}");
		var answered = new CompletableFuture<ApprovalAnswer>();
		AgentConsole plain = new AgentConsole(new BufferedReader(new StringReader(
				"y\n" + UUID.randomUUID() + " y\n" + request.requestId() + " n\n")),
				new PrintStream(java.io.OutputStream.nullOutputStream()), true);
		plain.requestApproval(request, answered::complete);
		assertThat(answered.get(5, TimeUnit.SECONDS).decision()).isEqualTo(ApprovalDecision.REJECTED);
		plain.dismissApproval(request.requestId());
		var eof = new CompletableFuture<ApprovalAnswer>();
		AgentConsole empty = new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(java.io.OutputStream.nullOutputStream()), true);
		empty.requestApproval(request, eof::complete);
		assertThat(eof.get(5, TimeUnit.SECONDS).decision()).isEqualTo(ApprovalDecision.DEFERRED);
		empty.dismissApproval(request.requestId());
	}

	static final class Screen extends AgentConsole {
		final List<ApprovalRequest> shown = new ArrayList<>();
		Consumer<ApprovalAnswer> response;
		Screen() { super(new BufferedReader(new StringReader("")), new PrintStream(java.io.OutputStream.nullOutputStream()), true); }
		@Override public void requestApproval(ApprovalRequest request, Consumer<ApprovalAnswer> response) {
			this.shown.add(request);
			this.response = response;
		}
	}

	static final class MutableClock extends Clock {
		Instant at = Instant.parse("2026-10-06T00:00:00Z");
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return this.at; }
	}
}
