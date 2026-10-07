package com.example.miniagent.core.approval;

import java.util.UUID;

/** Transport answer; remember is always restricted to this exact authority scope. */
public record ApprovalAnswer(UUID requestId, ApprovalDecision decision, boolean remember) {
	public static ApprovalAnswer parse(String text) {
		if (text == null) return null;
		String[] parts = text.strip().split("\\s+");
		if (parts.length != 2) return null;
		try {
			UUID id = UUID.fromString(parts[0]);
			return switch (parts[1].toLowerCase(java.util.Locale.ROOT)) {
				case "y", "yes" -> new ApprovalAnswer(id, ApprovalDecision.APPROVED, false);
				case "a", "always" -> new ApprovalAnswer(id, ApprovalDecision.APPROVED, true);
				case "n", "no" -> new ApprovalAnswer(id, ApprovalDecision.REJECTED, false);
				case "defer" -> new ApprovalAnswer(id, ApprovalDecision.DEFERRED, false);
				case "cancel" -> new ApprovalAnswer(id, ApprovalDecision.CANCELLED, false);
				default -> null;
			};
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
