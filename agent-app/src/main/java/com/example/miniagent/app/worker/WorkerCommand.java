package com.example.miniagent.app.worker;

import java.time.Duration;
import java.util.UUID;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.run.command.RunCommand;

/** Operator process control only; no model-callable worker tools. */
public final class WorkerCommand implements SlashCommand {
	private final WorkerController controller;
	private final String operator = "worker-console-" + UUID.randomUUID();
	public WorkerCommand(WorkerController controller) { this.controller = controller; }
	@Override public String name() { return "worker"; }
	@Override public String description() { return "start | status | stop | wakeup | resume <goalId> [run resume options]"; }
	public static boolean isControlInput(String input) {
		return input.strip().matches("/worker\\s+status");
	}
	@Override public void execute(String args, CommandContext context) { executeWithStatus(args, context); }
	@Override public boolean executeWithStatus(String args, CommandContext context) {
		try {
			if (args.length() > 16384) throw new IllegalArgumentException("Worker command too large");
			String[] parts = args.strip().split("\\s+");
			if (parts[0].equals("resume")) {
				if (parts.length < 2) throw new IllegalArgumentException("Expected goal ID");
				var current = controller.status();
				controller.resume(current.instance(), parts[1], RunCommand.resumeOptions(parts, operator));
			}
			else {
				if (parts.length != 1) throw new IllegalArgumentException("Unexpected worker arguments");
				switch (parts[0]) {
					case "start" -> {
						var started = controller.start();
						controller.awaitStarted(started.instance(), Duration.ofSeconds(60));
					}
					case "status" -> { }
					case "stop" -> {
						var current = controller.status();
						controller.stop(current.instance());
					}
					case "wakeup" -> {
						var current = controller.status();
						controller.wakeup(current.instance());
					}
					default -> throw new IllegalArgumentException("Unknown worker operation");
				}
			}
			var status = controller.status();
			context.console().println("worker=" + status.instance() + " pid=" + status.pid()
					+ " owned=" + status.owned() + " state=" + status.state()
					+ " activeGoal=" + status.activeGoal() + " lastGoal=" + status.lastGoal()
					+ " lastState=" + status.lastState() + " error=" + status.error());
			return true;
		}
		catch (Exception ex) {
			if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
			context.console().error("Worker control refused (" + ex.getClass().getSimpleName() + ")");
			return false;
		}
	}
}
