# Task15 engine ABI

All types are in `com.example.miniagent.core.change`. Parent owns wiring,
FileCheckpoints integration and Maven. This unit changes no existing files.

## Exact public API

`ApplyEngine(ChangeStore, PathPolicy, Clock)` and
`ApplyEngine(ChangeStore, PathPolicy, Clock, BoundaryHook)`:

- `OwnershipProof confirmExclusiveOwnership(AgentConsole, UUID, ChangeOwner,
  String digest) throws IOException`
- `ChangeStore.Snapshot apply(UUID, ChangeOwner, String digest, OwnershipProof)
  throws IOException`
- `Boundary { PREPARED, MUTATED, COMPLETED }`
- `BoundaryHook.reached(Boundary, Checkpoint) throws IOException`
- `OwnershipProof` is final, with no public constructor or factory. It is bound
  to the issuing engine, workspace, exact owner/UUID/digest, and consumed once.
- `Failure extends IOException`, `code()` and `proposal()` expose typed refusal
  and the exact proposal (including reviewable patch and endpoint metadata).
  Codes: `IDENTITY_MISMATCH`, `APPROVAL_REQUIRED`,
  `EXCLUSIVE_OWNERSHIP_REQUIRED`, `STALE`, `INVALID_PROPOSAL`.

`UndoEngine(ChangeStore, PathPolicy, Clock)` and the same four-argument form
with `ApplyEngine.BoundaryHook`:

- `ChangeStore.Snapshot undo(UUID, ChangeOwner, String digest,
  ApplyEngine.OwnershipProof) throws IOException`

Undo consumes a proof issued by an ApplyEngine for the same store workspace and
proposal. Only the issuing ApplyEngine may consume a proof for apply. Console
confirmation supports approved, applied and partial/recovery proposals.

`RecoveryScanner(ChangeStore, PathPolicy)`:

- `ConflictReport scan(UUID, ChangeOwner, String digest) throws IOException`

`ConflictReport(UUID changeSetId, ChangeJournal.State journalState,
ChangeStatus status, List<Entry> entries)`:

- `Position { BEFORE, AFTER, OTHER }`
- `Code { NONE, HUMAN_EDIT, UNSAFE_PATH, UNJOURNALED_EFFECT }`
- `Entry(String relativePath, UUID checkpointId, Checkpoint.State checkpointState,
  Fingerprint before, Fingerprint after, Fingerprint current,
  Position position, Code code)`; absent checkpoint/current fields are null.
- `boolean hasConflicts()`.

## Authority and durability

Confirmation checks the entire baseline under the workspace lease for APPROVED
proposals before and after asking the interactive console to type
`exclusive <UUID> <digest>`. Noninteractive, EOF and other text refuse without
source writes. Confirmation is an explicit operator assertion, not an OS lock
on external editors. The parent must keep these Java capabilities outside
model/tool arguments and use its busy/input policy around console interaction.

Apply checks exact approved owner/digest, full live baseline before and after
consuming proof, all mutation paths, bounded stage bytes and exact proposed
hashes/modes before entering APPLYING. Changed stage endpoints are read only
from lease.stagingDirectory(owner) through strict staged PathPolicy/Fingerprinter.
Limits are 16 MiB/file, 10,000 entries, 256 MiB total proposal content.
Unchanged symlinks stay evidence; symlink mutation is refused. Cross-kind
file/directory replacements are refused as INVALID_PROPOSAL; use separate
reviewed delete/add proposals. Directory and file add/delete and mode edits work.

For each path PREPARED backup is durably returned by lease.prepare before any
mutation. Deletions are deepest first, directory additions shallowest first,
file publications next, directory mode changes last. Undo orders inverse
endpoints the same way. Live regular files use a same-parent POSIX temporary,
full write, requested mode, file force, atomic move, parent force. They never
use AtomicFiles.write (private metadata only). Directory creation uses an atomic
same-parent temporary directory publication; deletions refuse nonempty
directories and force the parent. Mode changes force the entry and parent.

Hooks signal durable PREPARED, durable mutation and durable completion; tests
can interrupt or throw at these exact boundaries without timing. Cancellation
does not fabricate a completion: PREPARED remains classifiable. Interrupted
cleanup temporarily clears and restores the flag solely to remove its private
temporary, finish the published entry's force, record partial/recovery status
and close the lease. A failed storage
publication poisons the lease; a suppressed cleanup failure is retained and
the next instance must inspect journal authority.

Undo first transitions to RECOVERY_REQUIRED (never UNDOING). It restores only
matching after endpoints. PREPARED/CONFLICT already-before endpoints can be
recorded RESTORED without a source write; APPLIED already-before endpoints are
human drift, not proof of an undo. Other bytes/modes/unsafe paths are CONFLICT,
never overwritten. Nonempty human-added directories are preserved as CONFLICT.
Final UNDONE requires every prepared checkpoint restored; unprepared paths
must still match before. Otherwise PARTIALLY_UNDONE is retained.

Recovery is read-only: classifies all diff paths, including unprepared paths,
under a lease; never completes checkpoints, repairs or truncates a journal.
Damaged/future/truncated journals expose the validated prefix and journal state.
Unprepared non-before paths are UNJOURNALED_EFFECT.

Portable JDK path calls assume cooperating external writers, a POSIX provider
supporting atomic move/force, and canonical shared agent.home for store locks.
There is no multi-file atomicity, hostile-writer exclusion or atomic CAS claim.
Interruption after mutation but before completion leaves PREPARED, not success.
Parent integration and all Maven tests remain explicitly unrun by this unit.

## Delivered evidence

New production files: `ApplyEngine.java`, `UndoEngine.java`,
`RecoveryScanner.java`, `ConflictReport.java`, and the shared package-private
`EngineMutation.java`, under `mini-agent/agent-core/src/main/java/com/example/miniagent/core/change/`.

New tests under the matching test package: `ApplyEngineTest.java` (12 tests),
`UndoEngineTest.java` (8 tests, including `humanEditAfterApplyIsNotClobbered`),
`RecoveryScannerTest.java` (6 tests). All 26 tests are explicitly UNRUN.
They use real temporary filesystem/store/stage instances and deterministic
boundary hooks, with no sleeps, skipped tests, dependencies or external calls.

The parent's validation command, not run here, is:
`./mvnw -pl agent-core -am -Dtest=ApplyEngineTest,UndoEngineTest,RecoveryScannerTest -Dsurefire.failIfNoSpecifiedTests=false test`
from `mini-agent/`. LSP diagnostics are the only executed validation in this unit.
Final per-file LSP inspection of all eight new Java files returned no diagnostics:
zero errors, zero warnings, no file failures or truncated results. LSP document
symbols also confirmed the public ApplyEngine signatures and private proof
constructor. These are static diagnostics, not test-pass or filesystem-runtime
evidence. No existing source/test, storage/stage/policy/runtime/UI file,
plan/ledger, dependency or Git state was edited.

The selected authority is a nonconstructible console-issued capability plus
leased baseline checks, rather than a tool/model boolean. The selected recovery
behavior is classification and conflict preservation rather than auto-repair or
automatic overwrite. Parent wiring and FileCheckpoints integration are not
implemented in this unit, as requested.
