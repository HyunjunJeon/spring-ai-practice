# Task23 parent-owned evidence ABI

Status: implemented locally, authored Java LSP-clean. All tests UNRUN in this
unit. No Maven, commits, paid calls, external writes, runtime adapter, or wiring.
This supplements the approved plan and task18 contract: role-specific owned
research/staging proofs do not require an invented process test.

## Exact entry points

Package `com.example.miniagent.core.delegation`:

```java
new DelegationEvidenceCollector(RunStore runs, ToolEvidenceStore tools, ChangeStore changes)

record DelegationEvidenceCollector.ReadInput(String toolCallKey, String arguments)

record DelegationEvidenceCollector.Request(
    ChildSpec spec,
    ChangeOwner owner,
    RunId parentRunId,
    UUID parentConversationId,
    String revision,
    String configurationDigest,
    List<ReadInput> reads,
    PathPolicy stagePolicy,
    VerificationRunner.Request verificationRequest,
    VerificationRunner.Result verification)

ChildResult collect(Request request, ChildResult result) throws IOException

DelegationResult aggregate(
    DelegationPlan plan,
    List<ChildResult> results,
    boolean parentCancelled,
    Map<String, Request> requests) throws IOException

ChildResult.verifiedSuccess(ChildRole role, String revision, String configurationDigest)
ChildResult.verifiedSuccess(String revision, String configurationDigest) // preserved
DelegationResult.aggregate(DelegationPlan plan, List<ChildResult> results,
    boolean parentCancelled, String revision, String configurationDigest) // preserved, role-aware
```

`stagePolicy`, `verificationRequest`, and `verification` may be null for roles
that do not use them. `stagePolicy` is the existing `StagedWorkspace.policy()`,
not a reconstructed policy that weakens state-directory exclusions.

Minimal reader exposure, existing implementation unchanged:

```java
public static List<RunEvent> FileRunStore.events(RunStore store, RunId id)
    throws RunStoreException
```

No new parser, RunStore method, journal projection writer, meter, broker, store,
or loop. The existing events reader retains schema/CRC/sequence/projection checks.

Machine evidence:

```java
record ChildEvidence.Proposal(UUID changeSetId, String digest, UUID bindingId)
record ChildEvidence.Rejected(DelegationEvidenceCollector.Code code)

// Final class, package-only construction by collector:
ChildEvidence.Collected.role()                 // ChildRole
ChildEvidence.Collected.owner()                // ChangeOwner
ChildEvidence.Collected.revision()             // String SHA-256
ChildEvidence.Collected.configurationDigest()  // String SHA-256
ChildEvidence.Collected.facts()                // immutable List<ChildEvidence>

enum DelegationEvidenceCollector.Code {
    IDENTITY, NONTERMINAL, UNKNOWN, MISSING, FOREIGN, STALE, FAILED
}
DelegationEvidenceCollector.Rejection.code()
```

Existing ModelNarrative/ToolResult/Source/Verification signatures stay intact.
`Collected` is not a model-deserializable success record or a new persistence
format. Its facts retain acknowledged tool result digests, source paths/digests,
proposal identity/digest/binding, and actual process evidence.

## Source-of-truth checks

The request is a trusted parent expectation, not model-supplied metadata.
The collector strips supplied proof entries and retains narrative separately.
It reads actual run spec, replayed usage, latest ended attempt and events;
checks child/conversation/run/attempt/model/parent identities and canonical cwd;
and requires actual COMPLETED for a successful child's proof. Unknown starts
from any attempt remain disqualifying even if an operator later skipped them.
Nonterminal child results remain aggregation errors.

Every acknowledged tool is matched to STARTED and FINISHED. The private
content-addressed ResultEvidence must match schema/run/attempt/key/name/argument
digest, spec digest, source cwd, tool declarations and configuration. Snapshot
classification must agree with the actual event effect. Current-attempt tool
names must be in the child allowlist. The current source WorkspaceFingerprint
is independently recaptured and compared to each current-attempt completion.
Missing/corrupt private bytes and run journal errors propagate as IOException;
no CRC or unknown-schema suppression.

RESEARCH requires actual FINISHED read-only `read_file`, trusted captured exact
input whose SHA-256 matches the acknowledged arguments, a machine `path` field,
an in-scope regular source file, a snapshot source entry, and a freshly read
source SHA-256. Read-only before/after source identity must agree. Expected
revision is the existing Fingerprinter filtered live tree digest. Expected
configuration must match the acknowledged DriftGate configuration. This first
unit supports workspace built-in `read_file`; external-root/network/custom-tool
source adapters are not inferred from their text output and fail closed.

IMPLEMENT requires an actual FINISHED MUTATING event in the current attempt
and one current PENDING_APPROVAL/APPROVED immutable proposal with exact owner,
model, actual attempt binding, workspace cwd and recomputed proposal digest.
The proposal publication must fall inside an acknowledged mutation's interval.
Live filtered source must still equal the complete baseline. The retained
owner stage must still equal the complete proposed tree, including retained
omitted-link identities, and expected revision must equal its tree digest.
The existing ChangeStore lease supplies the owner stage path; the supplied
staged policy must have exactly that root. Superseded history is not counted as
a second current proposal. No apply approval or live mutation is implied.

VERIFY requires a real, trusted `VerificationRunner.Request` and returned
`Result`, plus a current proposal owned by that VERIFY execution. Request
owner/change-set/digest/source revision/argv must match the immutable store
and process evidence. The exact VerificationEvidence record must exist in
that proposal's store snapshot. Its source cwd and process timestamps must
match the source and actual child attempt interval. Only the observed
SUCCEEDED code plus noncancelled exit 0 and exact expected source/configuration
can pass. Logs are reread through existing lease.readLog and SHA-256 compared.
The process clone cwd remains the actual stored runner cwd; it is not replaced
with the source cwd or invented after the runner deletes its private clone.

`aggregate` recollects immediately before evaluating COMPLETE, using per-child
source/configuration expectations (research baseline and implement/verify
proposed revisions may differ). A typed evidence rejection becomes Rejected,
not a fabricated execution failure or verification success. Required-child
gating and identity/model checks remain in DelegationResult. Missing/duplicate/
foreign/nonterminal results cannot COMPLETE. Partial/failed/parent-cancelled
statuses retain honest children and evidence rejection codes.

Usage for stored runs is the existing replayed run total across attempts,
replacing rather than adding to reported usage. No second observation meter is
introduced; RunUsage.plus retains unknown and overshoot. Terminal unsuccessful
children with no allocated run retain scheduler usage and failure, never proof.
For a missing run on SUCCEEDED or a storage error other than NOT_FOUND, the
error propagates. The parent must supply honest unstarted scheduler failures.

## Required parent adapters and integration

1. Capture original read invocation arguments and journal call key in trusted
   Java, after the actual scope/classification path. Do not let the model fill
   ReadInput, Request, expected configuration, or observed runner result. The
   arguments are private parent inputs; they are not exposed by Collected.
2. Capture the actual ChangeOwner, latest run/attempt binding and immutable
   model. Pass the existing StagedWorkspace policy without changing its roots.
3. Implement the real verify-child process adapter later. Current
   VerificationRunner requires request owner equal proposal owner and writes
   evidence under that owner. A VERIFY child cannot use an IMPLEMENT-owned
   proposal/evidence by changing owner fields. The adapter must materialize a
   genuine VERIFY-owned proposal with its own actual binding and retained
   stage, run the actual runner during that child attempt, preserve original
   Request and Result (especially STALE/SOURCE_CHANGED/cleanup outcome), then
   end the run after recording its actual usage. If the parent chooses a
   cross-owner verification mechanism, it needs an explicit owner-aware runner/
   persistence contract change outside this unit; relabeling is not that change.
4. Derive expected verification configuration from the trusted launch/request
   configuration, not the model's desired success. Existing persisted evidence
   does not retain Runner.Code. The typed live Result is required; persisted
   exit 0 alone cannot recover SUCCEEDED after restart. A recovered run without
   a trusted observed outcome must not invent one.
5. Call collector.aggregate at the parent consumption boundary. The preserved
   pure aggregate/predicate APIs are value contracts, not store readers. They
   retain genuine direct owned Verification as stronger proof for compatibility;
   cached Collected values cannot be used to bypass final recollection.

These are finite source observations under cooperating ChangeStore writers,
not atomic protection against external editor changes after return. Existing
lease.stagingDirectory may create a missing stage directory; missing retained
source still fails the tree comparison. There are no journal or source writes
by this collector. Parent-only apply must repeat its own freshness/exclusive
ownership checks. Runtime remains narrative-only until the parent connects
these captures; this unit does not claim runtime integration or mixed-plan E2E.

## Authored evidence tests

`mini-agent/agent-core/src/test/java/com/example/miniagent/core/delegation/DelegationEvidenceCollectorTest.java`
uses actual FileRunStore append acknowledgements, ToolEvidenceStore blobs,
FileChangeStore proposals, StagedWorkspace mutations, and VerificationRunner
processes (`/usr/bin/true`, `/usr/bin/false`) when the parent runs it. Fixed
event clocks and bounded process completion replace sleeps/polling.

The mixed-role fixture tests owned store aggregation, not a runtime adapter.
Negatives cover narrative-only, forged arguments/private metadata/log digest/
proposal binding/verification owner, current live/stage drift, observed stale
exit 0, actual nonzero exit, unfinished tool, missing/nonterminal child,
unstarted failure partial results, cancellation, and unknown/overshoot usage.
All tests remain UNRUN here. LSP diagnostics are not test execution or a Maven
compile result. The parent owns compilation, execution and real integration.
