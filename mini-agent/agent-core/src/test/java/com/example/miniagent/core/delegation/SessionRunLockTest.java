package com.example.miniagent.core.delegation;

import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionRunLockTest {
	@Test void sameConversationRefusesAnotherRunAndOldCloseCannotUnlockNewOwner() {
		var locks = new SessionRunLock();
		var firstRun = RunId.random();
		var first = locks.acquire("conversation", firstRun);
		assertThatThrownBy(() -> locks.acquire("conversation", RunId.random()))
				.isInstanceOfSatisfying(SessionRunLock.Busy.class, ex -> {
					assertThat(ex.conversationId()).isEqualTo("conversation");
					assertThat(ex.runId()).isEqualTo(firstRun);
				});
		first.close();
		try (var next = locks.acquire("conversation", RunId.random())) {
			first.close();
			assertThatThrownBy(() -> locks.acquire("conversation", RunId.random()))
					.isInstanceOfSatisfying(SessionRunLock.Busy.class, ex -> assertThat(ex.runId()).isEqualTo(next.runId()));
		}
		try (var reopened = locks.acquire("conversation", RunId.random())) {
			assertThat(reopened.runId()).isNotEqualTo(firstRun);
		}
	}
	@Test void independentChildConversationDoesNotTakeParentOwnership() {
		var locks = new SessionRunLock();
		try (var parent = locks.acquire("parent", RunId.random());
				var child = locks.acquire("child", RunId.random())) {
			assertThat(parent.conversationId()).isNotEqualTo(child.conversationId());
			assertThat(parent.runId()).isNotEqualTo(child.runId());
		}
	}
}
