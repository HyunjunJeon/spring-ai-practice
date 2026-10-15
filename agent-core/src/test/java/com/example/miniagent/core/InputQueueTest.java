package com.example.miniagent.core;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InputQueueTest {
	@Test void priorityFifoAndCumulativeUtf8Bounds() {
		var queue = new InputQueue(3, 7);
		var target = queue.begin("s", true);
		queue.offer(target, "s", InputQueue.Kind.FOLLOW_UP, "f");
		queue.offer(target, "s", InputQueue.Kind.STEER, "한");
		queue.offer(target, "s", InputQueue.Kind.STEER, "two");
		assertThat(queue.drainSteering(target, "s")).extracting(InputQueue.Entry::text).containsExactly("한", "two");
		assertThatThrownBy(() -> queue.offer(target, "s", InputQueue.Kind.FOLLOW_UP, "x"))
			.hasMessage("QUEUE_FULL");
		assertThat(queue.nextOrEnd(target, "s").text()).isEqualTo("f");
		assertThat(queue.nextOrEnd(target, "s")).isNull();
		var next = queue.begin("s", true);
		assertThatThrownBy(() -> queue.offer(next, "s", InputQueue.Kind.STEER, "한한한")).hasMessage("QUEUE_FULL");
	}

	@Test void cancellationStaleSessionAndGenerationRefuseAllPendingWork() {
		var queue = new InputQueue();
		var target = queue.begin("s", true);
		assertThatThrownBy(() -> queue.offer(target, "other", InputQueue.Kind.STEER, "x"))
			.hasMessage("QUEUE_STALE_TARGET");
		queue.offer(target, "s", InputQueue.Kind.FOLLOW_UP, "pending");
		queue.cancel(target);
		assertThatThrownBy(() -> queue.offer(target, "s", InputQueue.Kind.STEER, "late")).hasMessage("QUEUE_CANCELLED");
		assertThat(queue.nextOrEnd(target, "s")).isNull();
		var newer = queue.begin("s", false);
		assertThatThrownBy(() -> queue.offer(target, "s", InputQueue.Kind.STEER, "stale")).hasMessage("QUEUE_STALE_TARGET");
		assertThatThrownBy(() -> queue.offer(newer, "s", InputQueue.Kind.FOLLOW_UP, "managed"))
			.hasMessage("QUEUE_MANAGED_COMMAND");
	}

	@Test void lateSteeringIsNotLostAtTurnEnd() {
		var queue = new InputQueue();
		var target = queue.begin("s", true);
		assertThat(queue.drainSteering(target, "s")).isEmpty();
		queue.offer(target, "s", InputQueue.Kind.FOLLOW_UP, "later");
		queue.offer(target, "s", InputQueue.Kind.STEER, "late steer");
		assertThat(queue.nextOrEnd(target, "s").text()).isEqualTo("late steer");
		assertThat(queue.nextOrEnd(target, "s").text()).isEqualTo("later");
		assertThat(queue.nextOrEnd(target, "s")).isNull();
		assertThatThrownBy(() -> queue.offer(target, "s", InputQueue.Kind.STEER, "too late"))
			.hasMessage("QUEUE_STALE_TARGET");
	}

	@Test void handlerReservesBeforeWorkerStartsAndQueuedSlashTextIsLiteral() {
		Agent agent = mock(Agent.class);
		when(agent.streamQueued(anyString(), any(), any())).thenReturn(new Agent.AgentReply("", null));
		var handler = handler(agent);
		var work = new AtomicReference<Runnable>();
		assertThat(handler.submit("first", work::set)).isTrue();
		assertThat(handler.handle("/follow-up /apply untrusted")).isTrue();
		assertThat(handler.handle("/model fake/b")).isFalse();
		work.get().run();
		var ordered = inOrder(agent);
		ordered.verify(agent).streamQueued(eq("first"), any(), any());
		ordered.verify(agent).streamQueued(eq("/apply untrusted"), any(), any());
		assertThat(handler.inputQueue().target()).isEmpty();
	}

	@Test void acceptedAtFinalCallbackDrainsBeforeIdleAndFailureClearsFollowUps() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		Agent agent = mock(Agent.class);
		when(agent.streamQueued(eq("first"), any(), any())).thenAnswer(call -> {
			entered.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			return new Agent.AgentReply("", null);
		});
		when(agent.streamQueued(eq("follow"), any(), any())).thenReturn(new Agent.AgentReply("", null));
		var handler = handler(agent);
		try (var worker = Executors.newSingleThreadExecutor()) {
			var result = worker.submit(() -> handler.handle("first"));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(handler.handle("follow")).isTrue();
			release.countDown();
			assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
		}
		verify(agent).streamQueued(eq("follow"), any(), any());
		assertThat(handler.inputQueue().target()).isEmpty();
		var scheduled = new AtomicReference<Runnable>();
		handler.submit("fail", scheduled::set);
		handler.handle("must not start");
		when(agent.streamQueued(eq("fail"), any(), any())).thenThrow(new IllegalStateException("fixture"));
		scheduled.get().run();
		verify(agent, never()).streamQueued(eq("must not start"), any(), any());
		assertThat(handler.inputQueue().target()).isEmpty();
	}

	@Test void cancellationBeforeWorkerStartDoesNotStartAcceptedEffects() {
		Agent agent = mock(Agent.class);
		var handler = handler(agent);
		var work = new AtomicReference<Runnable>();
		handler.submit("first", work::set);
		handler.handle("pending");
		handler.cancelPending();
		work.get().run();
		verifyNoInteractions(agent);
		assertThat(handler.inputQueue().target()).isEmpty();
	}

	private static AgentInputHandler handler(Agent agent) {
		return new AgentInputHandler(agent, new AgentSession(), new AgentConsole(
				new BufferedReader(new StringReader("")), new PrintStream(OutputStream.nullOutputStream()), false),
				List.of());
	}
}
