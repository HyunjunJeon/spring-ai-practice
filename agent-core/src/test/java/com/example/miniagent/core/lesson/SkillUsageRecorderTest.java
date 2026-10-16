package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.StatePaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.miniagent.core.SkillUseListener.Kind.*;
import static com.example.miniagent.core.lesson.LessonRecord.Scope.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Skill directories are built the way SkillRegistry builds them, from the raw configured roots. The temp folder is
 * left unresolved, so on macOS it sits under /var while lesson workspaces and the store home resolve to /private/var.
 */
class SkillUsageRecorderTest {
	static final Instant NOW = Instant.parse("2026-10-10T09:00:00.25Z");
	@TempDir Path root;
	Path workspace;
	Path home;
	final AtomicReference<Instant> now = new AtomicReference<>(NOW);
	SkillUsageStore store;
	SkillUsageRecorder recorder;
	@BeforeEach void layout() throws Exception {
		workspace = Files.createDirectory(root.resolve("workspace"));
		home = root.resolve("home");
		store = new SkillUsageStore(StatePaths.home(home), SkillUsageStoreTest.clock(now::get));
		recorder = new SkillUsageRecorder(store, workspace, StatePaths.home(home));
	}

	private static Path skill(Path skillRoot, String name) throws IOException {
		Path directory = Files.createDirectories(skillRoot.resolve(name));
		Files.writeString(directory.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: test\n---\n\nbody\n");
		return skillRoot.resolve(name);
	}
	/** The workspace string a lesson, and so its export link, carries. */
	private String lessonWorkspace() throws IOException {
		return LessonRecord.Actor.capture("operator-session", workspace).workspace();
	}
	private SkillUsage only() throws IOException {
		var entries = store.read().entries();
		assertThat(entries).hasSize(1);
		return entries.getFirst();
	}
	private static List<Path> tree(Path directory) throws IOException {
		try (Stream<Path> paths = Files.walk(directory)) { return paths.sorted().toList(); }
	}

	@Test void chosenAndForcedLoadsOfALinkedProjectSkillAreCounted() throws Exception {
		Path directory = skill(StatePaths.projectSkills(workspace), "parser-fix");
		store.link(SkillUsageStoreTest.link(PROJECT, lessonWorkspace(), "parser-fix"));
		now.set(NOW.plusSeconds(60));
		recorder.loaded(directory, CHOSEN);
		recorder.loaded(directory, CHOSEN);
		recorder.loaded(directory, FORCED);
		var entry = only();
		assertThat(entry.chosenLoads()).isEqualTo(2);
		assertThat(entry.forcedLoads()).isEqualTo(1);
		assertThat(entry.lastLoadedAt()).isEqualTo(NOW.plusSeconds(60));
		assertThat(recorder.failures()).isZero();
	}

	@Test void aLinkedGlobalSkillIsCounted() throws Exception {
		store.link(SkillUsageStoreTest.link(GLOBAL, null, "parser-fix"));
		Path directory = skill(StatePaths.userSkills(home), "parser-fix");
		recorder.loaded(directory, FORCED);
		assertThat(only().forcedLoads()).isEqualTo(1);
		assertThat(only().lastLoadedAt()).isEqualTo(NOW);
	}

	@Test void projectAndUserRootsThatCoincideStillCountAGlobalLink() throws Exception {
		// Started from the home's parent, the project root and the user root are one folder.
		Path user = Files.createDirectory(root.resolve("user"));
		Path sharedHome = StatePaths.home(user.resolve(".mini-agent"));
		var shared = new SkillUsageStore(sharedHome, SkillUsageStoreTest.clock(now::get));
		shared.link(SkillUsageStoreTest.link(GLOBAL, null, "parser-fix"));
		Path directory = skill(StatePaths.projectSkills(user), "parser-fix");
		new SkillUsageRecorder(shared, user, sharedHome).loaded(directory, CHOSEN);
		assertThat(shared.read().entries().getFirst().chosenLoads()).isEqualTo(1);
	}

	@Test void unlinkedOrForeignDirectoriesRecordNothingAndCreateNoStore() throws Exception {
		Path project = StatePaths.projectSkills(workspace);
		var directories = List.of(skill(project, "code-tour"), skill(root.resolve("elsewhere"), "parser-fix"),
				project.resolve("never-written"), root.resolve("no-such-root").resolve("parser-fix"));
		for (Path directory : directories) {
			recorder.loaded(directory, CHOSEN);
			recorder.loaded(directory, FORCED);
		}
		assertThat(home).doesNotExist();
		assertThat(recorder.failures()).isZero();
		// With a store present, the same loads still leave the linked entry alone.
		var linked = SkillUsageStoreTest.link(PROJECT, lessonWorkspace(), "parser-fix");
		store.link(linked);
		for (Path directory : directories) recorder.loaded(directory, CHOSEN);
		assertThat(only()).isEqualTo(linked);
		assertThat(recorder.failures()).isZero();
	}

	@Test void aRetiredEntryIsNotCounted() throws Exception {
		Path directory = skill(StatePaths.projectSkills(workspace), "parser-fix");
		var retired = new SkillUsage(PROJECT, lessonWorkspace(), "parser-fix", UUID.randomUUID().toString(), 2,
				Instant.parse("2026-09-01T10:00:00Z"), "b".repeat(64), 3, 0, Instant.parse("2026-09-02T10:00:00Z"),
				Instant.parse("2026-10-05T10:00:00Z"), "UNUSED", null);
		store.link(retired);
		recorder.loaded(directory, CHOSEN);
		recorder.loaded(directory, FORCED);
		assertThat(only()).isEqualTo(retired);
		assertThat(recorder.failures()).isZero();
	}

	@Test void aStoreHeldByAnotherOwnerFailsOnlyTheCountNotTheLoad() throws Exception {
		Path directory = skill(StatePaths.projectSkills(workspace), "parser-fix");
		store.link(SkillUsageStoreTest.link(PROJECT, lessonWorkspace(), "parser-fix"));
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var finished = SkillUsageStoreTest.hold(store, entered, release);
		try {
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatCode(() -> recorder.loaded(directory, CHOSEN)).doesNotThrowAnyException();
			assertThat(recorder.failures()).isEqualTo(1);
		}
		finally { release.countDown(); }
		finished.get(5, TimeUnit.SECONDS);
		assertThat(only().chosenLoads()).isZero();
		recorder.loaded(directory, CHOSEN);
		assertThat(only().chosenLoads()).isEqualTo(1);
		assertThat(recorder.failures()).isEqualTo(1);
	}

	@Test void aBrieflyHeldStoreIsRetried() throws Exception {
		Path directory = skill(StatePaths.projectSkills(workspace), "parser-fix");
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var reads = new AtomicInteger();
		var finished = new AtomicReference<CompletableFuture<Void>>();
		// The recorder reads the clock once per attempt. Its second read lets the holder finish first.
		var retrying = new SkillUsageStore(StatePaths.home(home), SkillUsageStoreTest.clock(() -> {
			if (reads.incrementAndGet() == 2) {
				release.countDown();
				finished.get().join();
			}
			return NOW;
		}));
		store.link(SkillUsageStoreTest.link(PROJECT, lessonWorkspace(), "parser-fix"));
		var counting = new SkillUsageRecorder(retrying, workspace, StatePaths.home(home));
		try {
			finished.set(SkillUsageStoreTest.hold(store, entered, release));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			counting.loaded(directory, CHOSEN);
		}
		finally { release.countDown(); }
		assertThat(only().chosenLoads()).isEqualTo(1);
		assertThat(counting.failures()).isZero();
	}

	@Test void aBrokenCallIsCountedInsteadOfThrown() {
		assertThatCode(() -> recorder.loaded(null, CHOSEN)).doesNotThrowAnyException();
		assertThat(recorder.failures()).isEqualTo(1);
	}

	@Test void recordingWritesNothingUnderTheWorkspace() throws Exception {
		Path directory = skill(StatePaths.projectSkills(workspace), "parser-fix");
		store.link(SkillUsageStoreTest.link(PROJECT, lessonWorkspace(), "parser-fix"));
		var before = tree(workspace);
		recorder.loaded(directory, CHOSEN);
		recorder.loaded(directory, FORCED);
		assertThat(only().chosenLoads()).isEqualTo(1);
		assertThat(tree(workspace)).isEqualTo(before);
		assertThat(workspace.resolve(".mini-agent/skill-usage")).doesNotExist();
		assertThat(StatePaths.home(home).resolve("skill-usage/usage.json")).exists();
	}

	@Test void theLessonConfigurationWiresARecorderWithoutTouchingTheHome() throws Exception {
		var properties = new AgentProperties();
		properties.setCwd(workspace);
		properties.setHome(home);
		var configuration = new LessonAutoConfiguration();
		var wiredStore = configuration.skillUsageStore(properties);
		var listener = configuration.skillUseListener(wiredStore, properties);
		assertThat(listener).isInstanceOf(SkillUsageRecorder.class);
		assertThat(home).doesNotExist();
		wiredStore.link(SkillUsageStoreTest.link(PROJECT, lessonWorkspace(), "parser-fix"));
		listener.loaded(skill(StatePaths.projectSkills(workspace), "parser-fix"), CHOSEN);
		assertThat(wiredStore.read().entries().getFirst().chosenLoads()).isEqualTo(1);
	}
}
