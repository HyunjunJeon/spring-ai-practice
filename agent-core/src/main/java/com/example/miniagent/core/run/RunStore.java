package com.example.miniagent.core.run;

import java.util.List;

public interface RunStore {
	RunWriter create(RunSpec spec) throws RunStoreException;
	RunWriter openWriter(RunId id) throws RunStoreException;
	RunRecord read(RunId id) throws RunStoreException;
	List<RunId> list() throws RunStoreException;
	/** Direct children of parent by spec lineage; callers read each child in full and fail closed on its damage. */
	List<RunId> children(RunId parent) throws RunStoreException;
	OpaqueRun export(RunId id) throws RunStoreException;

	record OpaqueRun(RunId runId, String specJson, String eventsLog) {}
}
