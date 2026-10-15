package com.example.miniagent.plugin.trace;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.ToolInterceptor;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * 한 플러그인이 여러 확장점(어드바이저 2개 + 도구 인터셉터 + 명령)을 동시에 쓰는 예.
 * 관찰한 내용은 {@code TraceEvent} 로 발행하고, 표시는 렌더러가 맡는다.
 */
@AutoConfiguration
@ConditionalOnBooleanProperty(name = "agent.plugins.trace.enabled", matchIfMissing = true)
public class TracePluginAutoConfiguration {

	@Bean
	TraceSettings traceSettings(@Value("${agent.plugins.trace.level:summary}") String level) {
		return new TraceSettings(level);
	}

	@Bean
	Advisor turnTraceAdvisor(ApplicationEventPublisher events, TraceSettings settings) {
		return new TurnTraceAdvisor(events, settings);
	}

	@Bean
	Advisor roundTraceAdvisor(ApplicationEventPublisher events) {
		return new RoundTraceAdvisor(events);
	}

	@Bean
	ToolInterceptor toolTraceInterceptor(ApplicationEventPublisher events, TraceSettings settings) {
		return new ToolTraceInterceptor(events, settings);
	}

	/** 줄 단위 REPL 용 렌더러. TUI 는 같은 이벤트를 사이드바로 그린다. */
	@Bean
	ConsoleTraceRenderer consoleTraceRenderer(AgentConsole console, TraceSettings settings) {
		return new ConsoleTraceRenderer(console, settings);
	}

	@Bean
	SlashCommand traceCommand(TraceSettings settings) {
		return SlashCommand.of("trace", "트레이스 수준 (off | summary: 턴당 한 줄 | detail: 라운드별 트리)", (args, ctx) -> {
			if (!args.isBlank()) {
				settings.setLevel(args);
			}
			ctx.console().println("trace = " + settings.level().name().toLowerCase());
		});
	}

	@Bean
	AgentPlugin tracePlugin() {
		return AgentPlugin.of("trace", "턴(루프 바깥) / 라운드(루프 안쪽) / 도구 실행 트레이스 (/trace detail)");
	}

}
