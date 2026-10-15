package com.example.miniagent.core.goal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.example.miniagent.core.change.AtomicFiles;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class GoalPersistenceTest {
	@TempDir Path home;
	@TempDir Path work;
	@org.junit.jupiter.api.BeforeEach void canonicalPaths() throws IOException {
		home = home.toRealPath(); work = work.toRealPath();
	}

	private GoalService service(GoalStore store) {
		return new GoalService(store, () -> { throw new AssertionError("Agent resolved"); },
				() -> { throw new AssertionError("Executor resolved"); },
				() -> { throw new AssertionError("Runs resolved"); },
				() -> { throw new AssertionError("Changes resolved"); },
				() -> { throw new AssertionError("Verifier resolved"); });
	}
	private GoalRecord submit(GoalService service) throws Exception {
		return service.submit(new GoalRequest("goal", List.of(new GoalRequest.Gate(
				List.of("/usr/bin/true"), 1000, 1024)), 6, 10000, null, 5),
				new ModelSelection(new ModelRef("codex", "gpt-6.1-sol"), java.util.Map.of("effort", "high")),
				"parent", work);
	}
	@Test void submissionIsPendingDurableAndDoesNotResolveExecutionServices() throws Exception {
		var store = new GoalStore(home);
		var service = service(store);
		var pending = submit(service);
		var restored = service(new GoalStore(home)).inspect(pending.id());
		assertThat(restored).isEqualTo(pending);
		assertThat(restored.state()).isEqualTo(GoalRecord.State.PENDING);
		assertThat(restored.spec().workspace()).isEqualTo(work.toRealPath().toString());
		assertThat(restored.spec().runId()).isNotNull();
		assertThat(service.list()).containsExactly(pending);
		assertThat(Files.readString(home.resolve("goals").resolve(pending.id()).resolve("state.json")))
				.doesNotContain("bindingId", "chatClient", "accessToken", "refreshToken");
	}
	@Test void stableClaimRejectsAnotherInstanceButAllowsBusyInspectionAndCancellation() throws Exception {
		var store = new GoalStore(home);
		var service = service(store);
		var pending = submit(service);
		Path lockPath = home.resolve("goals").resolve(pending.id()).resolve("claim.lock");
		Object inode;
		try (var claim = store.claim(pending.id()); var watch = store.watchCancellation(pending.id())) {
			inode = Files.readAttributes(lockPath, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
			assertThatThrownBy(() -> new GoalStore(home).claim(pending.id())).isInstanceOf(GoalStore.Busy.class);
			assertThat(service.inspect(pending.id())).isEqualTo(pending);
			service.cancel(pending.id());
			watch.signal().get(5, TimeUnit.SECONDS);
			assertThat(store.cancelled(pending.id())).isTrue();
		}
		try (var claim = new GoalStore(home).claim(pending.id())) {
			assertThat(Files.readAttributes(lockPath, java.nio.file.attribute.BasicFileAttributes.class).fileKey())
					.isEqualTo(inode);
		}
	}
	@Test void corruptionAndSymlinkEscapesFailClosedWithoutDeletingState() throws Exception {
		var store = new GoalStore(home);
		var pending = submit(service(store));
		Path state = home.resolve("goals").resolve(pending.id()).resolve("state.json");
		String json = Files.readString(state);
		AtomicFiles.write(state, json.replace("\"goal\":\"goal\"", "\"goal\":\"tampered\"").getBytes(
				java.nio.charset.StandardCharsets.UTF_8), 24 * 1024 * 1024);
		assertThatThrownBy(() -> store.read(pending.id())).isInstanceOf(IOException.class);
		assertThat(state).exists();
		assertThatThrownBy(() -> store.read("../escape")).isInstanceOf(IllegalArgumentException.class);
		Path alias = work.resolve("alias");
		Files.createSymbolicLink(alias, home);
		assertThatThrownBy(() -> new GoalStore(alias).list()).isInstanceOf(IOException.class);
	}
	@Test void pendingCancellationSurvivesRestartWithoutCreatingARun() throws Exception {
		var store = new GoalStore(home);
		var pending = submit(service(store));
		var cancelled = service(store).cancel(pending.id());
		assertThat(cancelled.state()).isEqualTo(GoalRecord.State.CANCELLED);
		assertThat(new GoalStore(home).cancelled(pending.id())).isTrue();
		assertThatThrownBy(() -> service(new GoalStore(home)).execute(pending.id())).isInstanceOf(IOException.class);
	}
}
