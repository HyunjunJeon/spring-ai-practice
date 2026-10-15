package com.example.miniagent.app.machine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import com.example.miniagent.app.MiniAgentApplication;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ConsoleApprovalBroker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

import static org.assertj.core.api.Assertions.assertThat;

class MachineConfigurationTest {
	@TempDir Path home;

	@Test void bothMachineModesReplaceConsoleBeforeCoreAndExcludeHumanRunner() {
		for (String mode : new String[] { "json", "rpc" }) {
			new ApplicationContextRunner().withUserConfiguration(MiniAgentApplication.class)
					.withInitializer(new ConfigDataApplicationContextInitializer())
					.withInitializer(context -> context.getBeanFactory().setConversionService(
							org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
					.withBean(org.springframework.boot.context.TypeExcludeFilter.class,
							() -> new org.springframework.boot.context.TypeExcludeFilter() {
								@Override public boolean match(
										org.springframework.core.type.classreading.MetadataReader metadata,
										org.springframework.core.type.classreading.MetadataReaderFactory factory) {
									var annotations = metadata.getAnnotationMetadata();
									String component = org.springframework.boot.test.context.TestComponent.class.getName();
									return annotations.hasAnnotation(component) || annotations.hasMetaAnnotation(component);
								}
							})
					.withPropertyValues("agent.ui=" + mode, "agent.cwd=" + this.home,
							"agent.home=" + this.home.resolve(mode), "agent.plugins.memory.compaction=false")
					.run(context -> {
						assertThat(context).hasNotFailed();
						assertThat(context).hasSingleBean(AgentConsole.class).hasSingleBean(ApplicationRunner.class);
						assertThat(context.getBean(AgentConsole.class)).isInstanceOf(RpcConsole.class);
						assertThat(context.getBean(ApplicationRunner.class)).isInstanceOf(MachineRunner.class);
						assertThat(context.getBean(ApprovalBroker.class)).isInstanceOf(ConsoleApprovalBroker.class);
						assertThat(context.containsBean("agentRepl")).isFalse();
					});
		}
	}

	@Test void incidentalStdoutIsRedirectedAndRestoredWhileProtocolRetainsOriginalStream() {
		PrintStream original = System.out;
		var stdout = new ByteArrayOutputStream();
		var capture = new PrintStream(stdout, true, StandardCharsets.UTF_8);
		System.setOut(capture);
		try {
			var streams = new MachineConfiguration.MachineStreams();
			try {
				streams.postProcessBeanFactory(new org.springframework.beans.factory.support.DefaultListableBeanFactory());
				assertThat(System.out).isSameAs(System.err);
				System.out.println("diagnostic, not JSONL");
				assertThat(stdout.size()).isZero();
			}
			finally { streams.close(); }
			assertThat(System.out).isSameAs(capture);
		}
		finally { System.setOut(original); }
	}
}
