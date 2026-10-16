package com.example.miniagent.core.approval;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The console text that can approve a request, and the identities that may reach a log. */
class ApprovalRequestAnswerTest {
	private static final String ID_TEXT = "123e4567-e89b-12d3-a456-426614174000";
	private static final UUID ID = UUID.fromString(ID_TEXT);
	private static final RunId RUN = new RunId("run-1");
	private static final String DIGEST = "a".repeat(64);

	@ParameterizedTest
	@CsvSource({ "y,APPROVED,false", "yes,APPROVED,false", "a,APPROVED,true", "always,APPROVED,true",
			"n,REJECTED,false", "no,REJECTED,false", "defer,DEFERRED,false", "cancel,CANCELLED,false" })
	void eachTokenMapsToItsDecisionAndRememberFlag(String token, ApprovalDecision decision, boolean remember) {
		assertThat(ApprovalAnswer.parse(ID_TEXT + " " + token))
				.isEqualTo(new ApprovalAnswer(ID, decision, remember));
	}

	@ParameterizedTest
	@CsvSource({ "Y,APPROVED,false", "YES,APPROVED,false", "A,APPROVED,true", "Always,APPROVED,true",
			"N,REJECTED,false", "No,REJECTED,false", "DeFer,DEFERRED,false", "CANCEL,CANCELLED,false" })
	void tokensAreCaseInsensitive(String token, ApprovalDecision decision, boolean remember) {
		assertThat(ApprovalAnswer.parse(ID_TEXT + " " + token))
				.isEqualTo(new ApprovalAnswer(ID, decision, remember));
	}

	@Test
	void surroundingAndRepeatedWhitespaceIsTolerated() {
		assertThat(ApprovalAnswer.parse("  " + ID_TEXT + " \t  always \n"))
				.isEqualTo(new ApprovalAnswer(ID, ApprovalDecision.APPROVED, true));
	}

	@Test
	void uppercaseUuidIdentifiesTheSameRequest() {
		assertThat(ApprovalAnswer.parse(ID_TEXT.toUpperCase() + " y"))
				.isEqualTo(new ApprovalAnswer(ID, ApprovalDecision.APPROVED, false));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "y", "yes", ID_TEXT, ID_TEXT + " maybe", ID_TEXT + " y please",
			ID_TEXT + " y n", "y " + ID_TEXT, "not-a-uuid y", "123e4567 y", ID_TEXT + "x y", ID_TEXT + " approve",
			ID_TEXT + " yy", ID_TEXT + " always!" })
	void malformedAnswersAreNotAnswers(String text) {
		assertThat(ApprovalAnswer.parse(text)).isNull();
	}

	@Test
	void validRequestKeepsEveryComponent() {
		UUID planned = UUID.randomUUID();
		var request = new ApprovalRequest(planned, RUN, "attempt-1", "plan_1", "child-1", "tool-call_1", DIGEST, DIGEST);
		assertThat(request.requestId()).isEqualTo(planned);
		assertThat(request.runId()).isEqualTo(RUN);
		assertThat(request.attemptId()).isEqualTo("attempt-1");
		assertThat(request.planId()).isEqualTo("plan_1");
		assertThat(request.childId()).isEqualTo("child-1");
		assertThat(request.toolCallKey()).isEqualTo("tool-call_1");
		assertThat(request.argsDigest()).isEqualTo(DIGEST);
		assertThat(request.diffDigest()).isEqualTo(DIGEST);
	}

	@Test
	void planChildAndDiffAreOptional() {
		var request = new ApprovalRequest(ID, RUN, "a", null, null, "t", DIGEST, null);
		assertThat(request.planId()).isNull();
		assertThat(request.childId()).isNull();
		assertThat(request.diffDigest()).isNull();
	}

	@Test
	void requestAndRunIdsAreRequired() {
		assertThatNullPointerException().isThrownBy(() -> new ApprovalRequest(null, RUN, "a", null, null, "t", DIGEST, null));
		assertThatNullPointerException().isThrownBy(() -> new ApprovalRequest(ID, null, "a", null, null, "t", DIGEST, null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "has space", "_leading", "-leading", "", " ", "a/b", "a.b", "a\nb", "한글" })
	void unsafeIdentitiesAreRefusedInEveryIdentityField(String unsafe) {
		for (var request : List.<Runnable>of(
				() -> request(unsafe, null, null, "t", DIGEST, null),
				() -> request("a", unsafe, null, "t", DIGEST, null),
				() -> request("a", null, unsafe, "t", DIGEST, null),
				() -> request("a", null, null, unsafe, DIGEST, null))) {
			assertThatThrownBy(request::run).isInstanceOf(IllegalArgumentException.class)
					.hasMessage("Invalid approval identity");
		}
	}

	@Test
	void requiredIdentitiesCannotBeNull() {
		assertThatThrownBy(() -> request(null, null, null, "t", DIGEST, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid approval identity");
		assertThatThrownBy(() -> request("a", null, null, null, DIGEST, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid approval identity");
	}

	@Test
	void identityLengthStopsAt128Characters() {
		String longest = "a" + "b".repeat(127);
		assertThat(request(longest, longest, longest, longest, DIGEST, null).attemptId()).isEqualTo(longest);
		assertThatThrownBy(() -> request(longest + "b", null, null, "t", DIGEST, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid approval identity");
	}

	@ParameterizedTest
	@NullSource
	@MethodSource("malformedDigests")
	void malformedArgsDigestIsRefused(String digest) {
		assertThatThrownBy(() -> request("a", null, null, "t", digest, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid SHA-256 digest");
	}

	@ParameterizedTest
	@MethodSource("malformedDigests")
	void malformedDiffDigestIsRefused(String digest) {
		assertThatThrownBy(() -> request("a", null, null, "t", DIGEST, digest))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid SHA-256 digest");
	}

	@Test
	void digestIsLowercaseSha256OfTheUtf8Text() {
		assertThat(ApprovalRequest.digest("abc"))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
		assertThat(ApprovalRequest.digest(""))
				.isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
		assertThat(request("a", null, null, "t", ApprovalRequest.digest("{\"path\":\"a\"}"), null).argsDigest())
				.hasSize(64);
	}

	@Test
	void requestsDifferingInAnySingleComponentAreNotEqual() {
		var base = new ApprovalRequest(ID, RUN, "a", "p", "c", "t", DIGEST, DIGEST);
		String other = "b".repeat(64);
		assertThat(base).isEqualTo(new ApprovalRequest(ID, RUN, "a", "p", "c", "t", DIGEST, DIGEST));
		assertThat(List.of(
				new ApprovalRequest(UUID.randomUUID(), RUN, "a", "p", "c", "t", DIGEST, DIGEST),
				new ApprovalRequest(ID, new RunId("run-2"), "a", "p", "c", "t", DIGEST, DIGEST),
				new ApprovalRequest(ID, RUN, "a2", "p", "c", "t", DIGEST, DIGEST),
				new ApprovalRequest(ID, RUN, "a", "p2", "c", "t", DIGEST, DIGEST),
				new ApprovalRequest(ID, RUN, "a", "p", "c2", "t", DIGEST, DIGEST),
				new ApprovalRequest(ID, RUN, "a", "p", "c", "t2", DIGEST, DIGEST),
				new ApprovalRequest(ID, RUN, "a", "p", "c", "t", other, DIGEST),
				new ApprovalRequest(ID, RUN, "a", "p", "c", "t", DIGEST, other),
				new ApprovalRequest(ID, RUN, "a", "p", "c", "t", DIGEST, null)))
				.allSatisfy(changed -> assertThat(changed).isNotEqualTo(base));
	}

	static Stream<String> malformedDigests() {
		return Stream.of("", "abc", "A".repeat(64), "g".repeat(64), "a".repeat(63), "a".repeat(65),
				" " + "a".repeat(63), "a".repeat(64) + "\n");
	}

	private static ApprovalRequest request(String attempt, String plan, String child, String tool, String args,
			String diff) {
		return new ApprovalRequest(ID, RUN, attempt, plan, child, tool, args, diff);
	}
}
