package com.example.miniagent.plugin.trace;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.plugin.trace.TraceSettings.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 트레이스 수준 표기와 {@code /trace}: 알 수 없는 값은 조용히 summary 가 되지 않고 거부된다. */
class TraceSettingsTest {

	private final ByteArrayOutputStream output = new ByteArrayOutputStream();

	private final AgentConsole console = new AgentConsole(new BufferedReader(new StringReader("")),
			new PrintStream(this.output, true, StandardCharsets.UTF_8), false);

	private final SlashCommand trace;

	private final TraceSettings settings = new TraceSettings("summary");

	TraceSettingsTest() {
		this.trace = new TracePluginAutoConfiguration().traceCommand(this.settings);
	}

	@ParameterizedTest
	@CsvSource({ "off,OFF", "false,OFF", "OFF,OFF", "' Off ',OFF", "summary,SUMMARY", "Summary,SUMMARY",
			"'  summary  ',SUMMARY", "detail,DETAIL", "details,DETAIL", "verbose,DETAIL", "DETAIL,DETAIL",
			"' Verbose ',DETAIL" })
	void recognisedSpellingsSelectTheirLevelAtStartupAndAtRuntime(String text, Level expected) {
		assertThat(new TraceSettings(text).level()).isEqualTo(expected);

		TraceSettings runtime = new TraceSettings("summary");
		runtime.setLevel(text);
		assertThat(runtime.level()).isEqualTo(expected);
		assertThat(runtime.isOff()).isEqualTo(expected == Level.OFF);
		assertThat(runtime.isDetail()).isEqualTo(expected == Level.DETAIL);
	}

	@Test
	void unknownLevelAtRuntimeIsRefusedAndTheCurrentLevelStays() {
		TraceSettings settings = new TraceSettings("detail");

		Throwable refusal = catchThrowable(() -> settings.setLevel("verbos"));

		assertThat(settings.level()).isEqualTo(Level.DETAIL);
		assertThat(refusal).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("verbos")
			.hasMessageContaining("off, summary or detail");
	}

	@ParameterizedTest
	@ValueSource(strings = { "verbos", "detial", "summary2", "" })
	void unknownConfiguredLevelIsRefusedAtStartup(String text) {
		assertThatThrownBy(() -> new TraceSettings(text)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Unknown trace level '" + text + "'");
	}

	@Test
	void traceCommandAppliesTheLevelAndEchoesIt() {
		this.trace.execute("detail", context());

		assertThat(this.settings.level()).isEqualTo(Level.DETAIL);
		assertThat(printed()).containsExactly("trace = detail");
	}

	@Test
	void traceCommandWithoutArgumentsShowsTheCurrentLevel() {
		this.trace.execute("", context());

		assertThat(this.settings.level()).isEqualTo(Level.SUMMARY);
		assertThat(printed()).containsExactly("trace = summary");
	}

	@Test
	void traceCommandRefusesATypoWithoutEchoingItAsAccepted() {
		this.trace.execute("detail", context());

		Throwable refusal = catchThrowable(() -> this.trace.execute("verbos", context()));

		assertThat(refusal).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("verbos");
		assertThat(this.settings.level()).isEqualTo(Level.DETAIL);
		assertThat(printed()).containsExactly("trace = detail");
	}

	@Test
	void configuredPropertySelectsTheStartupLevelAndSummaryIsTheDefault() {
		ApplicationContextRunner runner = runner();

		runner.run(context -> assertThat(context.getBean(TraceSettings.class).level()).isEqualTo(Level.SUMMARY));
		runner.withPropertyValues("agent.plugins.trace.level=off")
			.run(context -> assertThat(context.getBean(TraceSettings.class).isOff()).isTrue());
		runner.withPropertyValues("agent.plugins.trace.level=detail")
			.run(context -> assertThat(context.getBean(TraceSettings.class).isDetail()).isTrue());
	}

	@Test
	void misspelledPropertyStopsStartupInsteadOfFallingBackToSummary() {
		runner().withPropertyValues("agent.plugins.trace.level=verbos")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("verbos"));
	}

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(TracePluginAutoConfiguration.class))
			.withBean(AgentConsole.class, () -> this.console);
	}

	private SlashCommand.CommandContext context() {
		return new SlashCommand.CommandContext(null, null, this.console);
	}

	private List<String> printed() {
		return this.output.toString(StandardCharsets.UTF_8).lines().toList();
	}

}
