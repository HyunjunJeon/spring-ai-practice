package com.example.miniagent.core.run;

import java.io.IOException;
import com.example.miniagent.core.SlashCommand;

public final class RunsCommand implements SlashCommand {
	private final RunStore runs;
	public RunsCommand(RunStore runs) { this.runs = runs; }
	@Override public String name() { return "runs"; }
	@Override public String description() { return "List durable runs"; }
	@Override public void execute(String args, CommandContext context) { executeWithStatus(args, context); }
	@Override public boolean executeWithStatus(String args, CommandContext context) {
		if (!args.isBlank()) {
			context.console().error("Usage: /runs");
			return false;
		}
		try {
			boolean valid = true;
			for (RunId id : this.runs.list()) {
				try { RunCommand.summary(this.runs.read(id), context.console()); }
				catch (RunStoreException ex) {
					context.console().error(id.value() + " " + ex.code() + " opaque export available");
					valid = false;
				}
			}
			return valid;
		}
		catch (IOException ex) { context.console().error(RunCommand.failure(ex)); return false; }
	}
}
