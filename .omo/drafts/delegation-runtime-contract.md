# Task20 runtime integration contract

Parent-owned hooks required before production wiring:

Parent follow-up: hook2 is now implemented inside execute using only the exact
current RunContext and its existing meter's live usage/pause/resume, with an
actual controlled-clock test. Lazy Spring runtime/config/session-lock assembly
has been registered and actual app execution is being tested. Hooks1 and4 remain
task21's durable lineage/creation publication bridge.

1. `ChildScopeFactory.Scope.call()` must pass its captured
   `Metadata.parentRunId()` and `Metadata.childId()` to a parent-owned
   `RunExecutor.start` overload that writes those exact fields into `RunSpec`.
   Today the executor writes both fields as null. The runtime must not rewrite
   stored specs or pretend its in-memory metadata is durable lineage.
2. Parent execution must pause its existing `RunContext` budget while awaiting
   synchronous delegation and resume it afterwards. Its supplied `RunUsage`
   snapshot must exclude child deltas; the runtime returns child-only totals.
   Reserve against the parent budget and live parent-only usage, not defaults.
3. Task22 owns process/source/verification collection. Runtime model replies
   remain `ModelNarrative`; completion and exit status alone are not verified
   success. The existing structural aggregator is retained.

4. Add an acknowledged-created/bound lifecycle hook to the factory if task21
   must discover child IDs before the first model boundary. Today the runtime
   discovers the actual UUIDs through the existing `RunContext` at model/tool
   boundaries, usage delivery, and scope cleanup. A stage/bootstrap failure
   before capture can have a persisted run but no exposed context. The minimal
   hook is `Boundaries.created(Metadata, RunRecord)` called after successful
   `RunStore.create`, followed by an exact-context bound notification. Do not
   discover these runs by scanning or guessing historical store entries.

## Delivered paths and public ABI

Only these three paths were created:

- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/delegation/DefaultDelegationRuntime.java`
- `mini-agent/agent-core/src/test/java/com/example/miniagent/core/delegation/DelegationRuntimeTest.java`
- `.omo/drafts/delegation-runtime-contract.md`

`DelegationAggregator` is unnecessary: the existing
`DelegationResult.aggregate` retains its ownership and verification checks.
No parent-owned path was edited.

Package: `com.example.miniagent.core.delegation`.

```java
new DefaultDelegationRuntime(
    ChildScopeFactory factory,
    DelegationProperties properties,
    SessionRunLock sessions);

DelegationResult execute(
    TurnContext parent,
    DelegationPlan plan,
    ToolScope parentScope,
    RunBudget parentBudget,
    Supplier<RunUsage> parentUsage,
    String revision,
    String configurationDigest);

List<DefaultDelegationRuntime.Execution> executions();
boolean cancel(String conversationId, String planId);
void close();

record Execution(
    String conversationId, String planId, String childId,
    ChildStatus status, List<RunId> attempts,
    boolean cancellationRequested);

new DefaultDelegationRuntime.TransientFailure(String message);
```

`execute` is synchronous. Structural/whole-reservation refusals use the existing
machine-typed `DelegationPlanValidator.Rejection` / `BudgetLedger.Refusal`,
before model lookup or worker submission. Session ownership uses the existing
`SessionRunLock.Busy`. A closed runtime rejects admission. Execution-time
failures are `ChildFailure.Code`, not parsed messages.

## Scheduling and budgets

The executor has fixed `concurrency` workers, an `ArrayBlockingQueue` of exactly
`queueCapacity` (a `SynchronousQueue` at zero), and abort rejection. A global
semaphore also bounds total logically admitted children, including waiting DAG
nodes, to `concurrency + queueCapacity`. Per-plan completion queues have the
same finite bound. Topological overflow is explicitly `QUEUE_REJECTED`, never
silently deferred. A dependency that was admitted waits for execution-success
of all prerequisites; terminal unsuccessful prerequisites produce
`DEPENDENCY_FAILED` / `SKIPPED`. Queue-overflow children themselves retain
`QUEUE_REJECTED`, including children not admitted at all.

All reservations are made together using existing RunBudget units. Every model
boundary (including the factory's directly captured compaction model) and tool
boundary checks the same ledger against live parent-only usage. The executor
keeps its existing active-time meter. Queue and coordinator wait add no child
active time. Parent wait exclusion is hook 2, not an invented child timer.

Only acknowledged `UsageRecorded` deltas enter `BudgetLedger.observe`.
Deduplication uses `(runId, attemptId, callId)`, and marks a key consumed only
after observation succeeds. Cumulative `observed` snapshots and final records
are not added. ChildResult usage totals span attempts; DelegationResult usage
is child-only, not a second copy of parent usage. Known tokens, sticky unknown,
and measured overshoot are preserved. Budget failure detail distinguishes
`UNKNOWN_USAGE` from `EXHAUSTED`.

Attempts use `ledger.remaining(childId)` and the original captured ModelRef.
They do not reset or expand the total reservation. Automatic retry is deliberately
narrow: a trusted **ModelProvider.create** adapter can throw `TransientFailure`
only for a demonstrated pre-effect, pre-binding transient refusal. Such refusal
precedes durable RunStore creation in the existing executor. No unbound-context
heuristic authorizes retry of arbitrary exceptions. A marker thrown inside a
model round is refused: the existing ProvenanceChatModel records unknown usage
for failed calls. Unknown, mutating/uncertain tool outcomes, approval stops,
cancel/interruption, stale identity, and unavailable providers without explicit
pre-binding classification never trigger automatic retry. Each binding try
counts toward maxAttempts even when it allocates no run.

## Lifecycle and evidence boundaries

`executions()` returns only currently registered plans. Queued nodes are visible,
as are the actual durable RunIds of captured attempts. `cancel` is scoped by
conversation and plan, marks queued nodes, and invokes each current scope's
existing durable executor cancellation before its interrupt. It never opens
an unrelated historical run, a previous retry scope, or a foreign conversation.
Cancellation racing initial bind is rechecked before the first model/tool call.

A scope closes after each attempt; the coordinator drains completions before
releasing scheduling permits and the session lease. Deferred approval cancels
the retained executor writer/broker request rather than leaking a suspended run
outside this synchronous runtime. It is returned as typed `INTERRUPTED`, not
success or implicit resume. Scope authority is revoked and worker interrupt
flags are cleared before worker reuse. Current-plan registry entries are removed
on return. `close` cancels current plans, shuts down admission/workers and waits
up to 10 seconds for termination. An interrupt-ignoring provider cannot be killed
safely by Java: close reports an explicit lifecycle failure if workers remain,
rather than claiming cleanup. Providers and tool adapters must honor interruption.
Task21 owns restart/recovery and control-surface binding.

ChildResult requires three IDs even for never-started rejected/skipped/cancelled
nodes. Those nodes get independent descriptor UUIDs; **they are not persisted
runs or attempts**. Their live Execution attempt list is empty. Actual bound
attempts replace descriptors with the executor's exact conversation/run/attempt
UUIDs. Parent projection must not manufacture RunStore records for descriptors.
Hook 4 is required to eliminate the pre-capture bootstrap discovery gap.

The runtime emits only `ModelNarrative` evidence. A durable COMPLETED run
can gate execution dependencies, but cannot establish verification success.
Consequently execution-partial or all-execution-success plans without task22
evidence aggregate to **FAILED**, not a fabricated PARTIAL/COMPLETE verified
result. The plan's historical QA method name
`failedDependencySkipsDependentsPlanPartial` is retained, but its assertions
explicitly preserve this evidence boundary. No research-to-implementation-to-
verification integration, parent apply, process exit-zero evidence, or current
source/configuration verification is claimed. Task22 must supply owned,
revision/configuration-matching process/source evidence through its own path.

## Authored validation

Sixteen `DelegationRuntimeTest` methods exercise the actual ChildScopeFactory,
fake local models, official advisor/tool loop, RunExecutor, FileRunStore,
RunRequestStore, staging and session selection store. They cover whole-plan
cycle/reservation refusal, independent actual IDs and captured selection,
dependency failure, zero-queue overflow, live parent caps and unknown tokens,
tool refusal before effects, once-only durable round/tool/final totals,
direct scoped summary model accounting, overshoot, conservative in-flight
retry refusal, bounded pre-binding retries, two-worker/queued visibility, and
active/queued cancellation without touching a historical completed run.
An abnormal worker Error is returned as UNKNOWN_EFFECT, cancels siblings, and
drains their completions before releasing the plan lease and scheduling permits.

Async assertions subscribe with CountDownLatch before action and bounded
deadlines; there are no fixed sleeps, polling delays or timing-luck assertions.
The summary test is a scoped direct-model advisor, not a claim that plugin-memory
or task22 has been end-to-end verified.

Both changed Java files have zero LSP diagnostics. Maven, compiler commands,
and all sixteen tests are **UNRUN** for the parent as explicitly requested.
No commits, external writes, paid calls, or own Maven runs occurred.
