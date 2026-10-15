package com.example.miniagent.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import com.example.miniagent.app.machine.MachineMode;

@SpringBootApplication
public class MiniAgentApplication {

	public static void main(String[] args) {
		// REPL 이 끝나면 컨텍스트를 닫고 JVM 을 종료한다(MCP stdio 프로세스 같은 비데몬 스레드 정리).
		SpringApplication application = new SpringApplication(MiniAgentApplication.class);
		var output = new MachineOutputBootstrap();
		application.addListeners(output);
		application.addInitializers(context -> {
			if (output.machine) {
				context.getBeanFactory().registerSingleton("machineProtocolOutput",
						new MachineMode.Output(output.protocolOut));
			}
		});
		System.exit(SpringApplication.exit(application.run(args)));
	}

	/** After config-data loading, before Logback initialization or banner rendering. */
	private static final class MachineOutputBootstrap
			implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
		private final java.io.PrintStream protocolOut = System.out;
		private boolean machine;

		@Override public int getOrder() { return LoggingApplicationListener.DEFAULT_ORDER - 1; }

		@Override public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
			if (event.getEnvironment().getProperty("agent.worker.mode", Boolean.class, false)) {
				event.getEnvironment().getPropertySources().addFirst(
						new org.springframework.core.env.MapPropertySource("worker-mode",
								java.util.Map.of("agent.ui", "repl", "agent.repl.enabled", false,
										"spring.main.web-application-type", "none")));
			}
			this.machine = MachineMode.enabled(event.getEnvironment());
			if (this.machine) System.setOut(System.err);
		}
	}
}
