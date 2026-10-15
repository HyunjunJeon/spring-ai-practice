package com.example.miniagent.core.messaging;

import java.io.IOException;
import java.util.function.Supplier;
import com.example.miniagent.core.SlashCommand;

/** Console control of an existing ACTIVE managed operation; never allocates an uncharged parent. */
public final class MessageCommand implements SlashCommand {
	private final Supplier<MessageService> service;
	private final Supplier<com.example.miniagent.core.run.RunStore> runs;
	private final java.nio.file.Path workspace;
	public MessageCommand(Supplier<MessageService> service,
			Supplier<com.example.miniagent.core.run.RunStore> runs, java.nio.file.Path workspace) {
		this.service = service; this.runs = runs; this.workspace = workspace;
	}
	@Override public String name() { return "message"; }
	@Override public String description() {
		return "archive <parent-run> <plan> <child> [offset] | read <plan> <child> [offset]"
				+ " | send <plan> <child> <id> <version> <body> (active parent only)";
	}
	@Override public void execute(String args, CommandContext context) { executeWithStatus(args, context); }
	@Override public boolean executeWithStatus(String args, CommandContext context) {
		try {
			if (args.length() > 16384) throw new IllegalArgumentException("Message command too large");
			var parts = args.strip().split("\\s+", 6);
			if (parts.length < 3) throw new IllegalArgumentException(description());
			var messages = service.get();
			if (parts[0].equals("archive") && (parts.length == 4 || parts.length == 5)) {
				var page = messages.archive(context.session().id(), workspace, runs.get(), parts[1], parts[2],
						parts[3], parts.length == 5 ? Integer.parseInt(parts[4]) : 0);
				context.console().println(MessageStore.JSON.writeValueAsString(page));
				return true;
			}
			var actor = messages.operator(context.session().id(), parts[1]);
			Object result;
			if (parts[0].equals("read") && parts.length <= 4)
				result = messages.read(actor, parts[1], parts[2], parts.length == 4 ? Integer.parseInt(parts[3]) : 0);
			else if (parts[0].equals("send") && parts.length == 6)
				result = messages.send(actor, parts[1], parts[2], parts[3], Long.parseLong(parts[4]), parts[5]);
			else throw new IllegalArgumentException(description());
			context.console().println(MessageStore.JSON.writeValueAsString(result));
			return !(result instanceof MessageStore.Receipt receipt) || receipt.state() == MessageStore.State.DELIVERED
				|| receipt.state() == MessageStore.State.QUEUED;
		}
		catch (IOException | RuntimeException ex) { context.console().error(ex.getMessage()); return false; }
	}
}
