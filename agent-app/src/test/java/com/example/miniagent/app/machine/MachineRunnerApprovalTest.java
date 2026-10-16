package com.example.miniagent.app.machine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.example.miniagent.core.approval.ApprovalAnswer;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** The approval and question methods are the only gate between client bytes and an ApprovalAnswer. */
@Timeout(20)
class MachineRunnerApprovalTest {
	@TempDir Path home;
	private final List<ApprovalAnswer> answers = new CopyOnWriteArrayList<>();
	private MachineFixture fixture;
	private ApprovalRequest request;
	private int requests;

	@BeforeEach void pendingApproval() throws IOException {
		this.fixture = new MachineFixture(this.home, "rpc", MachineFixture.input(""), prompt -> {}, List.of());
		this.request = pend();
	}

	@AfterEach void closeFixture() { this.fixture.close(); }

	private ApprovalRequest pend() {
		ApprovalRequest pending = MachineFixture.approvalRequest();
		this.fixture.console.requestApproval(pending, this.answers::add);
		return pending;
	}

	private Map<String, Object> approval(String approvalId, String decision) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("approvalId", approvalId);
		payload.put("decision", decision);
		return payload;
	}

	private String refused(Map<String, ?> payload) {
		return this.fixture.refusalCode("a" + this.requests++, "approval", payload);
	}

	/** A refusal consumed nothing: no callback ran and the approval still takes a valid answer. */
	private void assertStillPending() {
		assertThat(this.answers).isEmpty();
		this.fixture.send("valid", "approval", approval(this.request.requestId().toString(), "REJECTED"));
		assertThat(this.fixture.output.await("response", "valid").path("status").asString()).isEqualTo("answered");
		assertThat(this.answers).containsExactly(new ApprovalAnswer(this.request.requestId(), ApprovalDecision.REJECTED, false));
	}

	@Test void decisionsOutsideTheEnumAreInvalidDecision() {
		String id = this.request.requestId().toString();
		for (String decision : List.of("approved", "Approved", "MAYBE", " APPROVED", "APPROVED ", "ALLOW"))
			assertThat(refused(approval(id, decision))).as(decision).isEqualTo("invalid_decision");
		assertStillPending();
	}

	@Test void missingBlankOrNonStringDecisionIsInvalidField() {
		String id = this.request.requestId().toString();
		assertThat(refused(Map.of("approvalId", id))).isEqualTo("invalid_field");
		assertThat(refused(approval(id, ""))).isEqualTo("invalid_field");
		assertThat(refused(Map.of("approvalId", id, "decision", true))).isEqualTo("invalid_field");
		assertStillPending();
	}

	@Test void rememberThatIsNotABooleanIsInvalidField() {
		String id = this.request.requestId().toString();
		for (Object remember : new Object[] {"true", 1, null}) {
			var payload = approval(id, "APPROVED");
			payload.put("remember", remember);
			assertThat(refused(payload)).as("remember " + remember).isEqualTo("invalid_field");
		}
		assertStillPending();
	}

	@Test void onlyAnApprovedDecisionMayBeRemembered() {
		String id = this.request.requestId().toString();
		for (String decision : List.of("REJECTED", "DEFERRED", "CANCELLED")) {
			var payload = approval(id, decision);
			payload.put("remember", true);
			assertThat(refused(payload)).as(decision).isEqualTo("invalid_decision");
		}
		assertStillPending();
	}

	@Test void approvalIdsMustBeCanonicalUuids() {
		String id = this.request.requestId().toString();
		// "1-2-3-4-5" is accepted by UUID.fromString but is not the canonical rendering of the UUID it yields.
		for (String malformed : List.of("not-a-uuid", "1-2-3-4-5", id.replace("-", ""), id + " ", "{" + id + "}"))
			assertThat(refused(approval(malformed, "APPROVED"))).as(malformed).isEqualTo("invalid_uuid");
		assertThat(refused(approval("", "APPROVED"))).isEqualTo("invalid_field");
		assertStillPending();
	}

	@Test void questionIdsMustBeCanonicalUuidsAndAnswersNonBlank() {
		for (String malformed : List.of("not-a-uuid", "1-2-3-4-5"))
			assertThat(this.fixture.refusalCode("q-" + malformed, "question",
					Map.of("questionId", malformed, "text", "yes"))).as(malformed).isEqualTo("invalid_uuid");
		assertThat(this.fixture.refusalCode("q-blank", "question",
				Map.of("questionId", UUID.randomUUID().toString(), "text", "  "))).isEqualTo("invalid_field");
		assertStillPending();
	}

	@Test void aCanonicalUuidIsAcceptedInUpperCaseToo() {
		this.fixture.send("upper", "approval", approval(this.request.requestId().toString().toUpperCase(), "APPROVED"));
		assertThat(this.fixture.output.await("response", "upper").path("status").asString()).isEqualTo("answered");
		assertThat(this.answers).containsExactly(new ApprovalAnswer(this.request.requestId(), ApprovalDecision.APPROVED, false));
	}

	@Test void rememberedApprovalReachesTheAnswerWithRememberSet() {
		var payload = approval(this.request.requestId().toString(), "APPROVED");
		payload.put("remember", true);
		this.fixture.send("always", "approval", payload);
		assertThat(this.fixture.output.await("response", "always").path("status").asString()).isEqualTo("answered");
		assertThat(this.answers).containsExactly(new ApprovalAnswer(this.request.requestId(), ApprovalDecision.APPROVED, true));
	}

	@Test void approvalWithoutRememberOrWithRememberFalseIsNotRemembered() {
		this.fixture.send("omitted", "approval", approval(this.request.requestId().toString(), "APPROVED"));
		ApprovalRequest second = pend();
		var payload = approval(second.requestId().toString(), "APPROVED");
		payload.put("remember", false);
		this.fixture.send("explicit", "approval", payload);
		assertThat(this.fixture.output.await("response", "explicit").path("status").asString()).isEqualTo("answered");
		assertThat(this.answers).containsExactly(
				new ApprovalAnswer(this.request.requestId(), ApprovalDecision.APPROVED, false),
				new ApprovalAnswer(second.requestId(), ApprovalDecision.APPROVED, false));
	}

	@Test void deferredAndCancelledDecisionsAreAcceptedFromTheClient() {
		ApprovalRequest second = pend();
		this.fixture.send("defer", "approval", approval(this.request.requestId().toString(), "DEFERRED"));
		this.fixture.send("cancel", "approval", approval(second.requestId().toString(), "CANCELLED"));
		assertThat(this.fixture.output.await("response", "defer").path("status").asString()).isEqualTo("answered");
		assertThat(this.fixture.output.await("response", "cancel").path("status").asString()).isEqualTo("answered");
		assertThat(this.answers).containsExactly(
				new ApprovalAnswer(this.request.requestId(), ApprovalDecision.DEFERRED, false),
				new ApprovalAnswer(second.requestId(), ApprovalDecision.CANCELLED, false));
	}

	@Test void approvalAndQuestionIdsAreNotInterchangeable() throws Exception {
		var answer = new CompletableFuture<String>();
		Thread asker = Thread.startVirtualThread(() -> answer.complete(this.fixture.console.readLine("confirm")));
		try {
			String questionId = this.fixture.output.awaitEvent("question").path("questionId").asString();
			assertThat(refused(approval(questionId, "APPROVED"))).isEqualTo("stale_approval");
			assertThat(this.fixture.refusalCode("swap", "question",
					Map.of("questionId", this.request.requestId().toString(), "text", "yes"))).isEqualTo("stale_question");
			assertThat(answer).isNotDone();
			this.fixture.send("right", "question", Map.of("questionId", questionId, "text", "yes"));
			assertThat(answer.get(5, TimeUnit.SECONDS)).isEqualTo("yes");
			assertStillPending();
		}
		finally { this.fixture.console.clearQuestions(true); asker.join(); }
	}
}
