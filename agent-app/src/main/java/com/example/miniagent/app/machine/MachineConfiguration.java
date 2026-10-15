package com.example.miniagent.app.machine;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.ConsoleAppender;
import org.slf4j.LoggerFactory;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** User configuration is processed before core's ConditionalOnMissingBean console. */
@Configuration(proxyBeanMethods = false)
@Conditional(MachineMode.class)
public class MachineConfiguration {
	@Bean static MachineStreams machineStreams(
			org.springframework.beans.factory.ObjectProvider<MachineMode.Output> output) {
		return new MachineStreams(output.getIfAvailable());
	}
	@Bean MachineProtocol machineProtocol(MachineStreams streams) { return new MachineProtocol(streams.protocolOut); }
	@Bean RpcConsole agentConsole(MachineProtocol protocol) { return new RpcConsole(protocol); }
	@Bean MachineRunner machineRunner(AgentInputHandler handler, AgentSession session, RpcConsole console,
			MachineProtocol protocol, Environment environment) {
		return new MachineRunner(handler, session, console, protocol, System.in,
				environment.getRequiredProperty("agent.ui"));
	}

	/**
	 * Reserve original stdout for JSONL before application beans initialize. Incidental
	 * System.out writes and the standard Boot Logback console appenders go to stderr.
	 * Configuration-time logging before this postprocessor remains Boot-owned.
	 * Restore process globals on close so embedded contexts do not retain redirection.
	 */
	static final class MachineStreams implements BeanFactoryPostProcessor, AutoCloseable {
		private final PrintStream protocolOut;
		private final PrintStream diagnosticOut = System.err;
		private final boolean processOwned;
		private final Map<ConsoleAppender<?>, String> appenders = new LinkedHashMap<>();

		MachineStreams() { this(null); }

		MachineStreams(MachineMode.Output output) {
			this.protocolOut = output == null ? System.out : output.stream();
			this.processOwned = output != null;
		}
		@Override public void postProcessBeanFactory(
				org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
			System.setOut(this.diagnosticOut);
			if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
				for (var logger : context.getLoggerList()) {
					var iterator = logger.iteratorForAppenders();
					while (iterator.hasNext()) {
						if (iterator.next() instanceof ConsoleAppender<?> appender) {
							this.appenders.putIfAbsent(appender, appender.getTarget());
							appender.stop();
							appender.setTarget("System.err");
							appender.start();
						}
					}
				}
			}
		}
		@Override public void close() {
			// main() owns redirection through process exit, including Boot failure reporting.
			if (this.processOwned) return;
			if (System.out == this.diagnosticOut) System.setOut(this.protocolOut);
			this.appenders.forEach((appender, target) -> {
				appender.stop();
				appender.setTarget(target);
				appender.start();
			});
		}
	}
}
