package com.example.miniagent.core.change;

/** No implicit retry after stale/partial effects; recovery must classify durable checkpoints. */
public enum ChangeStatus {
	PENDING_APPROVAL, APPROVED, REJECTED, SUPERSEDED, STALE, APPLYING, APPLIED,
	PARTIALLY_APPLIED, RECOVERY_REQUIRED, UNDONE, PARTIALLY_UNDONE;

	public boolean canTransitionTo(ChangeStatus next) {
		if (next == null || next == this) return false;
		return switch (this) {
			case PENDING_APPROVAL -> next == APPROVED || next == REJECTED || next == SUPERSEDED || next == STALE;
			case APPROVED -> next == APPLYING || next == REJECTED || next == SUPERSEDED || next == STALE;
			case APPLYING -> next == APPLIED || next == PARTIALLY_APPLIED || next == RECOVERY_REQUIRED || next == STALE;
			case APPLIED -> next == UNDONE || next == PARTIALLY_UNDONE || next == RECOVERY_REQUIRED;
			case PARTIALLY_APPLIED -> next == RECOVERY_REQUIRED || next == UNDONE || next == PARTIALLY_UNDONE;
			case RECOVERY_REQUIRED -> next == APPLIED || next == PARTIALLY_APPLIED || next == UNDONE || next == PARTIALLY_UNDONE;
			case PARTIALLY_UNDONE -> next == UNDONE || next == RECOVERY_REQUIRED;
			case REJECTED, SUPERSEDED, STALE, UNDONE -> false;
		};
	}
}
