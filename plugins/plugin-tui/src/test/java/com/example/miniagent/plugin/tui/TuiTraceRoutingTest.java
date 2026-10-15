package com.example.miniagent.plugin.tui;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.assertj.core.api.Assertions.assertThat;

class TuiTraceRoutingTest {
	@TempDir Path directory;
	@Test void independentParentRunRoutesToItsOriginalConsoleButChildAndForeignTurnsDoNot() throws Exception {
		var session = new AgentSession();
		session.switchTo("visible");
		var graph = new TraceGraph();
		var listener = new TuiAutoConfiguration.TuiTraceListener(graph,
				new DefaultListableBeanFactory().getBeanProvider(TuiFrontEnd.class), session);
		var store = new FileRunStore(this.directory);
		var ref = new ModelRef("fake", "captured");
		ChatModel model = prompt -> { throw new AssertionError("No model dispatch in routing test"); };
		var binding = new ModelBinding(UUID.randomUUID(), ref, "0".repeat(64), "fake", model, ChatClient.create(model));
		for (int scenario = 0; scenario < 3; scenario++) {
			String conversation = UUID.randomUUID().toString();
			var spec = new RunSpec(1, RunId.random(), conversation, scenario == 2 ? "foreign" : "visible",
					scenario == 1 ? RunId.random() : null, scenario == 1 ? "child" : null,
					ref, "fake", this.directory.toRealPath().toString(), RunBudget.defaults(),
					binding.optionsFingerprint(), "1".repeat(64), Instant.EPOCH);
			var attempt = UUID.randomUUID().toString();
			var turn = new TurnContext(conversation, UUID.randomUUID().toString(), this.directory.toRealPath(),
					binding, spec.runId().value(), attempt);
			try (var writer = store.create(spec)) {
				var context = new RunContext(spec, attempt, writer, turn, Clock.systemUTC());
				try (var runScope = context.open(); var turnScope = turn.open()) {
					listener.on(new TraceEvent.ModelBound(conversation, turn.turnId(), ref, binding.bindingId(),
							binding.optionsFingerprint(), spec.runId().value(), attempt));
					listener.on(new TraceEvent.RunStateChanged(spec.runId().value(), conversation, attempt, RunState.RUNNING, ref));
					listener.on(new TraceEvent.TurnStarted(1, "managed marker"));
				}
			}
			assertThat(graph.turnCount()).isEqualTo(1);
		}
		assertThat(graph.render(100, 38).stream().map(TraceGraph.Line::plain))
				.anyMatch(line -> line.contains("fake/captured"));
	}
}
