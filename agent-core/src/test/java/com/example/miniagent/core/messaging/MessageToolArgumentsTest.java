package com.example.miniagent.core.messaging;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tool arguments are not schema-validated before call(String, ToolContext), so a model that omits a
 * required key must get a bounded refusal that names the key, never a bare NullPointerException.
 * Every call runs with a valid captured actor, so only the arguments can be at fault.
 */
class MessageToolArgumentsTest {
	@TempDir Path root;
	private MessageToolTest.Fixture f;

	@BeforeEach void openFixture() throws Exception { f = new MessageToolTest.Fixture(root.toRealPath()); }
	@AfterEach void closeFixture() throws Exception { f.close(); }

	private String callAsTheCapturedActor(MessageTool tool, String arguments) throws Exception {
		try (var run = f.actor.open(); var turn = f.actor.turnContext().open()) {
			return tool.call(arguments, f.capturedBy(f.actor));
		}
	}

	private void assertRefusedWithoutSideEffects(MessageTool tool, String arguments, String message) {
		assertThatThrownBy(() -> callAsTheCapturedActor(tool, arguments))
			.isInstanceOf(IllegalArgumentException.class).hasMessage(message);
		verifyNoInteractions(f.runtime);
		assertThat(f.mailbox()).doesNotExist();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
		plan    | {"child":"child","id":"m-0","version":0,"body":"hello"}
		child   | {"plan":"plan","id":"m-0","version":0,"body":"hello"}
		id      | {"plan":"plan","child":"child","version":0,"body":"hello"}
		version | {"plan":"plan","child":"child","id":"m-0","body":"hello"}
		body    | {"plan":"plan","child":"child","id":"m-0","version":0}
		""")
	void sendNamesTheMissingKeyInsteadOfThrowingNullPointerException(String missing, String arguments) {
		assertRefusedWithoutSideEffects(f.send, arguments, "Missing message argument: " + missing);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
		plan   | {"child":"child","offset":0}
		child  | {"plan":"plan","offset":0}
		offset | {"plan":"plan","child":"child"}
		""")
	void readNamesTheMissingKeyInsteadOfThrowingNullPointerException(String missing, String arguments) {
		assertRefusedWithoutSideEffects(f.read, arguments, "Missing message argument: " + missing);
	}

	@ParameterizedTest @ValueSource(strings = {"{}", "[]", "null"})
	void anInputWithoutAnyKeyIsRefusedByBothTools(String arguments) {
		assertRefusedWithoutSideEffects(f.send, arguments, "Missing message argument: plan");
		assertRefusedWithoutSideEffects(f.read, arguments, "Missing message argument: plan");
	}

	@Test void completeReadArgumentsStillReturnTheEmptyPage() throws Exception {
		var page = MessageStore.JSON.readTree(callAsTheCapturedActor(f.read, MessageToolTest.READ));
		assertThat(page.get("version").asLong()).isZero();
		assertThat(page.get("nextOffset").asInt()).isZero();
		assertThat(page.get("entries").size()).isZero();
	}
}
