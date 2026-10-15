package com.example.miniagent.core.messaging;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.example.miniagent.core.delegation.runtime.DefaultDelegationRuntime;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.miniagent.core.messaging.MessageStore.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessageServiceTest {
	@TempDir Path root;
	@org.junit.jupiter.api.BeforeEach void canonicalPaths() throws java.io.IOException { root = root.toRealPath(); }
	@Test void responsePreviewsExposeTruncationAndPreserveSurrogatePairsAcrossStorageAndRetry() throws Exception {
		var runtime = mock(DefaultDelegationRuntime.class);
		var actor = mock(RunContext.class);
		var target = new DefaultDelegationRuntime.MessageTarget("operator", new RunId("parent-run"),
			"parent-conversation", "child-conversation", new RunId("original-run"), "parent");
		when(runtime.messageTarget(actor, "plan", "child")).thenReturn(target);
		var record = mock(com.example.miniagent.core.run.RunRecord.class);
		var spec = mock(com.example.miniagent.core.run.RunSpec.class);
		when(record.state()).thenReturn(com.example.miniagent.core.run.RunState.COMPLETED);
		when(record.uncertainTools()).thenReturn(java.util.List.of());
		when(record.spec()).thenReturn(spec);
		when(spec.runId()).thenReturn(new RunId("execution-run"));
		var service = new MessageService(new MessageStore(root.resolve("home")), runtime);
		String pair = new String(Character.toChars(0x1F680));
		String[] responses = {null, "", "short", "x".repeat(MAX_BODY),
			"x".repeat(MAX_BODY + 1), "x".repeat(MAX_BODY - 1) + pair,
			"x".repeat(MAX_BODY - 2) + pair + "tail"};
		for (int i = 0; i < responses.length; i++) {
			String full = responses[i], id = "response-" + i;
			when(runtime.followUp(actor, "plan", "child", id)).thenReturn(
				new com.example.miniagent.core.execution.RunExecutor.Result(record,
					full == null ? null : new com.example.miniagent.core.Agent.AgentReply(full, null)));
			var receipt = service.send(actor, "plan", "child", id, i * 2L, id);
			boolean truncated = full != null && full.length() > MAX_BODY;
			assertThat(receipt.state()).isEqualTo(State.DELIVERED);
			assertThat(receipt.responseTruncated()).isEqualTo(truncated);
			if (truncated) {
				assertThat(receipt.response()).hasSizeLessThanOrEqualTo(MAX_BODY);
				assertThat(full).startsWith(receipt.response());
				assertThat(Character.isHighSurrogate(receipt.response().charAt(receipt.response().length() - 1))).isFalse();
			}
			else assertThat(receipt.response()).isEqualTo(full);
			var fresh = new MessageService(new MessageStore(root.resolve("home")), runtime);
			assertThat(fresh.read(actor, "plan", "child", i).entries().getFirst()).isEqualTo(receipt);
			assertThat(fresh.send(actor, "plan", "child", id, 0, id)).isEqualTo(receipt);
			assertThat(MessageStore.JSON.readTree(MessageStore.JSON.writeValueAsString(receipt))
				.get("responseTruncated").asBoolean()).isEqualTo(truncated);
			verify(runtime, times(1)).followUp(actor, "plan", "child", id);
		}
	}
	@Test void busyClaimsNeverExecuteTwiceAndLostExecutionStaysUncertainAcrossInstances() throws Exception {
		var runtime = mock(DefaultDelegationRuntime.class);
		var actor = mock(RunContext.class);
		var child = mock(RunContext.class);
		var old = new DefaultDelegationRuntime.MessageTarget("operator", new RunId("parent-run"),
			"parent-conversation", "child-conversation", new RunId("original-run"), "parent");
		var started = new DefaultDelegationRuntime.MessageTarget("operator", old.parentRun(), old.parentConversation(),
			old.childConversation(), new RunId("new-run"), "parent");
		var isStarted = new AtomicBoolean();
		when(runtime.messageTarget(actor, "plan", "child")).thenAnswer(call -> isStarted.get() ? started : old);
		when(runtime.messageTarget(child, "plan", "child")).thenReturn(new DefaultDelegationRuntime.MessageTarget(
			old.originalConversation(), old.parentRun(), old.parentConversation(), old.childConversation(), old.childRun(), "child"));
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		when(runtime.followUp(actor, "plan", "child", "body")).thenAnswer(call -> {
			isStarted.set(true); entered.countDown();
			if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Dispatch release missing");
			throw new IllegalStateException("Lost provider result after execution began");
		});
		var store = new MessageStore(root.resolve("home"));
		var service = new MessageService(store, runtime);
		try (var worker = Executors.newSingleThreadExecutor()) {
			var dispatch = worker.submit(() -> service.send(actor, "plan", "child", "id", 0, "body"));
			try {
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				assertThat(service.read(actor, "plan", "child", 0).entries().getFirst().state()).isEqualTo(State.RUNNING);
				assertThat(new MessageService(new MessageStore(root.resolve("home")), runtime)
					.read(actor, "plan", "child", 0).entries().getFirst().state()).isEqualTo(State.UNCERTAIN);
				assertThat(service.send(actor, "plan", "child", "id", 0, "body").state()).isEqualTo(State.RUNNING);
				assertThatThrownBy(() -> service.send(actor, "plan", "child", "second", 1, "new body"))
					.hasMessageContaining("Thread busy");
				assertThat(service.send(child, "plan", "child", "child-reply", 1, "reply to parent").state())
					.isEqualTo(State.QUEUED);
			}
			finally { release.countDown(); }
			var receipt = dispatch.get(5, TimeUnit.SECONDS);
			assertThat(receipt.state()).isEqualTo(State.UNCERTAIN);
			assertThat(receipt.originalRun()).isEqualTo("original-run");
			assertThat(receipt.executionRun()).isEqualTo("new-run");
			var fresh = new MessageService(new MessageStore(root.resolve("home")), runtime);
			assertThat(fresh.send(actor, "plan", "child", "id", 0, "body")).isEqualTo(receipt);
			verify(runtime, times(1)).followUp(actor, "plan", "child", "body");
			assertThatThrownBy(() -> fresh.send(actor, "plan", "child", "stale", 0, "body"))
				.hasMessageContaining("Stale mailbox version");
		}
	}
	@Test void authorityRefusalHappensBeforeMailboxCreation() {
		var runtime = mock(DefaultDelegationRuntime.class);
		var actor = mock(RunContext.class);
		when(runtime.messageTarget(actor, "foreign", "child")).thenThrow(new IllegalArgumentException("Foreign"));
		var service = new MessageService(new MessageStore(root.resolve("home")), runtime);
		assertThatThrownBy(() -> service.send(actor, "foreign", "child", "id", 0, "body"))
			.hasMessageContaining("Foreign");
		assertThat(root.resolve("home")).doesNotExist();
	}
}
