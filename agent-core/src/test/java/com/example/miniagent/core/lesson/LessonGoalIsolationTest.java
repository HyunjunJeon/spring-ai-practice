package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import static com.example.miniagent.core.lesson.LessonRecord.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** goal-lesson:G4. One session's goal never creates knowledge for another session, workspace or run identity. */
class LessonGoalIsolationTest {
	private static final String FOREIGN_GOAL = "Lesson requires this session's verified goal or a goal its gate rejected";
	private static final String RUN_IDENTITY = "Lesson requires the goal's authoritative run";
	@TempDir Path root;

	private Actor foreignWorkspace(LessonFixture f) throws IOException {
		return Actor.capture(f.actor.session(), Files.createDirectory(root.toRealPath().resolve("elsewhere")));
	}
	/** The authoritative run of the fixture's goal with exactly one identity field replaced. */
	private static RunRecord runWith(LessonFixture f, RunState state, RunId runId, String conversation, String parent,
			ModelRef model, String cwd, String options) {
		var spec = f.completed.spec();
		var altered = new RunSpec(spec.schemaVersion(), runId, conversation, parent, null, null, model, spec.wireFamily(),
				cwd, spec.budget(), options, spec.toolsFingerprint(), spec.createdAt());
		return new RunRecord(altered, f.completed.sequence(), state, f.completed.reason(), f.completed.attempts(),
				f.completed.usage(), f.completed.uncertainTools());
	}

	@Test void aForeignSessionOrWorkspaceCannotProposeFromAVerifiedOrRejectedGoal() throws Exception {
		var f = new LessonFixture(root);
		var foreign = List.of(new Actor("other-session", f.actor.workspace()), foreignWorkspace(f));
		for (var actor : foreign)
			assertThatThrownBy(() -> f.service.propose("verified-goal", f.content("parser"), actor))
					.isInstanceOf(IOException.class).hasMessage(FOREIGN_GOAL);
		f.rejectGoal();
		for (var actor : foreign)
			assertThatThrownBy(() -> f.service.propose("verified-goal", f.content("parser"), actor))
					.isInstanceOf(IOException.class).hasMessage(FOREIGN_GOAL);
		assertThat(f.store.read()).isEmpty();
		assertThat(f.service.propose("verified-goal", f.content("parser"), f.actor).creator()).isEqualTo(f.actor);
	}

	@Test void refinementForAForeignSessionOrWorkspaceNeverCallsTheModel() throws Exception {
		var f = new LessonFixture(root);
		var calls = new AtomicInteger();
		ChatModel model = new ChatModel() {
			@Override public ChatResponse call(Prompt prompt) { calls.incrementAndGet(); throw new AssertionError("Model called"); }
			@Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
		};
		var binding = new ModelBinding(UUID.randomUUID(), new ModelRef("codex", "gpt-6.1-sol"), LessonFixture.HASH,
				"responses", model, ChatClient.builder(model).build());
		var foreign = List.of(new TurnContext("other-session", "refine", Path.of(f.actor.workspace()), binding),
				new TurnContext(f.actor.session(), "refine", Path.of(foreignWorkspace(f).workspace()), binding));
		for (var turn : foreign)
			assertThatThrownBy(() -> f.service.refine("verified-goal", turn, 1000))
					.isInstanceOf(IOException.class).hasMessage(FOREIGN_GOAL);
		assertThat(calls).hasValue(0);
		assertThat(f.store.read()).isEmpty();
	}

	@Test void aForeignActorCannotUpdateAGloballyVisibleLessonFromTheGoal() throws Exception {
		var f = new LessonFixture(root);
		var accepted = f.accepted("parser shared advice");
		var global = f.service.promote(accepted.id(), 2, Scope.GLOBAL, f.actor);
		var foreign = List.of(new Actor("other-session", f.actor.workspace()), foreignWorkspace(f));
		for (var actor : foreign) {
			assertThat(global.visibleTo(actor)).isTrue();
			assertThatThrownBy(() -> f.service.update(global.id(), global.version(), "verified-goal",
					f.content("parser hijacked"), actor)).isInstanceOf(IOException.class).hasMessage(FOREIGN_GOAL);
		}
		assertThat(f.service.history(global.id(), f.actor)).extracting(LessonRecord::version).containsExactly(1, 2, 3);
		assertThat(f.service.lookup(global.id(), f.actor).content()).isEqualTo(accepted.content());
	}

	@Test void everyRunIdentityMismatchRefusesTheGoalAndCreatesNoEntry() throws Exception {
		var f = new LessonFixture(root);
		var spec = f.completed.spec();
		var other = Files.createDirectory(root.toRealPath().resolve("other-workspace")).toString();
		for (boolean rejected : new boolean[] { false, true }) {
			if (rejected) f.rejectGoal();
			var state = rejected ? RunState.INTERRUPTED : RunState.COMPLETED;
			var runId = spec.runId();
			int entries = f.store.read().size();
			var variants = new java.util.LinkedHashMap<String, RunRecord>();
			variants.put("run state", runWith(f, rejected ? RunState.COMPLETED : RunState.INTERRUPTED, runId,
					spec.conversationId(), spec.parentConversationId(), spec.model(), spec.cwd(), spec.optionsFingerprint()));
			variants.put("run id", runWith(f, state, RunId.random(), spec.conversationId(), spec.parentConversationId(),
					spec.model(), spec.cwd(), spec.optionsFingerprint()));
			variants.put("cwd", runWith(f, state, runId, spec.conversationId(), spec.parentConversationId(),
					spec.model(), other, spec.optionsFingerprint()));
			variants.put("parent conversation", runWith(f, state, runId, spec.conversationId(), "other-operator",
					spec.model(), spec.cwd(), spec.optionsFingerprint()));
			variants.put("conversation", runWith(f, state, runId, "other-managed", spec.parentConversationId(),
					spec.model(), spec.cwd(), spec.optionsFingerprint()));
			variants.put("model", runWith(f, state, runId, spec.conversationId(), spec.parentConversationId(),
					new ModelRef("codex", "other-model"), spec.cwd(), spec.optionsFingerprint()));
			variants.put("options", runWith(f, state, runId, spec.conversationId(), spec.parentConversationId(),
					spec.model(), spec.cwd(), "b".repeat(64)));
			for (var variant : variants.entrySet()) {
				when(f.runs.read(runId)).thenReturn(variant.getValue());
				assertThatThrownBy(() -> f.service.propose("verified-goal", f.content("parser"), f.actor))
						.as((rejected ? "rejected goal, " : "verified goal, ") + variant.getKey())
						.isInstanceOf(IOException.class).hasMessage(RUN_IDENTITY);
			}
			assertThat(f.store.read()).hasSize(entries);
			// The unaltered authoritative run is accepted, so the refusals above came from the altered field.
			when(f.runs.read(runId)).thenReturn(runWith(f, state, runId, spec.conversationId(), spec.parentConversationId(),
					spec.model(), spec.cwd(), spec.optionsFingerprint()));
			assertThat(f.service.propose("verified-goal", f.content("parser"), f.actor).proof().goalId())
					.isEqualTo("verified-goal");
		}
		assertThat(f.service.list(f.actor)).hasSize(2);
	}
}
