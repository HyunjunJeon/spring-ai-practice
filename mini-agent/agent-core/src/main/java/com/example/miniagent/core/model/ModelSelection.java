package com.example.miniagent.core.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Persistable, immutable generation configuration. No arbitrary provider/auth fields. */
public record ModelSelection(ModelRef ref, Map<String, String> options) {

	private static final Set<String> OPTION_KEYS = Set.of("effort", "reasoningEffort", "thinkingLevel",
			"temperature", "topP", "topK", "maxTokens", "seed", "stopSequences");

	public ModelSelection {
		Objects.requireNonNull(ref, "ref");
		Objects.requireNonNull(options, "options");
		TreeMap<String, String> snapshot = new TreeMap<>();
		options.forEach((key, value) -> {
			if (!OPTION_KEYS.contains(key) || value == null || value.isBlank() || value.length() > 4096) {
				throw new IllegalArgumentException("Unsupported or invalid generation option");
			}
			snapshot.put(key, value);
		});
		options = Collections.unmodifiableMap(snapshot);
	}

	public ModelSelection(ModelRef ref) {
		this(ref, Map.of());
	}

	public String optionsFingerprint() {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			this.options.forEach((key, value) -> {
				update(digest, key);
				update(digest, value);
			});
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	private static void update(MessageDigest digest, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

}
