package com.example.miniagent.core.change;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Raw bytes and POSIX permission bits, with no target dereference for links. */
public record Fingerprint(String relativePath, EntryKind kind, String sha256, long size,
		int posixMode, String linkText) {
	public Fingerprint {
		PathPolicy.validateRelative(relativePath);
		Objects.requireNonNull(kind, "kind");
		if (size < 0 || posixMode < 0 || posixMode > 0777) {
			throw new IllegalArgumentException("Invalid size or POSIX mode");
		}
		if (kind == EntryKind.MISSING) {
			if (sha256 != null || size != 0 || posixMode != 0 || linkText != null) {
				throw new IllegalArgumentException("Missing entry has no content or mode");
			}
		}
		else {
			requireDigest(sha256);
			if (kind == EntryKind.SYMBOLIC_LINK) {
				Objects.requireNonNull(linkText, "linkText");
				byte[] bytes = linkText.getBytes(StandardCharsets.UTF_8);
				if (linkText.isEmpty() || bytes.length != size || !Fingerprinter.sha256(bytes).equals(sha256)) {
					throw new IllegalArgumentException("Link fingerprint must describe link text");
				}
			}
			else if (linkText != null) {
				throw new IllegalArgumentException("Only links have link text");
			}
			if (kind == EntryKind.DIRECTORY && (size != 0 || !sha256.equals(Fingerprinter.sha256(new byte[0])))) {
				throw new IllegalArgumentException("Directory fingerprint has no content");
			}
		}
	}

	public static Fingerprint missing(String path) {
		return new Fingerprint(path, EntryKind.MISSING, null, 0, 0, null);
	}

	static void requireDigest(String digest) {
		if (digest == null || !digest.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid SHA-256 digest");
		}
	}
}
