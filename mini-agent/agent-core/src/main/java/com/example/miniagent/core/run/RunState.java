package com.example.miniagent.core.run;

public enum RunState {
	CREATED, RUNNING, WAITING_APPROVAL, INTERRUPTED, COMPLETED, FAILED, CANCELLED;

	public boolean terminal() {
		return this == COMPLETED || this == FAILED || this == CANCELLED;
	}
}
