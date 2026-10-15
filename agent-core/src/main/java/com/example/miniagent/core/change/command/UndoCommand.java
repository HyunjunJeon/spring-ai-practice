package com.example.miniagent.core.change.command;

import java.io.IOException;
import java.util.function.Supplier;

import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.change.ApplyEngine;
import com.example.miniagent.core.change.ChangeStatus;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.change.RecoveryScanner;
import com.example.miniagent.core.change.UndoEngine;
import org.springframework.context.ApplicationEventPublisher;
import java.util.UUID;

/** The sole undo command: explicit managed identity, or legacy no-argument SPI. */
public final class UndoCommand implements SlashCommand {
	@FunctionalInterface
	public interface LegacyUndo {
		boolean undo(SlashCommand.CommandContext context);
	}

	private final Supplier<ChangeStore> stores;
	private final Supplier<ApplyEngine> apply;
	private final Supplier<UndoEngine> undo;
	private final Supplier<RecoveryScanner> scanners;
	private final Supplier<LegacyUndo> legacy;
	private final ApplicationEventPublisher events;

	public UndoCommand(Supplier<ChangeStore> stores, Supplier<ApplyEngine> apply, Supplier<UndoEngine> undo,
			Supplier<RecoveryScanner> scanners, Supplier<LegacyUndo> legacy, ApplicationEventPublisher events) {
		this.stores = stores; this.apply = apply; this.undo = undo; this.scanners = scanners; this.legacy = legacy;
		this.events = events;
	}
	@Override public String name() { return "undo"; }
	@Override public String description() { return "[UUID] undo managed change, or last legacy turn when omitted"; }
	@Override public void execute(String args, CommandContext context) { executeWithStatus(args, context); }

	@Override public boolean executeWithStatus(String args, CommandContext context) {
		ChangeStore store = null;
		UUID id = null;
		try {
			if (args.isBlank()) {
				var legacyUndo = this.legacy.get();
				if (legacyUndo == null) throw new IllegalArgumentException("Legacy undo is unavailable");
				return legacyUndo.undo(context);
			}
			id = ChangesCommand.id(args);
			store = this.stores.get();
			var snapshot = store.read().get(id);
			var set = snapshot.changeSet();
			ChangesCommand.summary(snapshot, context.console());
			CommandObservation.publish(this.events, snapshot, null, null);
			var scanner = this.scanners.get();
			ChangesCommand.recovery(scanner.scan(set.changeSetId(), set.owner(), snapshot.digest()), context.console());
			var proof = this.apply.get().confirmExclusiveOwnership(context.console(),
					set.changeSetId(), set.owner(), snapshot.digest());
			var result = this.undo.get().undo(set.changeSetId(), set.owner(), snapshot.digest(), proof);
			ChangesCommand.summary(result, context.console());
			CommandObservation.publish(this.events, result, null, null);
			var report = scanner.scan(set.changeSetId(), set.owner(), snapshot.digest());
			ChangesCommand.recovery(report, context.console());
			return result.changeSet().status() == ChangeStatus.UNDONE && !report.hasConflicts();
		}
		catch (IOException | RuntimeException ex) {
			CommandObservation.failed(this.events, store, id, ex);
			return ChangesCommand.failure(ex, context.console());
		}
	}
}
