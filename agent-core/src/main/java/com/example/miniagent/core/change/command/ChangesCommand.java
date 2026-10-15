package com.example.miniagent.core.change.command;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.change.ApplyEngine;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.change.ConflictReport;
import com.example.miniagent.core.change.RecoveryScanner;
import com.example.miniagent.core.change.VerificationRunner;
import org.springframework.context.ApplicationEventPublisher;

/** Read-only operator view of authoritative proposals and endpoint classifications. */
public final class ChangesCommand implements SlashCommand {
	private final Supplier<ChangeStore> stores;
	private final Supplier<RecoveryScanner> scanners;
	private final ApplicationEventPublisher events;

	public ChangesCommand(Supplier<ChangeStore> stores, Supplier<RecoveryScanner> scanners, ApplicationEventPublisher events) {
		this.stores = stores;
		this.scanners = scanners;
		this.events = events;
	}

	@Override public String name() { return "changes"; }
	@Override public String description() { return "[UUID] list changes or show full diff, recovery and evidence"; }
	@Override public void execute(String args, CommandContext context) { executeWithStatus(args, context); }

	@Override public boolean executeWithStatus(String args, CommandContext context) {
		try {
			var view = this.stores.get().read();
			context.console().println("journal=" + view.journalState() + " recoveryRequired=" + view.recoveryRequired());
			boolean conflicts = false;
			if (args.isBlank()) {
				for (var snapshot : view.changes()) {
					summary(snapshot, context.console());
					CommandObservation.publish(this.events, snapshot, null, null);
				}
			}
			else {
				var snapshot = view.get(id(args));
				proposal(snapshot, context.console());
				CommandObservation.publish(this.events, snapshot, null, null);
				var report = this.scanners.get().scan(snapshot.changeSet().changeSetId(),
						snapshot.changeSet().owner(), snapshot.digest());
				recovery(report, context.console());
				conflicts = report.hasConflicts();
			}
			return !view.recoveryRequired() && !conflicts;
		}
		catch (IOException | RuntimeException ex) { return failure(ex, context.console()); }
	}

	static UUID id(String args) { return UUID.fromString(args.strip()); }

	static void summary(ChangeStore.Snapshot snapshot, AgentConsole console) {
		var set = snapshot.changeSet();
		console.println("change=" + set.changeSetId() + " status=" + set.status() + " digest=" + snapshot.digest()
				+ " owner=" + set.owner() + " sourceCwd=" + set.cwd()
				+ " checkpoints=" + snapshot.checkpoints().size() + " evidence=" + snapshot.evidence().size());
	}

	static void proposal(ChangeStore.Snapshot snapshot, AgentConsole console) {
		summary(snapshot, console);
		var set = snapshot.changeSet();
		console.println("model=" + set.model() + " bindings=" + set.bindingIds() + " approval=" + set.approval());
		for (var diff : set.diffs()) {
			console.println("path=" + diff.relativePath() + " content=" + diff.content()
					+ " reviewable=" + diff.reviewable() + " detail=" + diff.detail());
			console.println("before=" + diff.before() + "\nafter=" + diff.after());
			if (!diff.patch().isEmpty()) console.println(diff.patch());
		}
		for (var checkpoint : snapshot.checkpoints()) console.println("checkpoint=" + checkpoint);
		for (var evidence : snapshot.evidence()) {
			console.println("evidence=" + evidence.evidenceId() + " argv=" + evidence.argv()
					+ " cwd=" + evidence.cwd() + " sourceCwd=" + evidence.sourceCwd()
					+ " revision=" + evidence.sourceRevision() + " configuration=" + evidence.configurationDigest()
					+ " exit=" + evidence.exitCode() + " cancelled=" + evidence.cancelled()
					+ " log=" + evidence.logSha256() + " started=" + evidence.startedAt()
					+ " finished=" + evidence.finishedAt());
		}
	}

	static void recovery(ConflictReport report, AgentConsole console) {
		console.println("recovery change=" + report.changeSetId() + " journal=" + report.journalState()
				+ " status=" + report.status() + " conflicts=" + report.hasConflicts());
		for (var entry : report.entries())
			console.println("path=" + entry.relativePath() + " position=" + entry.position()
					+ " code=" + entry.code() + " checkpoint=" + entry.checkpointState()
					+ " current=" + entry.current());
	}

	static boolean failure(Exception ex, AgentConsole console) {
		String code = ex instanceof ChangeStore.Failure f ? f.code().name()
				: ex instanceof ApplyEngine.Failure f ? f.code().name()
				: ex instanceof VerificationRunner.Refusal f ? f.code().name() : ex.getClass().getSimpleName();
		console.error(code + ": " + ex.getMessage());
		return false;
	}
}
