# Task23 backend ABI

Published early for parent-owned command/tool/Spring wiring. Package:
`com.example.miniagent.core.delegation`. No Maven execution in this lane.

Status: backend and fourteen app integration tests authored. Test execution
remains parent-owned; no claim that these new tests passed. The reported
Parent103 baseline (122 passing tests) predates this backend change.
Final authored-path LSP checks report zero errors, warnings or hints across
all eight Java paths. Maven/build/test execution is deliberately UNRUN here.

```java
DelegationController(DefaultDelegationRuntime runtime, RunStore runs,
    StagedApplyService apply, Consumer<DelegationController.Event> observer)
DelegationController observed(Consumer<TraceEvent> observer)
DefaultDelegationRuntime.Outcome execute(TurnContext parent, DelegationPlan plan,
    ToolScope authority, RunBudget budget, Supplier<RunUsage> usage)
RunExecutor.OperationResult<DefaultDelegationRuntime.Outcome> executeOperator(
    RunExecutor executor, String originalConversationId, ModelSelection selection,
    Path cwd, DelegationPlan plan, ToolScope authority, RunBudget budget)
// Exact components: OperationResult<T>(RunRecord record, T value)
RunExecutor.Result recover(RunId childRun, String planId, ChildSpec exactChild,
    String originalConversationId, ToolScope authority, RunExecutor.ResumeOptions options)
List<DelegationController.View> list(String originalConversationId)
DelegationController.View show(String originalConversationId, String planId)
RunRecord resolve(String originalConversationId, String planId,
    ApprovalRequest request, ApprovalDecision decision)
DefaultDelegationRuntime.Outcome resume(String originalConversationId,
    String planId, String childId, RunExecutor.ResumeOptions options)
boolean cancel(String originalConversationId, String planId)
boolean apply(TurnContext parent, String planId, String childId, UUID proposalId,
    SlashCommand.CommandContext console) throws IOException
```

`View(plan, parent, authority, outcome, busy)` is a transient exact current
plan snapshot. `outcome` may be null while busy. Completed plans remain
inspectable/applicable until replaced by an explicit execution with the same
ID; an active or pending ID cannot be replaced. No history discovery.
`Event(String conversationId, String planId, String operation, View view)`
is metadata delivered through the observer; parent maps it to TraceEvent.

Recover resumes ONLY the supplied exact persisted child. It reads that child's
exact parentRunId and then that parent record, authenticates the original
console conversation against persisted parentConversationId, and constructs
scope metadata directly from stored lineage. No parent TurnContext, run,
attempt or model evidence is invented. `View.parent()` is null for recovery
entries; their plan is the supplied one-child control unit, NOT a reconstructed
DAG success assertion. `Result` has `record()` and nullable `reply()`.
Deferred recovery retains the exact scope for resolve/resume/cancel; controller
close abandons those scopes without user cancellation. Resume reuses original
RunId/spec/budget, validates the private goal and logical role/scope/configuration
through Scope.resume, and starts only a genuine fresh child binding/attempt.
Missing original invocation/process captures are not recovered from prose or
exit codes. In particular VERIFY cannot silently reconstruct IMPLEMENT inputs.

Model callbacks MUST pass the bound parent RunContext's TurnContext.
Execute checks actual persisted parent identity and active RunContext.
Console list/show/resolve/resume/cancel MUST pass the authenticated current
original conversation, not a conversation supplied by model JSON.
Operator execution uses `RunExecutor.startOperation(...)`: the same executor
creates a real persisted parent run/attempt and opens its normal ownership
scopes. The console conversation is its parentConversationId, not a fake run.
Controller accepts that original conversation only for this authenticated
operator entry point.

Runtime wiring:

```java
DefaultDelegationRuntime evidence(DelegationEvidenceCollector collector)
DefaultDelegationRuntime.Outcome consume(String childParentConversationId, String planId)
DelegationVerificationTool(ChangeStore changes,
    VerificationRunner.LaunchPolicy sandbox, Clock clock)
ToolCallback callback()
StagingToolInterceptor.Policy stagingPolicy()
SideEffectClass.Policy readPolicy()
```

Parent contributes the verification callback/provider and both policies lazily
to ChildScopeFactory. Exact tool name is `verify_staged`; its JSON arguments
are `childId`, `argv`, `timeoutMillis`, `maxOutputBytes`. `childId` selects ONE
exact IMPLEMENT dependency from the current scheduler inputs, never a store
scan or arbitrary proposal. The trusted sandbox LaunchPolicy is mandatory;
there is no command heuristic or unsandboxed fallback.

Factory gains one completion boundary callback retaining actual invocation,
acknowledged journal key, stage owner/policy and bound model. Runtime passes
exact dependency result/capture inputs to factory scopes, captures role-specific
expectations, and recollects through collector.aggregate at consumption.
VERIFY imports the exact retained IMPLEMENT tree into its own existing stage,
records a genuine proposal under its actual binding, and invokes the real
VerificationRunner inside the active VERIFY attempt. It retains the original
Request and Result and derives configuration from trusted Launch before launch.
VERIFY source writes, shell/apply/delegate and foreign dependency selection
are refused before budget/permission/journal invocation.

Close is process/ownership loss, not an operator cancellation. Explicit cancel
uses the existing exact current-plan lifecycle. Unanswered approvals are never
reported as user CANCELLED by ordinary shutdown. Parent owns bean wiring,
commands/tools, trace mapping and test execution.

`StagingToolInterceptor.Kind.CONTROL` is the exact root-only pathless MUTATING
declaration for `apply_staged`. Parent contributes its exact definition digest
and preflight. CONTROL refuses any persisted child actor; exact `apply_staged`
also refuses a trusted-read journal declaration before dispatch.
ScopedToolInterceptor independently refuses CONTROL before budget/permission.
`delegate` uses CONTROL with its exact trusted-read declaration; child scopes still
refuse the reserved name and delegation effect.

Apply retains the actual parent stage, without rebasing its identity or copying
live source over it. Existing StagedWorkspace.record never publishes live files.
If the parent has outstanding proposals against its pre-apply baseline, the
existing external scanner stales only those parent-owned proposals; it cannot
undo or stale the applied child's separately owned proposal. Continued parent
file mutations against that old stage remain stale rather than overwriting
the now-approved live source. A fresh run is required for a fresh source view.

Operator terminal RunStateChanged is emitted only after startOperation returns
or throws and its executor finally has closed ownership; the controller rereads
that exact acknowledged parent record and tags its original console conversation.

## Delivered ownership and verification

The stable verification callback retains one executable identity across provider
enumerations. Dependency proof is collected at existing scheduler quiescent
boundaries, after sibling workers release change-journal leases and before
dependent dispatch; final outcome and apply independently recollect. There is
no new scheduler, store, broker, budget meter or model loop.

New backend sources:
`agent-core/.../delegation/DelegationController.java` and
`agent-core/.../delegation/DelegationVerificationTool.java`.
Existing-path edits are limited to DefaultDelegationRuntime, ChildScopeFactory,
ScopedToolInterceptor, StagingToolInterceptor and RunExecutor.
No parent-owned evidence/collector/store/engine/approval/UI/config/tool class
was changed by this lane.

`agent-app/src/test/java/com/example/miniagent/app/DelegationBackendIntegrationTest.java`
has fourteen deterministic tests using actual FileRunStore/FileChangeStore,
the actual ScopedAssembly ToolCallingAdvisor, FileTools callbacks, real
BashSandbox verification policy, local processes and shared StagedApplyService/
ApplyCommand. It covers mixed COMPLETE with different IMPLEMENT/VERIFY owners,
root-control and VERIFY source-write refusal, foreign dependency target,
narrative-only failure, actual nonzero/sandbox-denied processes, actual STALE
exit zero with pre-armed process-exit/FIFO signalling, post-process/current source
drift, parent-consumption recollection, pending cancellation and process-loss
shutdown, exact same-run restart recovery/foreign and role rejection, and
approved child apply inside a real parent attempt followed by parent-stage close.

Parent has reported lazy wiring for callback/policies/collector/controller and
frontend controls. Remaining parent work is CLI recovery/UNKNOWN options,
frontend/event integration QA and execution of the new backend tests together
with the existing 122-test baseline. Sandbox support is the actual trusted
BashSandbox contract: unsupported hosts produce an honest unsupported outcome,
not an unsandboxed fallback.
