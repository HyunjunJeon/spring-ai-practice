package com.example.miniagent.core.delegation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunRecord;
import com.example.miniagent.core.run.RunStore;
import com.example.miniagent.core.run.RunStoreException;

/** Read-only historical lineage. The supplied run store remains the state authority. */
public final class DelegationRecovery {
	private final RunStore runs;

	public DelegationRecovery(RunStore runs) {
		this.runs = Objects.requireNonNull(runs);
	}

	public enum Relation { CHILD, FOREIGN, MISSING_LINEAGE }

	public record Inspection(Relation relation, RunRecord record) {}

	/** An unreadable record has unknown lineage, not ownership by the requested parent. */
	public record ReadFailure(RunId runId, RunStoreException.Code code, String detail) {}

	public record Projection(RunRecord parent, List<RunRecord> children, List<ReadFailure> unclassified) {
		public Projection {
			children = List.copyOf(children);
			unclassified = List.copyOf(unclassified);
		}
	}

	/**
	 * Returns direct children only. Neither completion nor interruption implies
	 * verified success or permission to resume. Each record is a separate observation.
	 */
	public Projection children(RunId parentRunId) throws RunStoreException {
		Objects.requireNonNull(parentRunId);
		RunRecord parent = this.runs.read(parentRunId);
		List<RunRecord> children = new ArrayList<>();
		List<ReadFailure> unclassified = new ArrayList<>();
		for (RunId id : this.runs.list().stream().sorted(Comparator.comparing(RunId::value)).toList()) {
			if (id.equals(parentRunId)) {
				continue;
			}
			try {
				RunRecord record = this.runs.read(id);
				if (parentRunId.equals(record.spec().parentRunId()) && record.spec().childId() != null) {
					children.add(record);
				}
			}
			catch (RunStoreException ex) {
				unclassified.add(new ReadFailure(id, ex.code(), ex.getMessage()));
			}
		}
		return new Projection(parent, children, unclassified);
	}

	/** Inspect an actual persisted RunId, not a ChildResult's never-started descriptor. */
	public Inspection inspectChild(RunId parentRunId, String childId, RunId runId) throws RunStoreException {
		Objects.requireNonNull(parentRunId);
		// Same safe identifier boundary as RunSpec; no UUID-format inference.
		new RunId(childId);
		RunRecord record = this.runs.read(Objects.requireNonNull(runId));
		Relation relation;
		if (record.spec().parentRunId() == null || record.spec().childId() == null) {
			relation = Relation.MISSING_LINEAGE;
		}
		else if (parentRunId.equals(record.spec().parentRunId()) && childId.equals(record.spec().childId())) {
			relation = Relation.CHILD;
		}
		else {
			relation = Relation.FOREIGN;
		}
		return new Inspection(relation, record);
	}

	/**
	 * Exact opaque stored metadata, including approval payloads. Not validated
	 * evidence, a live permission grant, or an atomic snapshot with an earlier read.
	 */
	public RunStore.OpaqueRun storedMetadata(RunId runId) throws RunStoreException {
		return this.runs.export(Objects.requireNonNull(runId));
	}
}
