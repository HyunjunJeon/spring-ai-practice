package com.example.miniagent.core.change;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One durable journal boundary. The store must force PREPARED before applying bytes. */
public record Checkpoint(UUID checkpointId, UUID changeSetId, ChangeOwner owner,
		Fingerprint before, Fingerprint after, BlobRef beforeBlob, BlobRef afterBlob,
		State state, Instant recordedAt) {
	public enum State { PREPARED, APPLIED, RESTORED, CONFLICT }

	public record BlobRef(String sha256, long size) {
		public BlobRef {
			Fingerprint.requireDigest(sha256);
			if (size < 0) throw new IllegalArgumentException("Invalid blob size");
		}
	}

	public Checkpoint {
		Objects.requireNonNull(checkpointId);
		Objects.requireNonNull(changeSetId);
		Objects.requireNonNull(owner);
		Objects.requireNonNull(before);
		Objects.requireNonNull(after);
		Objects.requireNonNull(state);
		Objects.requireNonNull(recordedAt);
		if (!before.relativePath().equals(after.relativePath()) || before.equals(after)) {
			throw new IllegalArgumentException("Checkpoint must change one path");
		}
		checkBlob(before, beforeBlob);
		checkBlob(after, afterBlob);
	}

	private static void checkBlob(Fingerprint fingerprint, BlobRef blob) {
		boolean hasContent = fingerprint.kind() == EntryKind.REGULAR_FILE || fingerprint.kind() == EntryKind.SYMBOLIC_LINK;
		if (hasContent ? blob == null || !blob.sha256().equals(fingerprint.sha256()) || blob.size() != fingerprint.size()
				: blob != null) {
			throw new IllegalArgumentException("Checkpoint blob does not match fingerprint");
		}
	}
}
