# Parent task22 apply bridge preparation

`StagedApplyService(RunStore, ChangeStore, ChildScopeFactory, ApplyCommand)`
reuses exactly the existing stores and shared approval/exclusive command.

```java
ChangeSet inspect(TurnContext parent, ChildSpec child, ToolScope parentAuthority,
    RunId childRun, UUID proposalId) throws IOException;
boolean apply(TurnContext parent, ChildSpec child, ToolScope parentAuthority,
    RunId childRun, UUID proposalId, SlashCommand.CommandContext console)
    throws IOException;
```

The parent authority is captured Java execution authority, never a permission
grant deserialized from a model request. A child actor's persisted childId is
refused. The exact original parent conversation/run, child ID/model/budget,
proposal conversation/run/attempt/binding/model/cwd must match the actual records.
CREATED/RUNNING/WAITING_APPROVAL children and unresolved effects cannot apply.
Only IMPLEMENT is accepted.

A fresh unbound matching scope inspects the existing private drift fingerprint.
It dispatches no model or tool. Exact role/tool/path configuration and source
must match; unchanged source isn't enough to forge a different child role.
Changed source/configuration marks the selected unapplied proposal STALE and
refuses it before approval. No live source is overwritten.

Shared ApplyCommand still owns durable requestChange, matching digest approval,
the console-only exclusive OwnershipProof and actual ApplyEngine publication.
An absent/noninteractive answer remains pending and returns false. No duplicate
broker, journal, approval queue, apply engine or rollback path was introduced.
The console's owning original conversation is checked independently.

Lazy Spring bean is registered in AgentAutoConfiguration. Actual app
StagedApplyTest has six tests for inspection, exact approval/exclusive apply,
unattended pending, whole-child human drift, foreign/child actor rejection and
reduced parent apply rights. Both source/test LSP clean; tests currently UNRUN.
Parent103 completed: all 122 related tests passed, zero failures/errors/skips,
BUILD SUCCESS exit 0. This includes the six actual Spring bridge tests, eleven
lifecycle, seven collector, existing safety/recovery/memory and command checks.

Separate task23 evidence gap: existing role-agnostic ChildResult predicate
requires process Verification for every RESEARCH/IMPLEMENT, so real mixed plans
cannot yet become COMPLETE from honest role-specific proof. Child unit
st_01a11131 owns only ChildEvidence/ChildResult/DelegationResult + new collector
and actual store tests, optionally exposing the existing FileRunStore.events
reader. Parent owns runtime, process adapter, tools/control/Spring/UI.
No fake verification for research is acceptable.
