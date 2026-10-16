package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import com.example.miniagent.core.ControlInput;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.model.TurnContext;
import tools.jackson.databind.node.ObjectNode;
import static com.example.miniagent.core.lesson.LessonRecord.*;

/** Operator-only /lesson; mutating operations take strict bounded JSON with compare-version fields. */
public final class LessonCommand implements SlashCommand {
	// .mini-agent/skills is part of the verified tree, so every succeeded goal there is revoked at its next inspection.
	private static final String PROJECT_SKILL_WARNING = "PROJECT skills live inside the workspace, so this changes the "
			+ "workspace fingerprint: succeeded goals in this workspace lose their verified evidence and can no longer "
			+ "ground new lessons; run a new goal to verify again.";
	private final LessonService lessons;
	private final LessonSkillExport export;
	private final SkillUsageReport skills;
	private final Path workspace;
	public LessonCommand(LessonService lessons, LessonSkillExport export, SkillUsageReport skills, Path workspace) {
		this.lessons = lessons; this.export = export; this.skills = skills; this.workspace = workspace;
	}
	public record Proposal(String goalId, String title, String content, List<String> tags) {}
	public record Acceptance(String id, int expectedVersion, Scope scope) {}
	public record Edit(String id, int expectedVersion, String goalId, String title, String content, List<String> tags) {}
	public record Rollback(String id, int expectedVersion, int toVersion) {}
	public record Retirement(String id, int expectedVersion) {}
	public record Export(String id, int expectedVersion, String name) {}
	public record Exported(String skill, String path, String loads, String warning) {}
	/** expectedLoads and expectedDigest repeat the values the {@code /lesson skills} row printed. */
	public record Unexport(String name, Scope scope, long expectedLoads, String expectedDigest) {}
	public record Reinstate(String name, Scope scope) {}
	public record Moved(String skill, String from, String to, String loads, String warning) {}

	@Override public String name() { return "lesson"; }
	@Override public String description() {
		return "list | skills | show/inspect/history <id> | lookup <text> | refine <goal-id>"
				+ " | propose/accept/promote/update/rollback/retire/export/unexport/reinstate <JSON>";
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
			if (parts[0].equals("skills") && parts.length == 1) {
				print(skills.report(actor), context); return true;
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
				case "export" -> {
					var e = parse(arg, Export.class);
					// Read before exporting: export takes only this revision, and a revision's scope never changes.
					boolean project = lessons.lookup(e.id(), actor).scope() == Scope.PROJECT;
					var file = export.export(e.id(), e.expectedVersion(), e.name(), actor);
					yield new Exported(e.name(), file.toString(), "next start", project ? PROJECT_SKILL_WARNING : null);
				}
				case "unexport" -> {
					var u = parse(arg, Unexport.class);
					yield moved(u.name(), u.scope(), export.unexport(u.name(), u.scope(), u.expectedLoads(),
							u.expectedDigest(), retiredReason(u, actor), actor));
				}
				case "reinstate" -> {
					var r = parse(arg, Reinstate.class);
					yield moved(r.name(), r.scope(), export.reinstate(r.name(), r.scope(), actor));
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
	/**
	 * The report's current reasons for this skill, as unexport records them; OPERATOR when no rule applies. The report
	 * shows only this workspace's PROJECT rows, so name and scope pick one row.
	 */
	private String retiredReason(Unexport u, Actor actor) throws IOException {
		var reasons = skills.report(actor).skills().stream()
				.filter(row -> row.name().equals(u.name()) && row.scope() == u.scope())
				.findFirst().map(SkillUsageReport.Row::reasons).orElse(List.of());
		return reasons.isEmpty() ? "OPERATOR" : reasons.stream().map(Enum::name).collect(Collectors.joining(","));
	}
	/** Moving a PROJECT folder changes the verified tree just as writing one does. */
	private static Moved moved(String name, Scope scope, LessonSkillExport.Move move) {
		return new Moved(name, move.from().toString(), move.to().toString(), "next start",
				scope == Scope.PROJECT ? PROJECT_SKILL_WARNING : null);
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
