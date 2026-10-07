package com.example.miniagent.core.change.command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.change.Approval;
import com.example.miniagent.core.change.ApplyEngine;
import com.example.miniagent.core.change.ChangeStatus;
import com.example.miniagent.core.change.ChangeStore;
import org.springframework.context.ApplicationEventPublisher;

/** Shared durable approval followed by a separate, console-issued capability. */
public final class ApplyCommand implements SlashCommand {
	private final Supplier<ChangeStore> stores;
	private final Supplier<ApplyEngine> engines;
	private final ApprovalBroker broker;
	private final Clock clock;
	private final ApplicationEventPublisher events;
	private final String actor = "console-" + UUID.randomUUID();

	public ApplyCommand(Supplier<ChangeStore> stores, Supplier<ApplyEngine> engines, ApprovalBroker broker, Clock clock,
			ApplicationEventPublisher events) {
		this.stores = stores; this.engines = engines; this.broker = broker; this.clock = clock;
		this.events = events;
	}
	@Override public String name() { return "apply"; }
	@Override public String description() { return "UUID review, approve and exclusively apply a proposal"; }
	@Override public void execute(String args, CommandContext context) { executeWithStatus(args, context); }

	@Override public boolean executeWithStatus(String args, CommandContext context) {
		ChangeStore store = null;
		UUID id = null;
		try {
			id = ChangesCommand.id(args);
			store = this.stores.get();
			var decisionStore = store;
			var snapshot = store.read().get(id);
			var set = snapshot.changeSet();
			ChangesCommand.proposal(snapshot, context.console());
			CommandObservation.publish(this.events, snapshot, null, null);
			if (set.diffs().stream().anyMatch(diff -> !diff.reviewable()))
				throw new IllegalArgumentException("Proposal includes unreviewable content");
			if (set.status() == ChangeStatus.PENDING_APPROVAL) {
				var request = new ApprovalRequest(UUID.randomUUID(), set.owner().runId(), set.owner().attemptId(),
						null, null, "apply-" + set.changeSetId(), snapshot.digest(), snapshot.digest());
				var future = this.broker.requestChange(request, decision -> {
					ChangeStore.Snapshot decided;
					try (var lease = decisionStore.acquire()) {
						decided = lease.decide(set.changeSetId(), set.owner(), snapshot.digest(),
								new Approval(request.requestId(), set.changeSetId(), snapshot.digest(),
										set.owner(), this.actor, decision, this.clock.instant()));
					}
					catch (IOException ex) { throw new UncheckedIOException(ex); }
					CommandObservation.publish(this.events, decided, null, null);
				});
				ApprovalDecision decision;
				try { decision = future.get(); }
				catch (InterruptedException ex) {
					// Clear only while exact-request cancellation publishes the durable decision.
					Thread.interrupted();
					try { this.broker.cancel(request); }
					finally { Thread.currentThread().interrupt(); }
					context.console().error("CANCELLED: approval wait interrupted");
					return false;
				}
				if (decision != ApprovalDecision.APPROVED) {
					context.console().println("approval=" + decision);
					ChangesCommand.summary(store.read().get(set.changeSetId()), context.console());
					return false;
				}
			}
			else if (set.status() != ChangeStatus.APPROVED)
				throw new IllegalArgumentException("Proposal is not pending or approved: " + set.status());
			var engine = this.engines.get();
			var proof = engine.confirmExclusiveOwnership(context.console(), set.changeSetId(), set.owner(), snapshot.digest());
			var result = engine.apply(set.changeSetId(), set.owner(), snapshot.digest(), proof);
			ChangesCommand.summary(result, context.console());
			CommandObservation.publish(this.events, result, null, null);
			return result.changeSet().status() == ChangeStatus.APPLIED;
		}
		catch (ExecutionException ex) {
			CommandObservation.failed(this.events, store, id, ex);
			context.console().error("Approval failed: " + ex.getCause());
			return false;
		}
		catch (IOException | RuntimeException ex) {
			CommandObservation.failed(this.events, store, id, ex);
			return ChangesCommand.failure(ex, context.console());
		}
	}
}
