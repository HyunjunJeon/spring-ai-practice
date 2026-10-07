package com.example.miniagent.core.change.command;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.change.Fingerprinter;
import com.example.miniagent.core.change.VerificationRunner;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.context.ApplicationEventPublisher;
import java.util.UUID;

/** Exact argv JSON is passed only to the isolated, policy-bound runner. */
public final class VerifyCommand implements SlashCommand {
	private final Supplier<ChangeStore> stores;
	private final Supplier<VerificationRunner> runners;
	private final Duration timeout;
	private final int maxOutputBytes;
	private final ApplicationEventPublisher events;
	private final JsonMapper json = JsonMapper.builder().build();

	public VerifyCommand(Supplier<ChangeStore> stores, Supplier<VerificationRunner> runners,
			Duration timeout, int maxOutputBytes, ApplicationEventPublisher events) {
		this.stores = stores; this.runners = runners; this.timeout = timeout; this.maxOutputBytes = maxOutputBytes;
		this.events = events;
	}
	@Override public String name() { return "verify"; }
	@Override public String description() { return "UUID <JSON argv array> verify the exact proposal in isolation"; }
	@Override public void execute(String args, CommandContext context) { executeWithStatus(args, context); }

	@Override public boolean executeWithStatus(String args, CommandContext context) {
		ChangeStore store = null;
		UUID id = null;
		try {
			String[] parts = args.strip().split("\\s+", 2);
			if (parts.length != 2) throw new IllegalArgumentException("Expected UUID and JSON argv array");
			id = ChangesCommand.id(parts[0]);
			List<String> argv = argv(parts[1]);
			store = this.stores.get();
			var snapshot = store.read().get(id);
			CommandObservation.publish(this.events, snapshot, null, null);
			var set = snapshot.changeSet();
			String revision = Fingerprinter.treeDigest(set.proposed());
			var request = new VerificationRunner.Request(id, set.owner(), snapshot.digest(), revision,
					argv, this.timeout, this.maxOutputBytes);
			var result = this.runners.get().run(request, new CompletableFuture<>());
			var evidence = result.evidence();
			// Runner restores interruption after durable cleanup; reread must not lose it.
			boolean interrupted = Thread.interrupted();
			try {
				CommandObservation.publish(this.events, store.read().get(id), result.code(),
						evidence == null ? null : evidence.evidenceId());
			}
			finally { if (interrupted) Thread.currentThread().interrupt(); }
			boolean accepted = evidence != null && result.accepted(revision, evidence.configurationDigest());
			context.console().println("verification=" + result.code() + " accepted=" + accepted);
			if (evidence != null) {
				context.console().println("evidence=" + evidence.evidenceId() + " argv=" + evidence.argv()
						+ " cwd=" + evidence.cwd() + " sourceCwd=" + evidence.sourceCwd()
						+ " revision=" + evidence.sourceRevision() + " configuration=" + evidence.configurationDigest()
						+ " exit=" + evidence.exitCode() + " cancelled=" + evidence.cancelled() + " log=" + evidence.logSha256());
			}
			return accepted;
		}
		catch (IOException | RuntimeException ex) {
			CommandObservation.failed(this.events, store, id, ex);
			return ChangesCommand.failure(ex, context.console());
		}
	}

	private List<String> argv(String value) {
		var parser = this.json.createParser(value);
		try (parser) {
			var tree = this.json.readTree(parser);
			if (tree == null || !tree.isArray() || tree.isEmpty() || parser.nextToken() != null)
				throw new IllegalArgumentException("Expected one nonempty JSON argv array");
			var argv = new ArrayList<String>();
			for (var arg : tree) {
				if (!arg.isString()) throw new IllegalArgumentException("Every argv entry must be a JSON string");
				argv.add(arg.asString());
			}
			return List.copyOf(argv);
		}
	}
}
