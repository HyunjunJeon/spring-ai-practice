package com.example.miniagent.app.machine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MachineProtocolTest {
	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
	private final MachineProtocol protocol = new MachineProtocol(new PrintStream(this.bytes, true, StandardCharsets.UTF_8));

	@Test void rejectsDuplicateKeysIdsTrailingTokensAndNestedFields() {
		this.protocol.parse("{\"v\":1,\"id\":\"a\",\"method\":\"prompt\",\"text\":\"hello\"}");
		for (String line : new String[] {
				"{\"v\":1,\"id\":\"a\",\"method\":\"prompt\"}",
				"{\"v\":1,\"id\":\"b\",\"id\":\"c\",\"method\":\"prompt\"}",
				"{\"v\":1,\"id\":\"b\",\"method\":\"prompt\"} {}",
				"{\"v\":1,\"id\":\"b\",\"method\":\"prompt\",\"text\":{}}",
				"{\"v\":1,\"id\":\"" + "x".repeat(65) + "\",\"method\":\"prompt\"}" }) {
			assertThatThrownBy(() -> this.protocol.parse(line)).isInstanceOf(MachineProtocol.Invalid.class);
		}
	}

	@Test void boundsUtf8BytesAndRecoversAtNextLine() throws Exception {
		var input = new ByteArrayInputStream(("a".repeat(MachineProtocol.MAX_LINE_BYTES + 1)
				+ "\nvalid\n").getBytes(StandardCharsets.UTF_8));
		assertThatThrownBy(() -> this.protocol.readLine(input)).hasMessage("line_too_large");
		assertThat(this.protocol.readLine(input)).isEqualTo("valid");
		assertThat(this.protocol.readLine(input)).isNull();
		var request = this.protocol.parse("{\"v\":1,\"id\":\"utf8\",\"method\":\"prompt\",\"text\":\""
				+ "\uac00".repeat(11000) + "\"}");
		assertThatThrownBy(() -> request.text("text")).hasMessage("invalid_field");
		assertThatThrownBy(() -> this.protocol.readLine(new ByteArrayInputStream(new byte[] {(byte) 0xff, 10})))
				.hasMessage("invalid_utf8");
	}

	@Test void boundsConnectionIdMemoryWithoutAllowingReplays() {
		for (int i = 0; i < MachineProtocol.MAX_REQUESTS; i++)
			this.protocol.parse("{\"v\":1,\"id\":\"r" + i + "\",\"method\":\"shutdown\"}");
		assertThatThrownBy(() -> this.protocol.parse("{\"v\":1,\"id\":\"extra\",\"method\":\"shutdown\"}"))
				.hasMessage("request_limit");
		assertThatThrownBy(() -> this.protocol.parse("{\"v\":1,\"id\":\"r0\",\"method\":\"shutdown\"}"))
				.hasMessage("duplicate_id");
	}

	@Test void everyOutputIsOneVersionedJsonLineEvenWithHumanControlCharacters() {
		this.protocol.emit("event", new MachineProtocol.Correlation("r", "e", "s", "t"),
				Map.of("text", "line\nquote\"\u001b[31m"));
		String output = this.bytes.toString(StandardCharsets.UTF_8);
		assertThat(output.lines().count()).isEqualTo(1);
		var frame = JsonMapper.builder().build().readTree(output);
		assertThat(frame.path("v").asInt()).isEqualTo(1);
		assertThat(frame.path("requestId").asString()).isEqualTo("r");
		assertThat(output).doesNotContain("\u001b");
	}
}
