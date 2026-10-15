package com.example.miniagent.core.run;

import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ToolJournalTest {
	@TempDir Path temp;

	@Test void forcedStartedIsVisibleBeforeRealEffectThenHashedEvidenceFinishes() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		Path file = f.cwd.resolve("effect.txt");
		f.action.set(p -> f.invoke(i -> {
			try {
				RunContext context = RunContext.fromToolContext(i.context()).orElseThrow();
				var durable = new FileRunStore(f.home).read(context.spec().runId());
				assertThat(durable.uncertainTools()).hasSize(1);
				assertThat(Files.readString(f.home.resolve("runs").resolve(context.spec().runId().value()).resolve("events.log")))
						.contains("ToolStarted").doesNotContain("ToolFinished");
				assertThat(file).doesNotExist();
				Files.writeString(file, "real-effect");
				return Files.readString(file);
			}
			catch (java.io.IOException ex) { throw new UncheckedIOException(ex); }
		}));
		var record = f.start();
		assertThat(record.state()).isEqualTo(RunState.COMPLETED);
		var events = FileRunStore.events(f.runs, record.spec().runId());
		var start = events.stream().filter(RunEvent.ToolStarted.class::isInstance).map(RunEvent.ToolStarted.class::cast).findFirst().orElseThrow();
		var finish = events.stream().filter(RunEvent.ToolFinished.class::isInstance).map(RunEvent.ToolFinished.class::cast).findFirst().orElseThrow();
		assertThat(start.toolCallKey()).isNotEqualTo(start.toolName());
		assertThat(start.argsDigest()).isEqualTo(RunRequestStore.digest("{}".getBytes()));
		var evidence = f.requests.evidence().get(finish.resultDigest(), ToolEvidenceStore.ResultEvidence.class);
		assertThat(evidence.result()).isEqualTo(Files.readString(file));
		assertThat(new JournalResumeContributor(f.runs, f.requests.evidence()).verifiedEvidence(record, "goal")).contains(finish.resultDigest());
		assertThat(Files.getPosixFilePermissions(f.home.resolve("tool-evidence").resolve(finish.resultDigest() + ".json")))
				.isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
	}

	@Test void failureAndCancellationLeaveUnknownWithoutFabricatedResults() throws Exception {
		for (boolean cancel : List.of(false, true)) {
			var f = new SafeResumeTest.Fixture(Files.createDirectory(this.temp.resolve(String.valueOf(cancel))), false);
			f.action.set(p -> f.invoke(i -> {
				try { Files.writeString(f.cwd.resolve("effect"), "partial"); }
				catch (java.io.IOException ex) { throw new UncheckedIOException(ex); }
				if (cancel) Thread.currentThread().interrupt();
				else throw new IllegalStateException("mid-effect");
				return "uncommitted";
			}));
			try {
				var record = f.start();
				assertThat(record.state()).isEqualTo(RunState.INTERRUPTED);
				assertThat(record.uncertainTools()).hasSize(1);
				assertThat(Thread.currentThread().isInterrupted()).isEqualTo(cancel);
				Thread.interrupted(); // Inspect files only after observing the preserved cancellation flag.
				assertThat(f.runs.export(record.spec().runId()).eventsLog()).doesNotContain("ToolFinished");
			}
			finally { Thread.interrupted(); }
		}
	}

	@Test void forcedStartSurvivesBoundedSubprocessCrashWithoutClaimingSuccess() throws Exception {
		Path home = Files.createDirectory(this.temp.resolve("crash-home"));
		String javaCommand = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		Process child = new ProcessBuilder(javaCommand, "-cp", cp, CrashFixture.class.getName(), home.toString())
				.redirectErrorStream(true).start();
		var signal = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream()))) {
				return reader.readLine();
			}
			catch (java.io.IOException ex) { throw new UncheckedIOException(ex); }
		});
		try {
			assertThat(signal.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("STARTED_FORCED");
			child.destroyForcibly();
			child.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
			var runs = new FileRunStore(home);
			var record = runs.read(new RunId("crash"));
			assertThat(record.state()).isEqualTo(RunState.INTERRUPTED);
			assertThat(record.reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
			assertThat(record.uncertainTools()).hasSize(1);
			try (var writer = runs.openWriter(record.spec().runId())) {
				assertThat(writer.record().uncertainTools()).hasSize(1);
				assertThat(writer.record().attempts().getLast().endedAt()).isNotNull();
			}
			assertThat(runs.export(record.spec().runId()).eventsLog()).doesNotContain("ToolFinished");
		}
		finally {
			if (child.isAlive()) { child.destroyForcibly(); child.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS); }
		}
	}

	@Test void capturedAsyncContextKeepsCorrectWriterAndSummaryIsBoundedText() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, true);
		Path file = Files.writeString(f.cwd.resolve("source"), "x".repeat(5000));
		f.action.set(p -> {
			RunContext captured = RunContext.current().orElseThrow();
			var completed = new java.util.concurrent.CompletableFuture<String>();
			Thread thread = new Thread(() -> {
				try {
					assertThat(RunContext.current()).isEmpty();
					completed.complete(f.journal.intercept(new com.example.miniagent.core.ToolInterceptor.ToolInvocation(
							f.tool, "{}", new org.springframework.ai.chat.model.ToolContext(Map.of(
									RunContext.TOOL_CONTEXT_KEY, captured))), i -> {
								try {
									assertThat(captured.writer().record().uncertainTools()).hasSize(1);
									return Files.readString(file);
								}
								catch (java.io.IOException ex) { throw new UncheckedIOException(ex); }
							}));
				}
				catch (Throwable ex) { completed.completeExceptionally(ex); }
			});
			thread.start();
			try {
				assertThat(completed.get(5, java.util.concurrent.TimeUnit.SECONDS)).hasSize(5000);
				thread.join(5000);
				assertThat(thread.isAlive()).isFalse();
			}
			catch (Exception ex) { throw new RuntimeException(ex); }
			throw new Stop(Stop.Kind.INTERRUPTED);
		});
		var record = f.start();
		String summary = new JournalResumeContributor(f.runs, f.requests.evidence()).verifiedEvidence(record, "goal");
		assertThat(summary).contains("truncated").hasSizeLessThan(4096);
	}

	public static class CrashFixture {
		public static void main(String[] args) throws Exception {
			Path home = Path.of(args[0]);
			String digest = "a".repeat(64);
			var spec = new RunSpec(1, new RunId("crash"), "crash-conversation", "parent", null, null,
					new com.example.miniagent.core.model.ModelRef("test", "one"), "test", home.toString(),
					RunBudget.defaults(), digest, digest, java.time.Instant.now());
			try (var writer = new FileRunStore(home).create(spec)) {
				writer.append(new RunEvent.StateChanged(java.time.Instant.now(), RunState.RUNNING, RunEvent.Reason.NONE));
				writer.append(new RunEvent.AttemptStarted(java.time.Instant.now(), "old", java.util.UUID.randomUUID()));
				writer.append(new RunEvent.ToolStarted(java.time.Instant.now(), "old", "unique-key", "mutating_tool",
						digest, RunEvent.Effect.MUTATING));
				System.out.println("STARTED_FORCED");
				System.out.flush();
				System.in.read(); // Parent controls this event boundary; no time-based wait.
				throw new AssertionError("Parent must kill the fixture");
			}
		}
	}

	@Test void completedMutationCannotRepeatOnFreshAttemptJustBecauseGoalRepeats() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		Path file = f.cwd.resolve("effect");
		f.action.set(p -> {
			f.invoke(i -> {
				try { Files.writeString(file, "once", StandardOpenOption.CREATE_NEW); return Files.readString(file); }
				catch (java.io.IOException ex) { throw new UncheckedIOException(ex); }
			});
			throw new Stop(Stop.Kind.INTERRUPTED);
		});
		var record = f.start();
		assertThat(record.uncertainTools()).isEmpty();
		assertThat(f.executor.resume(record.spec().runId(), f.selected, f.cwd).record().state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(FileRunStore.events(f.runs, record.spec().runId()).stream().filter(RunEvent.ToolStarted.class::isInstance).count()).isEqualTo(1);
	}

	@Test void resultTamperingAndSchemaCorruptionAreRejectedAndPreserved() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		f.action.set(p -> { f.invoke(i -> {
			try { return Files.readString(Files.writeString(f.cwd.resolve("result"), "actual")); }
			catch (java.io.IOException ex) { throw new UncheckedIOException(ex); }
		}); throw new Stop(Stop.Kind.INTERRUPTED); });
		var record = f.start();
		var finish = FileRunStore.events(f.runs, record.spec().runId()).stream().filter(RunEvent.ToolFinished.class::isInstance)
				.map(RunEvent.ToolFinished.class::cast).findFirst().orElseThrow();
		Path artifact = f.home.resolve("tool-evidence").resolve(finish.resultDigest() + ".json");
		Files.writeString(artifact, "{}");
		assertThatThrownBy(() -> f.executor.resume(record.spec().runId(), f.selected, f.cwd)).isInstanceOf(java.io.IOException.class);
		assertThat(Files.readString(artifact)).isEqualTo("{}");
		String future = f.requests.evidence().put(Map.of("schemaVersion", 9));
		assertThatThrownBy(() -> f.requests.evidence().get(future, DriftGate.Snapshot.class)).isInstanceOf(java.io.IOException.class);
	}

	@Test void modelSuppliedEffectAndNewToolSchemaCannotGrantReadRetryAuthority() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		f.action.set(p -> f.journal.intercept(new com.example.miniagent.core.ToolInterceptor.ToolInvocation(f.tool,
				"{\"effect\":\"READ_ONLY\"}", new org.springframework.ai.chat.model.ToolContext(Map.of(
						RunContext.TOOL_CONTEXT_KEY, RunContext.current().orElseThrow()))),
				i -> { throw new Stop(Stop.Kind.INTERRUPTED); }));
		var record = f.start();
		assertThat(record.uncertainTools().getFirst().effect()).isEqualTo(RunEvent.Effect.MUTATING);
		f.action.set(p -> {});
		var unknown = record.uncertainTools().getFirst();
		assertThatThrownBy(() -> f.executor.resume(record.spec().runId(), f.selected, f.cwd,
				new RunExecutor.ResumeOptions(List.of(new RunExecutor.ToolResolutionInput(unknown.attemptId(),
						unknown.toolCallKey(), RunEvent.Resolution.READ_RETRY, "model", "claim")), null))).isInstanceOf(RunExecutor.Failure.class);
	}
}
