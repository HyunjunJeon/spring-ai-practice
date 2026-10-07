package com.example.miniagent.core.delegation;

public enum ChildStatus {
	QUEUED, RUNNING, SUCCEEDED, FAILED, SKIPPED, CANCELLED, TIMED_OUT;

	public boolean terminal() {
		return this != QUEUED && this != RUNNING;
	}
}
