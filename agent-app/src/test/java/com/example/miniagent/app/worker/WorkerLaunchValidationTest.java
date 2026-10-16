package com.example.miniagent.app.worker;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/** The constructor is the credential and endpoint allowlist of the detached worker process. */
class WorkerLaunchValidationTest {
	private static final String AUTH = "codex-cli";
	private static final String BASE_URL = "https://chatgpt.com/backend-api/codex";
	private static final Duration IDLE = Duration.ofMinutes(5);

	@TempDir Path temporary;

	private static WorkerLaunch launch(String provider, String model, Map<String, String> options, String auth,
			String baseUrl, Duration idle) {
		return new WorkerLaunch(new ModelSelection(new ModelRef(provider, model), options), auth, baseUrl,
				Path.of("codex"), Path.of("home"), idle);
	}

	private static WorkerLaunch withModel(String model) {
		return launch("codex", model, Map.of(), AUTH, BASE_URL, IDLE);
	}

	static Stream<Arguments> notTheOfficialCodexEndpoint() {
		return Stream.of(
				Arguments.of("openai", AUTH, BASE_URL),
				Arguments.of("codex", "auto", BASE_URL),
				Arguments.of("codex", "", BASE_URL),
				Arguments.of("codex", AUTH + " ", BASE_URL),
				Arguments.of("codex", AUTH, BASE_URL + "/"),
				Arguments.of("codex", AUTH, "http://chatgpt.com/backend-api/codex"),
				Arguments.of("codex", AUTH, "https://chatgpt.com.evil.example/backend-api/codex"),
				Arguments.of("codex", AUTH, ""));
	}

	@ParameterizedTest
	@MethodSource("notTheOfficialCodexEndpoint")
	void providerAuthAndBaseUrlAreRefusedWhenAnyOneOfThemIsWrong(String provider, String auth, String baseUrl) {
		assertThatThrownBy(() -> launch(provider, "gpt-6.1-sol", Map.of(), auth, baseUrl, IDLE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Worker requires official Codex CLI OAuth");
	}

	@ParameterizedTest
	@ValueSource(strings = { "gpt 5", "gpt/5", "gpt;rm", "gpt$(id)", "모델" })
	void modelNamesOutsideTheAllowlistedAlphabetAreRefused(String model) {
		assertThatThrownBy(() -> withModel(model))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid worker model");
	}

	@Test
	void modelNameOfMoreThan128CharactersIsRefused() {
		assertThatThrownBy(() -> withModel("a".repeat(129)))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid worker model");
	}

	@ParameterizedTest
	@ValueSource(strings = { "gpt-6.1-sol", "A.b_c-1", "x" })
	void modelNamesOfTheAllowlistedAlphabetAreAccepted(String model) {
		assertThat(withModel(model).selection().ref().model()).isEqualTo(model);
	}

	@Test
	void modelNameOfExactly128CharactersIsAccepted() {
		String model = "a".repeat(128);
		assertThat(withModel(model).selection().ref().model()).isEqualTo(model);
	}

	@ParameterizedTest
	@CsvSource({ "effort,minimal", "effort,low", "effort,medium", "effort,high", "effort,xhigh", "effort,max",
			"reasoningEffort,minimal", "reasoningEffort,low", "reasoningEffort,medium", "reasoningEffort,high",
			"reasoningEffort,xhigh", "reasoningEffort,max" })
	void effortOptionsWithAKnownLevelAreAccepted(String key, String level) {
		var launch = launch("codex", "gpt-6.1-sol", Map.of(key, level), AUTH, BASE_URL, IDLE);
		assertThat(launch.selection().options()).containsExactlyEntriesOf(Map.of(key, level));
	}

	static Stream<Map<String, String>> unsupportedOptions() {
		return Stream.of(
				Map.of("temperature", "1"), // Valid ModelSelection options that the worker must not forward.
				Map.of("maxTokens", "10"),
				Map.of("thinkingLevel", "high"), // A known level under a key the worker does not accept.
				Map.of("effort", "extreme"),
				Map.of("effort", "HIGH"),
				Map.of("reasoningEffort", "none"),
				Map.of("effort", "high", "seed", "7")); // One good option does not excuse a bad one.
	}

	@ParameterizedTest
	@MethodSource("unsupportedOptions")
	void optionKeysOrLevelsOutsideTheEffortAllowlistAreRefused(Map<String, String> options) {
		assertThatThrownBy(() -> launch("codex", "gpt-6.1-sol", options, AUTH, BASE_URL, IDLE))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Unsupported worker launch option");
	}

	static Stream<Duration> idleTimeoutsOutOfBounds() {
		return Stream.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(-1),
				Duration.ofHours(1).plusNanos(1), Duration.ofHours(2));
	}

	@ParameterizedTest
	@MethodSource("idleTimeoutsOutOfBounds")
	void idleTimeoutsThatAreNotPositiveOrExceedOneHourAreRefused(Duration idle) {
		assertThatThrownBy(() -> launch("codex", "gpt-6.1-sol", Map.of(), AUTH, BASE_URL, idle))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid worker idle timeout");
	}

	@ParameterizedTest
	@ValueSource(longs = { 1L, 60_000_000_000L, 3_600_000_000_000L })
	void idleTimeoutsFromOneNanosecondUpToOneHourAreAccepted(long nanos) {
		var idle = Duration.ofNanos(nanos);
		assertThat(launch("codex", "gpt-6.1-sol", Map.of(), AUTH, BASE_URL, idle).idleTimeout()).isEqualTo(idle);
	}

	@Test
	void homeDirectoriesAreStoredAbsoluteAndNormalized() {
		var launch = new WorkerLaunch(new ModelSelection(new ModelRef("codex", "gpt-6.1-sol")), AUTH, BASE_URL,
				temporary.resolve("elsewhere/../codex"), temporary.resolve("./user"), IDLE);
		assertThat(launch.codexHome()).isEqualTo(temporary.resolve("codex"));
		assertThat(launch.userHome()).isEqualTo(temporary.resolve("user"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankClasspathHasNoProductionClasspath(String blank) {
		assertThatThrownBy(() -> WorkerLaunch.productionClasspath(blank))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("No production classpath");
	}

	@Test
	void classpathOfOnlySeparatorsHasNoProductionClasspath() {
		String separators = File.pathSeparator + File.pathSeparator;
		assertThatThrownBy(() -> WorkerLaunch.productionClasspath(separators))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("No production classpath");
	}

	@Test
	void classpathOfOnlyProjectTestOutputHasNoProductionClasspath() {
		String classpath = String.join(File.pathSeparator, "/repo/agent-app/target/test-classes",
				"/repo/plugins/plugin-fs/target/test-classes");
		assertThatThrownBy(() -> WorkerLaunch.productionClasspath(classpath))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("No production classpath");
	}

	@Test
	void entriesBelowADirectoryNamedTestAreProductionAndOnlyTestOutputIsDropped() {
		String classpath = String.join(File.pathSeparator, "/home/test/.m2/lib.jar",
				"/work/tests/agent-app/target/classes", "/work/tests/agent-app/target/test-classes");
		assertThat(WorkerLaunch.productionClasspath(classpath)).isEqualTo(String.join(File.pathSeparator,
				"/home/test/.m2/lib.jar", "/work/tests/agent-app/target/classes"));
	}

	@Test
	void repositoryBelowADirectoryNamedTestStillHasAProductionClasspath() {
		String classpath = String.join(File.pathSeparator, "/test/repo/agent-app/target/classes",
				"/test/repo/agent-app/target/test-classes");
		assertThat(WorkerLaunch.productionClasspath(classpath)).isEqualTo("/test/repo/agent-app/target/classes");
	}

	@Test
	void filesystemRootEntryHasNoNameAndIsKept() {
		assertThat(WorkerLaunch.productionClasspath(File.separator)).isEqualTo(File.separator);
	}

	@Test
	void gradleStyleTestOutputDirectoriesAreStillDropped() {
		String classpath = String.join(File.pathSeparator, "/repo/build/classes/java/test",
				"/repo/build/resources/test", "/repo/build/classes/java/main", "/repo/build/tests");
		assertThat(WorkerLaunch.productionClasspath(classpath)).isEqualTo("/repo/build/classes/java/main");
	}
}
