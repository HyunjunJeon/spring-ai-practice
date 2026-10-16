package com.example.miniagent.app.machine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.miniagent.core.approval.ApprovalAnswer;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Approval and legacy-question lifecycle of the RPC console, driven without a Spring context or stdin. */
@Timeout(20)
class RpcConsoleApprovalTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final int CAP = 64;
	private static final String ARGS_DIGEST = "a".repeat(64);
	private static final String DIFF_DIGEST = "b".repeat(64);
	private static final MachineProtocol.Correlation CORRELATION =
			new MachineProtocol.Correlation("req-1", "exec-1", "session-1", "turn-1");

	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
	private final RpcConsole console = new RpcConsole(new MachineProtocol(
			new PrintStream(this.bytes, true, StandardCharsets.UTF_8)));
	private final List<ApprovalAnswer> answers = new ArrayList<>();

	private List<JsonNode> frames() {
		return this.bytes.toString(StandardCharsets.UTF_8).lines().map(line -> JSON.readTree(line)).toList();
	}

	private static ApprovalRequest request(String planId, String childId, String diffDigest) {
		return new ApprovalRequest(UUID.randomUUID(), new RunId("run-1"), "attempt-1", planId, childId,
				"write_file", ARGS_DIGEST, diffDigest);
	}

	private static ApprovalRequest request() {
		return request(null, null, null);
	}

	private List<ApprovalRequest> fillToTheCap() {
		this.console.begin(CORRELATION);
		var pending = new ArrayList<ApprovalRequest>();
		for (int i = 0; i < CAP; i++) {
			var request = request();
			this.console.requestApproval(request, this.answers::add);
			pending.add(request);
		}
		return pending;
	}

	@Test
	void approvalFrameCarriesEveryAuthorityFieldOfTheRequest() {
		this.console.begin(CORRELATION);
		var request = request("plan-1", "child-1", DIFF_DIGEST);
		this.console.requestApproval(request, this.answers::add);
		assertThat(frames()).containsExactly(JSON.readTree("""
				{"v":1,"type":"event","requestId":"req-1","executionId":"exec-1","sessionId":"session-1",
				"turnId":"turn-1","event":"approval","approvalId":"%s","runId":"run-1","attemptId":"attempt-1",
				"planId":"plan-1","childId":"child-1","toolCallKey":"write_file","argsDigest":"%s",
				"diffDigest":"%s"}""".formatted(request.requestId(), ARGS_DIGEST, DIFF_DIGEST)));
	}

	@Test
	void optionalAuthorityFieldsTheRequestLacksAreEmittedAsJsonNull() {
		this.console.begin(CORRELATION);
		var request = request();
		this.console.requestApproval(request, this.answers::add);
		assertThat(frames()).containsExactly(JSON.readTree("""
				{"v":1,"type":"event","requestId":"req-1","executionId":"exec-1","sessionId":"session-1",
				"turnId":"turn-1","event":"approval","approvalId":"%s","runId":"run-1","attemptId":"attempt-1",
				"planId":null,"childId":null,"toolCallKey":"write_file","argsDigest":"%s",
				"diffDigest":null}""".formatted(request.requestId(), ARGS_DIGEST)));
	}

	@Test
	void sixtyFifthPendingApprovalIsRefusedAndNeitherEmittedNorAnswered() {
		fillToTheCap();
		var overflow = request();
		assertThatThrownBy(() -> this.console.requestApproval(overflow, this.answers::add))
				.isInstanceOf(MachineProtocol.Invalid.class).hasMessage("question_limit");
		assertThat(frames()).hasSize(CAP);
		assertThat(this.answers).isEmpty();
		assertThat(this.console.answerApproval(overflow.requestId(), ApprovalDecision.APPROVED, false)).isFalse();
	}

	@Test
	void answeringAPendingApprovalFreesASlotUnderTheCap() {
		var pending = fillToTheCap();
		assertThat(this.console.answerApproval(pending.getFirst().requestId(), ApprovalDecision.APPROVED, true)).isTrue();
		this.console.requestApproval(request(), this.answers::add);
		assertThat(frames()).hasSize(CAP + 1);
		assertThatThrownBy(() -> this.console.requestApproval(request(), this.answers::add))
				.isInstanceOf(MachineProtocol.Invalid.class).hasMessage("question_limit");
	}

	@Test
	void dismissedApprovalFreesItsSlotAndCanNoLongerBeAnswered() {
		var pending = fillToTheCap();
		this.console.dismissApproval(pending.getFirst().requestId());
		assertThat(this.console.answerApproval(pending.getFirst().requestId(), ApprovalDecision.APPROVED, false)).isFalse();
		this.console.requestApproval(request(), this.answers::add);
		assertThat(frames()).hasSize(CAP + 1);
		assertThat(this.answers).isEmpty();
	}

	@Test
	void legacyQuestionIsRefusedWhileApprovalsFillTheCap() {
		fillToTheCap();
		assertThatThrownBy(() -> this.console.readLine("confirm"))
				.isInstanceOf(MachineProtocol.Invalid.class).hasMessage("question_limit");
		assertThat(frames()).hasSize(CAP);
	}

	@Test
	void approvalRequestedAfterCancellationIsCancelledImmediatelyWithoutAFrame() {
		this.console.begin(CORRELATION);
		this.console.clearQuestions(true);
		var request = request();
		this.console.requestApproval(request, this.answers::add);
		assertThat(this.answers).containsExactly(new ApprovalAnswer(request.requestId(), ApprovalDecision.CANCELLED, false));
		assertThat(frames()).isEmpty();
		assertThat(this.console.answerApproval(request.requestId(), ApprovalDecision.APPROVED, false)).isFalse();
	}

	@Test
	void approvalRequestedAfterInputClosedIsDeferredImmediatelyWithoutAFrame() {
		this.console.begin(CORRELATION);
		this.console.clearQuestions(false);
		var request = request();
		this.console.requestApproval(request, this.answers::add);
		assertThat(this.answers).containsExactly(new ApprovalAnswer(request.requestId(), ApprovalDecision.DEFERRED, false));
		assertThat(frames()).isEmpty();
		assertThat(this.console.answerApproval(request.requestId(), ApprovalDecision.APPROVED, false)).isFalse();
	}

	@Test
	void cancellationWinsOverClosedInput() {
		this.console.clearQuestions(false);
		this.console.clearQuestions(true);
		var request = request();
		this.console.requestApproval(request, this.answers::add);
		assertThat(this.answers).containsExactly(new ApprovalAnswer(request.requestId(), ApprovalDecision.CANCELLED, false));
	}

	@Test
	void beginningANewExecutionReopensTheConsoleAfterACancellation() {
		this.console.clearQuestions(true);
		this.console.begin(CORRELATION);
		this.console.requestApproval(request(), this.answers::add);
		assertThat(this.answers).isEmpty();
		assertThat(frames()).hasSize(1);
	}

	@Test
	void closedInputStaysClosedForEveryLaterExecution() {
		this.console.clearQuestions(false);
		this.console.begin(CORRELATION);
		var request = request();
		this.console.requestApproval(request, this.answers::add);
		assertThat(this.answers).containsExactly(new ApprovalAnswer(request.requestId(), ApprovalDecision.DEFERRED, false));
		assertThat(frames()).isEmpty();
	}

	@ParameterizedTest
	@CsvSource({ "true,CANCELLED", "false,DEFERRED" })
	void throwingApprovalCallbackStillReleasesTheOtherWaiters(boolean cancel, ApprovalDecision expected) {
		this.console.begin(CORRELATION);
		var throwing = request();
		var second = request();
		var third = request();
		this.console.requestApproval(throwing, answer -> { throw new IllegalStateException("broker failure"); });
		this.console.requestApproval(second, this.answers::add);
		this.console.requestApproval(third, this.answers::add);

		this.console.clearQuestions(cancel);

		assertThat(this.answers).containsExactly(new ApprovalAnswer(second.requestId(), expected, false),
				new ApprovalAnswer(third.requestId(), expected, false));
		assertThat(this.console.failed()).isTrue();
		assertThat(frames().getLast()).isEqualTo(JSON.readTree("""
				{"v":1,"type":"error","requestId":"req-1","executionId":"exec-1","sessionId":"session-1",
				"turnId":"turn-1","code":"execution_failed"}"""));
		assertThat(this.console.answerApproval(second.requestId(), ApprovalDecision.APPROVED, false)).isFalse();
	}
}
