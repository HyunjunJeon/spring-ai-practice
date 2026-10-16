package com.example.miniagent.core.approval;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Replay grants rebuilt from a real run journal: which later tool call is answered without asking.
 * The journals are written by a first broker over a real {@link FileRunStore}; the broker under test is a fresh one.
 */
class ConsoleApprovalBrokerRestoreTest {
	private static final String TOOL = "bash";
	private static final String ARGS = "{\"command\":\"ls\"}";

	@TempDir Path home;
	private final ApprovalBrokerTest.Screen screen = new ApprovalBrokerTest.Screen();
	private final ConsoleApprovalBroker restored = new ConsoleApprovalBroker(this.screen);
	private final ApprovalBrokerTest.MutableClock clock = new ApprovalBrokerTest.MutableClock();
	private final List<RunWriter> writers = new ArrayList<>();
	private FileRunStore store;

	@BeforeEach void openStore() throws Exception {
		this.store = new FileRunStore(this.home);
	}

	@AfterEach void close() throws Exception {
		for (RunWriter writer : this.writers) {
			this.restored.cancelRun(writer.record().spec().runId());
			writer.close();
		}
	}

	private RunContext run() throws Exception {
		ModelSelection selection = new ModelSelection(new ModelRef("fake", "test"));
		ChatModel model = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("unused"))));
		ModelBinding binding = new ModelBinding(UUID.randomUUID(), selection.ref(), selection.optionsFingerprint(),
				"fake", model, ChatClient.builder(model).build());
		RunSpec spec = new RunSpec(1, RunId.random(), UUID.randomUUID().toString(), "parent",
				null, null, selection.ref(), "fake", this.home.toRealPath().toString(),
				RunBudget.defaults(), selection.optionsFingerprint(), "a".repeat(64), this.clock.instant());
		RunWriter writer = this.store.create(spec);
		this.writers.add(writer);
		String attempt = UUID.randomUUID().toString();
		writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.RUNNING, RunEvent.Reason.NONE));
		writer.append(new RunEvent.AttemptStarted(this.clock.instant(), attempt, binding.bindingId()));
		return new RunContext(spec, attempt, writer, new TurnContext(spec.conversationId(),
				UUID.randomUUID().toString(), this.home.toRealPath(), binding, spec.runId().value(), attempt), this.clock);
	}

	private static ApprovalRequest call(RunContext run, String args) {
		return ApprovalRequest.tool(run, TOOL, args);
	}

	private static RunId id(RunContext run) {
		return run.spec().runId();
	}

	private String log(RunContext run) throws Exception {
		return this.store.export(id(run)).eventsLog();
	}

	private static ConsoleApprovalBroker unattended() {
		ApprovalBrokerTest.Screen console = new ApprovalBrokerTest.Screen();
		console.setInteractive(false);
		return new ConsoleApprovalBroker(console);
	}

	/** The request is deferred with no operator present, and the operator decides later (/run approve). */
	private String deferredThenResolved(RunContext run, ApprovalRequest request, ApprovalDecision decision)
			throws Exception {
		ConsoleApprovalBroker before = unattended();
		assertThat(before.request(request, run).get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.DEFERRED);
		assertThat(before.resolveDeferred(request, decision)).isTrue();
		return log(run);
	}

	private List<RunEvent.Decision> decisionsOf(RunContext run, ApprovalRequest request) throws Exception {
		return FileRunStore.events(this.store, id(run)).stream()
				.filter(RunEvent.ApprovalResolved.class::isInstance).map(RunEvent.ApprovalResolved.class::cast)
				.filter(resolved -> resolved.requestId().equals(request.requestId().toString()))
				.map(RunEvent.ApprovalResolved::decision).toList();
	}

	@Test void deferredThenApprovedAutoApprovesTheNextExactCallExactlyOnce() throws Exception {
		RunContext run = run();
		this.restored.restore(id(run), deferredThenResolved(run, call(run, ARGS), ApprovalDecision.APPROVED));
		ApprovalRequest next = call(run, ARGS);
		var first = this.restored.request(next, run);
		assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.APPROVED);
		assertThat(this.screen.shown).isEmpty();
		assertThat(decisionsOf(run, next)).containsExactly(RunEvent.Decision.APPROVED);
		var second = this.restored.request(call(run, ARGS), run);
		assertThat(second).isNotDone();
		assertThat(this.screen.shown).hasSize(1);
	}

	@Test void deferredThenRejectedReplaysTheRejection() throws Exception {
		RunContext run = run();
		this.restored.restore(id(run), deferredThenResolved(run, call(run, ARGS), ApprovalDecision.REJECTED));
		ApprovalRequest next = call(run, ARGS);
		assertThat(this.restored.request(next, run).get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.REJECTED);
		assertThat(this.screen.shown).isEmpty();
		assertThat(decisionsOf(run, next)).containsExactly(RunEvent.Decision.REJECTED);
	}

	@Test void deferredThenCancelledReplaysNothing() throws Exception {
		RunContext run = run();
		this.restored.restore(id(run), deferredThenResolved(run, call(run, ARGS), ApprovalDecision.CANCELLED));
		assertThat(this.restored.request(call(run, ARGS), run)).isNotDone();
		assertThat(this.screen.shown).hasSize(1);
	}

	@Test void grantCoversNeitherOtherArgumentsNorAnotherRun() throws Exception {
		RunContext run = run(), other = run();
		this.restored.restore(id(run), deferredThenResolved(run, call(run, ARGS), ApprovalDecision.APPROVED));
		ApprovalRequest changed = call(run, "{\"command\":\"rm\"}");
		var otherArguments = this.restored.request(changed, run);
		var otherRun = this.restored.request(call(other, ARGS), other);
		assertThat(otherArguments).isNotDone();
		assertThat(otherRun).isNotDone();
		assertThat(this.screen.shown).containsExactly(changed);
		// The unconsumed grant is still there for the exact call.
		assertThat(this.restored.request(call(run, ARGS), run).get(5, TimeUnit.SECONDS))
				.isEqualTo(ApprovalDecision.APPROVED);
	}

	@Test void approvalGivenToALiveQuestionIsNotAGrantAfterRestore() throws Exception {
		RunContext run = run();
		ApprovalRequest answered = call(run, ARGS);
		ApprovalBrokerTest.Screen console = new ApprovalBrokerTest.Screen();
		var live = new ConsoleApprovalBroker(console).request(answered, run);
		console.response.accept(new ApprovalAnswer(answered.requestId(), ApprovalDecision.APPROVED, false));
		assertThat(live.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.APPROVED);
		this.restored.restore(id(run), log(run));
		assertThat(this.restored.request(call(run, ARGS), run)).isNotDone();
		assertThat(this.screen.shown).hasSize(1);
	}

	@Test void deferralNeverResolvedGrantsNothing() throws Exception {
		RunContext run = run();
		assertThat(unattended().request(call(run, ARGS), run).get(5, TimeUnit.SECONDS))
				.isEqualTo(ApprovalDecision.DEFERRED);
		this.restored.restore(id(run), log(run));
		assertThat(this.restored.request(call(run, ARGS), run)).isNotDone();
	}

	@Test void laterExactCallThatConsumedTheGrantLeavesNothingToReplay() throws Exception {
		RunContext run = run();
		ConsoleApprovalBroker before = unattended();
		ApprovalRequest deferred = call(run, ARGS);
		before.request(deferred, run).get(5, TimeUnit.SECONDS);
		before.resolveDeferred(deferred, ApprovalDecision.APPROVED);
		assertThat(before.request(call(run, ARGS), run).get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.APPROVED);
		this.restored.restore(id(run), log(run));
		assertThat(this.restored.request(call(run, ARGS), run)).isNotDone();
		assertThat(this.screen.shown).hasSize(1);
	}

	@ParameterizedTest
	@EnumSource(value = RunEvent.Reason.class, names = { "PROCESS_LOST", "USER_CANCELLED" })
	void grantIsDroppedWhenTheRunWasLostOrCancelledAfterIt(RunEvent.Reason reason) throws Exception {
		RunContext run = run();
		String granted = deferredThenResolved(run, call(run, ARGS), ApprovalDecision.APPROVED);
		endRun(run, reason);
		String ended = log(run);
		// Control: the journal prefix before the loss does grant, so the drop below comes from the reason alone.
		RunContext live = run();
		ConsoleApprovalBroker control = new ConsoleApprovalBroker(new ApprovalBrokerTest.Screen());
		control.restore(id(live), granted);
		assertThat(control.request(call(live, ARGS), live).get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.APPROVED);
		this.restored.restore(id(live), ended);
		assertThat(this.restored.request(call(live, ARGS), live)).isNotDone();
		assertThat(this.screen.shown).hasSize(1);
	}

	private void endRun(RunContext run, RunEvent.Reason reason) throws Exception {
		RunWriter writer = run.writer();
		if (reason == RunEvent.Reason.PROCESS_LOST) {
			writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.INTERRUPTED, reason));
			return;
		}
		writer.append(new RunEvent.UsageRecorded(this.clock.instant(), run.attemptId(),
				RunEvent.UsageKind.MODEL_ROUND, "closing-round", RunUsage.empty()));
		writer.append(new RunEvent.AttemptEnded(this.clock.instant(), run.attemptId()));
		writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.CANCELLED, reason));
	}

	@Test void restoringAgainWithoutEventsDropsTheRunsGrantButNotAnotherRunsGrant() throws Exception {
		RunContext run = run(), other = run();
		this.restored.restore(id(run), deferredThenResolved(run, call(run, ARGS), ApprovalDecision.APPROVED));
		this.restored.restore(id(other), deferredThenResolved(other, call(other, ARGS), ApprovalDecision.APPROVED));
		// RunExecutor restores an empty journal for a run whose process was lost.
		this.restored.restore(id(run), "");
		assertThat(this.restored.request(call(run, ARGS), run)).isNotDone();
		assertThat(this.restored.request(call(other, ARGS), other).get(5, TimeUnit.SECONDS))
				.isEqualTo(ApprovalDecision.APPROVED);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"1\t9\tdeadbeef\t{\"type\":\"ApprovalReq",
			"1\t9\tdead",
			"1",
			"   ",
			"\n",
			"\n\n",
			"  \n\t\n" })
	void tornOrBlankTailDoesNotLoseOrBreakTheGrantBeforeIt(String tail) throws Exception {
		RunContext run = run();
		String granted = deferredThenResolved(run, call(run, ARGS), ApprovalDecision.APPROVED);
		this.restored.restore(id(run), granted + tail);
		assertThat(this.restored.request(call(run, ARGS), run).get(5, TimeUnit.SECONDS))
				.isEqualTo(ApprovalDecision.APPROVED);
	}

	@Test void tornTailAfterAnUnresolvedDeferralStillGrantsNothing() throws Exception {
		RunContext run = run();
		assertThat(unattended().request(call(run, ARGS), run).get(5, TimeUnit.SECONDS))
				.isEqualTo(ApprovalDecision.DEFERRED);
		this.restored.restore(id(run), log(run) + "1\t9\tdeadbeef\t{\"type\":\"ApprovalReso");
		assertThat(this.restored.request(call(run, ARGS), run)).isNotDone();
	}

	@Test void finalFrameWithoutItsNewlineIsNotDurableAndGrantsNothing() throws Exception {
		RunContext run = run();
		String granted = deferredThenResolved(run, call(run, ARGS), ApprovalDecision.APPROVED);
		assertThat(granted).endsWith("\n");
		// FileRunStore treats a frame without its newline as a torn append and truncates it on the next writer.
		this.restored.restore(id(run), granted.substring(0, granted.length() - 1));
		assertThat(this.restored.request(call(run, ARGS), run)).isNotDone();
	}

	@Test void completeButMalformedFrameStillFailsInsteadOfBeingSkipped() throws Exception {
		RunContext run = run();
		String granted = deferredThenResolved(run, call(run, ARGS), ApprovalDecision.APPROVED);
		assertThatThrownBy(() -> this.restored.restore(id(run), granted + "not a frame\n"))
				.isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> this.restored.restore(id(run), "1\t1\tdeadbeef\t{\"type\":\"Appro\n" + granted))
				.isInstanceOf(RuntimeException.class);
	}
}
