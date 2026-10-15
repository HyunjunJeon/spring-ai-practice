package com.example.miniagent.core.goal;

import java.io.IOException;
import java.nio.file.Path;
import com.example.miniagent.core.ControlInput;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.command.RunCommand;
import tools.jackson.databind.node.ObjectNode;

/** Operator-only JSON command. No model-callable goal/gate execution tool is installed. */
public final class GoalCommand implements SlashCommand {
	private final GoalService goals;
	private final Path workspace;
	private final RunBudget defaults;
	private final String operator = "goal-console-" + java.util.UUID.randomUUID();
	public GoalCommand(GoalService goals, Path workspace, RunBudget defaults) {
		this.goals = goals; this.workspace = workspace; this.defaults = defaults;
	}
	@Override public String name() { return "goal"; }
	@Override public String description() {
		return "start <JSON> | submit <JSON> | show <id> | list | resume <id> [run resume options] | cancel <id>";
	}
	public static boolean isControlInput(String input) { return ControlInput.isGoalControl(input); }
	@Override public void execute(String args, CommandContext context) { executeWithStatus(args, context); }
	@Override public boolean executeWithStatus(String args, CommandContext context) {
		try {
			if (args.length() > 131072) throw new IllegalArgumentException("Goal command too large");
			String[] parts = args.strip().split("\\s+", 2);
			if (parts[0].equals("start") || parts[0].equals("submit")) {
				if (parts.length != 2) throw new IllegalArgumentException("Expected goal JSON");
				var request = parse(parts[1], defaults);
				var record = goals.submit(request, context.agent().selectedModel(), context.session().id(), workspace);
				print(record, context); // Pending identity is visible even if execution refuses.
				if (parts[0].equals("submit")) return true;
				record = goals.execute(record.id());
				print(record, context);
				return record.state() == GoalRecord.State.SUCCEEDED;
			}
			if (parts[0].equals("list") && parts.length == 1) {
				for (var record : goals.list()) print(record, context);
				return true;
			}
			if (parts.length != 2) throw new IllegalArgumentException("Expected goal ID");
			String[] tail = parts[1].strip().split("\\s+");
			if (!parts[0].equals("resume") && tail.length != 1)
				throw new IllegalArgumentException("Unexpected goal arguments");
			var record = switch (parts[0]) {
				case "show" -> goals.inspect(tail[0]);
				case "cancel" -> goals.cancel(tail[0]);
				case "resume" -> {
					String[] resume = args.strip().split("\\s+");
					yield goals.resume(tail[0], RunCommand.resumeOptions(resume, operator));
				}
				default -> throw new IllegalArgumentException("Unknown goal operation");
			};
			print(record, context);
			return !parts[0].equals("resume") || record.state() == GoalRecord.State.SUCCEEDED;
		}
		catch (IOException | RuntimeException ex) {
			context.console().error("Goal " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
			return false;
		}
	}
	public static GoalRequest parse(String json, RunBudget defaults) throws IOException {
		if (json.length() > 131072) throw new IllegalArgumentException("Goal JSON too large");
		var tree = GoalStore.JSON.readTree(json);
		if (!(tree instanceof ObjectNode node)) throw new IllegalArgumentException("Expected goal object");
		if (!node.has("maxSteps")) node.put("maxSteps", 6);
		if (!node.has("wallMillis")) node.put("wallMillis", defaults.wall().toMillis());
		if (!node.has("tokenCap")) {
			if (defaults.tokenCap() == null) node.putNull("tokenCap");
			else node.put("tokenCap", defaults.tokenCap());
		}
		if (!node.has("toolCallCap")) node.put("toolCallCap", defaults.toolCallCap());
		return GoalStore.JSON.treeToValue(node, GoalRequest.class);
	}
	private static void print(GoalRecord r, CommandContext context) {
		context.console().println("goal=" + r.id() + " run=" + r.spec().runId().value()
				+ " state=" + r.state() + " reason=" + r.reason() + " steps=" + r.steps()
				+ "/" + r.spec().request().maxSteps() + " phase=" + r.phase()
				+ " target=" + (r.target() == null ? "-" : (r.target().readOnly() ? "readonly:" : "proposal:") + r.target().id())
				+ " cancel is persistent; success never applies live source");
	}
}
