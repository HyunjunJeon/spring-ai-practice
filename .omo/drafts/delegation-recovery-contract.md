# Task21 read-only durable child projection

## Exact public ABI

Package: `com.example.miniagent.core.delegation`.

```java
new DelegationRecovery(RunStore runs);

DelegationRecovery.Projection children(RunId parentRunId)
    throws RunStoreException;
DelegationRecovery.Inspection inspectChild(
    RunId parentRunId, String childId, RunId runId)
    throws RunStoreException;
RunStore.OpaqueRun storedMetadata(RunId runId)
    throws RunStoreException;

record Projection(
    RunRecord parent,
    List<RunRecord> children,
    List<ReadFailure> unclassified);
record ReadFailure(RunId runId, RunStoreException.Code code, String detail);
record Inspection(Relation relation, RunRecord record);
enum Relation { CHILD, FOREIGN, MISSING_LINEAGE }
```

`children` reads the exact parent first (missing/corrupt/future parent errors
propagate), then examines the same supplied store. It returns only direct
children with exact `spec.parentRunId == parentRunId` and a stored explicit
`childId`. Unreadable entries are separately reported as `unclassified`, never
attributed to this parent. List errors propagate, so a broken enumeration
cannot look like an empty successful result. Ordering is by actual RunId,
not timestamp, role, task description, model prose or guessed UUID.

`inspectChild` reads only the caller's exact RunId and checks both stored
`parentRunId` and `childId`. Missing/corrupt/future records propagate their
existing typed RunStoreException; unlinked records are MISSING_LINEAGE and
other owners/child identifiers are FOREIGN. A FOREIGN inspection exposes
the actual record for honest identity reporting, not execution authority.

`storedMetadata` is the existing store's opaque export, not another event
parser or a reconstructed permission decision. It preserves the exact stored
approval request/resolution payloads and tool/drift metadata, including
attempt/request/tool IDs and argument/diff digests. It can also export corrupt
or future journals. **Export is not validated evidence**; use the successful
inspection for state, and label export as raw stored metadata. Read/export
are separate observations and are not an atomic snapshot of an active writer.

## Parent metadata requirements and ownership

The injected store MUST be the very same RunStore/home used by RunExecutor;
this unit does not construct FileRunStore, another store, a broker, a provider
or memory. Parent-owned start-child integration must persist the exact
`RunSpec.parentRunId`, explicit `RunSpec.childId`, original
`parentConversationId`, independent child `conversationId`, actual `runId`,
captured `model`, `wireFamily`, cwd, budget and fingerprints at creation.
Existing events own the actual attempt/binding IDs, usage (including unknown
and overshoot), state/reason and uncertain tool outcomes.

Parent task21 now writes explicit childId even for unmanaged roots and exact
parentRunId for managed roots at creation. This read-only managed projection
deliberately still requires a real stored parentRunId and does not infer a parent
run or authorization from a conversation-based fallback.
Repeated child IDs across different exact parent runs remain independent.
Multiple runs with the same parent/child IDs are all returned; no inferred
latest run or plan membership is selected.

Created/bound lifecycle acknowledgements and live cancel, pending approval
resolution, explicit resume policy, command wiring and parent-only apply
remain parent-owned. Scanning here is for read-only historical status, never
for discovery of a live handle to cancel. Descriptor IDs for never-started
children cannot become durable records.

## Restart and evidence boundaries

RunStore.read / existing RunProjector are the state authority. While a writer
is present its RUNNING/WAITING_APPROVAL is preserved. After release and
reopening, abandoned active records project INTERRUPTED / PROCESS_LOST,
without rewriting the log, ending an attempt, adding unknown usage or
resolving an uncertain effect. COMPLETED is execution completion, not verified
success, and INTERRUPTED does not mean safely resumable.

There is no resume method, automatic model lookup, provider/tool invocation,
event mutation, approval answer, apply or live cancellation in this unit.
Permission metadata absent from the journal stays absent. Stored approval
is historical evidence only, never a reusable permission grant.

## Validation status

Delivered only these new files:

- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/delegation/DelegationRecovery.java`
- `mini-agent/agent-core/src/test/java/com/example/miniagent/core/delegation/DelegationRecoveryTest.java`
- `.omo/drafts/delegation-recovery-contract.md`

DelegationRunProjector is unnecessary: preserving the existing RunRecord
avoids another state/effect/usage projection and another serialization parser.
No existing source was edited.

Eight test methods (nine cases with RUNNING/WAITING_APPROVAL parameterization)
use actual FileRunStore, acknowledged append, synchronous writer release and
new store instances. They cover completed and active writer preservation,
all recorded attempt IDs/bindings and sticky unknown usage across attempts,
abandoned interruption, original cancel reasons, exact direct lineage,
foreign/historical/independent/grandchild exclusion, descriptor absence,
missing/corrupt parent failures, corrupt/future unclassified entries,
raw approval payloads, uncertain mutating tools and partial-tail preservation.
A read-only facade delegates real reads but fails on create/openWriter; whole
home file-byte snapshots check no records, evidence or source/staged files
are created/changed by recovery. There are no sleeps, polling or timing-based
assertions; no test needs a separate async action.

Both Java files are checked with all-severity LSP diagnostics. Markdown LSP
was requested too but no `.md` server is configured; no setup files were
edited outside the allowed paths. All tests and Maven/standalone compiler
commands remain **UNRUN** for the parent, as explicitly requested. No commits,
paid calls, external writes, provider lookup or app executions occurred.
