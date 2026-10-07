# B1 shared-parent budget fix contract

Status: implementation and core regressions delivered; all tests and Maven commands UNRUN by this worker. Parent owns application integration tests, process tests, Maven, and final verification.

## Accounting decision and public ABI

`RunContext.chargeChildUsage(RunStore, RunSpec child, RunEvent.UsageRecorded delta)` imports one acknowledged source observation. It validates exact child spec, parent run/conversation/cwd lineage, and exact event membership in the child's CRC-validated journal. It does not accept a cumulative `RunRecord.usage()` or `ChildResult.usage()` as a delta.

Identity is SHA-256 of newline-delimited child RunId, child attemptId, usage kind, and callId. Safe IDs cannot contain newlines. The resulting `child-<64 lowercase hex>` callId is reserved for a parent charge. It uses the existing `UsageRecorded` payload with `COMPACTION` kind: no new event type, enum, JSON property, RunRecord component, or schema version. This kind does not mark a parent model round or parent active-time emission as recorded. RunProjector validates the reserved identity shape/kind and root-parent authority, and rejects that identity globally across parent attempts. Ordinary usage identities remain attempt-local.

The captured parent context serializes imports with its own model/tool/final-time operations. It loads prior charge identities from the authoritative journal, appends a translated delta through the existing fsync/validation writer, and only after acknowledgement updates the live meter. Duplicate callbacks return without another append or meter update. Duplicate source identities with different token/unknown/tool/time values are rejected. Child overshoot is not copied: the charged overshoot delta is recomputed against the immutable parent's cap.

An accounting storage failure poisons that captured context. New parent model/tool calls and final-time emission then fail; an ordinary failed child result cannot permit uncharged parent continuation or terminal success. Recovery uses the authoritative journals, not an in-memory retry of a possibly accepted append.

Additional public APIs:

- `RunContext.requireActiveParent()` validates the actual current root-parent attempt and RUNNING state before dispatch.
- `RunContext.reconcileChildUsage(RunStore)` imports complete acknowledged direct-child journal prefixes before new parent work.
- `BudgetLedger(RunBudget, Supplier<RunUsage>, boolean parentIncludesChildren)` selects aggregate managed accounting. The existing two-argument constructor preserves unowned/legacy summation.
- `RunExecutor.resumeOperation(RunId, ModelSelection, Path, ResumeOptions, Function<RunContext,T>)` returns `OperationResult<T>` from an explicit operation under the same stored parent run with a fresh attempt/binding, using ordinary resume validation.
- `RunExecutor.forAgent(Agent)` returns the currently assembled executor for that exact Agent through a weak identity registration. Recovery reuses its actual drift policies/configuration, approvals, contributors, and staging suppliers; it does not recreate a default gate or trust a saved configuration as today's configuration. A restarted Spring application registers its current assembly through ordinary executor construction; unavailable assembly is a refusal.

No new `startOperation` overload or pending predicate remains. Existing controller, DelegateTool, startOperation, and child recovery caller signatures remain unchanged.

## Live enforcement and ledger invariants

ChildScopeFactory drains acknowledged usage after the child writer has returned, outside its writer executor. The captured parent is charged before the scheduler observes that delta. No parent writer lease is acquired while holding a child writer, and no child writer invokes a callback that waits on the parent.

Managed BudgetLedger's supplier reads the parent's aggregate live meter, so `usage()` never adds child observations to that aggregate again. Its per-child observations still determine reservation consumption, remaining child budget, and child-only result display. Additional reservations subtract already charged consumption from outstanding managed reservations. Legacy ledgers continue to sum their independent child observations.

Known consumption and sticky unknown enter both authoritative and live parent totals. Token-cap unknown now prevents new parent tools as well as models. Already admitted calls still record all returned consumption and parent-cap overshoot rather than clipping. Child active milliseconds enter the parent's imported baseline; the parent's own `activeDelta()` emits only its own active intervals. Nested pause/resume excludes synchronous delegation waiting and child resume waiting without dropping child active time.

Completed, pending, failed, and cancelled child outcomes have the same accounting hook. Result settlement never performs an additional parent debit. New plans start from the already charged aggregate.

## Pending lifecycle, continuation, and reconstruction

The pre-existing lifecycle gap was broader than missing usage: operator Pending values, and Pending JSON returned by DelegateTool followed by a final model response, could leave their parent COMPLETED. Terminal parents cannot legally receive future child consumption.

RunExecutor now consults authoritative direct-child states before selecting terminal completion/failure. Outstanding nonterminal children keep the parent INTERRUPTED. The model tool result can still be honestly journaled FINISHED; its success as a tool result is not treated as completion of the delegated child. Operator Pending values are retained in OperationResult.

At parent execution/resume, acknowledged child prefixes are reconciled before new work. A child still RUNNING or WAITING_APPROVAL under a live writer prevents parent recovery work. Resolve/end that retained child first, or release the old writer according to process-loss contracts. Interrupted child records do not imply successful effects; ordinary UNKNOWN/drift checks remain in the child executor.

Explicit controller reconstruction and retained runtime resume can reopen the SAME stored nonterminal parent through resumeOperation before resuming the exact child. Parent selection/options/cwd are loaded from its persisted identity. There is no replacement parent run. Foreign original conversation, root/child authority, terminal parent, mismatched task/spec/budget, scope/wire/model drift, and missing UNKNOWN resolutions remain refusals. Recovery refusals leave the parent resumable and propagate the actual exception; they do not fabricate a child result. Existing controller recovery views continue to have null parent metadata instead of inventing a parent TurnContext.

The reconstructed scope imports historical source observations before dispatch and at binding, including acknowledged process-loss observations created by child writer reconstruction. New child attempt observations use the same import hook. Reconciliation never creates source acknowledgements, tool results, or success evidence.

Root parents with a real stored lineage cannot enter an unowned runtime path using a supplied usage callback. Legacy turns and synthetic unowned parents without a stored parent record retain their previous execution/ledger behavior.

## Cross-record durability boundary

The two journals are not an atomic transaction. Ordering is acknowledged child source event, acknowledged parent import, then live meter absorption. A crash between source acknowledgement and parent import leaves an acknowledged source prefix for explicit reconciliation. A crash after parent import but before live absorption is repaired by seeding the fresh meter from the parent record; source identity prevents another charge. Incomplete/CRC-invalid source rows remain subject to existing store recovery/refusal rules. Unknown effects and absent observations are never reconstructed as measured zero or success.

The scope remains one captured parent context per active attempt, one writer per run, depth-one children, and synchronous parent waiting. Reconciliation conservatively refuses unreadable records rather than claiming an unclassified record is unrelated. Historical parents already terminal under the old implementation are not rewritten or made resumable.

## Exact changed paths

Production:

- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/RunContext.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/UsageRecorder.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/RunProjector.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/RunExecutor.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/delegation/BudgetLedger.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/delegation/ChildScopeFactory.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/delegation/DefaultDelegationRuntime.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/delegation/DelegationController.java`

Core tests:

- `mini-agent/agent-core/src/test/java/com/example/miniagent/core/run/SharedParentBudgetTest.java` (new)
- `mini-agent/agent-core/src/test/java/com/example/miniagent/core/delegation/DelegationRuntimeTest.java`

This document:

- `.omo/drafts/shared-parent-budget-fix-contract.md` (new, explicitly requested exception to source edit scope)

## Diagnostics and UNRUN test inventory

Java LSP returned no diagnostics at severity `all` for every changed production and test Java file after the final changes. No Maven, build, subprocess, provider, Git, or external write was executed by this worker. LSP is not proof of executed behavior.

New SharedParentBudgetTest coverage, all UNRUN:

- Four parameterized completed/failed/cancelled/waiting outcomes: live/durable exact-once charge, later parent model/tool refusal, second-plan reservations.
- Sticky unknown and already-started overshoot recomputed at parent cap.
- Imported child active time plus paused parent waiting and repeated parent final-time emission.
- Reopened parent seeding, acknowledged source/import gap, repeated reconciliation, attempt-global dedup, and identical callIds in different usage kinds.
- Direct writer append still rejects a repeated charge identity under a fresh parent attempt.
- Concurrent callback admission with bounded latches and duplicate identity delivery.
- Foreign/unacknowledged payload refusal, active-child recovery refusal, and terminal append prevention.
- Failed parent append stops live continuation and finalization; a fresh context imports the actual source without inventing a prior charge.

New DelegationRuntimeTest coverage, all UNRUN:

- Stored parent without exact captured authority is refused before binding or child allocation rather than falling back to legacy accounting.
- Two actual scoped child plans retain parent usage without ledger double addition, then parent model usage adds once.
- Child budget failure before a tool effect retains acknowledged usage in the real parent journal/meter.
- Pending operator remains INTERRUPTED; exact child restart resumes the same parent and child with fresh attempts, preserves sticky unknown, charges subsequent known usage, and allocates no new run.

Related existing suites UNRUN: RunBudgetTest, FileRunStoreTest, SafeResumeTest, ToolJournalTest, BudgetLedgerTest, ChildScopeFactoryTest, DelegationRuntimeTest, DelegationRecoveryTest, DelegationCancelTest, and all parent-owned app/F3 tests, particularly SharedParentBudgetIntegrationTest, DelegationBackendIntegrationTest, DelegationRecoveryIntegrationTest, and the parent's model-driven Pending regression.

Remaining limitations: no executed test or process-boundary result is claimed; true old terminal parents remain immutable; unknown consumption cannot be retrospectively measured; paid-token billing ceilings are not guaranteed for already admitted calls.

## Parent integration after handoff

The worker's UNRUN labels above describe its delivery checkpoint, not final QA.
Parent133 passed all 37 selected core cases and 51/53 selected app cases.
Parent took back all follow-up work and corrected these discovered boundaries:

- Plan workers explicitly open the captured parent RunContext only while creating
  a child scope. ThreadLocal inheritance is not assumed or used as authority.
- The new manual parent fixtures use canonical cwd/home and an uncapped child
  reservation when testing uncapped unknown recovery; strict production identity
  and reservation checks are unchanged.
- Read-only parent apply inspection now calls ChildScopeFactory.inspectDrift,
  which does not return a dispatchable scope. Completed parent proposals can be
  inspected without reopening a terminal parent or granting child execution.
- The app resume regression's scripted model explicitly requests a new tool
  after resume; a harmless final narrative does not prove tool-cap enforcement.

Parent134 is executing the same selected core/app regression after these fixes.
Actual launcher and final full-reactor verification remain pending.
