package com.example.miniagent.core.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunId;

/** Immutable authority, not the displayed prose or mutable console session. */
public record ApprovalRequest(UUID requestId, RunId runId, String attemptId,
		String planId, String childId, String toolCallKey, String argsDigest, String diffDigest) {
	public ApprovalRequest {
		Objects.requireNonNull(requestId);
		Objects.requireNonNull(runId);
		requireId(attemptId);
		requireId(toolCallKey);
		if (planId != null) requireId(planId);
		if (childId != null) requireId(childId);
		requireDigest(argsDigest);
		if (diffDigest != null) requireDigest(diffDigest);
	}

	public static ApprovalRequest tool(RunContext context, String tool, String input) {
		return new ApprovalRequest(UUID.randomUUID(), context.spec().runId(), context.attemptId(),
				null, context.spec().childId(), tool, digest(input), null);
	}

	public static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new AssertionError(ex);
		}
	}

	private static void requireDigest(String value) {
		if (value == null || !value.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid SHA-256 digest");
		}
	}

	private static void requireId(String value) {
		if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
			throw new IllegalArgumentException("Invalid approval identity");
		}
	}
}
