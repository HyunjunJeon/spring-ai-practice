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
import com.example.miniagent.core.run.RunWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the broker refuses outright: foreign identities, cancelled runs, unsafe change requests, stray answers. */
class ConsoleApprovalBrokerRefusalTest {
	@TempDir Path home;
	private final ApprovalBrokerTest.Screen screen = new ApprovalBrokerTest.Screen();
	private final ConsoleApprovalBroker broker = new ConsoleApprovalBroker(this.screen);
	private final ApprovalBrokerTest.MutableClock clock = new ApprovalBrokerTest.MutableClock();
	private final List<RunWriter> writers = new ArrayList<>();
	private FileRunStore store;

	@BeforeEach void openStore() throws Exception {
		this.store = new FileRunStore(this.home);
	}

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
		RunWriter writer = this.store.create(spec);
		this.writers.add(writer);
		String attempt = UUID.randomUUID().toString();
		writer.append(new RunEvent.StateChanged(this.clock.instant(), RunState.RUNNING, RunEvent.Reason.NONE));
		writer.append(new RunEvent.AttemptStarted(this.clock.instant(), attempt, binding.bindingId()));
		return new RunContext(spec, attempt, writer, new TurnContext(spec.conversationId(),
				UUID.randomUUID().toString(), this.home.toRealPath(), binding, spec.runId().value(), attempt), this.clock);
	}

	private long journaledRequests(RunContext run) throws Exception {
		return FileRunStore.events(this.store, run.spec().runId()).stream()
				.filter(RunEvent.ApprovalRequested.class::isInstance).count();
	}

	private static ApprovalRequest request(RunId run, String attempt, String childId) {
		return new ApprovalRequest(UUID.randomUUID(), run, attempt, null, childId, "write_file",
				ApprovalRequest.digest("{}"), null);
	}

	@Test void requestWithAnotherRunAttemptOrChildIsRefusedBeforeAnythingIsRecorded() throws Exception {
		RunContext run = context(null), other = context(null);
		List<ApprovalRequest> mismatched = List.of(
				request(other.spec().runId(), run.attemptId(), null),
				request(run.spec().runId(), other.attemptId(), null),
				request(run.spec().runId(), run.attemptId(), "child"));
		for (ApprovalRequest request : mismatched) {
			assertThatThrownBy(() -> this.broker.request(request, run))
					.isInstanceOf(IllegalArgumentException.class).hasMessage("Approval context mismatch");
		}
		assertThat(this.broker.pending()).isEmpty();
		assertThat(this.screen.shown).isEmpty();
		assertThat(journaledRequests(run)).isZero();
	}

	@Test void childRequestMustCarryTheChildIdOfItsContext() throws Exception {
		RunContext parent = context(null), child = context(parent.spec().runId());
		assertThatThrownBy(() -> this.broker.request(
				request(child.spec().runId(), child.attemptId(), null), child))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Approval context mismatch");
		assertThatThrownBy(() -> this.broker.request(
				request(child.spec().runId(), child.attemptId(), "another-child"), child))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Approval context mismatch");
		assertThat(this.broker.pending()).isEmpty();
		assertThat(journaledRequests(child)).isZero();
	}

	@Test void requestAfterCancelRunCompletesCancelledWithoutPromptOrJournalEntry() throws Exception {
		RunContext run = context(null);
		this.broker.cancelRun(run.spec().runId());
		var late = this.broker.request(ApprovalRequest.tool(run, "write_file", "{}"), run);
		assertThat(late.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.CANCELLED);
		assertThat(this.screen.shown).isEmpty();
		assertThat(this.broker.pending()).isEmpty();
		assertThat(journaledRequests(run)).isZero();
	}

	@Test void childOfACancelledParentIsCancelledButAnUnrelatedRunStillAsks() throws Exception {
		RunContext parent = context(null), child = context(parent.spec().runId()), unrelated = context(null);
		this.broker.cancelRun(parent.spec().runId());
		var cancelled = this.broker.request(ApprovalRequest.tool(child, "write_file", "{}"), child);
		assertThat(cancelled.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.CANCELLED);
		assertThat(journaledRequests(child)).isZero();
		ApprovalRequest asked = ApprovalRequest.tool(unrelated, "write_file", "{}");
		var open = this.broker.request(asked, unrelated);
		assertThat(open).isNotDone();
		assertThat(this.screen.shown).containsExactly(asked);
	}

	@Test void changeApprovalWithoutDiffDigestIsRefusedAndNeverQueued() {
		var request = new ApprovalRequest(UUID.randomUUID(), RunId.random(), "attempt",
				null, null, "apply", ApprovalRequest.digest("owner"), null);
		List<ApprovalDecision> persisted = new ArrayList<>();
		assertThatThrownBy(() -> this.broker.requestChange(request, persisted::add))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Change approval requires diff digest");
		assertThat(this.broker.pending()).isEmpty();
		assertThat(this.screen.shown).isEmpty();
		assertThat(persisted).isEmpty();
	}

	@Test void answerForAnotherRequestIdIsIgnoredEvenWhenItAsksToRemember() throws Exception {
		RunContext run = context(null);
		ApprovalRequest first = ApprovalRequest.tool(run, "bash", "{\"command\":\"ls\"}");
		var asked = this.broker.request(first, run);
		var wrong = new ApprovalAnswer(UUID.randomUUID(), ApprovalDecision.APPROVED, true);
		this.screen.response.accept(wrong);
		this.screen.response.accept(null);
		assertThat(asked).isNotDone();
		assertThat(this.broker.pending()).containsExactly(first);
		this.screen.response.accept(new ApprovalAnswer(first.requestId(), ApprovalDecision.APPROVED, false));
		assertThat(asked.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.APPROVED);
		// The ignored remember flag must not have turned into a standing grant for this exact call.
		var again = this.broker.request(ApprovalRequest.tool(run, "bash", "{\"command\":\"ls\"}"), run);
		assertThat(again).isNotDone();
	}

	@Test void answerNamingAQueuedButNotDisplayedRequestIsIgnored() throws Exception {
		RunContext a = context(null), b = context(null);
		ApprovalRequest shown = ApprovalRequest.tool(a, "write_file", "a");
		ApprovalRequest queued = ApprovalRequest.tool(b, "write_file", "b");
		var first = this.broker.request(shown, a);
		var second = this.broker.request(queued, b);
		this.screen.response.accept(new ApprovalAnswer(queued.requestId(), ApprovalDecision.APPROVED, false));
		assertThat(first).isNotDone();
		assertThat(second).isNotDone();
		assertThat(this.screen.shown).containsExactly(shown);
		assertThat(this.broker.pending()).containsExactly(shown, queued);
	}

	@Test void staleCallbackCannotAnswerTheNextQuestionEvenWithItsId() throws Exception {
		RunContext run = context(null);
		ApprovalRequest old = ApprovalRequest.tool(run, "write_file", "old");
		var first = this.broker.request(old, run);
		var oldCallback = this.screen.response;
		oldCallback.accept(new ApprovalAnswer(old.requestId(), ApprovalDecision.REJECTED, false));
		assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.REJECTED);
		ApprovalRequest current = ApprovalRequest.tool(run, "write_file", "current");
		var next = this.broker.request(current, run);
		oldCallback.accept(new ApprovalAnswer(current.requestId(), ApprovalDecision.APPROVED, false));
		assertThat(next).isNotDone();
		this.screen.response.accept(new ApprovalAnswer(current.requestId(), ApprovalDecision.APPROVED, false));
		assertThat(next.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.APPROVED);
	}

	@Test void rememberFlagOnARejectionNeverCreatesAGrant() throws Exception {
		RunContext run = context(null);
		ApprovalRequest first = ApprovalRequest.tool(run, "bash", "{\"command\":\"rm\"}");
		var asked = this.broker.request(first, run);
		this.screen.response.accept(new ApprovalAnswer(first.requestId(), ApprovalDecision.REJECTED, true));
		assertThat(asked.get(5, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.REJECTED);
		var again = this.broker.request(ApprovalRequest.tool(run, "bash", "{\"command\":\"rm\"}"), run);
		assertThat(again).isNotDone();
	}
}
