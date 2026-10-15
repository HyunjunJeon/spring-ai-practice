package com.example.miniagent.app.machine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Version 1, one UTF-8 JSON object per line. Requests are flat objects with
 * {@code v:1, id, method}; method-specific fields are checked by MachineRunner.
 * IDs match {@code [A-Za-z0-9][A-Za-z0-9_.:-]{0,63}} and cannot be reused on a
 * connection. At most 4096 IDs, 65536 bytes per line, 32768 bytes per text field.
 * Oversize lines are drained to the next newline without retaining their contents.
 * Every output has v/type/requestId/executionId/sessionId/turnId (nullable before
 * dispatch). No exceptions, reflection, model options or credentials are serialized.
 */
public final class MachineProtocol {
	public static final int MAX_LINE_BYTES = 65536;
	public static final int MAX_TEXT_BYTES = 32768;
	public static final int MAX_REQUESTS = 4096;
	private final JsonMapper json = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
	private final PrintStream out;
	private final Set<String> ids = new HashSet<>();

	public MachineProtocol(PrintStream out) {
		this.out = out;
	}

	public record Correlation(String requestId, String executionId, String sessionId, String turnId) {}

	public record Request(String id, String method, JsonNode fields) {
		public String text(String name) {
			JsonNode value = fields.get(name);
			if (value == null || !value.isString() || value.asString().isBlank()
					|| value.asString().getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES)
				throw new Invalid("invalid_field");
			return value.asString();
		}

		public void only(String... names) {
			Set<String> allowed = new HashSet<>(Set.of("v", "id", "method"));
			allowed.addAll(Set.of(names));
			for (String name : fields.propertyNames())
				if (!allowed.contains(name)) throw new Invalid("unknown_field");
		}
	}

	public static final class Invalid extends RuntimeException {
		public Invalid(String code) { super(code); }
	}

	public Request parse(String line) {
		if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) throw new Invalid("line_too_large");
		JsonNode node;
		try { node = this.json.readTree(line); }
		catch (RuntimeException ex) { throw new Invalid("malformed_json"); }
		if (node == null || !node.isObject() || !node.path("v").isIntegralNumber()
				|| !node.path("v").toString().equals("1")) throw new Invalid("invalid_request");
		if (node.size() > 8) throw new Invalid("invalid_request");
		for (var value : node)
			if (value.isObject() || value.isArray()) throw new Invalid("invalid_request");
		String id = string(node, "id");
		if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")) throw new Invalid("invalid_id");
		String method = string(node, "method");
		if (method.length() > 32) throw new Invalid("invalid_method");
		if (this.ids.contains(id)) throw new Invalid("duplicate_id");
		if (this.ids.size() == MAX_REQUESTS) throw new Invalid("request_limit");
		this.ids.add(id);
		return new Request(id, method, node);
	}

	private static String string(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isString()) throw new Invalid("invalid_request");
		return value.asString();
	}

	/** null is EOF; an unterminated final line is accepted. */
	public String readLine(InputStream input) throws IOException {
		var bytes = new ByteArrayOutputStream();
		boolean oversized = false;
		int value;
		while ((value = input.read()) != -1 && value != '\n') {
			if (bytes.size() < MAX_LINE_BYTES) bytes.write(value);
			else oversized = true;
		}
		if (oversized) throw new Invalid("line_too_large");
		if (value == -1 && bytes.size() == 0) return null;
		try {
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
		}
		catch (CharacterCodingException ex) { throw new Invalid("invalid_utf8"); }
	}

	public synchronized void emit(String type, Correlation correlation, Map<String, ?> payload) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("v", 1);
		frame.put("type", type);
		frame.put("requestId", correlation == null ? null : correlation.requestId());
		frame.put("executionId", correlation == null ? null : correlation.executionId());
		frame.put("sessionId", correlation == null ? null : correlation.sessionId());
		frame.put("turnId", correlation == null ? null : correlation.turnId());
		frame.putAll(payload);
		this.out.println(this.json.writeValueAsString(frame));
		this.out.flush();
	}
}
