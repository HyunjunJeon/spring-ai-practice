package com.example.miniagent.core;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentInputHandlerBusyTest {
	@Test void applyWhileBusyRejectedButReadControlsRemainAvailable() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var mutations = new AtomicInteger();
		var observations = new AtomicInteger();
		var console = new AgentConsole(new BufferedReader(new StringReader("")),
				new PrintStream(OutputStream.nullOutputStream()), false);
		var wait = SlashCommand.of("wait", "fixture", (args, context) -> {
			entered.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Unreleased fixture");
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new java.util.concurrent.CancellationException();
			}
		});
		var apply = SlashCommand.of("apply", "fixture", (args, context) -> mutations.incrementAndGet());
		var changes = SlashCommand.of("changes", "fixture", (args, context) -> observations.incrementAndGet());
		var handler = new AgentInputHandler(mock(Agent.class), new AgentSession(), console, List.of(wait, apply, changes));
		try (var worker = Executors.newSingleThreadExecutor()) {
			var task = worker.submit(() -> handler.handle("/wait"));
			try {
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				assertThat(handler.handle("/apply proposal")).isFalse();
				assertThat(mutations).hasValue(0);
				assertThat(handler.handle("/changes")).isTrue();
				assertThat(observations).hasValue(1);
			}
			finally { release.countDown(); }
			assertThat(task.get(5, TimeUnit.SECONDS)).isTrue();
			assertThat(handler.handle("/apply proposal")).isTrue();
			assertThat(mutations).hasValue(1);
		}
	}
}
