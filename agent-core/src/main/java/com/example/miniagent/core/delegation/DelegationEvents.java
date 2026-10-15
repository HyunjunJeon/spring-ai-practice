package com.example.miniagent.core.delegation;

import java.io.UncheckedIOException;
import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunStore;
import com.example.miniagent.core.run.RunStoreException;
import org.springframework.context.ApplicationEventPublisher;

/** Metadata from actual owned records; no inferred execution or verification success. */
public final class DelegationEvents {
	private DelegationEvents() {}
	public static void publish(DelegationController.Event event, ApplicationEventPublisher publisher, RunStore runs) {
		var view = event.view();
		try {
			if (view.parent() != null && view.parent().runId() != null) {
				var parent = runs.read(new RunId(view.parent().runId()));
				publisher.publishEvent(new TraceEvent.RunStateChanged(parent.spec().runId().value(),
						event.conversationId(), parent.attempts().isEmpty() ? null : parent.attempts().getLast().attemptId(),
						parent.state(), parent.spec().model()));
			}
			if (view.outcome() instanceof DefaultDelegationRuntime.Completed complete) {
				for (var child : complete.result().children()) {
					var stored = read(runs, child.runId());
					publisher.publishEvent(new TraceEvent.DelegationStateChanged(event.conversationId(), event.planId(),
							child.childId(), stored == null ? null : stored.spec().runId().value(),
							child.model(), child.status(), stored == null ? null : stored.state(), null,
							child.failure() == null ? child.evidence().stream()
									.filter(ChildEvidence.Rejected.class::isInstance)
									.map(ChildEvidence.Rejected.class::cast).map(e -> e.code().name())
									.findFirst().orElse(null) : child.failure().code().name()));
				}
				publisher.publishEvent(new TraceEvent.DelegationStateChanged(event.conversationId(), event.planId(),
						null, null, null, null, null, complete.result().status(), null));
			}
			else if (view.outcome() instanceof DefaultDelegationRuntime.Pending pending) {
				for (var child : pending.children()) {
					var record = child.record();
					publisher.publishEvent(new TraceEvent.DelegationStateChanged(event.conversationId(), event.planId(),
							child.childId(), record.spec().runId().value(), record.spec().model(),
							ChildStatus.RUNNING, record.state(), null, null));
				}
			}
			else {
				for (var child : view.plan().children()) publisher.publishEvent(new TraceEvent.DelegationStateChanged(
						event.conversationId(), event.planId(), child.childId(), null,
						view.parent() == null ? child.model() : child.effectiveModel(view.parent()),
						ChildStatus.QUEUED, null, null, null));
			}
		}
		catch (RunStoreException ex) { throw new UncheckedIOException(ex); }
	}
	private static com.example.miniagent.core.run.RunRecord read(RunStore runs, RunId id) throws RunStoreException {
		try { return runs.read(id); }
		catch (RunStoreException ex) {
			if (ex.code() == RunStoreException.Code.NOT_FOUND) return null;
			throw ex;
		}
	}
}
