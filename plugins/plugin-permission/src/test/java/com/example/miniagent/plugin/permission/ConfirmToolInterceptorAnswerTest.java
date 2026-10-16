package com.example.miniagent.plugin.permission;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.ToolInterceptor.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** 대화형 콘솔에서 사용자가 입력한 답이 도구 실행·거부·세션 허용으로 어떻게 해석되는지 확인한다. */
class ConfirmToolInterceptorAnswerTest {

	private static final String LINE = System.lineSeparator();

	private static final String REJECTED = "Permission denied: 'write_file' was not executed because the user "
			+ "rejected it. Do not retry it; ask the user how to proceed.";

	private final ByteArrayOutputStream output = new ByteArrayOutputStream();

	private ConfirmToolInterceptor interceptor(String answers, boolean interactive, boolean allowWhenNonInteractive) {
		var console = new AgentConsole(new BufferedReader(new StringReader(answers)),
				new PrintStream(this.output, true, StandardCharsets.UTF_8), interactive);
		return new ConfirmToolInterceptor(List.of("read_file"), List.of("bash"), console, allowWhenNonInteractive);
	}

	private ConfirmToolInterceptor interactive(String answers) {
		return interceptor(answers, true, false);
	}

	private static String run(ConfirmToolInterceptor interceptor, String tool, String input) {
		var definition = ToolDefinition.builder().name(tool).description(tool).inputSchema("{}").build();
		return interceptor.intercept(new ToolInvocation(definition, input, null), invocation -> "ran");
	}

	private String printed() {
		return this.output.toString(StandardCharsets.UTF_8);
	}

	private int promptCount() {
		return StringUtils.countOccurrencesOf(printed(), "[permission]");
	}

	@Test
	void yesRunsTheToolOnceAndAsksAgainNextTime() {
		ConfirmToolInterceptor interceptor = interactive("y\nn\n");

		assertThat(run(interceptor, "write_file", "{}")).isEqualTo("ran");
		assertThat(run(interceptor, "write_file", "{}")).isEqualTo(REJECTED);
		assertThat(promptCount()).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = { "y", "Y", "yes", "YES", "  yes  " })
	void yesInAnyCaseRunsTheTool(String answer) {
		assertThat(run(interactive(answer + "\n"), "write_file", "{}")).isEqualTo("ran");
	}

	@ParameterizedTest
	@ValueSource(strings = { "n", "no", "N", "", "   ", "maybe", "later" })
	void anythingThatIsNotYesOrAlwaysRejectsTheTool(String answer) {
		assertThat(run(interactive(answer + "\n"), "write_file", "{}")).isEqualTo(REJECTED);
	}

	@Test
	void endOfInputRejectsTheToolInsteadOfRunningIt() {
		// 입력이 끝나면 readLine 은 null 을 돌려준다. 사용자가 없으면 거부하는 것이 안전하다.
		assertThat(run(interactive(""), "write_file", "{}")).isEqualTo(REJECTED);
	}

	@Test
	void endOfInputDoesNotRememberAnything() {
		ConfirmToolInterceptor interceptor = interactive("");

		run(interceptor, "write_file", "{}");

		assertThat(run(interceptor, "write_file", "{}")).isEqualTo(REJECTED);
		assertThat(promptCount()).isEqualTo(2);
	}

	@Test
	void allowNonInteractiveRunsUnlistedToolsWithoutAnyPrompt() {
		ConfirmToolInterceptor interceptor = interceptor("n\n", false, true);

		assertThat(run(interceptor, "write_file", "{}")).isEqualTo("ran");
		assertThat(printed()).isEmpty();
	}

	@Test
	void nonInteractiveWithoutTheFlagDeniesWithoutAnyPrompt() {
		ConfirmToolInterceptor interceptor = interceptor("y\n", false, false);

		assertThat(run(interceptor, "write_file", "{}"))
			.startsWith("Permission denied: 'write_file' was not executed because non-interactive mode");
		assertThat(printed()).isEmpty();
	}

	@Test
	void allowNonInteractiveFlagDoesNotSkipTheQuestionOnAnInteractiveConsole() {
		ConfirmToolInterceptor interceptor = interceptor("n\n", true, true);

		assertThat(run(interceptor, "write_file", "{}")).isEqualTo(REJECTED);
		assertThat(promptCount()).isEqualTo(1);
	}

	@Test
	void promptNamesTheToolAndTheInputAndOffersTheThreeAnswers() {
		run(interactive("y\n"), "write_file", "{\"path\":\"a.txt\"}");

		assertThat(printed()).isEqualTo("\n[permission] write_file {\"path\":\"a.txt\"}" + LINE
				+ "  허용할까요? [y]es / [n]o / [a]lways(이 세션 동안): ");
	}

	@Test
	void promptOfARememberExactInputToolSaysAlwaysCoversTheSameInputOnly() {
		run(interactive("y\n"), "bash", "{\"command\":\"ls\"}");

		assertThat(printed()).endsWith("  허용할까요? [y]es / [n]o / [a]lways(이 세션 동안 같은 입력만): ");
	}

	@Test
	void promptShowsMultilineInputOnOneLine() {
		run(interactive("y\n"), "write_file", "{\n  \"path\":\t\"a.txt\",\r\n  \"content\": \"x\"\n}");

		assertThat(printed()).contains("[permission] write_file { \"path\": \"a.txt\", \"content\": \"x\" }" + LINE);
	}

	@Test
	void promptKeepsAnInputOfExactlyThreeHundredCharacters() {
		String input = "x".repeat(300);

		run(interactive("y\n"), "write_file", input);

		assertThat(printed()).contains("[permission] write_file " + input + LINE).doesNotContain("…");
	}

	@Test
	void promptCutsALongerInputAtThreeHundredCharactersWithAnEllipsis() {
		String input = "x".repeat(300) + "SECRET-TAIL";

		run(interactive("y\n"), "write_file", input);

		assertThat(printed()).contains("[permission] write_file " + "x".repeat(300) + "…" + LINE)
			.doesNotContain("SECRET-TAIL");
	}

	@Test
	void promptMeasuresTheInputAfterCollapsingWhitespace() {
		// 공백을 접으면 3자뿐이므로 원문이 300자를 넘어도 잘리지 않는다.
		run(interactive("y\n"), "write_file", "a" + " ".repeat(400) + "b");

		assertThat(printed()).contains("[permission] write_file a b" + LINE).doesNotContain("…");
	}

	@ParameterizedTest
	@ValueSource(strings = { "a", "A", "always", "ALWAYS", "  Always  " })
	void alwaysCoversTheWholeToolForTheRestOfTheSession(String answer) {
		// 두 번째 호출에 줄 답이 없다. 다시 물으면 입력 끝(null)이라 거부되므로, 실행되었다면 기억한 것이다.
		ConfirmToolInterceptor interceptor = interactive(answer + "\n");

		assertThat(run(interceptor, "write_file", "{\"path\":\"a.txt\"}")).isEqualTo("ran");
		assertThat(run(interceptor, "write_file", "{\"path\":\"b.txt\"}")).isEqualTo("ran");
		assertThat(promptCount()).isEqualTo(1);
	}

	@Test
	void alwaysForOneToolDoesNotCoverAnotherTool() {
		ConfirmToolInterceptor interceptor = interactive("always\nn\n");

		assertThat(run(interceptor, "write_file", "{}")).isEqualTo("ran");
		assertThat(run(interceptor, "edit_file", "{}")).startsWith("Permission denied: 'edit_file'");
	}

	@Test
	void alwaysTypedInFullForBashCoversOnlyTheSameCommand() {
		ConfirmToolInterceptor interceptor = interactive("always\nn\n");

		assertThat(run(interceptor, "bash", "{\"command\":\"ls\"}")).isEqualTo("ran");
		assertThat(run(interceptor, "bash", "{\"command\":\"ls\"}")).isEqualTo("ran");
		assertThat(run(interceptor, "bash", "{\"command\":\"rm -rf build\"}")).startsWith("Permission denied");
	}

	@ParameterizedTest
	@ValueSource(strings = { "abort", "absolutely not", "ask me later", "ahh" })
	void anAnswerThatMerelyStartsWithAIsRejectedNotRememberedAsAlways(String answer) {
		ConfirmToolInterceptor interceptor = interactive(answer + "\nn\n");

		assertThat(run(interceptor, "write_file", "{}")).isEqualTo(REJECTED);
		// 두 번째 호출이 실행되었다면 "abort" 가 세션 전체 허용으로 기억된 것이다.
		assertThat(run(interceptor, "write_file", "{}")).isEqualTo(REJECTED);
		assertThat(promptCount()).isEqualTo(2);
	}

}
