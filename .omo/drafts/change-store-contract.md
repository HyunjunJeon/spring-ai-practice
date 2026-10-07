# Task 13 persistence ABI and invariants

Implementation complete. No Maven, compiler command, test process, verification
process, live mutation, bean wiring, plan/ledger edit, or dependency addition was
performed by this unit. All types below are in
`com.example.miniagent.core.change`, using the stable task 12 records.

## Exact public ABI

All storage operations below declare `throws ChangeStore.Failure`, except the
three `ChangeStore` identity accessors and value constructors/accessors. `Failure`
extends `IOException`.

```java
public interface ChangeStore {
    Path workspace();
    Path directory();
    String workspaceHash();
    View read() throws Failure;
    Lease acquire() throws Failure;

    enum Code {
        IO, UNSUPPORTED_FILESYSTEM, LOCKED, CLOSED, NOT_FOUND, ALREADY_EXISTS,
        IDENTITY_MISMATCH, INVALID_TRANSITION, LIMIT, CORRUPT,
        TRUNCATED_JOURNAL, FUTURE_SCHEMA, RECOVERY_REQUIRED
    }
    final class Failure extends IOException {
        public Failure(Code code, String message);
        public Failure(Code code, String message, Throwable cause);
        public Code code();
    }
    record Snapshot(long sequence, ChangeSet changeSet, String digest,
                    List<Checkpoint> checkpoints,
                    List<VerificationEvidence> evidence) {}
    record View(ChangeJournal.State journalState, List<Snapshot> changes) {
        public boolean recoveryRequired();
        public Snapshot get(UUID id) throws Failure;
    }
    interface Lease extends AutoCloseable {
        View read() throws Failure;
        Path stagingDirectory(ChangeOwner owner) throws Failure;
        Snapshot propose(ChangeSet proposal) throws Failure;
        Snapshot decide(UUID id, ChangeOwner owner, String digest,
                        Approval approval) throws Failure;
        Snapshot transition(UUID id, ChangeOwner owner, String digest,
                            ChangeStatus next) throws Failure;
        Checkpoint prepare(UUID id, ChangeOwner owner, String digest,
                           UUID checkpointId, String relativePath,
                           byte[] beforeBytes, byte[] afterBytes,
                           Instant at) throws Failure;
        Checkpoint complete(UUID id, ChangeOwner owner, String digest,
                            UUID checkpointId, Checkpoint.State state,
                            Instant at) throws Failure;
        Snapshot recordEvidence(UUID id, ChangeOwner owner, String digest,
                                VerificationEvidence evidence,
                                byte[] log) throws Failure;
        byte[] readBlob(Checkpoint.BlobRef ref) throws Failure;
        byte[] readLog(UUID id, ChangeOwner owner, String digest,
                       UUID evidenceId) throws Failure;
        void close() throws Failure;
    }
}

public final class FileChangeStore implements ChangeStore {
    public FileChangeStore(Path agentHome, Path workspace) throws ChangeStore.Failure;
    public FileChangeStore(Path agentHome, Path workspace,
                           ChangeProperties limits) throws ChangeStore.Failure;
    // Implements the five ChangeStore methods above.
}

@ConfigurationProperties("agent.changes")
public record ChangeProperties(int maxBlobBytes, int maxManifestBytes,
                               int maxJournalBytes) {
    public static ChangeProperties defaults();
}

public final class ChangeJournal {
    public enum State { CLEAN, TRUNCATED, CORRUPT, FUTURE_SCHEMA }
    public record Scan(State state, List<ChangeStore.Snapshot> records,
                       long completeBytes) {}
    public Scan scan() throws ChangeStore.Failure;
}

public final class BlobStore {
    public byte[] read(Checkpoint.BlobRef ref) throws ChangeStore.Failure;
}

public final class AtomicFiles {
    public static byte[] read(Path path, int maxBytes) throws ChangeStore.Failure;
    public static void write(Path target, byte[] bytes,
                             int maxBytes) throws ChangeStore.Failure;
    public static void forceDirectory(Path directory) throws ChangeStore.Failure;
}
```

Record constructors and generated component accessors/equality are public.
Collections in `Snapshot`, `View`, and `Scan` are defensively copied.
`AtomicFiles` has no public constructor. `ChangeJournal` and `BlobStore`
constructors and write operations are package-private: external engines use
`ChangeStore.Lease`, never an unlocked journal writer.

Defaults, including Spring binding defaults, are 16 MiB per blob/log, 4 MiB per
manifest/journal payload, and 64 MiB per workspace journal. Limits must be
positive, journal >= manifest, and blob/journal <= Integer.MAX_VALUE - 1.
Manifest enumeration stops at 10,000 entries, including preserved temp files.
This version does not compact or delete journal/history/blobs.

## Layout and identity

```
<canonical agent.home>/changes/<workspaceHash>/
    workspace.lock
    workspace.json
    journal.log
    manifests/<changeSetId>.json
    blobs/<raw-sha256>
    staging/<sha256-of-serialized-ChangeOwner>/
    evidence/<raw-log-sha256>
```

`workspaceHash = SHA256(UTF8("workspace-v1" + NUL + workspace.toRealPath()))`.
Workspace aliases resolving to the same real path share identity. Identity is
path-based, not Git HEAD or an inode claim. Workspace metadata fixes schema 1,
the hash, and exact canonical cwd. A different cwd/hash is refused.
Home must be outside workspace; no state is stored inside source snapshots.

Private directories are 0700 and files 0600 at creation. Existing managed
directory/file permissions are checked rather than silently repaired.
Source trees reject `PathPolicy.excluded` paths before storage writes.
The store does not copy source, inspect live baseline bytes, or execute argv.
Task 14 owns staging content and read-only workspace skills; task 15 still owns
mutation-specific path/link checks.

## Ownership and read contracts

Acquire holds a JDK exclusive `FileLock` until lease close. A JVM-wide workspace
registry rejects another lease before opening a second POSIX lock descriptor,
including a different `FileChangeStore` instance. Operations within one lease
are synchronized. `ChangeStore.read()` takes a short lease and therefore returns
`LOCKED` while another lease is active; engines holding a lease use `lease.read()`.
Close is idempotent; subsequent lease operations fail `CLOSED`.

Cross-process cooperating writers must use the same canonical `agent.home`.
Different homes cannot share the same OS lock file. The in-JVM registry also
rejects concurrent leases using different homes for one workspace.
This is not exclusion of external editors or a multi-file transaction.
ApplyEngine must separately require console-confirmed exclusive ownership and
recheck the entire baseline under the lease before any mutation.

Reads require real ancestor directories and NOFOLLOW regular leaf files.
Bytes are bounded, JSON duplicate fields/trailing tokens/unknown fields/missing
creator fields/null primitives are refused, and immutable record constructors
revalidate nested values. Completed symlink swaps are refused; portable JDK
paths cannot exclude a simultaneous hostile ancestor swap. The cooperating
ownership assumption is still necessary.

UUID, owner, cwd, and full proposal digest bind updates. A proposal starts
PENDING_APPROVAL with no decision/checkpoint/evidence. Its content identity and
createdAt cannot change under its UUID. Approval uses `ChangeSet.decide`; state
updates use `ChangeSet.transition`. A new proposal UUID is required for changed
content. Stage paths are owner-scoped and private but allocate no snapshot.
Stages can therefore be allocated before proposing.

The actual stage-root allocation/access API is
`Path ChangeStore.Lease.stagingDirectory(ChangeOwner owner) throws Failure`;
callers retain the returned path and reacquire the same path using the same
owner. There is no hidden staged PathPolicy or alternative home authority.
`PathPolicy(stageRoot, agentHome, ...)` currently excludes that whole tree
because it is inside home. Parent owns a later explicit staged-view scope;
this unit does not broaden home/auth access or change PathPolicy.

## Durability and authority

The workspace journal is authority. Each frame is:

```
1<TAB>workspaceSequence<TAB>CRC32C<TAB>JSON(Snapshot)<LF>
```

Sequence starts at 1 and is global across this workspace. CRC covers UTF8 bytes
of `version<TAB>sequence<TAB>payload`. The complete payload includes ChangeSet,
its exact digest, checkpoint states, and verification metadata. Replay validates
identity, legal state changes, immutable checkpoint endpoints/blob references,
and evidence retention, not only CRC.

Publication order:

1. Required blobs/logs use private same-directory temp files, full writes,
   file force, atomic move, and parent-directory force.
2. The complete journal frame is appended and forced.
3. Schema-1 atomic manifest envelope `{schemaVersion, workspaceHash, snapshot}`
   is written, forced, atomically replaced, and directory-forced.
4. Only then does the API return success.

The manifest bundles approval/checkpoint/evidence metadata into one atomic file;
there is no unsafe cross-file approval transaction. A manifest may lag or be
absent after interruption. Read uses the newer validated journal without repairing
disk. An existing manifest must exactly equal a historical journal snapshot;
an invented/conflicting projection blocks writes. Future manifest schema is
classified separately.

No non-atomic or unforced fallback exists. Unsupported POSIX attributes,
atomic moves, or force operations return typed failures. I/O errors are not
absence. A journal/manifest publication failure poisons the lease: reopen and
inspect; do not assume the operation failed without durable effects.
Failed temp files and partial records remain on disk and are never promoted
or overwritten. An incomplete workspace bootstrap is refused, not reinitialized.

## PREPARED and completion

Typical apply storage sequence:

```java
try (ChangeStore.Lease lease = store.acquire()) {
    lease.propose(proposal);
    lease.decide(id, owner, digest, exactApproval);
    lease.transition(id, owner, digest, ChangeStatus.APPLYING);
    Checkpoint prepared = lease.prepare(id, owner, digest, checkpointId,
                                       path, beforeBytes, afterBytes, at);
    // Task 15 performs one live mutation only after prepare returns.
    lease.complete(id, owner, digest, checkpointId, Checkpoint.State.APPLIED, at);
    lease.transition(id, owner, digest, ChangeStatus.APPLIED);
}
```

`prepare` checks both byte endpoints before storing either. File/link bytes must
match exact size/hash; link bytes must equal UTF8 link text. Missing/directory
endpoints use empty byte arrays and null BlobRefs. Blobs are addressed by raw
SHA256 and revalidated on reads. Reusing an existing blob verifies its bytes
and re-forces file and directory; a corrupt existing backup is never replaced.
There is one checkpoint per changed path and per checkpoint UUID. PREPARED
requires APPLYING; returned PREPARED proves blobs, journal, and manifest force,
not a live mutation. Nondecreasing checkpoint time and proposal endpoint equality
are enforced.

Checkpoint transitions:

- PREPARED -> APPLIED, RESTORED, or CONFLICT.
- APPLIED -> RESTORED or CONFLICT.
- CONFLICT -> APPLIED or RESTORED after a later engine's explicit classification.
- RESTORED is final. Same-state completion is refused.

APPLIED ChangeSet requires one APPLIED checkpoint for every diff. UNDONE
requires all existing checkpoints RESTORED. Because APPLIED cannot coexist with
RESTORED/CONFLICT checkpoints, an undo/recovery engine first records
RECOVERY_REQUIRED, then records restoration/conflict, then transitions the
ChangeSet to its final supported state. No UNDOING status is invented.

An unresolved different ChangeSet blocks entry to APPLYING. New staging and
proposals can still be saved while an existing healthy journal has unresolved
checkpoints. Checkpoint completion only records the engine's observed result;
this store does not inspect, apply, restore, or classify live file bytes.

## Recovery-required classification

`View.journalState()` distinguishes:

- CLEAN: every complete frame and its semantic projection is validated.
- TRUNCATED: final current-schema frame lacks its newline/completion.
- CORRUPT: invalid complete framing/CRC/sequence/UTF8/JSON, malformed schema
  prefix, invalid replay, inconsistent manifest, or corrupt referenced backup/log.
- FUTURE_SCHEMA: identifiable future journal header, including a partial future
  tail, or future manifest schema. Unknown journal payload stays opaque.

Read returns only the validated prefix, never a fabricated applied/restored
record. `View.recoveryRequired()` is true for every non-CLEAN state, APPLYING,
RECOVERY_REQUIRED, PARTIALLY_APPLIED, PARTIALLY_UNDONE, or PREPARED/CONFLICT
checkpoint. Callers must present recovery-required behavior using this flag,
not reinterpret the stored ChangeSet as a successful final result.

Damaged journals are not truncated, rewritten, or appended to. Mutations fail
TRUNCATED_JOURNAL, CORRUPT, or FUTURE_SCHEMA respectively. This unit intentionally
provides no repair or automatic apply operation. Task 15 owns explicit recovery.
Raw unknown bytes remain at `directory()/journal.log`; no unknown payload is
deserialized. File/read-limit or permission/I/O failures are typed exceptions,
not silently converted into a prefix.

## Verification evidence

`recordEvidence` binds UUID/owner/sourceCwd and the supplied full proposal digest,
checks the raw log SHA256, durably stores log bytes, and appends immutable
VerificationEvidence metadata. Duplicate evidence UUIDs are refused; previous
evidence is retained. `readLog` repeats identity/digest checks.
Exact argv boundaries, exit/cancelled state, actual source revision,
configuration digest, and times come from the later VerificationRunner.
`VerificationEvidence.cwd` records the actual isolated execution directory;
`sourceCwd` must equal the ChangeSet workspace. The store does not invent exits or demand that a failed verification's actual
source revision match proposed source. Consumers use
`VerificationEvidence.validFor` and actual exit/cancelled/source observations.

## Delivered files and diagnostic evidence

New main source files, all under
`mini-agent/agent-core/src/main/java/com/example/miniagent/core/change/`:
`ChangeStore.java`, `FileChangeStore.java`, `ChangeJournal.java`,
`BlobStore.java`, `AtomicFiles.java`, `ChangeProperties.java`.

New test:
`mini-agent/agent-core/src/test/java/com/example/miniagent/core/change/FileChangeStoreTest.java`.

Final LSP diagnostics: main change package scanned 18 Java files, zero errors,
zero diagnostics, no file failures and no truncation. New persistence test:
zero diagnostics, no truncation. This is language-server evidence, not an
executed Maven build or a passing test result.

All 16 new tests are explicitly **unrun** while the parent owns Maven:

1. newInstanceRestoresManifestApprovalCheckpointEvidenceAndExactBytes
2. workspaceAliasesShareIdentityAndLeaseExcludesOtherInstances
3. rejectedOwnersDigestsApprovalsAndCheckpointBytesLeaveJournalUnchanged
4. truncatedJournalMarksRecoveryRequired
5. corruptCompleteRowIsNotMistakenForTruncatedTail
6. futureCompleteAndPartialJournalRecordsRemainOpaque
7. preparedCheckpointSurvivesNewInstanceWithoutAutomaticApply
8. contentAddressedBackupIntegrityFailurePreservesCorruptBytes
9. restoredAndConflictStatesPersistAndRequireExplicitCompletion
10. laggingAtomicManifestDoesNotHideNewerJournalState
11. duplicateUnknownAndFutureManifestFieldsAreRejectedWithoutRewrite
12. boundedReadsAndUnsupportedForceFailTyped
13. unsafeStateLinksAndWorkspaceIdentityMismatchAreRejected
14. stablePrivateStagingAndClosedLeaseCannotWrite
15. appliedUndoRequiresExplicitRecoveryStateBeforeRestoring
16. unresolvedEffectsBlockAnotherProposalFromEnteringApplying

No sleeps, polling, skipped/disabled tests, mocks hiding storage integration, or
weakened existing tests. Deterministic cut/corruption fixtures test recovery via
new instances. Forced child-JVM termination and cross-process integration remain
the plan's later F3 verification, not claims made by these fixture tests.

Parent-owned next validation, when its Maven reservation is released:
`./mvnw -pl agent-core -am -Dtest=FileChangeStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`
from `mini-agent/`.

The design decision was to make complete snapshots in the journal authoritative
and atomic manifests projections, rather than independently updating approval,
checkpoint, and manifest files and pretending that forms one transaction.
The assumptions are POSIX storage with supported force/atomic move, cooperating
writers sharing agent.home, and future engines honoring the recovery/console
ownership requirements above.
