package com.example.miniagent.plugin.tui;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.delegation.ChildStatus;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.RunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code TuiTraceListener} 가 RunStateChanged, DelegationStateChanged 를 대화 id 로 가려 그래프에 넣고
 * 화면을 다시 그리게 하는지 확인한다.
 */
class TuiTraceListenerRoutingTest {

	private static final String IDLE_STATUS = "준비됨";

	private final AgentSession session = new AgentSession();

	private final TraceGraph graph = new TraceGraph();

	private final ModelRef ref = new ModelRef("fake", "captured");

	private final AtomicInteger redraws = new AtomicInteger();

	private final TuiAutoConfiguration.TuiTraceListener listener = listenerWithFrontEnd();

	@BeforeEach
	void showConversation() {
		this.session.switchTo("visible");
	}

	@Test
	void runStateOfTheVisibleConversationIsPaintedAndRedrawnOnce() {
		this.listener.on(runState("run-visible", "visible", RunState.RUNNING));

		assertThat(this.graph.runStatus()).contains("run=run-visible", "attempt=attempt-1", "RUNNING", "fake/captured");
		assertThat(this.graph.activeRunId()).isEqualTo("run-visible");
		assertThat(this.redraws).hasValue(1);
	}

	@Test
	void delegationStateOfTheVisibleConversationIsPaintedAndRedrawnOnce() {
		this.listener.on(delegation("visible", "plan-1"));

		assertThat(this.graph.runStatus()).isEqualTo("delegate=plan-1 child-a=RUNNING(fake/captured)");
		assertThat(this.redraws).hasValue(1);
	}

	@Test
	void delegationStateOfAnotherConversationIsDroppedWithoutRedraw() {
		this.listener.on(delegation("other", "plan-foreign"));

		assertThat(this.graph.runStatus()).isEqualTo(IDLE_STATUS);
		assertThat(this.redraws).hasValue(0);

		this.listener.on(delegation("visible", "plan-1"));

		assertThat(this.graph.runStatus()).startsWith("delegate=plan-1 ");
		assertThat(this.redraws).hasValue(1);
	}

	@Test
	void runStateOfAnotherConversationInsideAForeignTurnIsDroppedWithoutRedraw() {
		try (var scope = foreignTurn("child-conversation").open()) {
			this.listener.on(runState("run-child", "child-conversation", RunState.RUNNING));
		}

		assertThat(this.graph.runStatus()).isEqualTo(IDLE_STATUS);
		assertThat(this.graph.activeRunId()).isNull();
		assertThat(this.redraws).hasValue(0);
	}

	/**
	 * RunCommand 는 run 이 가진 자기 conversationId 로 상태를 발행하고, RunSpec 은 그 값이 부모 대화와 같은 것을
	 * 막는다. 그래서 턴 범위 밖에서 온 RunStateChanged 는 대화 id 가 달라도 그려져야 한다. 일치할 때만 받으면
	 * 끝난 run 이 사이드바에 RUNNING 으로 남고 Esc 가 이미 끝난 run 을 취소하려 든다.
	 */
	@Test
	void runStateWithTheRunsOwnConversationOutsideAnyTurnStillReachesTheSidebar() {
		this.listener.on(runState("run-own", "visible", RunState.RUNNING));
		assertThat(this.graph.activeRunId()).isEqualTo("run-own");

		this.listener.on(runState("run-own", "run-own-conversation", RunState.COMPLETED));

		assertThat(this.graph.runStatus()).contains("run=run-own", "COMPLETED");
		assertThat(this.graph.activeRunId()).isNull();
		assertThat(this.redraws).hasValue(2);
	}

	@Test
	void routingFollowsTheCurrentSessionId() {
		this.listener.on(delegation("visible", "plan-1"));
		this.session.switchTo("resumed");

		this.listener.on(delegation("visible", "plan-2"));
		assertThat(this.graph.runStatus()).startsWith("delegate=plan-1 ");

		this.listener.on(delegation("resumed", "plan-3"));
		assertThat(this.graph.runStatus()).startsWith("delegate=plan-3 ");
		assertThat(this.redraws).hasValue(2);
	}

	@Test
	void visibleEventsStillReachTheGraphWhenNoFrontEndBeanIsAvailable() {
		var withoutFrontEnd = new TuiAutoConfiguration.TuiTraceListener(this.graph,
				new DefaultListableBeanFactory().getBeanProvider(TuiFrontEnd.class), this.session);

		withoutFrontEnd.on(runState("run-visible", "visible", RunState.WAITING_APPROVAL));

		assertThat(this.graph.runStatus()).contains("run=run-visible", "WAITING_APPROVAL");
		assertThat(this.redraws).hasValue(0);
	}

	private TraceEvent.RunStateChanged runState(String runId, String conversationId, RunState state) {
		return new TraceEvent.RunStateChanged(runId, conversationId, "attempt-1", state, this.ref);
	}

	private TraceEvent.DelegationStateChanged delegation(String conversationId, String planId) {
		return new TraceEvent.DelegationStateChanged(conversationId, planId, "child-a", "child-run", this.ref,
				ChildStatus.RUNNING, RunState.RUNNING, null, null);
	}

	private TurnContext foreignTurn(String sessionId) {
		ChatModel model = prompt -> {
			throw new AssertionError("No model dispatch in routing test");
		};
		var binding = new ModelBinding(UUID.randomUUID(), this.ref, "0".repeat(64), "fake", model,
				ChatClient.create(model));
		return new TurnContext(sessionId, UUID.randomUUID().toString(), Path.of("unused"), binding);
	}

	/** requestRedraw 호출 횟수를 세는 화면. TuiAutoConfiguration 도 같은 방식으로 화면을 상속해 쓴다. */
	private TuiAutoConfiguration.TuiTraceListener listenerWithFrontEnd() {
		var console = new TuiConsole(new ChatLog());
		var frontEnd = new TuiFrontEnd(new AgentInputHandler(mock(Agent.class), this.session, console, List.of()),
				this.session, console, new ChatLog(), this.graph, () -> "test", Path.of("unused.log")) {
			@Override
			public void requestRedraw() {
				TuiTraceListenerRoutingTest.this.redraws.incrementAndGet();
			}
		};
		var beans = new DefaultListableBeanFactory();
		beans.registerSingleton("tuiFrontEnd", frontEnd);
		return new TuiAutoConfiguration.TuiTraceListener(this.graph, beans.getBeanProvider(TuiFrontEnd.class),
				this.session);
	}

}
