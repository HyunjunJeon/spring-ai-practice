package com.example.miniagent.core.change;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.model.ToolContextKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import static org.assertj.core.api.Assertions.*;

class StagedWorkspaceTest {
	@TempDir Path work;
	@TempDir Path state;

	private TurnContext turn(String attempt) throws Exception {
		ChatModel model = prompt -> new ChatResponse(List.of());
		var binding = new ModelBinding(UUID.randomUUID(), new ModelRef("fake", "test"), "a".repeat(64),
				"fake", model, ChatClient.builder(model).build());
		return new TurnContext("session", UUID.randomUUID().toString(), this.work.toRealPath(),
				binding, "run", attempt);
	}

	@Test void allocatedStageWithRealHomePersistsActualTreesDiffsOwnerAndModes() throws Exception {
		Path source = Files.writeString(this.work.resolve("source.txt"), "before\n");
		var mode = PosixFilePermissions.fromString("rwxr-x---");
		Files.setPosixFilePermissions(source, mode);
		var store = new FileChangeStore(this.state, this.work);
		var turn = turn("attempt");
		Path stagePath;
		ChangeOwner owner;
		try (var stage = StagedWorkspace.open(store, turn, Clock.systemUTC())) {
			stagePath = stage.root();
			owner = stage.owner();
			assertThat(stage.policy().agentHome()).isEqualTo(this.state.toRealPath());
			assertThat(stage.policy().allowsRead(stagePath.resolve("source.txt"))).isTrue();
			assertThat(stage.policy().allowsRead(this.state.resolve("auth"))).isFalse();
			assertThat(stage.resolve(source.toString(), true, List.of())).isEqualTo(stagePath.resolve("source.txt"));
			Files.writeString(stage.resolve("source.txt", true, List.of()), "after\n");
			stage.record();
			var set = store.read().changes().getLast().changeSet();
			assertThat(set.owner()).isEqualTo(owner);
			assertThat(set.bindingIds()).containsExactly(turn.binding().bindingId());
			assertThat(set.model()).isEqualTo(turn.binding().ref());
			assertThat(set.baseline().get("source.txt").sha256()).isEqualTo(Fingerprinter.sha256("before\n".getBytes()));
			assertThat(set.proposed().get("source.txt").posixMode()).isEqualTo(0750);
			assertThat(set.diffs()).hasSize(1);
			assertThat(set.diffs().getFirst().patch()).contains("-before", "+after");
			assertThat(Files.readString(source)).isEqualTo("before\n");
			assertThat(Files.getPosixFilePermissions(source)).isEqualTo(mode);
		}
		try (var lease = new FileChangeStore(this.state, this.work).acquire()) {
			assertThat(lease.stagingDirectory(owner)).isEqualTo(stagePath);
			assertThat(Files.readString(stagePath.resolve("source.txt"))).isEqualTo("after\n");
		}
	}

	@Test void absoluteWorkspaceAliasMapsWithoutFollowingLinksInsideWorkspace() throws Exception {
		Files.createDirectory(this.work.resolve("src"));
		Files.writeString(this.work.resolve("src/source.txt"), "source");
		Files.createSymbolicLink(this.work.resolve("internal-link"), this.work.resolve("src"));
		Path alias = this.state.resolve("workspace-alias");
		Files.createSymbolicLink(alias, this.work.toRealPath());
		try (var stage = StagedWorkspace.open(new FileChangeStore(this.state, this.work),
				turn("alias"), Clock.systemUTC())) {
			assertThat(stage.resolve(alias.resolve("src/source.txt").toString(), true, List.of()))
					.isEqualTo(stage.root().resolve("src/source.txt"));
			assertThat(stage.resolve(alias.resolve("src/new.txt").toString(), true, List.of()))
					.isEqualTo(stage.root().resolve("src/new.txt"));
			assertThatThrownBy(() -> stage.resolve(alias.resolve("internal-link/new.txt").toString(), true, List.of()))
					.isInstanceOfSatisfying(PathPolicy.Violation.class,
							ex -> assertThat(ex.code()).isEqualTo(PathPolicy.Code.SYMLINK));
		}
	}

	@Test void excludesBeforeCopyRetainsLinksAsEvidenceAndSkillsReadOnly() throws Exception {
		Files.writeString(this.work.resolve(".env"), "secret");
		Files.createDirectories(this.work.resolve("target"));
		Files.writeString(this.work.resolve("target/generated"), "generated");
		Files.createDirectories(this.work.resolve(".mini-agent/skills/demo"));
		Files.writeString(this.work.resolve(".mini-agent/skills/demo/SKILL.md"), "instructions");
		Files.createSymbolicLink(this.work.resolve("link"), this.state);
		var store = new FileChangeStore(this.state, this.work);
		try (var stage = StagedWorkspace.open(store, turn("one"), Clock.systemUTC())) {
			assertThat(stage.root().resolve(".env")).doesNotExist();
			assertThat(stage.root().resolve("target")).doesNotExist();
			assertThat(stage.root().resolve("link")).doesNotExist();
			assertThat(Files.readString(stage.resolve(".mini-agent/skills/demo/SKILL.md", false, List.of())))
					.isEqualTo("instructions");
			for (String path : List.of(".env", "target/x", ".mini-agent/skills/demo/SKILL.md", "link", "link/x", "../escape")) {
				assertThatThrownBy(() -> stage.resolve(path, true, List.of()))
						.isInstanceOfAny(PathPolicy.Violation.class, PathPolicy.InvalidRelativePath.class);
			}
			Files.writeString(stage.resolve("new.txt", true, List.of()), "new");
			stage.record();
			assertThat(store.read().changes().getLast().changeSet().diffs())
					.extracting(Diff::relativePath).containsExactly("new.txt");
		}
	}

	@Test void externalRootsRemainReadOnlyAndHomeIsNeverGranted() throws Exception {
		Path outside = Files.createDirectory(this.state.resolve("external"));
		Path external = Files.writeString(outside.resolve("note"), "read");
		Path home = Files.createDirectory(this.state.resolve("home"));
		var store = new FileChangeStore(home, this.work);
		try (var stage = StagedWorkspace.open(store, turn("one"), Clock.systemUTC())) {
			assertThat(stage.resolve(external.toString(), false, List.of(outside))).isEqualTo(external.toRealPath());
			assertThatThrownBy(() -> stage.resolve(external.toString(), true, List.of(outside)))
					.isInstanceOf(PathPolicy.Violation.class);
			assertThatThrownBy(() -> stage.resolve(home.resolve("auth/token").toString(), false, List.of(home)))
					.isInstanceOf(PathPolicy.Violation.class);
			Files.createSymbolicLink(stage.root().resolve("alias"), external);
			assertThatThrownBy(() -> stage.resolve("alias", true, List.of(outside)))
					.isInstanceOf(PathPolicy.Violation.class);
			Files.delete(stage.root().resolve("alias"));
		}
	}

	@Test void oversizedFileToolContentIsRefusedBeforeStagePublication() throws Exception {
		var store = new FileChangeStore(this.state, this.work);
		try (var stage = StagedWorkspace.open(store, turn("one"), Clock.systemUTC())) {
			Path target = stage.resolve("oversized", true, List.of());
			assertThatThrownBy(() -> stage.validateWrite(target, "x".repeat(16 * 1024 * 1024 + 1)))
					.isInstanceOfSatisfying(PathPolicy.Violation.class,
							ex -> assertThat(ex.code()).isEqualTo(PathPolicy.Code.LIMIT));
			assertThat(target).doesNotExist();
			assertThat(store.read().changes()).isEmpty();
		}
	}

	@Test void freshAttemptSupersedesUnappliedProposalWithoutDeletingOldStage() throws Exception {
		Files.writeString(this.work.resolve("source"), "base");
		var store = new FileChangeStore(this.state, this.work);
		Path old;
		try (var stage = StagedWorkspace.open(store, turn("one"), Clock.systemUTC())) {
			old = stage.root();
			Files.writeString(stage.resolve("source", true, List.of()), "first");
		}
		try (var stage = StagedWorkspace.open(store, turn("two"), Clock.systemUTC())) {
			assertThat(store.read().changes().getFirst().changeSet().status()).isEqualTo(ChangeStatus.SUPERSEDED);
			assertThat(Files.readString(old.resolve("source"))).isEqualTo("first");
			assertThat(Files.readString(stage.resolve("source", false, List.of()))).isEqualTo("base");
		}
	}

	@Test void liveExternalEditMarksProposalStaleWithoutOverwritingEitherTree() throws Exception {
		Path source = Files.writeString(this.work.resolve("source"), "baseline");
		var store = new FileChangeStore(this.state, this.work);
		try (var stage = StagedWorkspace.open(store, turn("one"), Clock.systemUTC())) {
			Files.writeString(stage.resolve("source", true, List.of()), "proposal");
			stage.record();
			Files.writeString(source, "human");
			stage.record();
			assertThat(store.read().changes().getLast().changeSet().status()).isEqualTo(ChangeStatus.STALE);
			assertThat(Files.readString(source)).isEqualTo("human");
			assertThat(Files.readString(stage.resolve("source", false, List.of()))).isEqualTo("proposal");
		}
	}

	@Test void interruptionPersistsProposalClearsScopeAndDeletesOnlyPrivateTemp() throws Exception {
		var store = new FileChangeStore(this.state, this.work);
		var stage = StagedWorkspace.open(store, turn("one"), Clock.systemUTC());
		Path temp = stage.tempDirectory();
		try (stage; var scope = stage.openScope()) {
			Files.writeString(stage.resolve("created", true, List.of()), "retained");
			Files.writeString(temp.resolve("scratch"), "temporary");
			assertThat(StagedWorkspace.from(new ToolContext(Map.of(ToolContextKeys.STAGED_WORKSPACE, stage))))
					.contains(stage);
			Thread.currentThread().interrupt();
		}
		try {
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally { Thread.interrupted(); }
		assertThat(StagedWorkspace.current()).isEmpty();
		assertThat(temp).doesNotExist();
		assertThat(Files.readString(stage.root().resolve("created"))).isEqualTo("retained");
		assertThat(this.work.resolve("created")).doesNotExist();
		assertThat(store.read().changes()).hasSize(1);
	}
}
