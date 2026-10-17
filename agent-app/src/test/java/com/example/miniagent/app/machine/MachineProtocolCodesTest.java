package com.example.miniagent.app.machine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exact error codes of MachineProtocol; MachineProtocolTest only asserts that some Invalid is thrown. */
class MachineProtocolCodesTest {
	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
	private final MachineProtocol protocol = new MachineProtocol(new PrintStream(this.bytes, true, StandardCharsets.UTF_8));

	private static String request(String id, String method) {
		return "{\"v\":1,\"id\":\"" + id + "\",\"method\":\"" + method + "\"}";
	}

	private void assertRefused(String line, String code) {
		assertThatThrownBy(() -> this.protocol.parse(line)).as(line.length() > 80 ? line.substring(0, 80) : line)
				.isInstanceOf(MachineProtocol.Invalid.class).hasMessage(code);
	}

	private JsonNode emitted() {
		return JsonMapper.builder().build().readTree(this.bytes.toString(StandardCharsets.UTF_8));
	}

	@Test void malformedJsonIsMalformedJson() {
		for (String line : List.of("{\"v\":1,\"id\":\"a\",\"method\":\"prompt\"", "not json", "{'v':1}",
				"{\"v\":1,\"id\":\"a\",\"id\":\"b\",\"method\":\"prompt\"}",
				request("a", "prompt") + " {}"))
			assertRefused(line, "malformed_json");
	}

	@Test void linesThatAreNotAVersionOneObjectAreInvalidRequest() {
		for (String line : List.of("[]", "\"text\"", "42", "null",
				"{\"id\":\"a\",\"method\":\"prompt\"}",
				"{\"v\":2,\"id\":\"a\",\"method\":\"prompt\"}",
				"{\"v\":\"1\",\"id\":\"a\",\"method\":\"prompt\"}",
				"{\"v\":1.0,\"id\":\"a\",\"method\":\"prompt\"}",
				"{\"v\":true,\"id\":\"a\",\"method\":\"prompt\"}"))
			assertRefused(line, "invalid_request");
	}

	@Test void missingOrNonStringIdAndMethodAreInvalidRequest() {
		for (String line : List.of("{\"v\":1,\"method\":\"prompt\"}", "{\"v\":1,\"id\":5,\"method\":\"prompt\"}",
				"{\"v\":1,\"id\":\"a\"}", "{\"v\":1,\"id\":\"a\",\"method\":7}", "{\"v\":1,\"id\":\"a\",\"method\":null}"))
			assertRefused(line, "invalid_request");
	}

	@Test void nestedValuesAreInvalidRequest() {
		assertRefused("{\"v\":1,\"id\":\"a\",\"method\":\"prompt\",\"text\":[\"x\"]}", "invalid_request");
		assertRefused("{\"v\":1,\"id\":\"b\",\"method\":\"prompt\",\"text\":{\"x\":1}}", "invalid_request");
	}

	@Test void atMostEightFieldsAreAccepted() {
		String eight = "{\"v\":1,\"id\":\"a\",\"method\":\"prompt\",\"f1\":1,\"f2\":2,\"f3\":3,\"f4\":4,\"f5\":5}";
		String nine = "{\"v\":1,\"id\":\"b\",\"method\":\"prompt\",\"f1\":1,\"f2\":2,\"f3\":3,\"f4\":4,\"f5\":5,\"f6\":6}";
		assertThat(this.protocol.parse(eight).id()).isEqualTo("a");
		assertRefused(nine, "invalid_request");
	}

	@Test void idsOutsideTheDocumentedCharsetAndLengthAreInvalidId() {
		for (String id : List.of("", "-a", "_a", ".a", "a b", "a/b", "aé", "x".repeat(65)))
			assertRefused(request(id, "prompt"), "invalid_id");
	}

	@Test void idsInsideTheDocumentedCharsetAndLengthAreAccepted() {
		for (String id : List.of("0", "a.b:c_d-e", "x".repeat(64)))
			assertThat(this.protocol.parse(request(id, "prompt")).id()).isEqualTo(id);
	}

	@Test void methodLongerThanThirtyTwoCharactersIsInvalidMethod() {
		assertRefused(request("a", "m".repeat(33)), "invalid_method");
		assertThat(this.protocol.parse(request("b", "m".repeat(32))).method()).isEqualTo("m".repeat(32));
	}

	@Test void idIsValidatedBeforeMethodAndMethodBeforeDuplicate() {
		assertRefused(request("-bad", "m".repeat(33)), "invalid_id");
		this.protocol.parse(request("a", "prompt"));
		assertRefused(request("a", "m".repeat(33)), "invalid_method");
		assertRefused(request("a", "prompt"), "duplicate_id");
	}

	@Test void aRefusedLineDoesNotBurnItsId() {
		assertRefused(request("a", "m".repeat(33)), "invalid_method");
		assertThat(this.protocol.parse(request("a", "prompt")).id()).isEqualTo("a");
	}

	@Test void anIdIsBurnedOnceParsedEvenWhenAFieldCheckFailsLater() {
		var parsed = this.protocol.parse("{\"v\":1,\"id\":\"a\",\"method\":\"prompt\",\"text\":\"  \"}");
		assertThatThrownBy(() -> parsed.text("text")).isInstanceOf(MachineProtocol.Invalid.class).hasMessage("invalid_field");
		assertRefused(request("a", "prompt"), "duplicate_id");
	}

	@Test void textFieldsMustBePresentNonBlankStrings() {
		int index = 0;
		for (String body : List.of("", ",\"text\":\"\"", ",\"text\":\"   \"", ",\"text\":5", ",\"text\":null", ",\"text\":true")) {
			var parsed = this.protocol.parse("{\"v\":1,\"id\":\"t" + index++ + "\",\"method\":\"prompt\"" + body + "}");
			assertThatThrownBy(() -> parsed.text("text")).as(body).isInstanceOf(MachineProtocol.Invalid.class)
					.hasMessage("invalid_field");
		}
	}

	@Test void textIsAcceptedUpToExactlyThirtyTwoKibiBytesAndCountedInBytes() {
		String ascii = "a".repeat(MachineProtocol.MAX_TEXT_BYTES);
		assertThat(this.protocol.parse("{\"v\":1,\"id\":\"ok\",\"method\":\"prompt\",\"text\":\"" + ascii + "\"}")
				.text("text")).isEqualTo(ascii);
		var over = this.protocol.parse("{\"v\":1,\"id\":\"over\",\"method\":\"prompt\",\"text\":\"" + ascii + "a\"}");
		assertThatThrownBy(() -> over.text("text")).hasMessage("invalid_field");
		// 10922 Hangul syllables (3 bytes each) plus two ASCII bytes also fill exactly 32768 bytes.
		String hangul = "가".repeat(10922) + "ab";
		assertThat(this.protocol.parse("{\"v\":1,\"id\":\"hangul\",\"method\":\"prompt\",\"text\":\"" + hangul + "\"}")
				.text("text")).isEqualTo(hangul);
	}

	@Test void onlyRefusesEveryFieldOutsideTheMethodsSchema() {
		var parsed = this.protocol.parse("{\"v\":1,\"id\":\"a\",\"method\":\"prompt\",\"text\":\"hi\",\"extra\":1}");
		assertThatThrownBy(() -> parsed.only("text")).isInstanceOf(MachineProtocol.Invalid.class).hasMessage("unknown_field");
		var bare = this.protocol.parse("{\"v\":1,\"id\":\"b\",\"method\":\"shutdown\",\"text\":\"hi\"}");
		assertThatThrownBy(bare::only).hasMessage("unknown_field");
		var exact = this.protocol.parse("{\"v\":1,\"id\":\"c\",\"method\":\"prompt\",\"text\":\"hi\"}");
		assertThatCode(() -> exact.only("text")).doesNotThrowAnyException();
	}

	@Test void aLineLongerThanTheLimitHandedToParseIsLineTooLargeCountedInBytes() {
		assertRefused("x".repeat(MachineProtocol.MAX_LINE_BYTES + 1), "line_too_large");
		// 21846 Hangul syllables are 65538 bytes but only 21846 characters.
		assertRefused("가".repeat(21846), "line_too_large");
		assertRefused("x".repeat(MachineProtocol.MAX_LINE_BYTES), "malformed_json");
	}

	@Test void anUnterminatedFinalLineIsReadBeforeEof() throws Exception {
		var input = new ByteArrayInputStream("first\nsecond".getBytes(StandardCharsets.UTF_8));
		assertThat(this.protocol.readLine(input)).isEqualTo("first");
		assertThat(this.protocol.readLine(input)).isEqualTo("second");
		assertThat(this.protocol.readLine(input)).isNull();
	}

	@Test void aLineOfExactlyTheByteLimitIsRead() throws Exception {
		String line = "a".repeat(MachineProtocol.MAX_LINE_BYTES);
		var input = new ByteArrayInputStream((line + "\nnext\n").getBytes(StandardCharsets.UTF_8));
		assertThat(this.protocol.readLine(input)).isEqualTo(line);
		assertThat(this.protocol.readLine(input)).isEqualTo("next");
	}

	@Test void emitWithoutCorrelationWritesNullEnvelopeFieldsBesideThePayload() {
		this.protocol.emit("ready", null, Map.of("mode", "rpc"));
		JsonNode frame = emitted();
		assertThat(frame.propertyNames()).containsExactlyInAnyOrder("v", "type", "requestId", "executionId",
				"sessionId", "turnId", "mode");
		assertThat(frame.path("v").asInt()).isEqualTo(1);
		assertThat(frame.path("type").asString()).isEqualTo("ready");
		assertThat(frame.path("mode").asString()).isEqualTo("rpc");
		for (String key : List.of("requestId", "executionId", "sessionId", "turnId"))
			assertThat(frame.get(key).isNull()).as(key).isTrue();
	}

	@Test void payloadKeysCannotShadowTheEnvelopeKeys() {
		this.protocol.emit("event", new MachineProtocol.Correlation("r", "e", "s", "t"),
				Map.of("type", "hijacked", "requestId", "other"));
		JsonNode frame = emitted();
		assertThat(frame.path("type").asString()).isEqualTo("event");
		assertThat(frame.path("requestId").asString()).isEqualTo("r");
	}

	@Test void payloadKeysCannotFillTheNullEnvelopeKeysOfAnUncorrelatedFrame() {
		this.protocol.emit("ready", null, Map.of("requestId", "forged", "mode", "rpc"));
		JsonNode frame = emitted();
		assertThat(frame.get("requestId").isNull()).isTrue();
		assertThat(frame.path("mode").asString()).isEqualTo("rpc");
	}
}
