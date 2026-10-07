package com.example.miniagent.core.run;

public interface RunWriter extends AutoCloseable {
	RunRecord record();
	RunRecord append(RunEvent event) throws RunStoreException;
	@Override
	void close() throws RunStoreException;
}
