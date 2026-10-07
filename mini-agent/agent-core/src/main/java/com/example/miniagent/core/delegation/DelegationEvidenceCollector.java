package com.example.miniagent.core.delegation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.example.miniagent.core.change.ChangeOwner;
import com.example.miniagent.core.change.ChangeSet;
import com.example.miniagent.core.change.ChangeStatus;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.change.EntryKind;
import com.example.miniagent.core.change.Fingerprinter;
import com.example.miniagent.core.change.PathPolicy;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.change.VerificationRunner.Result;
import com.example.miniagent.core.run.*;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;

/** Parent-owned observations; collection neither starts tools nor appends journal projections. */
public final class DelegationEvidenceCollector {
	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
	private final RunStore runs;
	private final ToolEvidenceStore tools;
	private final ChangeStore changes;

	public DelegationEvidenceCollector(RunStore runs, ToolEvidenceStore tools, ChangeStore changes) {
		this.runs = Objects.requireNonNull(runs);
		this.tools = Objects.requireNonNull(tools);
		this.changes = Objects.requireNonNull(changes);
	}

	/** Exact invocation input captured by trusted Java, not returned by the model. */
	public record ReadInput(String toolCallKey, String arguments) {
		public ReadInput { Objects.requireNonNull(toolCallKey); Objects.requireNonNull(arguments); }
	}

	/**
	 * Parent expectation. Stage policy must be the existing StagedWorkspace.policy().
	 * Verification must be the actual Runner result, including its post-process outcome.
	 */
	public record Request(ChildSpec spec, ChangeOwner owner, RunId parentRunId, UUID parentConversationId,
			String revision, String configurationDigest, List<ReadInput> reads,
			@Nullable PathPolicy stagePolicy, VerificationRunner.Request verificationRequest,
			@Nullable Result verification) {
		public Request {
			Objects.requireNonNull(spec); Objects.requireNonNull(owner);
			Objects.requireNonNull(parentRunId); Objects.requireNonNull(parentConversationId);
			if (revision == null || !revision.matches("[0-9a-f]{64}")
					|| configurationDigest == null || !configurationDigest.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("Invalid expected digest");
			reads = List.copyOf(reads);
		}
	}

	public enum Code { IDENTITY, NONTERMINAL, UNKNOWN, MISSING, FOREIGN, STALE, FAILED }
	public static final class Rejection extends IOException {
		private final Code code;
		public Rejection(Code code) { super(code.name()); this.code = code; }
		public Code code() { return this.code; }
	}
	private static void require(boolean condition, Code code) throws Rejection {
		if (!condition) throw new Rejection(code);
	}

	/** A missing proof rejects collection; execution success alone never produces Collected. */
	public ChildResult collect(Request request, ChildResult result) throws IOException {
		require(request.spec().childId().equals(result.childId())
				&& request.owner().sessionId().equals(result.conversationId().toString())
				&& request.owner().runId().equals(result.runId())
				&& request.owner().attemptId().equals(result.attemptId().toString()), Code.IDENTITY);
		require(result.status().terminal(), Code.NONTERMINAL);
		var record = this.runs.read(result.runId());
		var spec = record.spec();
		require(spec.conversationId().equals(result.conversationId().toString())
				&& spec.childId() != null && spec.childId().equals(result.childId())
				&& request.parentRunId().equals(spec.parentRunId())
				&& request.parentConversationId().toString().equals(spec.parentConversationId())
				&& spec.model().equals(result.model())
				&& (request.spec().model() == null || request.spec().model().equals(result.model()))
				&& Path.of(spec.cwd()).toRealPath().equals(this.changes.workspace()), Code.IDENTITY);
		var attempt = record.attempts().stream().filter(a ->
				a.attemptId().equals(result.attemptId().toString())).findFirst().orElseThrow(() -> new Rejection(Code.IDENTITY));
		require(attempt.endedAt() != null && record.state().terminal(), Code.NONTERMINAL);
		require(record.attempts().getLast().equals(attempt), Code.STALE);
		var facts = new ArrayList<ChildEvidence>();
		// Discard model-supplied proof metadata. Only narrative survives collection.
		result.evidence().stream().filter(ChildEvidence.ModelNarrative.class::isInstance).forEach(facts::add);
		if (result.status() != ChildStatus.SUCCEEDED) {
			return copy(result, facts, record.usage());
		}
		require(record.state() == RunState.COMPLETED, Code.FAILED);
		require(record.uncertainTools().isEmpty(), Code.UNKNOWN);
		var events = FileRunStore.events(this.runs, result.runId());
		var starts = new HashMap<String, RunEvent.ToolStarted>();
		var finishes = new HashMap<String, RunEvent.ToolFinished>();
		var acknowledged = new HashMap<String, ToolEvidenceStore.ResultEvidence>();
		for (var event : events) {
			if (event instanceof RunEvent.ToolStarted start) {
				// Unknown effects in any attempt cannot disappear behind a later success.
				require(start.snapshotDigest() != null, Code.MISSING);
				starts.put(start.attemptId() + ":" + start.toolCallKey(), start);
			}
			else if (event instanceof RunEvent.ToolFinished finish) {
				String key = finish.attemptId() + ":" + finish.toolCallKey();
				var start = starts.get(key);
				require(start != null, Code.MISSING);
				var value = this.tools.get(finish.resultDigest(), ToolEvidenceStore.ResultEvidence.class);
				var before = this.tools.get(start.snapshotDigest(), DriftGate.Snapshot.class);
				String specDigest = Fingerprinter.sha256(JSON.writeValueAsBytes(spec));
				require(value.schemaVersion() == 1 && value.runId().equals(result.runId().value())
						&& value.attemptId().equals(finish.attemptId())
						&& value.toolCallKey().equals(finish.toolCallKey())
						&& value.toolName().equals(start.toolName()) && value.argsDigest().equals(start.argsDigest())
						&& before.specDigest().equals(specDigest) && value.after().specDigest().equals(specDigest)
						&& before.workspace().cwd().equals(spec.cwd()) && value.after().workspace().cwd().equals(spec.cwd())
						&& before.tools().equals(value.after().tools())
						&& before.configurationDigest().equals(value.after().configurationDigest()), Code.FOREIGN);
				var definition = before.tools().get(start.toolName());
				require(definition != null && definition.effect().name().equals(start.effect().name()), Code.FOREIGN);
				if (request.spec().role() != ChildRole.IMPLEMENT) {
					require(start.effect() == RunEvent.Effect.READ_ONLY
							&& before.workspace().equals(value.after().workspace()), Code.FAILED);
				}
				if (finish.attemptId().equals(attempt.attemptId())) {
					require(request.spec().scope().toolNames().contains(start.toolName()), Code.FOREIGN);
					if (request.spec().role() != ChildRole.VERIFY)
						require(before.configurationDigest().equals(request.configurationDigest()), Code.STALE);
					require(value.after().workspace().equals(WorkspaceFingerprint.capture(
							Path.of(spec.cwd()), this.tools.home())), Code.STALE);
					finishes.put(start.toolCallKey(), finish);
					acknowledged.put(start.toolCallKey(), value);
					facts.add(new ChildEvidence.ToolResult(start.toolCallKey(), start.toolName(),
							finish.resultDigest(), definition.effect(), ChildEvidence.ToolResult.Outcome.FINISHED));
				}
			}
		}
		for (var start : starts.values())
			require(events.stream().anyMatch(e -> e instanceof RunEvent.ToolFinished f
					&& f.attemptId().equals(start.attemptId()) && f.toolCallKey().equals(start.toolCallKey())), Code.UNKNOWN);

		var live = new PathPolicy(this.changes.workspace(), this.tools.home(), List.of());
		switch (request.spec().role()) {
			case RESEARCH -> {
				require(!request.reads().isEmpty(), Code.MISSING);
				require(Fingerprinter.treeDigest(new Fingerprinter(live).snapshot()).equals(request.revision()), Code.STALE);
				var seen = new java.util.HashSet<String>();
				for (var input : request.reads()) {
					require(seen.add(input.toolCallKey()), Code.IDENTITY);
					var value = acknowledged.get(input.toolCallKey());
					require(value != null && value.toolName().equals("read_file"), Code.MISSING);
					require(Fingerprinter.sha256(input.arguments().getBytes(StandardCharsets.UTF_8))
							.equals(value.argsDigest()), Code.FOREIGN);
					var args = JSON.readTree(input.arguments());
					require(args.isObject() && args.path("path").isString(), Code.MISSING);
					Path path = Path.of(args.path("path").asString());
					Path absolute = path.isAbsolute() ? path.normalize() : live.root().resolve(path).normalize();
					require(absolute.startsWith(live.root()) && request.spec().scope().readRoots().stream()
							.anyMatch(absolute::startsWith), Code.FOREIGN);
					String relative = live.root().relativize(absolute).toString();
					var source = new Fingerprinter(live).fingerprint(relative);
					require(source.kind() == EntryKind.REGULAR_FILE
							&& value.after().workspace().entries().containsKey(relative), Code.MISSING);
					facts.add(new ChildEvidence.Source(absolute, source.sha256()));
				}
			}
			case IMPLEMENT, VERIFY -> {
				require(request.stagePolicy() != null, Code.MISSING);
				try (var lease = this.changes.acquire()) {
					var view = lease.read();
					require(!view.recoveryRequired(), Code.STALE);
					var proposals = view.changes().stream().filter(s -> s.changeSet().owner().equals(request.owner())
							&& (s.changeSet().status() == ChangeStatus.PENDING_APPROVAL
							|| s.changeSet().status() == ChangeStatus.APPROVED)).toList();
					require(proposals.size() == 1, Code.MISSING);
					var snapshot = proposals.getFirst();
					ChangeSet set = snapshot.changeSet();
					require(set.model().equals(result.model()) && set.bindingIds().contains(attempt.bindingId())
							&& set.cwd().equals(this.changes.workspace().toString())
							&& snapshot.digest().equals(set.digest()), Code.FOREIGN);
					require(request.stagePolicy().root().equals(lease.stagingDirectory(request.owner())), Code.FOREIGN);
					require(new Fingerprinter(live).snapshot().equals(set.baseline()), Code.STALE);
					var staged = new java.util.TreeMap<>(new Fingerprinter(request.stagePolicy()).snapshot());
					for (var f : set.proposed().values()) if (f.kind() == EntryKind.SYMBOLIC_LINK) {
						require(!staged.containsKey(f.relativePath()), Code.STALE);
						staged.put(f.relativePath(), f);
					}
					require(staged.equals(set.proposed())
							&& Fingerprinter.treeDigest(staged).equals(request.revision()), Code.STALE);
					facts.add(new ChildEvidence.Proposal(set.changeSetId(), snapshot.digest(), attempt.bindingId()));
					if (request.spec().role() == ChildRole.IMPLEMENT) {
						require(finishes.values().stream().anyMatch(f -> {
							var s = starts.get(f.attemptId() + ":" + f.toolCallKey());
							return s.effect() == RunEvent.Effect.MUTATING && !set.createdAt().isBefore(s.at())
									&& !set.createdAt().isAfter(f.at());
						}), Code.MISSING);
					}
					else {
						var observed = request.verification();
						var invocation = request.verificationRequest();
						require(observed != null && observed.evidence() != null && invocation != null, Code.MISSING);
						var v = observed.evidence();
						require(v.owner().equals(request.owner()) && v.changeSetId().equals(set.changeSetId())
								&& snapshot.evidence().contains(v) && invocation.owner().equals(set.owner())
								&& invocation.changeSetId().equals(set.changeSetId())
								&& invocation.digest().equals(snapshot.digest())
								&& invocation.sourceRevision().equals(request.revision())
								&& invocation.argv().equals(v.argv()), Code.FOREIGN);
						require(!v.startedAt().isBefore(attempt.startedAt())
								&& !v.finishedAt().isAfter(attempt.endedAt())
								&& v.sourceCwd().equals(set.cwd()), Code.FOREIGN);
						require(observed.accepted(request.revision(), request.configurationDigest()), Code.FAILED);
						require(Fingerprinter.sha256(lease.readLog(set.changeSetId(), set.owner(), snapshot.digest(),
								v.evidenceId())).equals(v.logSha256()), Code.FOREIGN);
						facts.add(new ChildEvidence.Verification(observed.code(), v));
					}
				}
			}
		}
		facts.add(new ChildEvidence.Collected(request.spec().role(), request.owner(), request.revision(),
				request.configurationDigest(), facts));
		return copy(result, facts, record.usage());
	}

	/** Recollect immediately before COMPLETE. Per-role source/configuration identities may differ. */
	public DelegationResult aggregate(DelegationPlan plan, List<ChildResult> results, boolean parentCancelled,
			Map<String, Request> requests) throws IOException {
		var collected = new ArrayList<ChildResult>();
		for (var result : results) {
			Request request = requests.get(result.childId());
			require(request != null && plan.children().contains(request.spec()), Code.IDENTITY);
			try { collected.add(collect(request, result)); }
			catch (RunStoreException missing) {
				// Scheduler failures before allocation have no run journal, and cannot prove success.
				if (missing.code() != RunStoreException.Code.NOT_FOUND
						|| !result.status().terminal() || result.status() == ChildStatus.SUCCEEDED) throw missing;
				collected.add(copy(result, result.evidence().stream()
						.filter(ChildEvidence.ModelNarrative.class::isInstance).toList(), result.usage()));
			}
			catch (Rejection rejected) {
				// Evidence refusal is distinct from the honest child execution status.
				var facts = new ArrayList<ChildEvidence>();
				result.evidence().stream().filter(ChildEvidence.ModelNarrative.class::isInstance).forEach(facts::add);
				facts.add(new ChildEvidence.Rejected(rejected.code()));
				collected.add(copy(result, facts, this.runs.read(result.runId()).usage()));
			}
		}
		return DelegationResult.aggregateChecked(plan, collected, parentCancelled, (spec, result) -> {
			var request = requests.get(result.childId());
			return result.verifiedSuccess(spec.role(), request.revision(), request.configurationDigest());
		});
	}

	private static ChildResult copy(ChildResult result, List<ChildEvidence> facts, RunUsage usage) {
		return new ChildResult(result.childId(), result.conversationId(), result.runId(), result.attemptId(),
				result.model(), result.status(), result.failure(), facts, usage);
	}
}
