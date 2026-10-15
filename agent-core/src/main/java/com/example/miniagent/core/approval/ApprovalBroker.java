package com.example.miniagent.core.approval;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunId;

public interface ApprovalBroker {
	CompletableFuture<ApprovalDecision> request(ApprovalRequest request, RunContext context);
	CompletableFuture<ApprovalDecision> requestChange(ApprovalRequest request,
			java.util.function.Consumer<ApprovalDecision> durableDecision);
	boolean respond(ApprovalRequest request, ApprovalDecision decision);
	boolean cancel(ApprovalRequest request);
	boolean resolveDeferred(ApprovalRequest request, ApprovalDecision decision);
	void cancelRun(RunId runId);
	void abandonRun(RunId runId, Throwable failure);
	List<ApprovalRequest> pending();
	void restore(RunId runId, String authoritativeEvents);
}
