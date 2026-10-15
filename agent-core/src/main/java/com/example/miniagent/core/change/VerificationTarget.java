package com.example.miniagent.core.change;

import java.util.Objects;
import java.util.UUID;

/** Distinguishes a published proposal from a read-only, unchanged stage snapshot. */
public record VerificationTarget(UUID id, ChangeOwner owner, String digest, String revision,
		boolean readOnly) {
	public VerificationTarget {
		Objects.requireNonNull(id);
		Objects.requireNonNull(owner);
		Fingerprint.requireDigest(digest);
		Fingerprint.requireDigest(revision);
		if (readOnly && !digest.equals(revision)) throw new IllegalArgumentException("Snapshot digest mismatch");
	}
}
