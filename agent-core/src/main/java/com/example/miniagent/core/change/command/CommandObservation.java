package com.example.miniagent.core.change.command;

import java.io.IOException;
import java.util.UUID;

import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.change.VerificationRunner;
import org.springframework.context.ApplicationEventPublisher;

/** Shared boundary observation; only persisted states are sent to renderers. */
final class CommandObservation {
	private CommandObservation() {}

	static void publish(ApplicationEventPublisher events, ChangeStore.Snapshot snapshot,
			VerificationRunner.Code verification, UUID evidenceId) {
		var set = snapshot.changeSet();
		events.publishEvent(new TraceEvent.ChangeStateChanged(set.changeSetId(), set.owner(),
				snapshot.digest(), set.status(), verification, evidenceId));
	}

	static void failed(ApplicationEventPublisher events, ChangeStore store, UUID id, Exception failure) {
		if (store == null || id == null) return;
		boolean interrupted = Thread.interrupted();
		try { publish(events, store.read().get(id), null, null); }
		catch (IOException | RuntimeException reporting) { failure.addSuppressed(reporting); }
		finally { if (interrupted) Thread.currentThread().interrupt(); }
	}
}
