package com.example.miniagent.app.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static com.example.miniagent.app.e2e.AppProcess.JSON;
import static com.example.miniagent.app.e2e.AppProcess.is;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S7 -> S8 -> S11 -> S21 -> S9/S10 -> S12 -> S13 as one ordered story on one layout. The order is forced:
 * propose, accept and refine need the goal's own session, and a PROJECT export writes under .mini-agent/skills,
 * which the read-only goal proof fingerprints, so every lesson that needs a fresh proof comes before the export.
 * Gates run through the pass-through FixtureLaunchPolicySource, never sandbox-exec.
 */
class GoalLessonStoryE2ETest {
	private static final Pattern GOAL = Pattern.compile(
			"goal=(\\S+) run=(\\S+) state=(\\S+) reason=(\\S+) steps=(\\d+)/(\\d+) phase=\\S+ target=(\\S+)");
	/** A lesson injection shows in the fake model's request description as one extra SYSTEM line. */
	private static final Pattern INJECTED = Pattern.compile("(?m)^\\s+SYSTEM\\s+Untrusted advisory lesson data");
	private static final String READY_PROMPT = "READY check please";
	private static final String DONE_PROMPT = "DONE gate status";
	private static final String SKILL = "keep-ready";

	@TempDir Path directory;

	private record GoalLine(String goal, String run, String state, String reason, int steps, String target) {
		static GoalLine parse(String line) {
			Matcher m = GOAL.matcher(line);
			assertThat(m.find()).as(line).isTrue();
			return new GoalLine(m.group(1), m.group(2), m.group(3), m.group(4), Integer.parseInt(m.group(5)), m.group(7));
		}
		String verdict() { return this.state + " " + this.reason; }
	}

	@AfterEach void stop() throws Exception { AppProcess.killAll(); }

	@Test void goalsGroundLessonsThatInjectRetireRollBackAndExportASkillThatOnlyARestartLoads() throws Exception {
		var layout = Layout.under(this.directory);
		Path answer = layout.work().resolve("answer.txt");
		Files.writeString(answer, "READY\n");
		var app = AppProcess.rpc(layout, AppProcess.FIXTURE_GATES);

		// S7 (and the gate smoke check): a READY gate runs through the fixture policy and exits 0.
		String start = app.command("/goal start " + goal("answer.txt must be the single line READY. Do not apply live source.",
				"READY", 2));
		var startCompletion = app.completion(start);
		var passing = goalLines(app.outputs(start));
		assertThat(passing.getFirst().state()).isEqualTo("PENDING");
		var passed = passing.getLast();
		assertThat(passed.verdict()).as("gate smoke: READY gate under the fixture policy, not UNSUPPORTED_SANDBOX")
				.isEqualTo("SUCCEEDED VERIFIED_READONLY");
		assertThat(passed.target()).startsWith("readonly:");
		assertThat(is(startCompletion, "status", "completed")).isTrue();
		String session = startCompletion.path("sessionAfter").asString();
		assertThat(goalLines(app.outputs(completed(app, "/goal show " + passed.goal())))).singleElement()
				.satisfies(line -> assertThat(line.verdict()).isEqualTo("SUCCEEDED VERIFIED_READONLY"));
		assertThat(runLine(app, passed.run())).contains("state=COMPLETED");
		assertThat(layout.state().resolve("goals").resolve(passed.goal()).resolve("state.json")).isRegularFile();

		// S8: the same session's failing goals stop at the step limit, then on an unchanged failed revision.
		var stepLimit = failingGoal(app, 2);
		assertThat(stepLimit.verdict()).isEqualTo("FAILED STEP_LIMIT");
		assertThat(stepLimit.steps()).isEqualTo(2);
		var noProgress = failingGoal(app, 3);
		assertThat(noProgress.verdict()).isEqualTo("BLOCKED NO_PROGRESS");
		assertThat(runLine(app, stepLimit.run())).contains("state=INTERRUPTED");
		assertThat(Files.readString(answer)).isEqualTo("READY\n");

		// S11: a lesson grounded in the gate rejection carries Java's marker and is injected once accepted.
		assertThat(injectedOn(app, DONE_PROMPT)).as("no lesson exists yet").isFalse();
		var pitfall = lesson(app, "/lesson propose " + proposal(stepLimit.goal(), "Gate DONE pitfall",
				"The DONE gate needs its marker line."));
		assertRevision(pitfall, 1, "CANDIDATE", "SESSION");
		assertThat(pitfall.path("content").path("content").asString()).startsWith("[이전 작업 실패에서 배운 주의점] gate 0 "
				+ "`/usr/bin/grep -q ^DONE$ answer.txt` 종료 코드 1 (목표 중단 사유: STEP_LIMIT)\n\nThe DONE gate needs");
		String pitfallId = pitfall.path("id").asString();
		assertRevision(lesson(app, "/lesson accept " + json(revision(pitfallId, 1))), 2, "ACCEPTED", "SESSION");
		assertThat(injectedOn(app, DONE_PROMPT)).as("accepted failure lesson").isTrue();

		// S21: refinement needs strict JSON from the model; the fake's description fails closed and adds nothing.
		String refine = app.command("/lesson refine " + passed.goal());
		assertThat(is(app.completion(refine), "status", "failed")).isTrue();
		assertThat(app.frames(refine)).anySatisfy(frame -> assertThat(is(frame, "code", "execution_failed")).isTrue())
				.noneSatisfy(frame -> assertThat(is(frame, "event", "ToolExecuted")).isTrue());
		assertThat(lessonIds(app)).containsExactly(pitfallId);

		// S9 with the S10 prompts: the passing goal's lesson through its whole lifecycle, then the export.
		String content = "Check answer.txt holds exactly READY before finishing.";
		var candidate = lesson(app, "/lesson propose " + proposal(passed.goal(), "Keep answer.txt READY", content));
		assertRevision(candidate, 1, "CANDIDATE", "SESSION");
		assertThat(candidate.path("content").path("content").asString()).isEqualTo(content);
		String id = candidate.path("id").asString();
		assertThat(injectedOn(app, READY_PROMPT)).as("a candidate is not injected").isFalse();
		assertRevision(lesson(app, "/lesson accept " + json(revision(id, 1))), 2, "ACCEPTED", "SESSION");
		assertThat(injectedOn(app, READY_PROMPT)).as("S10 accepted").isTrue();
		assertRevision(lesson(app, "/lesson promote " + json(revision(id, 2).put("scope", "PROJECT"))), 3, "ACCEPTED", "PROJECT");
		assertRevision(lesson(app, "/lesson retire " + json(revision(id, 3))), 4, "RETIRED", "PROJECT");
		assertThat(injectedOn(app, READY_PROMPT)).as("S10 retired").isFalse();
		var rolledBack = lesson(app, "/lesson rollback " + json(revision(id, 4).put("toVersion", 3)));
		assertRevision(rolledBack, 5, "ACCEPTED", "PROJECT");
		assertThat(rolledBack.path("rollbackOf").asInt()).isEqualTo(3);
		assertThat(injectedOn(app, READY_PROMPT)).as("S10 rolled back").isTrue();

		var exported = lesson(app, "/lesson export " + json(revision(id, 5).put("name", SKILL)));
		Path skill = layout.work().resolve(".mini-agent/skills").resolve(SKILL).resolve("SKILL.md");
		assertThat(exported.path("loads").asString()).isEqualTo("next start");
		assertThat(exported.path("path").asString()).isEqualTo(skill.toString());
		assertThat(Files.readString(skill)).startsWith("---\nname: " + SKILL + "\ndescription: Keep answer.txt READY\n---\n")
				.contains(content)
				.endsWith("Source: accepted lesson " + id + " v5 (PROJECT), grounded in goal " + passed.goal() + ".\n");
		var history = JSON.readTree(app.outputs(completed(app, "/lesson history " + id)).getFirst());
		assertThat(history.valueStream().map(r -> r.path("version").asInt() + ":" + r.path("state").asString() + ":"
				+ r.path("scope").asString()).toList()).containsExactly("1:CANDIDATE:SESSION", "2:ACCEPTED:SESSION",
				"3:ACCEPTED:PROJECT", "4:RETIRED:PROJECT", "5:ACCEPTED:PROJECT");

		// S12, first half: skills are scanned at startup, so the running app cannot load the new skill yet.
		String early = app.prompt("!tool load_skill {\"name\":\"" + SKILL + "\"}");
		assertThat(is(app.completion(early), "status", "completed")).isTrue();
		assertThat(app.streamed(early)).contains("Unknown skill: " + SKILL);
		app.shutdown();
		assertThat(app.awaitExit()).as("S8 and S21 failed completions are kept in the RPC exit code").isEqualTo(1);

		// Durable state, read only after the child exited.
		var results = JSON.readTree(layout.state().resolve("goals").resolve(stepLimit.goal()).resolve("state.json").toFile())
				.path("record").path("results");
		assertThat(results.size()).as("the unchanged second step ran no gate").isEqualTo(1);
		assertThat(results.path(0).path("code").asString()).isEqualTo("NON_ZERO_EXIT");
		assertThat(results.path(0).path("evidence").path("exitCode").asInt()).isEqualTo(1);
		assertThat(Files.readString(answer)).isEqualTo("READY\n");

		// S12, second half: a restarted app lists and loads the exported skill; a new session sees only PROJECT lessons.
		var skills = AppProcess.oneShot(layout, "repl", "/skills", AppProcess.FIXTURE_GATES);
		assertThat(skills.exit()).isZero();
		assertThat(skills.stdout()).containsPattern("- " + SKILL + " +Keep answer\\.txt READY");
		var restarted = AppProcess.rpc(layout, AppProcess.FIXTURE_GATES);
		String load = restarted.prompt("!tool load_skill {\"name\":\"" + SKILL + "\"}");
		assertThat(is(restarted.completion(load), "status", "completed")).isTrue();
		assertThat(restarted.frames(load)).anySatisfy(frame -> {
			assertThat(is(frame, "event", "ToolExecuted")).isTrue();
			assertThat(is(frame, "tool", "load_skill")).isTrue();
		});
		assertThat(restarted.streamed(load)).contains("Source: accepted lesson " + id + " v5 (PROJECT)");
		assertThat(lessonIds(restarted)).containsExactly(id);
		restarted.shutdown();
		assertThat(restarted.awaitExit()).isZero();

		// S13: the export wrote into the fingerprinted tree (.mini-agent/skills is not excluded), so the next
		// inspection of the passing goal revokes its read-only evidence and it no longer grounds a lesson.
		var repl = AppProcess.repl(layout, AppProcess.FIXTURE_GATES);
		repl.line("/resume " + session);
		repl.await(Pattern.compile("세션 전환: " + Pattern.quote(session)));
		repl.line("/lesson propose " + proposal(passed.goal(), "Second READY lesson", content));
		repl.await(Pattern.compile("Lesson requires this session's verified goal or a goal its gate rejected"));
		repl.line("/goal show " + passed.goal());
		assertThat(GoalLine.parse(repl.await(GOAL).group()).verdict()).isEqualTo("FAILED EVIDENCE_STALE");
		repl.line("/lesson retire " + json(revision(id, 5)));
		var retired = JSON.readTree(repl.await(Pattern.compile("\\{\"id\":\"" + Pattern.quote(id) + "\".*}")).group());
		assertRevision(retired, 6, "RETIRED", "PROJECT");
		repl.line("/exit");
		assertThat(repl.awaitExit()).as("the refused proposal fails the piped REPL").isEqualTo(1);
		// Pinned for review: retiring the lesson does not withdraw the skill exported from it.
		assertThat(skill).isRegularFile();
		var after = AppProcess.oneShot(layout, "repl", "/skills", AppProcess.FIXTURE_GATES);
		assertThat(after.stdout()).containsPattern("- " + SKILL + " +Keep answer\\.txt READY");
	}

	private static GoalLine failingGoal(AppProcess app, int maxSteps) {
		String id = app.command("/goal start " + goal("answer.txt must be the single line DONE.", "DONE", maxSteps));
		assertThat(is(app.completion(id), "status", "failed")).isTrue();
		var lines = goalLines(app.outputs(id));
		assertThat(lines.getFirst().state()).isEqualTo("PENDING");
		return lines.getLast();
	}

	private static String goal(String text, String marker, int maxSteps) {
		ObjectNode gate = JSON.createObjectNode();
		gate.putArray("argv").add("/usr/bin/grep").add("-q").add("^" + marker + "$").add("answer.txt");
		gate.put("timeoutMillis", 30000).put("maxOutputBytes", 4096);
		ObjectNode goal = JSON.createObjectNode().put("goal", text).put("maxSteps", maxSteps);
		goal.putArray("gates").add(gate);
		return JSON.writeValueAsString(goal);
	}

	private static String proposal(String goalId, String title, String content) {
		ObjectNode node = JSON.createObjectNode().put("goalId", goalId).put("title", title).put("content", content);
		node.putArray("tags").add("e2e");
		return JSON.writeValueAsString(node);
	}

	private static ObjectNode revision(String id, int expectedVersion) {
		return JSON.createObjectNode().put("id", id).put("expectedVersion", expectedVersion);
	}

	private static String json(ObjectNode node) { return JSON.writeValueAsString(node); }

	private static List<GoalLine> goalLines(List<String> outputs) {
		return outputs.stream().filter(line -> line.startsWith("goal=")).map(GoalLine::parse).toList();
	}

	private static String runLine(AppProcess app, String run) {
		return app.outputs(completed(app, "/runs")).stream().filter(line -> line.startsWith("run=" + run + " "))
				.findFirst().orElseThrow(() -> new AssertionError("No /runs line for " + run));
	}

	/** One lesson command that printed one JSON record. */
	private static JsonNode lesson(AppProcess app, String command) {
		return JSON.readTree(app.outputs(completed(app, command)).getFirst());
	}

	private static List<String> lessonIds(AppProcess app) {
		return JSON.readTree(app.outputs(completed(app, "/lesson list")).getFirst()).valueStream()
				.map(record -> record.path("id").asString()).toList();
	}

	private static void assertRevision(JsonNode record, int version, String state, String scope) {
		assertThat(record.path("version").asInt() + ":" + record.path("state").asString() + ":"
				+ record.path("scope").asString()).isEqualTo(version + ":" + state + ":" + scope);
	}

	private static boolean injectedOn(AppProcess app, String prompt) {
		String id = app.prompt(prompt);
		assertThat(is(app.completion(id), "status", "completed")).isTrue();
		return INJECTED.matcher(app.streamed(id)).find();
	}

	private static String completed(AppProcess app, String command) {
		String id = app.command(command);
		assertThat(is(app.completion(id), "status", "completed")).as(command).isTrue();
		return id;
	}
}
