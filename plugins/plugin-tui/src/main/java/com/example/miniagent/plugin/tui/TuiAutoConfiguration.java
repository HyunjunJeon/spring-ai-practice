package com.example.miniagent.plugin.tui;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.Agent;
import com.example.miniagent.core.model.ModelDisplay;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.InteractiveFrontEnd;
import com.example.miniagent.core.TraceEvent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

/**
 * 화면 플러그인. 코어보다 먼저 돌아서 {@link AgentConsole} 을 TUI 용으로 바꾸고,
 * {@link InteractiveFrontEnd} 를 등록해 대화형 세션을 맡는다. {@code agent.ui=repl} 이면 꺼진다.
 *
 * <p>트레이스 그래프는 trace 플러그인이 발행하는 {@link TraceEvent} 를 듣고 그린다. 이 플러그인은 trace 플러그인을
 * 직접 알지 못한다(이벤트 타입만 코어에 있다).
 */
@AutoConfiguration(beforeName = "com.example.miniagent.core.AgentAutoConfiguration")
@ConditionalOnProperty(name = "agent.ui", havingValue = "tui", matchIfMissing = true)
public class TuiAutoConfiguration {

	@Bean
	ChatLog chatLog() {
		return new ChatLog();
	}

	@Bean
	TraceGraph traceGraph() {
		return new TraceGraph();
	}

	/** 코어의 AgentConsole 빈은 @ConditionalOnMissingBean 이라 이 빈이 있으면 물러난다. */
	@Bean
	@ConditionalOnMissingBean(AgentConsole.class)
	TuiConsole tuiConsole(ChatLog log) {
		return new TuiConsole(log);
	}

	@Bean
	TuiFrontEnd tuiFrontEnd(AgentInputHandler inputHandler, AgentSession session, AgentConsole console, ChatLog log,
			TraceGraph graph, Agent agent, AgentProperties properties) {
		if (!(console instanceof TuiConsole tuiConsole)) {
			// 테스트처럼 다른 콘솔이 들어온 경우: TUI 는 만들되 쓰지 않는다(isAvailable 이 false 가 되도록).
			return new TuiFrontEnd(inputHandler, session, new TuiConsole(log), log, graph, () -> "", properties.getHome()
				.resolve("logs/tui.log")) {
				@Override
				public boolean isAvailable() {
					return false;
				}
			};
		}
		return new TuiFrontEnd(inputHandler, session, tuiConsole, log, graph,
				() -> ModelDisplay.label(agent), properties.getHome().resolve("logs/tui.log"));
	}

	/** 트레이스 이벤트 → 그래프. 에이전트 작업 스레드에서 불린다. */
	@Bean
	TuiTraceListener tuiTraceListener(TraceGraph graph, ObjectProvider<TuiFrontEnd> frontEnd, AgentSession session) {
		return new TuiTraceListener(graph, frontEnd, session);
	}

	@Bean
	AgentPlugin tuiPlugin() {
		return AgentPlugin.of("tui", "전체 화면 TUI + 트레이스 그래프 사이드바 (agent.ui=tui)");
	}

	public static class TuiTraceListener {

		private final TraceGraph graph;

		private final ObjectProvider<TuiFrontEnd> frontEnd;

		private final AgentSession session;

		TuiTraceListener(TraceGraph graph, ObjectProvider<TuiFrontEnd> frontEnd, AgentSession session) {
			this.graph = graph;
			this.frontEnd = frontEnd;
			this.session = session;
		}

		@EventListener
		public void on(TraceEvent event) {
			if (event instanceof TraceEvent.RunStateChanged run && run.conversationId().equals(this.session.id())) {
				this.graph.accept(event);
				this.frontEnd.ifAvailable(TuiFrontEnd::requestRedraw);
				return;
			}
			if (event instanceof TraceEvent.DelegationStateChanged delegation) {
				if (!delegation.conversationId().equals(this.session.id())) return;
				this.graph.accept(event);
				this.frontEnd.ifAvailable(TuiFrontEnd::requestRedraw);
				return;
			}
			boolean managedParent = com.example.miniagent.core.run.RunContext.current()
					.filter(context -> context.spec().childId() == null
							&& context.spec().parentConversationId().equals(this.session.id())).isPresent();
			if (event instanceof TraceEvent.ModelBound bound && !bound.sessionId().equals(this.session.id()) && !managedParent) {
				return;
			}
			if (!managedParent && TurnContext.current().filter(context -> !context.sessionId().equals(this.session.id())).isPresent()) {
				return;
			}
			this.graph.accept(event);
			this.frontEnd.ifAvailable(TuiFrontEnd::requestRedraw);
		}

	}

}
