package com.example.miniagent.core.change;

import java.util.List;
import java.util.UUID;

/** Read-only endpoint classification; missing observations are never invented. */
public record ConflictReport(UUID changeSetId, ChangeJournal.State journalState,
		ChangeStatus status, List<Entry> entries) {
	public enum Position { BEFORE, AFTER, OTHER }
	public enum Code { NONE, HUMAN_EDIT, UNSAFE_PATH, UNJOURNALED_EFFECT }
	public record Entry(String relativePath, UUID checkpointId, Checkpoint.State checkpointState,
			Fingerprint before, Fingerprint after, Fingerprint current, Position position, Code code) {}
	public ConflictReport { entries = List.copyOf(entries); }
	public boolean hasConflicts() { return this.entries.stream().anyMatch(e -> e.code() != Code.NONE); }
}
