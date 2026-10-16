package com.example.miniagent.core.lesson;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.miniagent.core.lesson.LessonRecord.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LessonServiceTest {
	@TempDir Path root;

	@Test void candidateNeedsExplicitAcceptanceAndDoesNotLeakToChildren() throws Exception {
		var f = new LessonFixture(root);
		var candidate = f.service.propose("verified-goal", f.content("parser retains source positions"), f.actor);
		assertThat(f.service.lookup(f.actor, "parser")).isEmpty();
		var accepted = f.service.accept(candidate.id(), 1, Scope.SESSION, f.actor);
		assertThat(accepted.version()).isEqualTo(2);
		assertThat(f.service.lookup(f.actor, "parser")).extracting(LessonRecord::id).containsExactly(candidate.id());
		var child = new Actor("child-session", f.actor.workspace());
		assertThat(f.service.lookup(child, "parser")).isEmpty();
		assertThatThrownBy(() -> f.service.promote(candidate.id(), 2, Scope.GLOBAL, child)).isInstanceOf(IOException.class);
	}

	@Test void explicitPromotionDoesNotWidenOtherEntriesOrRewriteHistory() throws Exception {
		var f = new LessonFixture(root);
		var local = f.accepted("parser local lesson");
		var project = f.service.promote(local.id(), 2, Scope.PROJECT, f.actor);
		var child = new Actor("child-session", f.actor.workspace());
		var elsewhere = new Actor("elsewhere", root.toRealPath().toString());
		assertThat(f.service.lookup(child, "parser")).hasSize(1);
		assertThat(f.service.lookup(elsewhere, "parser")).isEmpty();
		var global = f.service.promote(local.id(), project.version(), Scope.GLOBAL, f.actor);
		assertThat(f.service.lookup(elsewhere, "parser")).hasSize(1);
		assertThat(f.service.history(global.id(), f.actor)).extracting(LessonRecord::scope)
				.containsExactly(Scope.SESSION, Scope.SESSION, Scope.PROJECT, Scope.GLOBAL);
	}

	@Test void staleAcceptanceAndIncompleteAuthoritativeRunCannotCreateKnowledge() throws Exception {
		var f = new LessonFixture(root);
		var candidate = f.service.propose("verified-goal", f.content("parser lesson"), f.actor);
		var run = f.completed;
		when(f.runs.read(run.spec().runId())).thenReturn(new RunRecord(run.spec(), run.sequence(), RunState.FAILED,
				run.reason(), run.attempts(), run.usage(), run.uncertainTools()));
		assertThatThrownBy(() -> f.service.accept(candidate.id(), 1, Scope.SESSION, f.actor)).isInstanceOf(IOException.class);
		when(f.runs.read(run.spec().runId())).thenReturn(run);
		f.failGoal();
		assertThatThrownBy(() -> f.service.accept(candidate.id(), 1, Scope.SESSION, f.actor)).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> f.service.propose("verified-goal", f.content("parser lesson"), f.actor)).isInstanceOf(IOException.class);
		assertThat(f.service.lookup(candidate.id(), f.actor).state()).isEqualTo(State.CANDIDATE);
	}

	@Test void compareVersionEditsAndRollbackAppendWithoutRevivingScope() throws Exception {
		var f = new LessonFixture(root);
		var accepted = f.accepted("parser original");
		var edit = f.service.update(accepted.id(), 2, "verified-goal", f.content("parser corrected"), f.actor);
		assertThatThrownBy(() -> f.service.update(accepted.id(), 2, "verified-goal",
				f.content("parser lost update"), f.actor)).isInstanceOf(IOException.class).hasMessageContaining("expectedVersion");
		f.failGoal();
		// Historical accepted knowledge remains reusable; rollback is a fresh operator action.
		assertThat(f.service.lookup(f.actor, "parser")).hasSize(1);
		var rollback = f.service.rollback(accepted.id(), edit.version(), 2, f.actor);
		assertThat(rollback.version()).isEqualTo(4);
		assertThat(rollback.rollbackOf()).isEqualTo(2);
		assertThat(rollback.content()).isEqualTo(accepted.content());
		assertThat(rollback.scope()).isEqualTo(Scope.SESSION);
		assertThat(f.service.history(accepted.id(), f.actor)).extracting(LessonRecord::version).containsExactly(1, 2, 3, 4);
		assertThatThrownBy(() -> f.service.rollback(accepted.id(), 4, 1, f.actor)).isInstanceOf(IOException.class);
	}

	@Test void retirementStopsInjectionKeepsHistoryAndRollbackRestores() throws Exception {
		var f = new LessonFixture(root);
		var accepted = f.accepted("parser outdated advice");
		var candidate = f.service.propose("verified-goal", f.content("parser candidate"), f.actor);
		assertThatThrownBy(() -> f.service.retire(candidate.id(), 1, f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("Accepted lesson required");
		var child = new Actor("child-session", f.actor.workspace());
		assertThatThrownBy(() -> f.service.retire(accepted.id(), 2, child))
				.isInstanceOf(IOException.class).hasMessageContaining("not visible");
		assertThatThrownBy(() -> f.service.retire(accepted.id(), 1, f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("expectedVersion");
		// Retirement needs no fresh proof: it exists to remove lessons whose source no longer verifies.
		f.failGoal();
		var retired = f.service.retire(accepted.id(), 2, f.actor);
		assertThat(retired.state()).isEqualTo(State.RETIRED);
		assertThat(retired.version()).isEqualTo(3);
		assertThat(retired.operation()).isEqualTo("retire");
		assertThat(retired.content()).isEqualTo(accepted.content());
		assertThat(retired.scope()).isEqualTo(Scope.SESSION);
		assertThat(f.service.lookup(f.actor, "parser")).isEmpty();
		assertThat(f.service.lookup(accepted.id(), f.actor).state()).isEqualTo(State.RETIRED);
		assertThat(f.service.list(f.actor)).extracting(LessonRecord::id).contains(accepted.id());
		assertThatThrownBy(() -> f.service.retire(accepted.id(), 3, f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("Accepted lesson required");
		assertThatThrownBy(() -> f.service.promote(accepted.id(), 3, Scope.PROJECT, f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("Accepted lesson required");
		assertThatThrownBy(() -> f.service.update(accepted.id(), 3, "verified-goal", f.content("parser revived"), f.actor))
				.isInstanceOf(IOException.class).hasMessageContaining("Accepted lesson required");
		var restored = f.service.rollback(accepted.id(), 3, 2, f.actor);
		assertThat(restored.state()).isEqualTo(State.ACCEPTED);
		assertThat(restored.rollbackOf()).isEqualTo(2);
		assertThat(restored.content()).isEqualTo(accepted.content());
		assertThat(f.service.lookup(f.actor, "parser")).extracting(LessonRecord::id).containsExactly(accepted.id());
		assertThat(f.service.history(accepted.id(), f.actor)).extracting(LessonRecord::state)
				.containsExactly(State.CANDIDATE, State.ACCEPTED, State.RETIRED, State.ACCEPTED);
	}

	@Test void retireCommandTakesStrictCompareVersionJson() throws Exception {
		var f = new LessonFixture(root);
		var accepted = f.accepted("parser command lesson");
		var out = new ByteArrayOutputStream();
		var console = new AgentConsole(new BufferedReader(new StringReader("")), new PrintStream(out, true), false);
		var session = new AgentSession();
		session.switchTo(f.actor.session());
		var context = new SlashCommand.CommandContext(null, session, console);
		var command = new LessonCommand(f.service, Path.of(f.actor.workspace()));
		assertThat(command.executeWithStatus("retire {\"id\":\"" + accepted.id() + "\"}", context)).isFalse();
		assertThat(command.executeWithStatus("retire {\"id\":\"" + accepted.id()
				+ "\",\"expectedVersion\":2,\"scope\":\"GLOBAL\"}", context)).isFalse();
		assertThat(f.service.lookup(accepted.id(), f.actor).state()).isEqualTo(State.ACCEPTED);
		assertThat(command.executeWithStatus("retire {\"id\":\"" + accepted.id() + "\",\"expectedVersion\":2}", context))
				.isTrue();
		assertThat(f.service.lookup(accepted.id(), f.actor).state()).isEqualTo(State.RETIRED);
		assertThat(out.toString()).contains("\"state\":\"RETIRED\"");
	}

	@Test void successfulLabelCannotSubstituteForMatchingGateEvidence() throws Exception {
		var f = new LessonFixture(root);
		var g = f.goal.get();
		var gate = g.results().getFirst();
		var wrongLog = new com.example.miniagent.core.goal.GoalRecord.GateResult(gate.gate(), gate.revision(),
				gate.configuration(), gate.code(), gate.evidence(), java.util.Base64.getEncoder().encodeToString(new byte[] { 1 }));
		f.goal.set(new com.example.miniagent.core.goal.GoalRecord(1, g.id(), g.spec(), g.state(), g.reason(),
				g.steps(), g.phase(), g.target(), List.of(wrongLog), true, ""));
		assertThatThrownBy(() -> f.service.propose(g.id(), f.content("parser purported lesson"), f.actor))
				.isInstanceOf(IOException.class);
		assertThat(f.service.list(f.actor)).isEmpty();
	}

	@Test void strictModelJsonRejectsCoercionsDuplicatesUnknownAndTrailingData() throws Exception {
		assertThat(LessonService.parseContent("{\"title\":\"parser\",\"content\":\"retain offsets\",\"tags\":[]}").tags()).isEmpty();
		for (String json : List.of(
				"{\"title\":\"parser\",\"content\":\"a\",\"tags\":[],\"scope\":\"GLOBAL\"}",
				"{\"title\":\"parser\",\"title\":\"second\",\"content\":\"a\",\"tags\":[]}",
				"{\"title\":\"parser\",\"content\":\"a\",\"tags\":[]} {}",
				"{\"title\":42,\"content\":\"a\",\"tags\":[]}",
				"{\"title\":\"parser\",\"content\":\"a\",\"tags\":[42]}",
				"{\"title\":\"parser\",\"content\":\"a\"}")) {
			assertThatThrownBy(() -> LessonService.parseContent(json)).isInstanceOf(Exception.class);
		}
		assertThatThrownBy(() -> new Content("parser", "x".repeat(4001), List.of())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test void onlyReadCommandsAreBusyControlsAndOnlyAcceptanceDefaultsScope() throws Exception {
		for (String input : List.of("/lesson list", "/lesson show id", "/lesson inspect id", "/lesson history id", "/lesson lookup parser"))
			assertThat(LessonCommand.isControlInput(input)).isTrue();
		for (String input : List.of("/lesson refine goal", "/lesson propose {}", "/lesson accept {}", "/lesson promote {}",
				"/lesson update {}", "/lesson rollback {}", "/lesson retire {}", "/lesson list extra"))
			assertThat(LessonCommand.isControlInput(input)).isFalse();
		String json = "{\"id\":\"id\",\"expectedVersion\":1}";
		assertThat(LessonCommand.parseAcceptance(json, true).scope()).isEqualTo(Scope.SESSION);
		assertThatThrownBy(() -> LessonCommand.parseAcceptance(json, false)).isInstanceOf(Exception.class);
	}
}
