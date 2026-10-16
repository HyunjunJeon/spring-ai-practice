package com.example.miniagent.app.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static com.example.miniagent.app.e2e.AppProcess.JSON;
import static org.assertj.core.api.Assertions.assertThat;

/** S14: operator delegation from a piped REPL (RPC refuses /delegate), with memory off and then on. */
class DelegationE2ETest {
	private static final Pattern BANNER = Pattern.compile("session: (\\S+)\\)");
	private static final Pattern PARENT = Pattern.compile("parent run=(\\S+) state=(\\S+)");
	private static final Pattern RUN = Pattern.compile("run=(\\S+) conversation=(\\S+) parent=(\\S+) attempt=\\S+ state=(\\S+)");
	private static final String README = "seed readme\n";

	@TempDir Path directory;

	@AfterEach void stop() throws Exception { AppProcess.killAll(); }

	@Test void researchChildCompletesWithMemoryOffAndBothRunsAreDurable() throws Exception {
		var layout = seeded();
		var app = AppProcess.repl(layout, List.of("--agent.plugins.memory.enabled=false"));
		String session = app.await(BANNER).group(1);
		app.line("/delegate start " + plan("read", "!tool read_file {\"path\":\"README.md\"}"));
		var parent = app.await(PARENT);
		assertThat(parent.group(2)).isEqualTo("COMPLETED");
		var outcome = outcome(app, "read");
		assertThat(outcome.path("status").asString()).isEqualTo("COMPLETE");
		var child = outcome.path("children").path(0);
		assertThat(child.path("childId").asString()).isEqualTo("research");
		assertThat(child.path("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(child.path("evidence").path(0).path("text").asString()).contains("seed readme");

		app.line("/delegate list");
		app.await(Pattern.compile("plan=read busy=false"));
		app.line("/delegate show read");
		app.await(Pattern.compile("plan=read busy=false"));
		assertThat(outcome(app, "read").path("status").asString()).isEqualTo("COMPLETE");
		app.line("/runs");
		var first = app.await(RUN);
		var second = app.await(RUN);
		app.closeInput();
		assertThat(app.awaitExit()).isZero();

		var runs = List.of(first, second);
		var parentRun = runs.stream().filter(run -> run.group(1).equals(parent.group(1))).findFirst().orElseThrow();
		var childRun = runs.stream().filter(run -> run != parentRun).findFirst().orElseThrow();
		assertThat(parentRun.group(3)).as("the parent run belongs to the operator session").isEqualTo(session);
		assertThat(childRun.group(1)).isEqualTo(child.path("runId").path("value").asString());
		assertThat(childRun.group(3)).as("the child run's parent is the parent run's conversation").isEqualTo(parentRun.group(2));
		assertThat(runs).allSatisfy(run -> assertThat(run.group(4)).isEqualTo("COMPLETED"));
		assertLiveTreeUnchanged(layout);
	}

	@Test void withMemoryOnAResearchChildCompletesAndANarrativeChildFailsTheExitCode() throws Exception {
		var layout = seeded();
		var app = AppProcess.repl(layout, List.of());
		app.line("/delegate start " + plan("read", "!tool read_file {\"path\":\"README.md\"}"));
		assertThat(app.await(PARENT).group(2)).isEqualTo("COMPLETED");
		assertThat(outcome(app, "read").path("status").asString()).isEqualTo("COMPLETE");
		app.line("/delegate start " + plan("narrative", "I verified everything"));
		app.await(PARENT);
		var narrative = outcome(app, "narrative");
		assertThat(narrative.path("status").asString()).as("no evidence means no fabricated COMPLETE").isEqualTo("FAILED");
		app.closeInput();
		assertThat(app.awaitExit()).isEqualTo(1);
		assertLiveTreeUnchanged(layout);
	}

	private Layout seeded() throws Exception {
		var layout = Layout.under(this.directory);
		Files.writeString(layout.work().resolve("README.md"), README);
		return layout;
	}

	private static void assertLiveTreeUnchanged(Layout layout) throws Exception {
		try (var files = Files.list(layout.work())) {
			assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly("README.md");
		}
		assertThat(Files.readString(layout.work().resolve("README.md"))).isEqualTo(README);
	}

	/** The next printed delegation outcome of a plan; key order is not stable, so it is parsed. */
	private static JsonNode outcome(AppProcess app, String planId) {
		return JSON.readTree(app.await(Pattern.compile("\\{.*\"planId\":\"" + planId + "\".*}")).group());
	}

	private static String plan(String planId, String goal) {
		ObjectNode child = JSON.createObjectNode().put("childId", "research").put("goal", goal).put("role", "RESEARCH")
				.put("wallSeconds", 60).put("toolCallCap", 5);
		child.putArray("tools").add("read_file");
		ObjectNode plan = JSON.createObjectNode().put("planId", planId);
		plan.putArray("children").add(child);
		return JSON.writeValueAsString(plan);
	}
}
