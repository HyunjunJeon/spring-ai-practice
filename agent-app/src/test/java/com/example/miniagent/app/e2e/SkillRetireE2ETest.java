package com.example.miniagent.app.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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
 * A lesson exported as a PROJECT skill is used, retired by the operator and reinstated, over real child JVMs on one
 * layout. The skills plugin scans its roots once at startup, so every step that changes what the model can load is
 * observed after a restart. The goal that grounds the lesson comes first: the export writes into the fingerprinted
 * workspace and revokes that goal's evidence.
 */
class SkillRetireE2ETest {
	private static final Pattern GOAL = Pattern.compile("goal=(\\S+) run=\\S+ state=(\\S+) reason=(\\S+)");
	private static final String SKILL = "keep-ready";
	private static final String LOAD = "!tool load_skill {\"name\":\"" + SKILL + "\"}";
	/** The part of the PROJECT warning that tells the operator what the move costs. */
	private static final String WARNING = "succeeded goals in this workspace lose their verified evidence";

	@TempDir Path directory;

	@AfterEach void stop() throws Exception { AppProcess.killAll(); }

	@Test void anExportedSkillIsRetiredByTheOperatorAndReinstatedByteForByteAcrossRestarts() throws Exception {
		var layout = Layout.under(this.directory);
		Path live = layout.work().resolve(".mini-agent/skills").resolve(SKILL);
		Path retired = layout.work().resolve(".mini-agent/skills-retired").resolve(SKILL);
		Files.writeString(layout.work().resolve("answer.txt"), "READY\n");

		// A verified goal grounds a lesson; the lesson becomes an accepted PROJECT lesson and is exported.
		var app = AppProcess.rpc(layout, AppProcess.FIXTURE_GATES);
		String start = completed(app, "/goal start " + goal());
		Matcher verdict = lastGoalLine(app, start);
		assertThat(verdict.group(2) + " " + verdict.group(3)).isEqualTo("SUCCEEDED VERIFIED_READONLY");
		String content = "Check answer.txt holds exactly READY before finishing.";
		String id = lesson(app, "/lesson propose " + proposal(verdict.group(1), content)).path("id").asString();
		lesson(app, "/lesson accept " + json(revision(id, 1)));
		lesson(app, "/lesson promote " + json(revision(id, 2).put("scope", "PROJECT")));
		var exported = lesson(app, "/lesson export " + json(revision(id, 3).put("name", SKILL)));
		assertThat(exported.path("path").asString()).isEqualTo(live.resolve("SKILL.md").toString());
		assertThat(exported.path("loads").asString()).isEqualTo("next start");
		assertThat(exported.path("warning").asString()).contains(WARNING);
		byte[] original = Files.readAllBytes(live.resolve("SKILL.md"));
		assertThat(new String(original, StandardCharsets.UTF_8)).contains(content);
		finish(app);

		// A restart lists the exported skill. /skill is not an RPC command, so the forced load runs as a one-shot REPL
		// input; the chosen load is a load_skill tool call of the next process, whose report prints the values an
		// operator must repeat to retire the skill.
		assertThat(skillsListing(layout)).containsPattern("- " + SKILL + " ");
		var forced = AppProcess.oneShot(layout, "repl", "/skill " + SKILL + " check the answer file", AppProcess.FIXTURE_GATES);
		assertThat(forced.exit()).isZero();
		app = AppProcess.rpc(layout, AppProcess.FIXTURE_GATES);
		JsonNode row = skillRow(app);
		assertThat(row.path("scope").asString()).isEqualTo("PROJECT");
		assertThat(row.path("workspace").asString()).isEqualTo(layout.work().toString());
		assertThat(row.path("lessonId").asString()).isEqualTo(id);
		assertThat(row.path("linkedVersion").asInt()).isEqualTo(3);
		assertThat(loads(row)).as("chosen:forced:expected after /skill").isEqualTo("0:1:1");
		String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
		assertThat(row.path("expectedDigest").asString()).isEqualTo(digest);
		String load = app.prompt(LOAD);
		assertThat(is(app.completion(load), "status", "completed")).isTrue();
		assertThat(app.streamed(load)).contains(content);
		assertThat(loads(skillRow(app))).as("chosen:forced:expected after load_skill").isEqualTo("1:1:2");
		finish(app);

		// A report that is one load behind is refused, and nothing moves.
		var stale = AppProcess.oneShot(layout, "repl", unexport(1, digest), AppProcess.FIXTURE_GATES);
		assertThat(stale.exit()).isEqualTo(1);
		assertThat(stale.stdout()).contains("Stale expectedLoads");
		assertThat(live.resolve("SKILL.md")).hasBinaryContent(original);
		assertThat(retired).doesNotExist();

		// The current values retire it: the folder moves byte for byte, the running app keeps what it scanned.
		app = AppProcess.rpc(layout, AppProcess.FIXTURE_GATES);
		var moved = lesson(app, unexport(2, digest));
		assertMoved(moved, live, retired);
		assertThat(live).doesNotExist();
		assertThat(retired.resolve("SKILL.md")).hasBinaryContent(original);
		var retiredRow = skillRow(app);
		assertThat(retiredRow.path("retiredAt").isNull()).isFalse();
		assertThat(retiredRow.path("retiredReason").asString()).isEqualTo("OPERATOR");
		assertThat(retiredRow.path("candidate").asBoolean()).isFalse();
		finish(app);

		// After the restart the model no longer finds it, and its name stays reserved for a reinstate.
		assertThat(skillsListing(layout)).doesNotContainPattern("- " + SKILL + " ");
		var reexport = AppProcess.oneShot(layout, "repl", "/lesson export " + json(revision(id, 3).put("name", SKILL)),
				AppProcess.FIXTURE_GATES);
		assertThat(reexport.exit()).isEqualTo(1);
		assertThat(reexport.stdout()).contains("Skill is retired, use /lesson reinstate");
		assertThat(live).doesNotExist();

		// Reinstate moves the same bytes back; the next start lists the skill again.
		app = AppProcess.rpc(layout, AppProcess.FIXTURE_GATES);
		var reinstated = lesson(app, "/lesson reinstate " + json(JSON.createObjectNode().put("name", SKILL).put("scope", "PROJECT")));
		assertMoved(reinstated, retired, live);
		assertThat(retired).doesNotExist();
		assertThat(live.resolve("SKILL.md")).hasBinaryContent(original);
		var restoredRow = skillRow(app);
		assertThat(restoredRow.path("retiredAt").isNull()).isTrue();
		assertThat(restoredRow.path("restoredAt").isNull()).isFalse();
		assertThat(loads(restoredRow)).as("a restore keeps the counts").isEqualTo("1:1:2");
		finish(app);
		assertThat(skillsListing(layout)).containsPattern("- " + SKILL + " ");
	}

	private static void assertMoved(JsonNode moved, Path from, Path to) {
		assertThat(moved.path("skill").asString()).isEqualTo(SKILL);
		assertThat(moved.path("from").asString()).isEqualTo(from.toString());
		assertThat(moved.path("to").asString()).isEqualTo(to.toString());
		assertThat(moved.path("loads").asString()).isEqualTo("next start");
		assertThat(moved.path("warning").asString()).contains(WARNING);
	}

	private static String skillsListing(Layout layout) throws Exception {
		var listing = AppProcess.oneShot(layout, "repl", "/skills", AppProcess.FIXTURE_GATES);
		assertThat(listing.exit()).isZero();
		return listing.stdout();
	}

	private static void finish(AppProcess app) throws Exception {
		app.shutdown();
		assertThat(app.awaitExit()).isZero();
	}

	/** The one exported skill's row of /lesson skills; hand-written skills never appear in it. */
	private static JsonNode skillRow(AppProcess app) {
		JsonNode skills = lesson(app, "/lesson skills").path("skills");
		assertThat(skills.size()).isEqualTo(1);
		return skills.get(0);
	}

	private static String loads(JsonNode row) {
		return row.path("chosenLoads").asLong() + ":" + row.path("forcedLoads").asLong() + ":" + row.path("expectedLoads").asLong();
	}

	private static String goal() {
		ObjectNode gate = JSON.createObjectNode();
		gate.putArray("argv").add("/usr/bin/grep").add("-q").add("^READY$").add("answer.txt");
		gate.put("timeoutMillis", 30000).put("maxOutputBytes", 4096);
		ObjectNode goal = JSON.createObjectNode().put("goal", "answer.txt must be the single line READY. Do not apply live source.")
				.put("maxSteps", 2);
		goal.putArray("gates").add(gate);
		return JSON.writeValueAsString(goal);
	}

	private static String proposal(String goalId, String content) {
		ObjectNode node = JSON.createObjectNode().put("goalId", goalId).put("title", "Keep answer.txt READY").put("content", content);
		node.putArray("tags").add("e2e");
		return JSON.writeValueAsString(node);
	}

	private static String unexport(long expectedLoads, String expectedDigest) {
		return "/lesson unexport " + json(JSON.createObjectNode().put("name", SKILL).put("scope", "PROJECT")
				.put("expectedLoads", expectedLoads).put("expectedDigest", expectedDigest));
	}

	private static ObjectNode revision(String id, int expectedVersion) {
		return JSON.createObjectNode().put("id", id).put("expectedVersion", expectedVersion);
	}

	private static String json(ObjectNode node) { return JSON.writeValueAsString(node); }

	/** The goal's final state line of a request that printed its states in order. */
	private static Matcher lastGoalLine(AppProcess app, String requestId) {
		String line = app.outputs(requestId).stream().filter(text -> text.startsWith("goal=")).reduce((first, last) -> last)
				.orElseThrow(() -> new AssertionError("No goal line printed"));
		Matcher matcher = GOAL.matcher(line);
		assertThat(matcher.find()).as(line).isTrue();
		return matcher;
	}

	/** One lesson command that printed one JSON record. */
	private static JsonNode lesson(AppProcess app, String command) {
		return JSON.readTree(app.outputs(completed(app, command)).getFirst());
	}

	private static String completed(AppProcess app, String command) {
		String id = app.command(command);
		assertThat(is(app.completion(id), "status", "completed")).as(command).isTrue();
		return id;
	}
}
