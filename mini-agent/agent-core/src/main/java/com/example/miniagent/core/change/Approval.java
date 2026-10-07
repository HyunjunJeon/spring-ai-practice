package com.example.miniagent.core.change;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.example.miniagent.core.approval.ApprovalDecision;

/** Broker decision bound to the complete proposal, not display text. */
public record Approval(UUID approvalId, UUID changeSetId, String changeSetDigest, ChangeOwner owner,
		String actor, ApprovalDecision decision, Instant decidedAt) {
	public Approval {
		Objects.requireNonNull(approvalId);
		Objects.requireNonNull(changeSetId);
		Fingerprint.requireDigest(changeSetDigest);
		Objects.requireNonNull(owner);
		if (actor == null || actor.isBlank() || actor.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Invalid approval actor");
		}
		Objects.requireNonNull(decision);
		Objects.requireNonNull(decidedAt);
	}
}
