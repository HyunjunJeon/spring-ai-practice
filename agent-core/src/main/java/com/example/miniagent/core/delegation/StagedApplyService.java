package com.example.miniagent.core.delegation;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.change.ChangeSet;
import com.example.miniagent.core.change.ChangeStatus;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.change.command.ApplyCommand;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunStore;

/** Parent ownership gate; shared ApplyCommand still owns approval and exclusive publication. */
public final class StagedApplyService {
	public enum Code { NOT_PARENT, APPLY_DENIED, FOREIGN_CHILD, NOT_IMPLEMENT, CHILD_ACTIVE,
		UNKNOWN_EFFECTS, FOREIGN_PROPOSAL, STALE }
	public static final class Failure extends IOException {
		private final Code code;
		public Failure(Code code) { super(code.name()); this.code = code; }
		public Code code() { return this.code; }
	}
	private final RunStore runs;
	private final ChangeStore changes;
	private final ChildScopeFactory scopes;
	private final ApplyCommand apply;

	public StagedApplyService(RunStore runs, ChangeStore changes, ChildScopeFactory scopes, ApplyCommand apply) {
		this.runs = Objects.requireNonNull(runs);
		this.changes = Objects.requireNonNull(changes);
		this.scopes = Objects.requireNonNull(scopes);
		this.apply = Objects.requireNonNull(apply);
	}

	/** Supplied scope is captured parent authority, not a model-requested permission grant. */
	public ChangeSet inspect(TurnContext parent, ChildSpec child, ToolScope parentScope,
			RunId childRun, UUID proposalId) throws IOException {
		if (parent.runId() != null) {
			var record = this.runs.read(new RunId(parent.runId()));
			if (record.spec().childId() != null || !record.spec().conversationId().equals(parent.sessionId()))
				throw new Failure(Code.NOT_PARENT);
		}
		if (!parentScope.apply()) throw new Failure(Code.APPLY_DENIED);
		if (child.role() != ChildRole.IMPLEMENT) throw new Failure(Code.NOT_IMPLEMENT);
		var record = this.runs.read(childRun);
		var spec = record.spec();
		if (!spec.parentConversationId().equals(parent.sessionId())
				|| !Objects.equals(spec.parentRunId(), parent.runId() == null ? null : new RunId(parent.runId()))
				|| !Objects.equals(spec.childId(), child.childId())
				|| !spec.model().equals(child.effectiveModel(parent)) || !spec.budget().equals(child.budget()))
			throw new Failure(Code.FOREIGN_CHILD);
		if (record.state() == RunState.CREATED || record.state() == RunState.RUNNING
				|| record.state() == RunState.WAITING_APPROVAL) throw new Failure(Code.CHILD_ACTIVE);
		if (!record.uncertainTools().isEmpty()) throw new Failure(Code.UNKNOWN_EFFECTS);
		ChangeSet set = this.changes.read().get(proposalId).changeSet();
		if (!set.owner().runId().equals(childRun) || !set.owner().sessionId().equals(spec.conversationId())
				|| !set.cwd().equals(spec.cwd()) || !set.model().equals(spec.model())
				|| record.attempts().stream().noneMatch(attempt ->
						attempt.attemptId().equals(set.owner().attemptId()) && set.bindingIds().contains(attempt.bindingId())))
			throw new Failure(Code.FOREIGN_PROPOSAL);
		// This verifies the original role/tool/path configuration without executing a model.
		if (this.scopes.inspectDrift(parent, child, parentScope, childRun).changed()) {
			if (set.status() == ChangeStatus.PENDING_APPROVAL || set.status() == ChangeStatus.APPROVED) {
				try (var lease = this.changes.acquire()) {
					lease.transition(set.changeSetId(), set.owner(), set.digest(), ChangeStatus.STALE);
				}
			}
			throw new Failure(Code.STALE);
		}
		return set;
	}

	public boolean apply(TurnContext parent, ChildSpec child, ToolScope parentScope,
			RunId childRun, UUID proposalId, SlashCommand.CommandContext console) throws IOException {
		inspect(parent, child, parentScope, childRun, proposalId);
		String consoleOwner = parent.runId() == null ? parent.sessionId()
				: this.runs.read(new RunId(parent.runId())).spec().parentConversationId();
		if (!console.session().id().equals(consoleOwner)) throw new Failure(Code.NOT_PARENT);
		return this.apply.executeWithStatus(proposalId.toString(), console);
	}
}
