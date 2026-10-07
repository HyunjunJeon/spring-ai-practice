package com.example.miniagent.core.delegation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.Attempt;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunRecord;
import com.example.miniagent.core.run.RunSpec;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunStore;
import com.example.miniagent.core.run.RunStoreException;
import com.example.miniagent.core.run.RunUsage;
import com.example.miniagent.core.run.RunWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static com.example.miniagent.core.delegation.DelegationRecovery.Relation.*;
import static com.example.miniagent.core.run.RunStoreException.Code.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DelegationRecoveryTest {
	private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
	private static final String DIGEST = "a".repeat(64);
	private static final String DIFF = "b".repeat(64);
	private static final UUID BINDING = UUID.fromString("00000000-0000-0000-0000-000000000021");
	private static final ModelRef MODEL = new ModelRef("fake", "captured-child");
	private static final RunId PARENT = new RunId("parent");

	@TempDir
	Path home;

	private RunSpec spec(String id, RunId parent, String childId) {
		return new RunSpec(RunSpec.SCHEMA_VERSION, new RunId(id), "conversation-" + id,
				"original", parent, childId, MODEL, "fake", this.home.toString(),
				RunBudget.defaults(), DIGEST, DIGEST, AT);
	}

	private void root(FileRunStore store, String id) throws Exception {
		try (RunWriter writer = store.create(spec(id, null, null))) {
			assertThat(writer.record().state()).isEqualTo(RunState.CREATED);
		}
	}

	private void start(RunWriter writer) throws Exception {
		writer.append(new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE));
		writer.append(new RunEvent.AttemptStarted(AT, "actual-attempt", BINDING));
	}

	private void complete(FileRunStore store, String id, RunId parent, String childId) throws Exception {
		try (RunWriter writer = store.create(spec(id, parent, childId))) {
			start(writer);
			writer.append(new RunEvent.UsageRecorded(AT, "actual-attempt", RunEvent.UsageKind.MODEL_ROUND,
					"round", new RunUsage(7, false, 0, 100, 2)));
			writer.append(new RunEvent.AttemptEnded(AT, "actual-attempt"));
			writer.append(new RunEvent.StateChanged(AT, RunState.COMPLETED, RunEvent.Reason.NONE));
		}
	}

	private Path file(String id, String name) {
		return this.home.resolve("runs").resolve(id).resolve(name);
	}

	private void code(RunStoreException.Code expected, org.junit.jupiter.api.function.Executable action) {
		assertThat(assertThrows(RunStoreException.class, action).code()).isEqualTo(expected);
	}

	@Test
	void completedChildrenRetainActualIdentityModelAndUsageWithoutMemory() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		root(store, "parent");
		complete(store, "actual-child-run", PARENT, "research");
		DelegationRecovery recovery = new DelegationRecovery(new FileRunStore(this.home));
		var projection = recovery.children(PARENT);
		assertThat(projection.parent().spec().runId()).isEqualTo(PARENT);
		assertThat(projection.children()).hasSize(1);
		assertThat(projection.unclassified()).isEmpty();
		RunRecord child = projection.children().getFirst();
		assertThat(child.spec()).isEqualTo(spec("actual-child-run", PARENT, "research"));
		assertThat(child.spec().conversationId()).isNotEqualTo(child.spec().parentConversationId());
		assertThat(child.spec().model()).isEqualTo(MODEL);
		assertThat(child.attempts()).containsExactly(new Attempt("actual-attempt", BINDING, AT, AT));
		assertThat(child.state()).isEqualTo(RunState.COMPLETED);
		assertThat(child.reason()).isEqualTo(RunEvent.Reason.NONE);
		assertThat(child.usage()).isEqualTo(new RunUsage(7, false, 0, 100, 2));
		assertThat(recovery.inspectChild(PARENT, "research", new RunId("actual-child-run")).record())
				.isEqualTo(child);
		assertThat(this.home.resolve("sessions")).doesNotExist();
	}

	@Test
	void completedLiveWriterAndAllStoredAttemptsRemainUnchanged() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		root(store, "parent");
		try (RunWriter writer = store.create(spec("completed-live", PARENT, "research"))) {
			start(writer);
			writer.append(new RunEvent.UsageRecorded(AT, "actual-attempt", RunEvent.UsageKind.MODEL_ROUND,
					"first-round", new RunUsage(2, true, 0, 10, 0)));
			writer.append(new RunEvent.AttemptEnded(AT, "actual-attempt"));
			writer.append(new RunEvent.StateChanged(AT, RunState.INTERRUPTED, RunEvent.Reason.ERROR));
			writer.append(new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE));
			UUID nextBinding = UUID.fromString("00000000-0000-0000-0000-000000000022");
			writer.append(new RunEvent.AttemptStarted(AT, "next-attempt", nextBinding));
			writer.append(new RunEvent.UsageRecorded(AT, "next-attempt", RunEvent.UsageKind.COMPACTION,
					"summary", new RunUsage(3, false, 0, 20, 1)));
			writer.append(new RunEvent.AttemptEnded(AT, "next-attempt"));
			writer.append(new RunEvent.StateChanged(AT, RunState.COMPLETED, RunEvent.Reason.NONE));
			Map<Path, byte[]> before = bytes();
			DelegationRecovery recovery = new DelegationRecovery(new FileRunStore(this.home));
			RunRecord completed = recovery.children(PARENT).children().getFirst();
			assertThat(completed).isEqualTo(writer.record());
			assertThat(completed.attempts()).containsExactly(
					new Attempt("actual-attempt", BINDING, AT, AT),
					new Attempt("next-attempt", nextBinding, AT, AT));
			assertThat(completed.usage()).isEqualTo(new RunUsage(5, true, 0, 30, 1));
			code(RUN_LOCKED, () -> store.openWriter(new RunId("completed-live")));
			assertBytes(before);
		}
	}

	@Test
	void exactParentExcludesForeignHistoricalRootsAndGrandchildren() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		root(store, "parent");
		root(store, "other-parent");
		root(store, "independent-root");
		complete(store, "z-current", PARENT, "same-child");
		complete(store, "a-current", PARENT, "same-child");
		complete(store, "historical", new RunId("other-parent"), "same-child");
		complete(store, "grandchild", new RunId("a-current"), "same-child");
		DelegationRecovery recovery = new DelegationRecovery(new FileRunStore(this.home));
		assertThat(recovery.children(PARENT).children()).extracting(r -> r.spec().runId().value())
				.containsExactly("a-current", "z-current");
		assertThat(recovery.children(new RunId("other-parent")).children())
				.extracting(r -> r.spec().runId().value()).containsExactly("historical");
		assertThat(recovery.inspectChild(PARENT, "same-child", new RunId("historical")).relation())
				.isEqualTo(FOREIGN);
		assertThat(recovery.inspectChild(PARENT, "wrong-child", new RunId("a-current")).relation())
				.isEqualTo(FOREIGN);
		assertThat(recovery.inspectChild(PARENT, "same-child", new RunId("independent-root")).relation())
				.isEqualTo(MISSING_LINEAGE);
		assertThat(recovery.children(new RunId("independent-root")).children()).isEmpty();
		code(NOT_FOUND, () -> recovery.inspectChild(PARENT, "same-child", new RunId("descriptor-only")));
		code(NOT_FOUND, () -> recovery.children(new RunId("missing-parent")));
	}

	@ParameterizedTest
	@EnumSource(value = RunState.class, names = {"RUNNING", "WAITING_APPROVAL"})
	void writerReleaseAndReopenProjectInterruptionWithoutChangingEvidence(RunState state) throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		root(store, "parent");
		RunEvent.ToolStarted unknown = new RunEvent.ToolStarted(AT, "actual-attempt", "uncertain-write",
				"write", DIGEST, RunEvent.Effect.MUTATING, DIFF);
		try (RunWriter writer = store.create(spec("active-child", PARENT, "implement"))) {
			start(writer);
			writer.append(new RunEvent.UsageRecorded(AT, "actual-attempt", RunEvent.UsageKind.MODEL_ROUND,
					"round", new RunUsage(11, true, 1, 25, 3)));
			writer.append(unknown);
			if (state == RunState.WAITING_APPROVAL) {
				writer.append(new RunEvent.ApprovalRequested(AT, "actual-attempt", "request",
						"approval-tool", DIGEST, DIFF));
				writer.append(new RunEvent.ApprovalResolved(AT, "actual-attempt", "request",
						RunEvent.Decision.DEFERRED));
				writer.append(new RunEvent.StateChanged(AT, state, RunEvent.Reason.NONE));
			}
			DelegationRecovery live = new DelegationRecovery(new FileRunStore(this.home));
			assertThat(live.children(PARENT).children().getFirst().state()).isEqualTo(state);
			code(RUN_LOCKED, () -> store.openWriter(new RunId("active-child")));
			// Append acknowledgement establishes the exact persisted boundary, not a timed wait.
			writer.append(new RunEvent.UsageRecorded(AT, "actual-attempt", RunEvent.UsageKind.ACTIVE_TIME,
					"active", new RunUsage(0, false, 0, 5, 0)));
		}
		Map<Path, byte[]> before = bytes();
		DelegationRecovery restarted = new DelegationRecovery(new FileRunStore(this.home));
		RunRecord interrupted = restarted.children(PARENT).children().getFirst();
		assertThat(interrupted.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(interrupted.reason()).isEqualTo(RunEvent.Reason.PROCESS_LOST);
		assertThat(interrupted.usage()).isEqualTo(new RunUsage(11, true, 1, 30, 3));
		assertThat(interrupted.attempts()).containsExactly(new Attempt("actual-attempt", BINDING, AT, null));
		assertThat(interrupted.uncertainTools()).containsExactly(unknown);
		assertThat(interrupted.spec().model()).isEqualTo(MODEL);
		assertThat(restarted.inspectChild(PARENT, "implement", new RunId("active-child")).record())
				.isEqualTo(interrupted);
		assertBytes(before);
	}

	@Test
	void storedApprovalExportPreservesOnlyExistingMetadataWithoutGrantingPermission() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		root(store, "parent");
		try (RunWriter writer = store.create(spec("waiting", PARENT, "research"))) {
			start(writer);
			writer.append(new RunEvent.ApprovalRequested(AT, "actual-attempt", "request",
					"tool-call", DIGEST, DIFF));
			writer.append(new RunEvent.ApprovalResolved(AT, "actual-attempt", "request",
					RunEvent.Decision.DEFERRED));
			writer.append(new RunEvent.StateChanged(AT, RunState.WAITING_APPROVAL, RunEvent.Reason.NONE));
		}
		var expected = store.export(new RunId("waiting"));
		DelegationRecovery recovery = new DelegationRecovery(new FileRunStore(this.home));
		var stored = recovery.storedMetadata(new RunId("waiting"));
		assertThat(stored).isEqualTo(expected);
		JsonMapper json = JsonMapper.builder().build();
		var request = json.readTree(stored.eventsLog().lines().toList().get(2).split("\t", 4)[3]);
		assertThat(request.path("type").asString()).isEqualTo("ApprovalRequested");
		assertThat(request.path("payload").path("requestId").asString()).isEqualTo("request");
		assertThat(request.path("payload").path("attemptId").asString()).isEqualTo("actual-attempt");
		assertThat(request.path("payload").path("toolCallKey").asString()).isEqualTo("tool-call");
		assertThat(request.path("payload").path("argsDigest").asString()).isEqualTo(DIGEST);
		assertThat(request.path("payload").path("diffDigest").asString()).isEqualTo(DIFF);
		var resolution = json.readTree(stored.eventsLog().lines().toList().get(3).split("\t", 4)[3]);
		assertThat(resolution.path("payload").path("decision").asString()).isEqualTo("DEFERRED");
		assertThat(recovery.children(PARENT).children().getFirst().state()).isEqualTo(RunState.INTERRUPTED);
	}

	@Test
	void corruptAndFutureEntriesRemainUnclassifiedAndExportsRemainOpaque() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		root(store, "parent");
		complete(store, "good", PARENT, "research");
		complete(store, "corrupt", PARENT, "research");
		complete(store, "future", PARENT, "research");
		String corrupt = Files.readString(file("corrupt", "events.log")).replaceFirst("RUNNING", "FAILED");
		Files.writeString(file("corrupt", "events.log"), corrupt);
		JsonMapper json = JsonMapper.builder().build();
		ObjectNode future = (ObjectNode) json.readTree(Files.readString(file("future", "spec.json")));
		future.put("schemaVersion", RunSpec.SCHEMA_VERSION + 1);
		Files.writeString(file("future", "spec.json"), json.writeValueAsString(future));
		Map<Path, byte[]> before = bytes();
		DelegationRecovery recovery = new DelegationRecovery(new FileRunStore(this.home));
		var projection = recovery.children(PARENT);
		assertThat(projection.children()).extracting(r -> r.spec().runId().value()).containsExactly("good");
		assertThat(projection.unclassified()).extracting(DelegationRecovery.ReadFailure::runId)
				.containsExactly(new RunId("corrupt"), new RunId("future"));
		assertThat(projection.unclassified()).extracting(DelegationRecovery.ReadFailure::code)
				.containsExactly(CORRUPT, FUTURE_SCHEMA);
		code(CORRUPT, () -> recovery.inspectChild(PARENT, "research", new RunId("corrupt")));
		code(FUTURE_SCHEMA, () -> recovery.inspectChild(PARENT, "research", new RunId("future")));
		assertThat(recovery.storedMetadata(new RunId("corrupt")).eventsLog()).isEqualTo(corrupt);
		assertBytes(before);
		Files.writeString(file("parent", "spec.json"), "{}");
		code(CORRUPT, () -> recovery.children(PARENT));
	}

	@Test
	void incompleteTailRemainsUntouchedAndDurableCancelReasonIsPreserved() throws Exception {
		FileRunStore store = new FileRunStore(this.home);
		root(store, "parent");
		try (RunWriter writer = store.create(spec("cancelled", PARENT, "research"))) {
			start(writer);
			writer.append(new RunEvent.UsageRecorded(AT, "actual-attempt", RunEvent.UsageKind.MODEL_ROUND,
					"round", RunUsage.empty()));
			writer.append(new RunEvent.AttemptEnded(AT, "actual-attempt"));
			writer.append(new RunEvent.StateChanged(AT, RunState.CANCELLED, RunEvent.Reason.USER_CANCELLED));
		}
		Files.writeString(file("cancelled", "events.log"), "1\t6\t00000000\t{", StandardOpenOption.APPEND);
		Map<Path, byte[]> before = bytes();
		DelegationRecovery recovery = new DelegationRecovery(new FileRunStore(this.home));
		RunRecord record = recovery.children(PARENT).children().getFirst();
		assertThat(record.sequence()).isEqualTo(5);
		assertThat(record.state()).isEqualTo(RunState.CANCELLED);
		assertThat(record.reason()).isEqualTo(RunEvent.Reason.USER_CANCELLED);
		assertBytes(before);
	}

	@Test
	void recoveryUsesOnlySuppliedStoreReadListExportAndNeverAppliesOrWrites() throws Exception {
		FileRunStore actual = new FileRunStore(this.home);
		root(actual, "parent");
		complete(actual, "child", PARENT, "research");
		Files.writeString(this.home.resolve("source.txt"), "original");
		Files.writeString(this.home.resolve("staged.txt"), "unapplied");
		Map<Path, byte[]> before = bytes();
		// These read operations still use the real FileRunStore; any writer acquisition fails the test.
		RunStore readOnly = new RunStore() {
			@Override public RunWriter create(RunSpec spec) { throw new AssertionError("create"); }
			@Override public RunWriter openWriter(RunId id) { throw new AssertionError("openWriter"); }
			@Override public RunRecord read(RunId id) throws RunStoreException { return actual.read(id); }
			@Override public List<RunId> list() throws RunStoreException { return actual.list(); }
			@Override public OpaqueRun export(RunId id) throws RunStoreException { return actual.export(id); }
		};
		DelegationRecovery recovery = new DelegationRecovery(readOnly);
		assertThat(recovery.children(PARENT).children()).hasSize(1);
		assertThat(recovery.inspectChild(PARENT, "research", new RunId("child")).relation()).isEqualTo(CHILD);
		assertThat(recovery.storedMetadata(new RunId("child"))).isEqualTo(actual.export(new RunId("child")));
		assertBytes(before);
		assertThat(this.home.resolve("sessions")).doesNotExist();
	}

	private Map<Path, byte[]> bytes() throws Exception {
		Map<Path, byte[]> result = new LinkedHashMap<>();
		try (var paths = Files.walk(this.home)) {
			for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
				result.put(this.home.relativize(path), Files.readAllBytes(path));
			}
		}
		return result;
	}

	private void assertBytes(Map<Path, byte[]> before) throws Exception {
		Map<Path, byte[]> after = bytes();
		assertThat(after.keySet()).isEqualTo(before.keySet());
		for (Path path : before.keySet()) {
			assertThat(after.get(path)).as(path.toString()).isEqualTo(before.get(path));
		}
	}
}
