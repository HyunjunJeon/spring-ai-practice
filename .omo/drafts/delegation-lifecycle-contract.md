# Task21 live scoped lifecycle ABI

This lane owns only RunExecutor, ChildScopeFactory, DefaultDelegationRuntime,
DelegationCancelTest and this draft. Agent needs no net change.
Maven and test execution remain parent-owned.

Early ABI for DelegateCommand and recovery:

* Factory Boundaries gains `created(Metadata, RunRecord)` after acknowledged
  creation, before request/bootstrap, and `bound(Metadata, RunContext)` after
  exact capture. `Scope.record()` returns the exact acknowledged snapshot.
* `Scope.cancel()` cancels only its acknowledged owner, including bootstrap.
  `Scope.resume(RunId, ResumeOptions)` is explicit, same-run reconstruction;
  supplied ChildSpec, parent lineage, request goal, model, cwd and logical
  configuration must match. Generic parent executor resume refuses children.
* Runtime `executeOutcome(...)` returns sealed `Outcome`: `Completed(result)`
  or `Pending(planId, children, approvals, completed, queued)`. Pending child snapshots retain
  exact RunRecords; completed child results and queued child IDs are separate.
  Legacy `execute(...)` throws typed `PendingApproval` carrying that Pending.
* Runtime `resolveApproval(conversationId, planId, request, decision)` settles
  the retained exact deferred owner without dispatch. `resume(conversationId,
  planId, childId, options)` explicitly starts a fresh binding/attempt on the
  same child run and continues eligible DAG nodes. `cancel(conversationId,
  planId)` also settles retained approvals. No history scan or plan store.
  `pending(conversationId, planId)` returns the retained exact typed snapshot.

Pending is neither terminal execution success nor interrupted failure.
Runtime retains its scope/executor writer until explicit resolve/cancel;
stage proposals remain on disk, temporary staging authority is released.
Explicit resolution does not automatically resume. Runtime close cancels
retained current plans. Restart reconstruction requires an operator-supplied
ChildSpec/scope; stored lineage and baseline configuration are authoritative.
Unknown effects and source drift use existing RunExecutor resolution policies.

## Exact public additions

Package `com.example.miniagent.core.delegation`:

```java
// ChildScopeFactory.Boundaries
void created(ChildScopeFactory.Metadata child, RunRecord record);
void bound(ChildScopeFactory.Metadata child, RunContext context);

// ChildScopeFactory.Scope
Optional<RunRecord> record();
List<ApprovalRequest> pendingApprovals();
void cancel() throws RunStoreException;
RunExecutor.Result resume(RunId id, RunExecutor.ResumeOptions options) throws IOException;

// DefaultDelegationRuntime
sealed interface Outcome permits Completed, Pending {}
record Completed(DelegationResult result) implements Outcome {}
record PendingChild(String childId, RunRecord record) {}
record Pending(String planId, List<PendingChild> children,
    List<ApprovalRequest> approvals, List<ChildResult> completed,
    List<String> queued) implements Outcome {}
class PendingApproval extends RuntimeException { Pending pending(); }
Outcome executeOutcome(TurnContext parent, DelegationPlan plan,
    ToolScope parentScope, RunBudget parentBudget, Supplier<RunUsage> parentUsage,
    String revision, String configurationDigest);
Optional<Pending> pending(String conversationId, String planId);
RunRecord resolveApproval(String conversationId, String planId,
    ApprovalRequest request, ApprovalDecision decision) throws RunStoreException;
Outcome resume(String conversationId, String planId, String childId,
    RunExecutor.ResumeOptions options);
boolean cancel(String conversationId, String planId); // Existing API, expanded lifecycle.
```

RunExecutor's factory assembly hooks are fluent `created(Consumer<RunRecord>)`,
`observed(Consumer<RunContext>)` and
`childResumeAuthority(BiPredicate<RunSpec,String>)`. The authority predicate
checks the exact stored spec and original private request goal before any
writer acquisition. Generic executors have no predicate and refuse child
resume even with accept-drift. Direct access through a sibling scope's
executor also refuses foreign lineage. Closed or previously captured scopes
cannot authorize a new child attempt.

Source/tool definition drift still uses the existing exact acknowledgement;
logical role/scope/configuration drift cannot be acknowledged away. The
logical fingerprint uses sorted names/paths, collection counts and
length-framed fields. Scope reconstruction does not allocate a new run or
conversation. No mutation is replayed automatically; UNKNOWN requires the
existing exact operator resolutions. No tool result or successful pair is
fabricated.

## Ownership and cleanup

Creation publication happens after RunStore acknowledgement and after
executor cancellation ownership is installed, before private request/stage
bootstrap or first Agent capture. Cancellation can therefore settle CREATED
without a fake attempt. Scope cancellation before acknowledged creation
interrupts only its current bootstrap thread and is rechecked on creation.
After creation, durable cancellation always precedes worker interruption.
The existing exact JVM staging-lease notification remains the only stage
wait; cancellation interrupts/prevents that wait and the executor ends its
attempt and records CANCELLED rather than closing an interrupted writer as
PROCESS_LOST.

Pending scopes and their dependent queued children retain the existing global
scheduling permits, not worker threads or stage/temp authority. Permits are
released once on terminal settlement/cancel. The synchronous session lease
is released on return; explicit resume reacquires it. The existing plan
registry retains owners and exposes typed pending state until resolve/resume,
cancel or runtime close. Resolving APPROVED/REJECTED ends the old attempt
without dispatch; the retained snapshot is then INTERRUPTED awaiting explicit
resume. Resolving CANCELLED cancels the current plan. A pending child's legacy
Execution status is nonterminal RUNNING; `pending()` supplies its exact
WAITING_APPROVAL/INTERRUPTED durable state, not an execution-success assertion.

The final exact-owner observation also delivers acknowledged fresh-attempt
usage when stage bootstrap failed before capture. Resume's old lost-attempt
cleanup deltas are not relabeled as new-owner usage; RunContext initializes
its meter from the existing authoritative cumulative record, preserving
unknown usage and previous reservation consumption.

Stage close retains original source proposals and stage bytes, removes temp
authority, and releases the staging interceptor capture. Fresh attempts
supersede only the existing unapplied same-run proposals through the existing
change recorder. There is no live apply, change-engine modification, additional
broker, FIFO queue, store, budget subsystem, model loop or automatic recovery.
Interrupt-ignoring providers retain the existing explicit close failure; this
unit does not claim Java can forcibly terminate them.

## Authored verification and boundaries

`DelegationCancelTest` contains eleven deterministic methods using actual
FileRunStore, RunExecutor, ChildScopeFactory, FileChangeStore and
ConsoleApprovalBroker with local fake models. They cover created/bound order,
pre-capture bootstrap cancellation, cancellation while an exact workspace
lease is held, two FIFO approvals, durable noninteractive pending questions,
current-plan approval/worker/queued cancellation, historical/foreign isolation,
explicit retained-owner resolution/resume, abandoned process-loss reconstruction,
generic/sibling/role-widening refusal even with drift acknowledgement, sorted
scope reconstruction, unresolved mutation refusal and proposal/temp cleanup.
The fixture's RunStore.list throws if lifecycle code attempts a history scan.
All event/latch/future subscriptions precede their triggers and waits have
bounded timeouts; there are no sleeps or polling delays.

Tests and Maven are **UNRUN** in this lane. Parent owns execution and follow-up.
No commits, external writes or paid application calls occurred.

Parent22 integration: `Scope.executor().inspectDrift(childRunId)` remains
callable on a fresh, unbound IMPLEMENT scope and now accepts completed as
well as interrupted readable runs. Inspection neither acquires a run writer
nor dispatches/captures a model, mutates the run journal, or grants resume
authority. It retains the existing private content-addressed snapshot
mechanism. A new test verifies matching completed-child inspection leaves
the exact RunRecord unchanged and terminal resume still refuses.

Existing-path changes:

* `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/RunExecutor.java`
* `mini-agent/agent-core/src/main/java/com/example/miniagent/core/delegation/ChildScopeFactory.java`
* `mini-agent/agent-core/src/main/java/com/example/miniagent/core/delegation/DefaultDelegationRuntime.java`

New paths:

* `mini-agent/agent-core/src/test/java/com/example/miniagent/core/delegation/DelegationCancelTest.java`
* `.omo/drafts/delegation-lifecycle-contract.md`

Parent owns DelegateCommand/recovery wiring, Spring configuration, trace/UI
mapping, tests execution and all other paths. There is no durable plan store:
restart recovery reconstructs a supplied exact ChildSpec via Scope.resume,
not a silently invented scheduler replay.
