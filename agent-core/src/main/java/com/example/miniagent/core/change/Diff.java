package com.example.miniagent.core.change;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Patch text is byte faithful UTF8. Omitted content is an explicit nonreviewable result. */
public record Diff(Fingerprint before, Fingerprint after, Content content, String patch, String detail) {
	public enum Content {
		TEXT, METADATA_ONLY, BINARY, TOO_LARGE, LINE_LIMIT, OPERATION_LIMIT, UNSUPPORTED, INVALID_UTF8
	}

	public Diff {
		Objects.requireNonNull(before);
		Objects.requireNonNull(after);
		Objects.requireNonNull(content);
		Objects.requireNonNull(patch);
		Objects.requireNonNull(detail);
		if (!before.relativePath().equals(after.relativePath()) || before.equals(after)) {
			throw new IllegalArgumentException("Diff must change one matching path");
		}
		if (content != Content.TEXT && content != Content.METADATA_ONLY && !patch.isEmpty()) {
			throw new IllegalArgumentException("Omitted diff cannot carry a partial patch");
		}
		if (content == Content.TEXT && (patch.isEmpty()
				|| before.kind() != EntryKind.REGULAR_FILE && before.kind() != EntryKind.MISSING
				|| after.kind() != EntryKind.REGULAR_FILE && after.kind() != EntryKind.MISSING)) {
			throw new IllegalArgumentException("Text diff requires a patch and regular-file endpoints");
		}
		if (content == Content.METADATA_ONLY) {
			String empty = Fingerprinter.sha256(new byte[0]);
			String oldDigest = before.kind() == EntryKind.MISSING ? empty : before.sha256();
			String newDigest = after.kind() == EntryKind.MISSING ? empty : after.sha256();
			if (before.size() != after.size() || !oldDigest.equals(newDigest)) {
				throw new IllegalArgumentException("Metadata-only diff cannot omit changed bytes");
			}
		}
	}

	public String relativePath() { return this.before.relativePath(); }
	public boolean reviewable() { return this.content == Content.TEXT || this.content == Content.METADATA_ONLY; }
	public byte[] patchBytes() { return this.patch.getBytes(StandardCharsets.UTF_8); }
	public String digest() { return DiffRenderer.digest(List.of(this)); }
}
