package com.example.miniagent.core.run;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import com.example.miniagent.core.model.ModelRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static com.example.miniagent.core.run.RunStoreException.Code.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileRunStoreTest {
	private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
	private static final String DIGEST = "a".repeat(64);
	private static final UUID BINDING = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@TempDir
	Path home;

	private RunSpec spec(String id) {
		return new RunSpec(1, new RunId(id), "conversation-" + id, "original", null, null,
				new ModelRef("fake", "test"), "fake", this.home.toString(), RunBudget.defaults(),
				DIGEST, DIGEST, AT);
	}

	private Path file(String id, String name) {
		return this.home.resolve("runs").resolve(id).resolve(name);
	}

	private void start(RunWriter writer) throws Exception {
		writer.append(new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE));
		writer.append(new RunEvent.AttemptStarted(AT, "attempt", BINDING));
	}

	private void end(RunWriter writer) throws Exception {
		writer.append(new RunEvent.UsageRecorded(AT, "attempt", RunEvent.UsageKind.MODEL_ROUND,
				"round", new RunUsage(7, false, 0, 100, 0)));
		writer.append(new RunEvent.AttemptEnded(AT, "attempt"));
	}

	private void code(RunStoreException.Code expected, org.junit.jupiter.api.function.Executable action) {
		assertThat(assertThrows(RunStoreException.class, action).code()).isEqualTo(expected);
	}

	@Test
	void createReopenProjectSpecAndListWithoutModelExecution() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		RunSpec spec = spec("one");
		try (RunWriter writer = store.create(spec)) {
			assertThat(writer.record().state()).isEqualTo(RunState.CREATED);
			assertThat(store.read(spec.runId())).isEqualTo(writer.record());
			start(writer);
			end(writer);
			writer.append(new RunEvent.StateChanged(AT, RunState.COMPLETED, RunEvent.Reason.NONE));
			assertThat(writer.record().sequence()).isEqualTo(5);
		}
		FileRunStore restarted = new FileRunStore(this.home);
		RunRecord record = restarted.read(spec.runId());
		assertThat(record.spec()).isEqualTo(spec);
		assertThat(record.state()).isEqualTo(RunState.COMPLETED);
		assertThat(record.usage()).isEqualTo(new RunUsage(7, false, 0, 100, 0));
		assertThat(record.attempts()).containsExactly(new Attempt("attempt", BINDING, AT, AT));
		assertThat(restarted.list()).containsExactly(spec.runId());
		try (RunWriter writer = restarted.openWriter(spec.runId())) {
			assertThat(writer.record()).isEqualTo(record);
			code(TERMINAL, () -> writer.append(new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE)));
		}
		assertThat(this.home.resolve("sessions")).doesNotExist();
	}

	@Test
	void secondWriterRunLocked() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			code(RUN_LOCKED, () -> new FileRunStore(this.home).openWriter(new RunId("one")));
			start(writer);
			assertThat(store.read(new RunId("one")).state()).isEqualTo(RunState.RUNNING);
			// Failed same-JVM acquisition and reads must not release the process lock.
			assertProcessLocked(file("one", "writer.lock"));
			code(RUN_LOCKED, () -> store.openWriter(new RunId("one")));
			writer.append(new RunEvent.StateChanged(AT, RunState.INTERRUPTED, RunEvent.Reason.NONE));
		}
		try (RunWriter reopened = store.openWriter(new RunId("one"))) {
			assertThat(reopened.record().state()).isEqualTo(RunState.INTERRUPTED);
		}
	}

	private void assertProcessLocked(Path lockFile) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		Process process = new ProcessBuilder(java, "-cp", classpath, LockProbe.class.getName(),
				lockFile.toString()).redirectErrorStream(true).start();
		try {
			CompletableFuture<Process> exit = process.onExit();
			assertThat(exit.get(10, TimeUnit.SECONDS).exitValue()).isZero();
			assertThat(new String(process.getInputStream().readAllBytes(), UTF_8).trim()).isEqualTo("LOCKED");
		}
		finally {
			if (process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	public static class LockProbe {
		public static void main(String[] args) throws Exception {
			try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE)) {
				try (var lock = channel.tryLock()) {
					System.out.println(lock == null ? "LOCKED" : "STOLEN");
				}
			}
		}
	}

	@Test
	void activeLogWithoutWriterProjectsProcessLostAndAcquisitionRecordsIt() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			start(writer);
			writer.append(new RunEvent.ToolStarted(AT, "attempt", "tool-call", "write", DIGEST, RunEvent.Effect.MUTATING));
		}
		byte[] beforeRead = Files.readAllBytes(file("one", "events.log"));
		RunRecord lost = store.read(new RunId("one"));
		assertThat(lost.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(lost.reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
		assertThat(lost.uncertainTools()).hasSize(1);
		assertThat(Files.readAllBytes(file("one", "events.log"))).isEqualTo(beforeRead);
		try (RunWriter writer = store.openWriter(new RunId("one"))) {
			assertThat(writer.record().state()).isEqualTo(RunState.INTERRUPTED);
			assertThat(writer.record().usage().tokensUnknown()).isTrue();
			assertThat(writer.record().attempts().getLast().endedAt()).isEqualTo(AT);
			writer.append(new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE));
			writer.append(new RunEvent.AttemptStarted(AT, "next", UUID.randomUUID()));
			assertThat(writer.record().uncertainTools()).hasSize(1);
		}
	}

	@Test
	void waitingApprovalProcessLostIsNotUserCancellation() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			start(writer);
			writer.append(new RunEvent.ApprovalRequested(AT, "attempt", "request", "tool", DIGEST, DIGEST));
			writer.append(new RunEvent.StateChanged(AT, RunState.WAITING_APPROVAL, RunEvent.Reason.NONE));
		}
		assertThat(store.read(new RunId("one")).state()).isEqualTo(RunState.INTERRUPTED);
		try (RunWriter writer = store.openWriter(new RunId("one"))) {
			assertThat(writer.record().reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
			writer.append(new RunEvent.StateChanged(AT, RunState.CANCELLED, RunEvent.Reason.USER_CANCELLED));
		}
		assertThat(store.read(new RunId("one")).reason()).isEqualTo(RunEvent.Reason.USER_CANCELLED);
	}

	@Test
	void incompleteTailOnlyRecoveredUnderExclusiveWriter() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			writer.append(new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE));
			writer.append(new RunEvent.StateChanged(AT, RunState.INTERRUPTED, RunEvent.Reason.NONE));
		}
		Path log = file("one", "events.log");
		byte[] original = Files.readAllBytes(log);
		Files.writeString(log, "1\t3\t00000000\t{\"type\":", StandardOpenOption.APPEND);
		assertThat(store.read(new RunId("one")).sequence()).isEqualTo(2);
		assertThat(Files.size(log)).isGreaterThan(original.length);
		try (RunWriter writer = store.openWriter(new RunId("one"))) {
			assertThat(Files.readAllBytes(log)).isEqualTo(original);
			writer.append(new RunEvent.StateChanged(AT, RunState.CANCELLED, RunEvent.Reason.USER_CANCELLED));
		}
		assertThat(store.read(new RunId("one")).sequence()).isEqualTo(3);
	}

	@Test
	void midCorruptionRefused() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			start(writer);
			end(writer);
		}
		Path log = file("one", "events.log");
		String damaged = Files.readString(log).replaceFirst("RUNNING", "FAILED");
		Files.writeString(log, damaged);
		code(CORRUPT, () -> store.read(new RunId("one")));
		code(CORRUPT, () -> store.openWriter(new RunId("one")));
		assertThat(Files.readString(log)).isEqualTo(damaged);
	}

	@Test
	void completeFinalRowBadCrcIsNotARecoverableTail() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			writer.append(new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE));
		}
		Path log = file("one", "events.log");
		String damaged = Files.readString(log).replaceFirst("\t[0-9a-f]{8}\t", "\tffffffff\t");
		Files.writeString(log, damaged);
		code(CORRUPT, () -> store.openWriter(new RunId("one")));
		assertThat(Files.readString(log)).isEqualTo(damaged);
	}

	@Test
	void sequenceGapRejectedEvenWithValidCrcAndPartialTailPreserved() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			start(writer);
		}
		Path log = file("one", "events.log");
		String[] rows = Files.readString(log).split("\n");
		String[] parts = rows[1].split("\t", 4);
		String damaged = rows[0] + "\n" + frame(1, 4, parts[3]) + "partial";
		Files.writeString(log, damaged);
		code(CORRUPT, () -> store.openWriter(new RunId("one")));
		assertThat(Files.readString(log)).isEqualTo(damaged);
	}

	private static String frame(int version, long sequence, String payload) {
		String content = version + "\t" + sequence + "\t" + payload;
		CRC32C crc = new CRC32C();
		crc.update(content.getBytes(UTF_8));
		return version + "\t" + sequence + "\t"
				+ String.format(java.util.Locale.ROOT, "%08x", crc.getValue()) + "\t" + payload + "\n";
	}

	@Test
	void futureSpecOpaqueExportAndListWithoutCurrentDeserialization() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			assertThat(writer.record().sequence()).isZero();
		}
		String future = "{\"schemaVersion\":2,\"unknownFuturePayload\":{\"credential\":\"not-an-auth-file\"}}";
		Files.writeString(file("one", "spec.json"), future);
		code(FUTURE_SCHEMA, () -> store.read(new RunId("one")));
		code(FUTURE_SCHEMA, () -> store.openWriter(new RunId("one")));
		assertThat(store.export(new RunId("one")).specJson()).isEqualTo(future);
		assertThat(store.list()).containsExactly(new RunId("one"));
		assertThat(Files.readString(file("one", "spec.json"))).isEqualTo(future);
	}

	@Test
	void futureEventSchemaCannotBeTruncatedOrDecodedAsCurrent() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			assertThat(writer.record().state()).isEqualTo(RunState.CREATED);
		}
		String future = frame(2, 1, "{\"unrecognized\":\"opaque\"}") + "2\t2\tpartial";
		Files.writeString(file("one", "events.log"), future);
		code(FUTURE_SCHEMA, () -> store.read(new RunId("one")));
		code(FUTURE_SCHEMA, () -> store.openWriter(new RunId("one")));
		assertThat(store.export(new RunId("one")).eventsLog()).isEqualTo(future);
		assertThat(Files.readString(file("one", "events.log"))).isEqualTo(future);
	}

	@Test
	void identifiableFuturePartialTailIsPreserved() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			assertThat(writer.record().sequence()).isZero();
		}
		Files.writeString(file("one", "events.log"), "2\t1\tpartial");
		code(FUTURE_SCHEMA, () -> store.openWriter(new RunId("one")));
		assertThat(Files.readString(file("one", "events.log"))).isEqualTo("2\t1\tpartial");
	}

	@Test
	void interruptedWorkerAppendDoesNotCloseWriterChannel() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			CountDownLatch ready = new CountDownLatch(1);
			CountDownLatch trigger = new CountDownLatch(1);
			CompletableFuture<Boolean> result = new CompletableFuture<>();
			Thread worker = new Thread(() -> {
				ready.countDown();
				try {
					assertThat(trigger.await(10, TimeUnit.SECONDS)).isTrue();
					Thread.currentThread().interrupt();
					writer.append(new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE));
					result.complete(Thread.currentThread().isInterrupted());
				}
				catch (Throwable ex) {
					result.completeExceptionally(ex);
				}
			});
			worker.start();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			trigger.countDown();
			assertThat(result.get(10, TimeUnit.SECONDS)).isTrue();
			writer.append(new RunEvent.StateChanged(AT, RunState.INTERRUPTED, RunEvent.Reason.NONE));
			assertThat(writer.record().sequence()).isEqualTo(2);
			assertThat(store.read(new RunId("one")).sequence()).isEqualTo(2);
		}
	}

	@Test
	void terminalMustBeLastAfterUsageAndAttemptEnd() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			start(writer);
			byte[] before = Files.readAllBytes(file("one", "events.log"));
			code(INVALID_TRANSITION, () -> writer.append(
					new RunEvent.StateChanged(AT, RunState.COMPLETED, RunEvent.Reason.NONE)));
			code(INVALID_TRANSITION, () -> writer.append(new RunEvent.AttemptEnded(AT, "attempt")));
			assertThat(Files.readAllBytes(file("one", "events.log"))).isEqualTo(before);
			end(writer);
			writer.append(new RunEvent.StateChanged(AT, RunState.COMPLETED, RunEvent.Reason.NONE));
			byte[] terminal = Files.readAllBytes(file("one", "events.log"));
			code(TERMINAL, () -> writer.append(new RunEvent.UsageRecorded(AT, "attempt",
					RunEvent.UsageKind.COMPACTION, "summary", RunUsage.empty())));
			assertThat(Files.readAllBytes(file("one", "events.log"))).isEqualTo(terminal);
		}
	}

	@Test
	void unknownUsageStickyAndDuplicateCallUsageRejected() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			start(writer);
			RunEvent usage = new RunEvent.UsageRecorded(AT, "attempt", RunEvent.UsageKind.MODEL_ROUND,
					"first", new RunUsage(0, true, 0, 0, 0));
			writer.append(usage);
			code(INVALID_TRANSITION, () -> writer.append(usage));
			writer.append(new RunEvent.UsageRecorded(AT, "attempt", RunEvent.UsageKind.COMPACTION,
					"summary", new RunUsage(4, false, 0, 0, 2)));
			assertThat(writer.record().usage()).isEqualTo(new RunUsage(4, true, 0, 0, 2));
			writer.append(new RunEvent.AttemptEnded(AT, "attempt"));
			writer.append(new RunEvent.StateChanged(AT, RunState.COMPLETED, RunEvent.Reason.NONE));
		}
		assertThat(store.read(new RunId("one")).usage().tokensUnknown()).isTrue();
		assertThat(RunUsage.empty().tokensUnknown()).isFalse();
	}

	@Test
	void approvalsAndToolEventsRoundTripWithTypedDigests() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			start(writer);
			writer.append(new RunEvent.ApprovalRequested(AT, "attempt", "request", "tool", DIGEST, null));
			writer.append(new RunEvent.StateChanged(AT, RunState.WAITING_APPROVAL, RunEvent.Reason.NONE));
			writer.append(new RunEvent.ApprovalResolved(AT, "attempt", "request", RunEvent.Decision.DEFERRED));
			code(INVALID_TRANSITION, () -> writer.append(
					new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE)));
			writer.append(new RunEvent.ApprovalResolved(AT, "attempt", "request", RunEvent.Decision.APPROVED));
			code(INVALID_TRANSITION, () -> writer.append(
					new RunEvent.ApprovalResolved(AT, "attempt", "request", RunEvent.Decision.APPROVED)));
			writer.append(new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE));
			writer.append(new RunEvent.ToolStarted(AT, "attempt", "tool", "read", DIGEST, RunEvent.Effect.READ_ONLY));
			assertThat(writer.record().uncertainTools()).hasSize(1);
			writer.append(new RunEvent.ToolFinished(AT, "attempt", "tool", DIGEST));
			writer.append(new RunEvent.UsageRecorded(AT, "attempt", RunEvent.UsageKind.TOOLS,
					"tool-usage", new RunUsage(0, false, 1, 0, 0)));
			end(writer);
			writer.append(new RunEvent.StateChanged(AT, RunState.COMPLETED, RunEvent.Reason.NONE));
		}
		RunRecord loaded = store.read(new RunId("one"));
		assertThat(loaded.uncertainTools()).isEmpty();
		assertThat(loaded.usage().toolCalls()).isEqualTo(1);
	}

	@Test
	void privateDataAndParentChildFields() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		RunSpec spec = new RunSpec(1, new RunId("child-run"), "child-conversation", "parent-conversation",
				new RunId("parent-run"), "child", new ModelRef("fake", "a"), "fake", this.home.toString(),
				new RunBudget(Duration.ofMinutes(1), 100L, 5), DIGEST, DIGEST, AT);
		try (RunWriter writer = store.create(spec)) {
			assertThat(writer.record().spec()).isEqualTo(spec);
		}
		assertThat(store.read(spec.runId()).spec()).isEqualTo(spec);
		Path directory = file("child-run", "spec.json").getParent();
		assertThat(Files.getPosixFilePermissions(this.home.resolve("runs")))
			.isEqualTo(PosixFilePermissions.fromString("rwx------"));
		assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
		for (String name : List.of("spec.json", "events.log", "writer.lock")) {
			assertThat(Files.getPosixFilePermissions(directory.resolve(name)))
				.isEqualTo(PosixFilePermissions.fromString("rw-------"));
		}
		JsonMapper json = JsonMapper.builder().build();
		assertThat(json.readTree(Files.readAllBytes(directory.resolve("spec.json"))).properties()
				.stream().map(java.util.Map.Entry::getKey)).containsExactlyInAnyOrder(
						"schemaVersion", "runId", "conversationId", "parentConversationId", "parentRunId",
						"childId", "model", "wireFamily", "cwd", "budget", "optionsFingerprint", "toolsFingerprint", "createdAt");
		assertThat(store.export(spec.runId()).specJson()).doesNotContain("apiKey", "headers", "client",
				"environment", "password", "synthetic-secret");
	}

	@ParameterizedTest
	@ValueSource(strings = {"../escape", "..", ".", "/absolute", "a/b", "a\\b", "", "a.json", "a\nb", "-start"})
	void unsafeIdsRejected(String id) {
		assertThatThrownBy(() -> new RunId(id)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void invalidParentChildIdentityAndBudgetRejected() throws Exception {
		assertThat(RunBudget.defaults()).isEqualTo(new RunBudget(Duration.ofMinutes(30), null, 50));
		assertThatThrownBy(() -> new RunBudget(Duration.ZERO, null, 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunBudget(Duration.ofSeconds(1), -1L, 1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunSpec(1, new RunId("one"), "conversation", "original",
				new RunId("one"), "child", new ModelRef("fake", "a"), "fake", this.home.toString(),
				RunBudget.defaults(), DIGEST, DIGEST, AT)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RunSpec(1, new RunId("one"), "conversation", "original",
				new RunId("parent"), null, new ModelRef("fake", "a"), "fake", this.home.toString(),
				RunBudget.defaults(), DIGEST, DIGEST, AT)).isInstanceOf(IllegalArgumentException.class);
		var unmanaged = new RunSpec(1, new RunId("one"), "conversation", "original",
				null, "child", new ModelRef("fake", "a"), "fake", this.home.toString(),
				RunBudget.defaults(), DIGEST, DIGEST, AT);
		var store = new FileRunStore(this.home);
		try (var writer = store.create(unmanaged)) {
			assertThat(writer.record().spec()).isEqualTo(unmanaged);
		}
		assertThat(new FileRunStore(this.home).read(unmanaged.runId()).spec()).isEqualTo(unmanaged);
	}

	@Test
	void invalidTransitionsAndNonmonotonicTimeDoNotAppend() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			code(INVALID_TRANSITION, () -> writer.append(
					new RunEvent.StateChanged(AT, RunState.COMPLETED, RunEvent.Reason.NONE)));
			start(writer);
			code(INVALID_TRANSITION, () -> writer.append(
					new RunEvent.UsageRecorded(AT.minusSeconds(1), "attempt", RunEvent.UsageKind.MODEL_ROUND,
							"bad", RunUsage.empty())));
			code(INVALID_TRANSITION, () -> writer.append(new RunEvent.AttemptStarted(AT, "other", UUID.randomUUID())));
			writer.append(new RunEvent.StateChanged(AT, RunState.INTERRUPTED, RunEvent.Reason.NONE));
			code(INVALID_TRANSITION, () -> writer.append(
					new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE)));
			end(writer);
			writer.append(new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE));
			code(INVALID_TRANSITION, () -> writer.append(new RunEvent.AttemptStarted(AT, "attempt", UUID.randomUUID())));
		}
	}

	@Test
	void unknownCurrentTypeAndExtraCredentialFieldsAreCorruptNotDropped() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			assertThat(writer.record().sequence()).isZero();
		}
		String bad = frame(1, 1, "{\"type\":\"Unrecognized\",\"payload\":{}}");
		Files.writeString(file("one", "events.log"), bad);
		code(CORRUPT, () -> store.openWriter(new RunId("one")));
		assertThat(Files.readString(file("one", "events.log"))).isEqualTo(bad);
		Files.writeString(file("one", "events.log"), "");
		String original = Files.readString(file("one", "spec.json"));
		String extra = original.substring(0, original.length() - 1) + ",\"apiKey\":\"synthetic-secret\"}";
		Files.writeString(file("one", "spec.json"), extra);
		code(CORRUPT, () -> store.read(new RunId("one")));
		assertThat(Files.readString(file("one", "spec.json"))).isEqualTo(extra);
	}

	@Test
	void missingUsageFieldsCannotDeserializeAsMeasuredZero() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			start(writer);
		}
		String original = Files.readString(file("one", "events.log"));
		String payload = "{\"type\":\"UsageRecorded\",\"payload\":{\"at\":\"" + AT
				+ "\",\"attemptId\":\"attempt\",\"kind\":\"MODEL_ROUND\",\"callId\":\"round\","
				+ "\"usage\":{\"knownTokens\":0,\"toolCalls\":0,\"activeMillis\":0,\"overshootTokens\":0}}}";
		String damaged = original + frame(1, 3, payload);
		Files.writeString(file("one", "events.log"), damaged);
		code(CORRUPT, () -> store.read(new RunId("one")));
		code(CORRUPT, () -> store.openWriter(new RunId("one")));
		assertThat(Files.readString(file("one", "events.log"))).isEqualTo(damaged);
	}

	@Test
	void symlinksAndMissingFilesProduceTypedFailuresWithoutClobbering() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		try (RunWriter writer = store.create(spec("one"))) {
			assertThat(writer.record().sequence()).isZero();
		}
		Path outside = this.home.resolve("outside");
		Files.writeString(outside, "sentinel");
		Files.delete(file("one", "spec.json"));
		Files.createSymbolicLink(file("one", "spec.json"), outside);
		code(IO, () -> store.read(new RunId("one")));
		code(IO, () -> store.openWriter(new RunId("one")));
		assertThat(Files.readString(outside)).isEqualTo("sentinel");
		code(NOT_FOUND, () -> store.read(new RunId("absent")));
		code(ALREADY_EXISTS, () -> store.create(spec("one")));
	}

	@Test
	void explicitCloseIdempotentAndLaterAppendRejected() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		RunWriter writer = store.create(spec("one"));
		writer.close();
		writer.close();
		code(CLOSED, () -> writer.append(new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE)));
		try (RunWriter next = store.openWriter(new RunId("one"))) {
			assertThat(next.record().state()).isEqualTo(RunState.CREATED);
		}
	}
}
