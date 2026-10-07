package com.example.miniagent.core.delegation;

import java.nio.file.Path;
import java.util.Objects;
import java.util.List;

import com.example.miniagent.core.change.ChangeOwner;
import com.example.miniagent.core.change.VerificationEvidence;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.run.SideEffectClass;

/** Evidence origin is part of its type; narrative cannot stand in for a tool or process outcome. */
public sealed interface ChildEvidence {
	record Rejected(DelegationEvidenceCollector.Code code) implements ChildEvidence {
		public Rejected { Objects.requireNonNull(code); }
	}
	/** Constructed by the parent collector, never by a model response decoder. */
	final class Collected implements ChildEvidence {
		private final ChildRole role;
		private final ChangeOwner owner;
		private final String revision;
		private final String configurationDigest;
		private final List<ChildEvidence> facts;

		Collected(ChildRole role, ChangeOwner owner, String revision, String configurationDigest,
				List<ChildEvidence> facts) {
			this.role = Objects.requireNonNull(role);
			this.owner = Objects.requireNonNull(owner);
			requireDigest(revision);
			requireDigest(configurationDigest);
			this.revision = revision;
			this.configurationDigest = configurationDigest;
			this.facts = List.copyOf(facts);
		}
		public ChildRole role() { return this.role; }
		public ChangeOwner owner() { return this.owner; }
		public String revision() { return this.revision; }
		public String configurationDigest() { return this.configurationDigest; }
		public List<ChildEvidence> facts() { return this.facts; }
	}

	record Proposal(java.util.UUID changeSetId, String digest, java.util.UUID bindingId) implements ChildEvidence {
		public Proposal {
			Objects.requireNonNull(changeSetId);
			Objects.requireNonNull(bindingId);
			requireDigest(digest);
		}
	}

	record ModelNarrative(String text) implements ChildEvidence {
		public ModelNarrative { Objects.requireNonNull(text); }
	}

	record ToolResult(String toolCallId, String toolName, String resultSha256,
			SideEffectClass effect, Outcome outcome) implements ChildEvidence {
		public enum Outcome { FINISHED, FAILED, UNKNOWN }
		public ToolResult {
			Objects.requireNonNull(toolCallId);
			Objects.requireNonNull(toolName);
			requireDigest(resultSha256);
			Objects.requireNonNull(effect);
			Objects.requireNonNull(outcome);
			if (toolCallId.isBlank() || toolName.isBlank()) throw new IllegalArgumentException("Missing tool identity");
		}
	}

	record Source(Path path, String sha256) implements ChildEvidence {
		public Source {
			Objects.requireNonNull(path);
			if (!path.isAbsolute() || !path.normalize().equals(path)) {
				throw new IllegalArgumentException("Source path must be absolute and normalized");
			}
			requireDigest(sha256);
		}
	}

	record Verification(VerificationRunner.Code outcome, VerificationEvidence evidence) implements ChildEvidence {
		public Verification { Objects.requireNonNull(outcome); Objects.requireNonNull(evidence); }
	}

	private static void requireDigest(String digest) {
		if (digest == null || !digest.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid SHA-256 digest");
		}
	}
}
