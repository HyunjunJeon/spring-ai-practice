package com.example.miniagent.core.run;

import java.util.List;

public interface RunStore {
	RunWriter create(RunSpec spec) throws RunStoreException;
	RunWriter openWriter(RunId id) throws RunStoreException;
	RunRecord read(RunId id) throws RunStoreException;
	List<RunId> list() throws RunStoreException;
	OpaqueRun export(RunId id) throws RunStoreException;

	record OpaqueRun(RunId runId, String specJson, String eventsLog) {}
}
