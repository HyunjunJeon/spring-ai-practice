# Task18 delegation ABI

Published before implementation for task19. Package: `com.example.miniagent.core.delegation`.
No changes to existing integration types. No Maven execution in this lane.

## Public constructors and entry points

```java
enum ChildRole { RESEARCH, IMPLEMENT, VERIFY }
enum ChildStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, SKIPPED, CANCELLED, TIMED_OUT }
record ToolScope(Set<String> toolNames, Set<Path> readRoots, Set<Path> writeRoots,
    Set<Path> generatedRoots, boolean mutation, boolean network, boolean delegation, boolean apply)
record ChildSpec(String childId, String task, ChildRole role, ModelRef model,
    Set<String> dependencies, ToolScope scope, RunBudget budget, int maxAttempts, boolean required)
// model == null means captured parent binding.ref(), never mutable selection.
ChildSpec.effectiveModel(ModelRef parent)
ChildSpec.effectiveModel(TurnContext parent)
record DelegationPlan(String planId, List<ChildSpec> children, int depth)
record DelegationProperties(int concurrency, int queueCapacity, int maxDepth,
    int maxAttempts, Duration wall, Long tokenCap, int toolCallCap)
DelegationProperties.defaults()
DelegationProperties.budget()
DelegationPlanValidator.validate(DelegationPlan plan, ToolScope parentScope,
    RunBudget parentBudget, RunUsage parentUsage, DelegationProperties properties)
// Returns privately constructed ValidatedPlan with plan()/topologicalOrder();
// throws typed Rejection(code, subject).
RolePolicy.validateScope(ChildRole role, ToolScope child, ToolScope parent)
RolePolicy.check(ChildRole role, ToolScope scope, ToolUse use) // typed Denied
record RolePolicy.ToolUse(String toolName, Set<Effect> effects, List<Access> accesses)
record RolePolicy.Access(Path path, Target target, boolean mutation)
enum RolePolicy.Effect { READ_ONLY, MUTATING, NETWORK, DELEGATION, APPLY, UNKNOWN }
enum RolePolicy.Target { LIVE, STAGE, VERIFICATION_CLONE, CACHE }
record ChildFailure(Code code, String detail, boolean retryable)
record ChildResult(String childId, UUID conversationId, RunId runId, UUID attemptId,
    ModelRef model, ChildStatus status, ChildFailure failure,
    List<ChildEvidence> evidence, RunUsage usage)
ChildResult.verifiedSuccess(String revision, String configurationDigest)
// final class DelegationResult: private constructor; planId()/status()/children()/usage()
DelegationResult.aggregate(DelegationPlan plan, List<ChildResult> results,
    boolean parentCancelled, String revision, String configurationDigest)
```

## Authority and lifecycle

Scope collections are defensive immutable copies; roots must be absolute normalized paths.
Each child tool name and root must be contained by parent authority. Mutation/network/delegation/apply
cannot widen parent flags. Child delegation/apply are always denied: those tools are parent-only.
The reserved exact names `delegate` and `apply_staged` cannot occur in child scope.
Research scope cannot grant mutation. Implement writes only to stage or explicitly authorized generated/cache
paths, never live. Verify writes only generated/cache paths in verification clone/cache, never source.
RolePolicy checks exact names, trusted effect sets, canonical path containment (including future paths),
and write target/root classifications. Unknown effects are denied, including unclassified MCP.
Classification is supplied by trusted Java code, never tool arguments/model prose. This is a path gate,
not a process sandbox; task19/task22 must still enforce PathPolicy exclusions, sandboxing, stage ownership,
clone source/generated separation, TOCTOU revalidation, and complete effect classification.
Task19 freezes dynamic tool name/schema definitions; RolePolicy does not discover or snapshot tools.
ScopedToolInterceptor must invoke RolePolicy before budget and permission.
Scopes in preflight use parent source authority coordinates. Task19 must project validated
roots into the owner stage/clone before RolePolicy.check; it cannot grant extra roots in that projection.
RolePolicy.check operates on the resolved physical paths and scope, not raw tool path strings.

ChildSpec contains no client, credentials, mutable selection, raw history, or allocated execution identity.
Task19 allocates independent UUID conversation/run/attempt identities and fresh ModelBinding.
ChildResult records those identities and effective immutable ModelRef. Interrupted recovery uses
FAILED + ChildFailure.Code.INTERRUPTED, not success or a made-up ChildStatus.
Unavailable model binding is ChildFailure.Code.MODEL_UNAVAILABLE, never structural plan rejection.

## Budget and validation

Defaults: concurrency 2, queue 8, delegation nesting depth 1, attempts 2,
active wall 30 minutes, tools 50, token cap null. Dependency chain length is not nesting depth.
Reservations reuse RunBudget, with RunUsage known/unknown/overshoot preserved. Child budget is a total
reservation across attempts, not multiplied by attempt count. Runtime must not reset its meter on retry.
Validate the whole plan before allocating/starting children. IDs/duplicate children/missing or self edges,
cycle/depth, role/scope escalation, attempts, invalid/sub-millisecond reservations, overflow and total
active milliseconds/tool/token reservations exceeding remaining parent authority are typed rejections.
Active milliseconds sum conservatively across children, including concurrent children; queue/approval
waits are excluded. Runtime scheduler owns atomic reservation against concurrent parent consumption.
A finite parent token cap rejects an uncapped child reservation, and unknown parent tokens block new
reservations. Uncapped parent permits uncapped children; unknown never becomes measured zero.

## Evidence and aggregation

Additional required sealed `ChildEvidence` distinguishes ModelNarrative, ToolResult, Source and
Verification (wraps the observed VerificationRunner.Code and existing change.VerificationEvidence).
Tool/source entries carry digests. Verification must retain the observed outcome,
including STALE or SOURCE_CHANGED even when the honest process exit is zero.
Narrative cannot prove verification. Verified success requires SUCCEEDED and successful verification
evidence for the expected revision/configuration and matching child execution ownership.
Parent must independently compare stored evidence digests/current revision before consuming/applying.
Aggregate COMPLETE requires every required child to have verified success; PARTIAL requires at least
one verified success; FAILED means none; explicit parent cancellation overrides with CANCELLED.
Missing/duplicate/foreign child results are rejected, never silently counted. Nonterminal results cannot
be aggregated. Usage is summed with RunUsage.plus, retaining unknown and overshoot.
Conversation/run/attempt identities must be distinct both within and across returned children.

## Typed codes

`DelegationPlanValidator.Code`: INVALID_ID, EMPTY_PLAN, DUPLICATE_CHILD, INVALID_EDGE, CYCLE,
DEPTH_EXCEEDED, INVALID_ATTEMPTS, INVALID_RESERVATION, RESERVATION_EXCEEDED, UNKNOWN_USAGE, SCOPE_DENIED.

`RolePolicy.Code`: TOOL_NOT_ALLOWED, SCOPE_WIDENING, ROLE_MUTATION, PARENT_ONLY, UNKNOWN_EFFECT,
NETWORK_DENIED, PATH_DENIED, INVALID_CLASSIFICATION.

`ChildFailure.Code`: MODEL_UNAVAILABLE, SCOPE_DENIED, BUDGET_EXHAUSTED, DEPENDENCY_FAILED, TOOL_FAILED,
VERIFICATION_FAILED, QUEUE_REJECTED, CANCELLED, TIMED_OUT, INTERRUPTED, UNKNOWN_EFFECT.

ChildEvidence records:
`ModelNarrative(String text)`,
`ToolResult(String toolCallId, String toolName, String resultSha256, SideEffectClass effect, Outcome outcome)`,
`Source(Path path, String sha256)`, `Verification(VerificationEvidence evidence)`.
ToolResult.Outcome is FINISHED/FAILED/UNKNOWN. Existing SideEffectClass remains READ_ONLY/MUTATING.

ChildResult QUEUED/RUNNING/SUCCEEDED must have null failure; other states require failure.
SKIPPED/CANCELLED/TIMED_OUT respectively require DEPENDENCY_FAILED/CANCELLED/TIMED_OUT.
INTERRUPTED is FAILED with an explicit failure; unknown effects and interruption cannot be marked
automatically retryable. Verification evidence owner must equal the returned child conversation/run/attempt.
Public records enforce local value invariants (including the existing RunBudget constructor);
the validator's typed Rejection covers cross-record structural invalidity.
