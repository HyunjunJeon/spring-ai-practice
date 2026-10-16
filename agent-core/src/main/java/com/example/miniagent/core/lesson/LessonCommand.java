package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import com.example.miniagent.core.ControlInput;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.model.TurnContext;
import tools.jackson.databind.node.ObjectNode;
import static com.example.miniagent.core.lesson.LessonRecord.*;

/** Operator-only /lesson; mutating operations take strict bounded JSON with compare-version fields. */
public final class LessonCommand implements SlashCommand {
	private final LessonService lessons;
	private final Path workspace;
	public LessonCommand(LessonService lessons, Path workspace) { this.lessons = lessons; this.workspace = workspace; }
	public record Proposal(String goalId, String title, String content, List<String> tags) {}
	public record Acceptance(String id, int expectedVersion, Scope scope) {}
	public record Edit(String id, int expectedVersion, String goalId, String title, String content, List<String> tags) {}
	public record Rollback(String id, int expectedVersion, int toVersion) {}
	public record Retirement(String id, int expectedVersion) {}

	@Override public String name() { return "lesson"; }
	@Override public String description() {
		return "list | show/inspect/history <id> | lookup <text> | refine <goal-id>"
				+ " | propose/accept/promote/update/rollback/retire <JSON>";
	}
	public static boolean isControlInput(String input) { return ControlInput.isLessonControl(input); }
	@Override public void execute(String args, CommandContext context) { executeWithStatus(args, context); }
	@Override public boolean executeWithStatus(String args, CommandContext context) {
		try {
			if (args.length() > 16384) throw new IOException("Lesson command bound");
			var actor = Actor.capture(context.session().id(), workspace);
			String[] parts = args.strip().split("\\s+", 2);
			if (parts[0].equals("list") && parts.length == 1) {
				print(lessons.list(actor), context); return true;
			}
			if (parts.length != 2) throw new IOException("Expected lesson arguments");
			String arg = parts[1];
			Object result = switch (parts[0]) {
				case "show", "inspect" -> lessons.lookup(arg, actor);
				case "history" -> lessons.history(arg, actor);
				case "lookup" -> lessons.lookup(actor, arg);
				case "propose" -> {
					var p = parse(arg, Proposal.class);
					yield lessons.propose(p.goalId(), new Content(p.title(), p.content(), p.tags()), actor);
				}
				case "refine" -> {
					var agent = context.agent();
					// Snapshot once, before any goal inspection or model work; do not use console state in advisors.
					var captured = new TurnContext(actor.session(), UUID.randomUUID().toString(), Path.of(actor.workspace()),
							agent.modelRegistry().bind(agent.selectedModel()));
					yield lessons.refine(arg, captured, agent.toolCallingAdvisorOrder()).candidate();
				}
				case "accept", "promote" -> {
					var a = parseAcceptance(arg, parts[0].equals("accept"));
					yield parts[0].equals("accept")
							? lessons.accept(a.id(), a.expectedVersion(), a.scope(), actor)
							: lessons.promote(a.id(), a.expectedVersion(), a.scope(), actor);
				}
				case "update" -> {
					var edit = parse(arg, Edit.class);
					yield lessons.update(edit.id(), edit.expectedVersion(), edit.goalId(),
							new Content(edit.title(), edit.content(), edit.tags()), actor);
				}
				case "rollback" -> {
					var r = parse(arg, Rollback.class);
					yield lessons.rollback(r.id(), r.expectedVersion(), r.toVersion(), actor);
				}
				case "retire" -> {
					var r = parse(arg, Retirement.class);
					yield lessons.retire(r.id(), r.expectedVersion(), actor);
				}
				default -> throw new IOException("Unknown lesson operation");
			};
			print(result, context); return true;
		}
		catch (IOException | RuntimeException ex) {
			context.console().error("Lesson " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
			return false;
		}
	}
	public static <T> T parse(String json, Class<T> type) throws IOException {
		if (json.length() > 16384) throw new IOException("Lesson JSON bound");
		return LessonStore.JSON.readValue(json, type);
	}
	public static Acceptance parseAcceptance(String json, boolean sessionDefault) throws IOException {
		if (json.length() > 16384) throw new IOException("Lesson JSON bound");
		var tree = LessonStore.JSON.readTree(json);
		if (!(tree instanceof ObjectNode node)) throw new IOException("Expected lesson object");
		if (sessionDefault && !node.has("scope")) node.put("scope", Scope.SESSION.name());
		var result = LessonStore.JSON.treeToValue(node, Acceptance.class);
		if (result.scope() == null) throw new IOException("Explicit lesson scope required");
		return result;
	}
	private static void print(Object value, CommandContext context) {
		context.console().println(LessonStore.JSON.writeValueAsString(value));
	}
}
