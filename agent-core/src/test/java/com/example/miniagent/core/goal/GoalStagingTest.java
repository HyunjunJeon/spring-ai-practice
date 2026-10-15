package com.example.miniagent.core.goal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import static org.assertj.core.api.Assertions.*;

/** No model or process is invoked: these regressions cover the trusted checkpoint boundary. */
class GoalStagingTest {
	@TempDir Path home;
	@TempDir Path work;
	private TurnContext turn(String run, String attempt) throws IOException {
		ChatModel neverCalled = prompt -> { throw new AssertionError("No model invocation authorized"); };
		var selection = new ModelSelection(new ModelRef("codex", "gpt-6.1-sol"));
		var binding = new ModelBinding(UUID.randomUUID(), selection.ref(), selection.optionsFingerprint(),
				"test-binding-only", neverCalled, ChatClient.builder(neverCalled).build());
		return new TurnContext("session", UUID.randomUUID().toString(), work.toRealPath(), binding, run, attempt);
	}
	private VerificationRunner runner(ChangeStore store) {
		return new VerificationRunner(store, (context, argv) -> {
			throw new AssertionError("No verification process authorized");
		}, Clock.systemUTC());
	}
	@Test void unchangedStageHasDistinctReadonlyTargetAndNeverInventsEmptyProposal() throws Exception {
		Files.writeString(work.resolve("source"), "already satisfied");
		var store = new FileChangeStore(home, work);
		try (var stage = StagedWorkspace.open(store, turn("run", "one"), Clock.systemUTC())) {
			stage.record();
			var target = runner(store).capture(stage);
			assertThat(target.readOnly()).isTrue();
			assertThat(store.read().changes()).isEmpty();
			assertThat(runner(store).current(target)).isTrue();
			Files.writeString(stage.resolve("source", true, List.of()), "changed");
			assertThat(runner(store).current(target)).isFalse();
		}
	}
	@Test void restoreSurvivesBootstrapSupersessionButPublishesFreshOwnershipWithoutEvidence() throws Exception {
		Files.writeString(work.resolve("source"), "baseline");
		var store = new FileChangeStore(home, work);
		VerificationTarget saved;
		try (var stage = StagedWorkspace.open(store, turn("run", "one"), Clock.systemUTC())) {
			Files.writeString(stage.resolve("source", true, List.of()), "acknowledged edit");
			stage.record();
			saved = runner(store).capture(stage);
		}
		try (var stage = StagedWorkspace.open(store, turn("run", "two"), Clock.systemUTC())) {
			assertThat(store.read().get(saved.id()).changeSet().status()).isEqualTo(ChangeStatus.SUPERSEDED);
			stage.restoreProposal(store, saved.id(), saved.owner(), saved.digest(), saved.revision());
			var restored = runner(store).capture(stage);
			assertThat(restored.readOnly()).isFalse();
			assertThat(restored.id()).isNotEqualTo(saved.id());
			assertThat(restored.owner()).isEqualTo(stage.owner());
			assertThat(restored.revision()).isEqualTo(saved.revision());
			assertThat(store.read().get(restored.id()).evidence()).isEmpty();
			assertThat(store.read().get(restored.id()).changeSet().approval()).isNull();
			assertThat(Files.readString(stage.resolve("source", false, List.of()))).isEqualTo("acknowledged edit");
			assertThat(Files.readString(work.resolve("source"))).isEqualTo("baseline");
		}
	}
	@Test void foreignRunChangedBaselineAndRetainedContentCannotBeRestored() throws Exception {
		Files.writeString(work.resolve("source"), "baseline");
		var store = new FileChangeStore(home, work);
		VerificationTarget saved;
		Path retained;
		try (var stage = StagedWorkspace.open(store, turn("run", "one"), Clock.systemUTC())) {
			retained = stage.root();
			Files.writeString(stage.resolve("source", true, List.of()), "edit");
			stage.record();
			saved = runner(store).capture(stage);
		}
		try (var stage = StagedWorkspace.open(store, turn("foreign", "two"), Clock.systemUTC())) {
			assertThatThrownBy(() -> stage.restoreProposal(store, saved.id(), saved.owner(), saved.digest(), saved.revision()))
					.isInstanceOf(IOException.class);
		}
		Files.writeString(retained.resolve("source"), "tampered");
		try (var stage = StagedWorkspace.open(store, turn("run", "three"), Clock.systemUTC())) {
			assertThatThrownBy(() -> stage.restoreProposal(store, saved.id(), saved.owner(), saved.digest(), saved.revision()))
					.isInstanceOf(IOException.class);
		}
		Files.writeString(retained.resolve("source"), "edit");
		Files.writeString(work.resolve("source"), "human edit");
		try (var stage = StagedWorkspace.open(store, turn("run", "four"), Clock.systemUTC())) {
			assertThatThrownBy(() -> stage.restoreProposal(store, saved.id(), saved.owner(), saved.digest(), saved.revision()))
					.isInstanceOf(IOException.class);
			assertThat(Files.readString(work.resolve("source"))).isEqualTo("human edit");
		}
	}
}
