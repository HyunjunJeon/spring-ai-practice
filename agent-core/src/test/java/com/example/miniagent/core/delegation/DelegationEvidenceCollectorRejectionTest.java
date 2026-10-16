package com.example.miniagent.core.delegation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.example.miniagent.core.change.*;
import com.example.miniagent.core.delegation.DelegationEvidenceCollector.Code;
import com.example.miniagent.core.delegation.DelegationEvidenceCollector.ReadInput;
import com.example.miniagent.core.delegation.DelegationEvidenceCollector.Request;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.run.*;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

/** Each forged journal, read or proposal yields the exact collector Rejection code, never COMPLETE. */
class DelegationEvidenceCollectorRejectionTest {
	private static final String SOURCE_READ = "{\"path\":\"source.txt\"}";
	@TempDir Path work;
	@TempDir Path home;
	private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
	private final ModelRef model = new ModelRef("fake", "test");
	private final String config = "a".repeat(64);
	private final UUID parentConversation = UUID.randomUUID();
	private final RunId parentRun = RunId.random();
	private FileRunStore runs;
	private ToolEvidenceStore tools;
	private FileChangeStore changes;
	private DelegationEvidenceCollector collector;

	@BeforeEach void prepare() throws Exception {
		Files.writeString(this.work.resolve("source.txt"), "before\n");
		this.runs = new FileRunStore(this.home);
		this.tools = new ToolEvidenceStore(this.home);
		this.changes = new FileChangeStore(this.home, this.work);
		this.collector = new DelegationEvidenceCollector(this.runs, this.tools, this.changes);
	}

	/** Child journal written through the real RunStore, which replays every append through RunProjector. */
	private final class Fixture implements AutoCloseable {
		final ChildSpec child;
		final UUID conversation = UUID.randomUUID();
		final UUID attempt = UUID.randomUUID();
		final UUID binding = UUID.randomUUID();
		final String turn = UUID.randomUUID().toString();
		final RunId run = RunId.random();
		final ChangeOwner owner;
		final RunSpec spec;
		final RunWriter writer;
		StagedWorkspace stage;

		Fixture(String id, ChildRole role) throws Exception {
			this(id, role, Set.of(work.toRealPath()));
		}

		Fixture(String id, ChildRole role, Set<Path> readRoots) throws Exception {
			this.owner = owner(this.attempt);
			this.child = new ChildSpec(id, "Collect source evidence", role, model, Set.of(),
					new ToolScope(Set.of("read_file", "list_files", "write_file"), readRoots,
							role == ChildRole.IMPLEMENT ? Set.of(work.toRealPath()) : Set.of(),
							Set.of(), role == ChildRole.IMPLEMENT, false, false, false),
					RunBudget.defaults(), 1, true);
			this.spec = new RunSpec(1, this.run, this.conversation.toString(), parentConversation.toString(),
					parentRun, id, model, "fake", work.toRealPath().toString(), RunBudget.defaults(),
					config, config, Instant.EPOCH);
			this.writer = runs.create(this.spec);
			this.writer.append(new RunEvent.StateChanged(Instant.EPOCH, RunState.RUNNING, RunEvent.Reason.NONE));
			this.writer.append(new RunEvent.AttemptStarted(Instant.EPOCH, this.attempt.toString(), this.binding));
		}

		ChangeOwner owner(UUID attemptId) {
			return new ChangeOwner(this.conversation.toString(), this.run, attemptId.toString(), this.turn);
		}

		void stage() throws Exception {
			ChatModel fake = new ChatModel() {
				@Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
				@Override public ChatResponse call(Prompt prompt) { return new ChatResponse(List.of()); }
			};
			var bound = new ModelBinding(this.binding, model, config, "fake", fake, ChatClient.builder(fake).build());
			var context = new TurnContext(this.owner.sessionId(), this.owner.turnId(), work.toRealPath(),
					bound, this.run.value(), this.attempt.toString());
			this.stage = StagedWorkspace.open(changes, context, clock);
		}

		DriftGate.Snapshot snapshot(String name, boolean mutation) throws Exception {
			return new DriftGate.Snapshot(1,
					Fingerprinter.sha256(JsonMapper.builder().build().writeValueAsBytes(this.spec)),
					WorkspaceFingerprint.capture(work, home),
					Map.of(name, new DriftGate.Definition(config,
							mutation ? SideEffectClass.MUTATING : SideEffectClass.READ_ONLY)), config);
		}

		void acknowledge(String key, String name, String arguments, boolean mutation, Callable<String> action)
				throws Exception {
			acknowledge(this.attempt, key, name, arguments, mutation, action);
		}

		/** The after snapshot is captured once the action ran, so a read that changed the workspace is visible. */
		void acknowledge(UUID attemptId, String key, String name, String arguments, boolean mutation,
				Callable<String> action) throws Exception {
			String args = sha256(arguments);
			this.writer.append(new RunEvent.ToolStarted(Instant.EPOCH, attemptId.toString(), key, name, args,
					mutation ? RunEvent.Effect.MUTATING : RunEvent.Effect.READ_ONLY,
					tools.put(snapshot(name, mutation))));
			String output = action.call();
			String result = tools.put(new ToolEvidenceStore.ResultEvidence(1, this.run.value(), attemptId.toString(),
					key, name, args, output, snapshot(name, mutation)));
			this.writer.append(new RunEvent.ToolFinished(Instant.EPOCH, attemptId.toString(), key, result));
		}

		void end(UUID attemptId) throws Exception {
			this.writer.append(new RunEvent.UsageRecorded(Instant.EPOCH, attemptId.toString(),
					RunEvent.UsageKind.MODEL_ROUND, "round", new RunUsage(7, true, 1, 2, 3)));
			this.writer.append(new RunEvent.AttemptEnded(Instant.EPOCH, attemptId.toString()));
		}

		ChildResult finish() throws Exception {
			end(this.attempt);
			this.writer.append(new RunEvent.StateChanged(Instant.EPOCH, RunState.COMPLETED, RunEvent.Reason.NONE));
			return result(this.attempt);
		}

		ChildResult result(UUID attemptId) {
			return new ChildResult(this.child.childId(), this.conversation, this.run, attemptId, model,
					ChildStatus.SUCCEEDED, null, List.of(new ChildEvidence.ModelNarrative("Done")), RunUsage.empty());
		}

		String liveRevision() throws Exception {
			return Fingerprinter.treeDigest(new Fingerprinter(new PathPolicy(work, home, List.of())).snapshot());
		}

		Request request(ChangeOwner by, String revision, List<ReadInput> reads, @Nullable PathPolicy stagePolicy) {
			return new Request(this.child, by, parentRun, parentConversation, revision, config, reads, stagePolicy,
					null, null);
		}

		Request request(List<ReadInput> reads) throws Exception {
			return request(this.owner, liveRevision(), reads, null);
		}

		/** Revision of the owner's first retained proposal, bound to the open stage. */
		Request stagedRequest() throws Exception {
			var proposal = changes.read().changes().stream().filter(s -> s.changeSet().owner().equals(this.owner))
					.findFirst().orElseThrow().changeSet();
			return request(this.owner, Fingerprinter.treeDigest(proposal.proposed()), List.of(), this.stage.policy());
		}

		@Override public void close() throws Exception {
			if (this.stage != null) this.stage.close();
			this.writer.close();
		}
	}

	private static String sha256(String value) {
		return Fingerprinter.sha256(value.getBytes(StandardCharsets.UTF_8));
	}

	private String readSource() throws Exception {
		return Files.readString(this.work.resolve("source.txt"));
	}

	private String mutate(Fixture fixture) throws Exception {
		Files.writeString(fixture.stage.resolve("source.txt", true, List.of()), "after\n");
		fixture.stage.record();
		return Files.readString(fixture.stage.resolve("source.txt", false, List.of()));
	}

	private void rejects(Code code, Request request, ChildResult result) {
		assertThatThrownBy(() -> this.collector.collect(request, result))
				.isInstanceOfSatisfying(DelegationEvidenceCollector.Rejection.class,
						ex -> assertThat(ex.code()).isEqualTo(code));
	}

	@Test void identityThatDisagreesWithTheRequestOrTheJournalIsRejected() throws Exception {
		try (var f = new Fixture("research", ChildRole.RESEARCH)) {
			f.acknowledge("call", "read_file", SOURCE_READ, false, this::readSource);
			var result = f.finish();
			var request = f.request(List.of(new ReadInput("call", SOURCE_READ)));
			assertThat(this.collector.collect(request, result).evidence())
					.anyMatch(ChildEvidence.Collected.class::isInstance);
			var other = new ModelRef("fake", "other");
			rejects(Code.IDENTITY, request, new ChildResult("stranger", f.conversation, f.run, f.attempt, this.model,
					ChildStatus.SUCCEEDED, null, List.of(), RunUsage.empty()));
			rejects(Code.IDENTITY, request, new ChildResult(f.child.childId(), f.conversation, f.run, f.attempt, other,
					ChildStatus.SUCCEEDED, null, List.of(), RunUsage.empty()));
			rejects(Code.IDENTITY, new Request(f.child, f.owner, RunId.random(), this.parentConversation,
					request.revision(), this.config, request.reads(), null, null, null), result);
			rejects(Code.IDENTITY, new Request(f.child, f.owner, this.parentRun, UUID.randomUUID(),
					request.revision(), this.config, request.reads(), null, null, null), result);
			var explicit = new ChildSpec(f.child.childId(), f.child.task(), f.child.role(), other, Set.of(),
					f.child.scope(), f.child.budget(), 1, true);
			rejects(Code.IDENTITY, new Request(explicit, f.owner, this.parentRun, this.parentConversation,
					request.revision(), this.config, request.reads(), null, null, null), result);
			var stranger = UUID.randomUUID();
			rejects(Code.IDENTITY, f.request(f.owner(stranger), request.revision(), request.reads(), null),
					f.result(stranger));
		}
	}

	@Test void succeededResultOverAnUnsettledOrFailedJournalIsRejected() throws Exception {
		try (var f = new Fixture("running", ChildRole.RESEARCH)) {
			f.acknowledge("call", "read_file", SOURCE_READ, false, this::readSource);
			rejects(Code.NONTERMINAL, f.request(List.of(new ReadInput("call", SOURCE_READ))), f.result(f.attempt));
		}
		try (var f = new Fixture("failed", ChildRole.RESEARCH)) {
			f.acknowledge("call", "read_file", SOURCE_READ, false, this::readSource);
			f.end(f.attempt);
			f.writer.append(new RunEvent.StateChanged(Instant.EPOCH, RunState.FAILED, RunEvent.Reason.ERROR));
			rejects(Code.FAILED, f.request(List.of(new ReadInput("call", SOURCE_READ))), f.result(f.attempt));
		}
	}

	@Test void nonImplementRolesThatMutatedOrChangedTheWorkspaceAreFailed() throws Exception {
		for (var role : List.of(ChildRole.RESEARCH, ChildRole.VERIFY)) {
			try (var f = new Fixture(role.name().toLowerCase(), role)) {
				f.acknowledge("write", "write_file", "{}", true, () -> "no bytes written");
				rejects(Code.FAILED, f.request(List.of()), f.finish());
			}
		}
		try (var f = new Fixture("drift", ChildRole.RESEARCH)) {
			f.acknowledge("call", "read_file", SOURCE_READ, false, () -> {
				Files.writeString(this.work.resolve("side-effect.txt"), "written by a read-only tool");
				return readSource();
			});
			rejects(Code.FAILED, f.request(List.of(new ReadInput("call", SOURCE_READ))), f.finish());
		}
	}

	@Test void toolStartWithoutAnAuthoritativeSnapshotIsMissing() throws Exception {
		try (var f = new Fixture("legacy", ChildRole.RESEARCH)) {
			f.writer.append(new RunEvent.ToolStarted(Instant.EPOCH, f.attempt.toString(), "call", "read_file",
					sha256(SOURCE_READ), RunEvent.Effect.READ_ONLY));
			f.writer.append(new RunEvent.ToolFinished(Instant.EPOCH, f.attempt.toString(), "call", "c".repeat(64)));
			rejects(Code.MISSING, f.request(List.of(new ReadInput("call", SOURCE_READ))), f.finish());
		}
	}

	@Test void readsWithoutAnAcknowledgedReadFileOfARegularFileAreMissingAndDuplicatesAreIdentity() throws Exception {
		try (var f = new Fixture("research", ChildRole.RESEARCH)) {
			String absent = "{\"path\":\"missing.txt\"}";
			// A regular file through another read-only tool: only the read_file name check can refuse it.
			f.acknowledge("list", "list_files", SOURCE_READ, false, () -> "source.txt");
			f.acknowledge("array", "read_file", "[1]", false, () -> "");
			f.acknowledge("absent", "read_file", absent, false, () -> "");
			f.acknowledge("call", "read_file", SOURCE_READ, false, this::readSource);
			var result = f.finish();
			assertThat(this.collector.collect(f.request(List.of(new ReadInput("call", SOURCE_READ))), result).evidence())
					.anyMatch(ChildEvidence.Source.class::isInstance);
			rejects(Code.MISSING, f.request(List.of()), result);
			rejects(Code.MISSING, f.request(List.of(new ReadInput("unacknowledged", SOURCE_READ))), result);
			rejects(Code.MISSING, f.request(List.of(new ReadInput("list", SOURCE_READ))), result);
			rejects(Code.MISSING, f.request(List.of(new ReadInput("array", "[1]"))), result);
			rejects(Code.MISSING, f.request(List.of(new ReadInput("absent", absent))), result);
			rejects(Code.IDENTITY, f.request(List.of(new ReadInput("call", SOURCE_READ),
					new ReadInput("call", SOURCE_READ))), result);
		}
	}

	@Test void readsOutsideTheWorkspaceOrTheChildReadRootsAreForeign() throws Exception {
		Files.createDirectories(this.work.resolve("docs"));
		Files.writeString(this.work.resolve("docs/guide.txt"), "guide\n");
		String escape = "{\"path\":\"../outside.txt\"}", guide = "{\"path\":\"docs/guide.txt\"}";
		try (var f = new Fixture("escape", ChildRole.RESEARCH)) {
			f.acknowledge("up", "read_file", escape, false, () -> "outside");
			rejects(Code.FOREIGN, f.request(List.of(new ReadInput("up", escape))), f.finish());
		}
		try (var f = new Fixture("docs", ChildRole.RESEARCH, Set.of(this.work.toRealPath().resolve("docs")))) {
			f.acknowledge("root", "read_file", SOURCE_READ, false, this::readSource);
			f.acknowledge("guide", "read_file", guide, false, () -> Files.readString(this.work.resolve("docs/guide.txt")));
			var result = f.finish();
			rejects(Code.FOREIGN, f.request(List.of(new ReadInput("root", SOURCE_READ))), result);
			assertThat(this.collector.collect(f.request(List.of(new ReadInput("guide", guide))), result).evidence())
					.anyMatch(ChildEvidence.Source.class::isInstance);
		}
	}

	@Test void earlierAttemptIsStaleAndItsUnresolvedToolStaysUnknownAfterARetrySucceeds() throws Exception {
		try (var f = new Fixture("research", ChildRole.RESEARCH)) {
			f.writer.append(new RunEvent.ToolStarted(Instant.EPOCH, f.attempt.toString(), "lost", "read_file",
					sha256(SOURCE_READ), RunEvent.Effect.READ_ONLY, this.tools.put(f.snapshot("read_file", false))));
			f.end(f.attempt);
			f.writer.append(new RunEvent.StateChanged(Instant.EPOCH, RunState.INTERRUPTED, RunEvent.Reason.PROCESS_LOST));
			f.writer.append(new RunEvent.ToolResolution(Instant.EPOCH, f.attempt.toString(), "lost",
					RunEvent.Resolution.SKIP, "operator", "evidence"));
			f.writer.append(new RunEvent.StateChanged(Instant.EPOCH, RunState.RUNNING, RunEvent.Reason.NONE));
			var retry = UUID.randomUUID();
			f.writer.append(new RunEvent.AttemptStarted(Instant.EPOCH, retry.toString(), UUID.randomUUID()));
			f.acknowledge(retry, "call", "read_file", SOURCE_READ, false, this::readSource);
			f.end(retry);
			f.writer.append(new RunEvent.StateChanged(Instant.EPOCH, RunState.COMPLETED, RunEvent.Reason.NONE));
			assertThat(this.runs.read(f.run).uncertainTools()).isEmpty();
			var reads = List.of(new ReadInput("call", SOURCE_READ));
			rejects(Code.STALE, f.request(reads), f.result(f.attempt));
			rejects(Code.UNKNOWN, f.request(f.owner(retry), f.liveRevision(), reads, null), f.result(retry));
		}
	}

	@Test void implementWithoutExactlyOneOwnedProposalFromAMutatingToolIsMissing() throws Exception {
		try (var f = new Fixture("unstaged", ChildRole.IMPLEMENT)) {
			rejects(Code.MISSING, f.request(List.of()), f.finish());
		}
		try (var f = new Fixture("unrecorded", ChildRole.IMPLEMENT)) {
			f.stage();
			var result = f.finish();
			rejects(Code.MISSING, f.request(f.owner, f.liveRevision(), List.of(), f.stage.policy()), result);
		}
		try (var f = new Fixture("untracked", ChildRole.IMPLEMENT)) {
			f.stage();
			f.acknowledge("call", "read_file", SOURCE_READ, false, this::readSource);
			mutate(f);
			rejects(Code.MISSING, f.stagedRequest(), f.finish());
		}
		try (var f = new Fixture("twice", ChildRole.IMPLEMENT)) {
			f.stage();
			f.acknowledge("write", "write_file", "{}", true, () -> mutate(f));
			var result = f.finish();
			var request = f.stagedRequest();
			assertThat(this.collector.collect(request, result)
					.verifiedSuccess(ChildRole.IMPLEMENT, request.revision(), this.config)).isTrue();
			rejects(Code.MISSING, f.request(f.owner, request.revision(), List.of(), null), result);
			try (var lease = this.changes.acquire()) {
				var set = lease.read().changes().stream().filter(s -> s.changeSet().owner().equals(f.owner))
						.findFirst().orElseThrow().changeSet();
				lease.propose(ChangeSet.propose(UUID.randomUUID(), set.owner(), set.cwd(), set.model(),
						set.bindingIds(), set.baseline(), set.proposed(), set.diffs(), set.createdAt()));
			}
			rejects(Code.MISSING, request, result);
		}
	}

	@Test void verifyWithoutAnObservedVerificationIsMissing() throws Exception {
		try (var f = new Fixture("verification", ChildRole.VERIFY)) {
			f.stage();
			mutate(f);
			rejects(Code.MISSING, f.stagedRequest(), f.finish());
		}
	}

	@Test void aggregationKeepsNarrativeAndTheExactRejectionCodeWithJournalUsage() throws Exception {
		try (var f = new Fixture("research", ChildRole.RESEARCH)) {
			f.acknowledge("write", "write_file", "{}", true, () -> "no bytes written");
			var result = f.finish();
			var plan = new DelegationPlan("plan", List.of(f.child), 1);
			var aggregate = this.collector.aggregate(plan, List.of(result), false,
					Map.of(f.child.childId(), f.request(List.of())));
			assertThat(aggregate.status()).isEqualTo(DelegationResult.Status.FAILED);
			var child = aggregate.children().getFirst();
			assertThat(child.evidence()).containsExactly(new ChildEvidence.ModelNarrative("Done"),
					new ChildEvidence.Rejected(Code.FAILED));
			assertThat(child.usage()).isEqualTo(new RunUsage(7, true, 1, 2, 3));
		}
	}
}
