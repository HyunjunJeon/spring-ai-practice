package com.example.miniagent.core.change;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Evidence is valid only for the exact source and verification configuration. */
public record VerificationEvidence(UUID evidenceId, UUID changeSetId, ChangeOwner owner,
		List<String> argv, String cwd, String sourceCwd, String sourceRevision, String configurationDigest,
		String logSha256, Integer exitCode, boolean cancelled, Instant startedAt, Instant finishedAt) {
	public VerificationEvidence {
		Objects.requireNonNull(evidenceId);
		Objects.requireNonNull(changeSetId);
		Objects.requireNonNull(owner);
		argv = List.copyOf(argv);
		if (argv.isEmpty() || argv.getFirst().isEmpty() || argv.stream().anyMatch(a -> a.indexOf('\0') >= 0)) {
			throw new IllegalArgumentException("Invalid verification argv");
		}
		requireCwd(cwd);
		requireCwd(sourceCwd);
		Fingerprint.requireDigest(sourceRevision);
		Fingerprint.requireDigest(configurationDigest);
		Fingerprint.requireDigest(logSha256);
		Objects.requireNonNull(startedAt);
		Objects.requireNonNull(finishedAt);
		if (finishedAt.isBefore(startedAt) || (!cancelled && exitCode == null)) {
			throw new IllegalArgumentException("Invalid verification completion");
		}
	}

	public VerificationEvidence(UUID evidenceId, UUID changeSetId, ChangeOwner owner, List<String> argv,
			String cwd, String sourceRevision, String configurationDigest, String logSha256,
			Integer exitCode, boolean cancelled, Instant startedAt, Instant finishedAt) {
		this(evidenceId, changeSetId, owner, argv, cwd, cwd, sourceRevision, configurationDigest,
				logSha256, exitCode, cancelled, startedAt, finishedAt);
	}

	static void requireCwd(String cwd) {
		Path p = Path.of(cwd);
		if (!p.isAbsolute() || !p.normalize().toString().equals(cwd)) {
			throw new IllegalArgumentException("Cwd must be absolute and normalized");
		}
	}

	public boolean successful() { return !this.cancelled && Integer.valueOf(0).equals(this.exitCode); }
	public boolean validFor(String revision, String configuration) {
		return this.sourceRevision.equals(revision) && this.configurationDigest.equals(configuration);
	}
}
