# Task12 change foundation ABI

All types are in `com.example.miniagent.core.change`. This is the task12 ABI for
tasks13-17 and 22, not a persistence or apply implementation. Java 21, JDK only.

## Immutable values

- `ChangeOwner(String sessionId, RunId runId, String attemptId, String turnId)`.
  Every identity is required, including ordinary legacy turns. Callers allocate
  their internal run/attempt identity explicitly. No mutable context lookup.
- `EntryKind`: `MISSING`, `REGULAR_FILE`, `DIRECTORY`, `SYMBOLIC_LINK`.
- `Fingerprint(String relativePath, EntryKind kind, String sha256, long size,
  int posixMode, String linkText)`. Missing entries have null digest/link,
  size 0 and mode 0. Directory digest is SHA256(empty), size 0. Links hash
  UTF8 link text, never target bytes. Mode is POSIX permission bits 0000-0777.
- `Diff(Fingerprint before, Fingerprint after, Diff.Content content,
  String patch, String detail)`. Content is `TEXT`, `METADATA_ONLY`, `BINARY`,
  `TOO_LARGE`, `LINE_LIMIT`, `OPERATION_LIMIT`, `UNSUPPORTED`, `INVALID_UTF8`.
  Omitted content has an empty patch and cannot be approved.
  TEXT requires a nonempty patch and regular-file/missing endpoints.
  METADATA_ONLY rejects differing raw content digests or sizes.
  `relativePath()`, `reviewable()`, `patchBytes()`, `digest()`.
- `ChangeStatus`: plan's eleven states; `canTransitionTo(next)`.
- `ChangeSet(UUID changeSetId, ChangeOwner owner, String cwd, ModelRef model,
  List<UUID> bindingIds, Map<String,Fingerprint> baseline,
  Map<String,Fingerprint> proposed, List<Diff> diffs, ChangeStatus status,
  Approval approval, Instant createdAt)`. Complete filtered trees, not only
  changed paths. Collections are sorted/copied. `digest()` binds UUID, owner,
  canonical cwd, model, binding IDs, full trees and diff bytes (not status or
  decision). `propose(...)` creates pending; `decide(Approval)`, `transition(next)`
  return new values. Approval identity/digest must match; omitted content blocks
  APPROVED. Approval fields remain for audit after stale/superseded transitions.
- `Approval(UUID approvalId, UUID changeSetId, String changeSetDigest,
  ChangeOwner owner, String actor, ApprovalDecision decision, Instant decidedAt)`.
  Reuses core approval enum. DEFERRED stays pending; CANCELLED rejects.
- `Checkpoint(UUID checkpointId, UUID changeSetId, ChangeOwner owner,
  Fingerprint before, Fingerprint after, Checkpoint.BlobRef beforeBlob,
  Checkpoint.BlobRef afterBlob, Checkpoint.State state, Instant recordedAt)`.
  States `PREPARED`, `APPLIED`, `RESTORED`, `CONFLICT`;
  `BlobRef(String sha256, long size)` is content-addressed, not a workspace path.
  Nonmissing nondirectory entries require corresponding blobs. Link blobs hold
  UTF8 link text. A PREPARED value alone is not proof of fsync; task13 owns that.
- `VerificationEvidence(UUID evidenceId, UUID changeSetId, ChangeOwner owner,
  List<String> argv, String cwd, String sourceCwd, String sourceRevision, String configurationDigest,
  String logSha256, Integer exitCode, boolean cancelled, Instant startedAt,
  Instant finishedAt)`. `successful()` and
  `validFor(sourceRevision, configurationDigest)`. Exact argument boundaries,
  no shell joining or fabricated exit on cancellation.
  `cwd` is the actual execution directory; `sourceCwd` binds the original
  workspace. The original constructor defaults `sourceCwd` to `cwd`.

## Filesystem and diff operations

- `PathPolicy(Path cwd, Path agentHome, List<Path> allowedRoots) throws IOException`.
  `root()`, `agentHome()`, `allowedRoots()`, `allowsRead(Path)`,
  `excluded(String)`, `validateRelative(String)`, `resolve(String)`,
  `resolveMutation(String)`, `observe(String)`, `revalidate(Observation)`.
  Observations contain target and existing ancestor identities. Root is actual
  real cwd; home is resolved through its nearest existing real ancestor.
  All managed paths are strictly relative canonical slash paths. No traversal,
  absolute paths, control characters or backslashes. Parent symlinks are refused
  (even internal links). Leaf links can be observed, never mutated.
  `resolve` returns an observed pathname, not permission to dereference a link;
  baseline/content readers use `Fingerprinter.read` with NOFOLLOW.
  The FS allowed-roots list remains explicit read authority via `allowsRead`;
  it never grants managed write authority outside the workspace.
- `PathPolicy.Violation extends IOException`, `code()` uses `Code`:
  `INVALID_PATH`, `EXCLUDED`, `OUTSIDE_ROOT`, `SYMLINK`, `CHANGED`,
  `UNSUPPORTED_FILESYSTEM`, `UNSUPPORTED_ENTRY`, `LIMIT`.
  `validateRelative` instead raises `InvalidRelativePath extends
  IllegalArgumentException` with `code() == INVALID_PATH`, also usable in
  immutable value constructors. Ordinary permission/I/O errors are propagated,
  not converted to absence.
- `.git`, generated/cache/state/auth, `.env*`, keys and certificates are excluded
  before opening content or hashing/backing up/evidence capture. `.mini-agent`
  retains only `skills` descendants for read-only snapshots; mutation is denied.
  The configured home is always excluded, even if placed inside cwd.
- `Fingerprinter(PathPolicy policy, long maxBytes, int maxEntries)`;
  `fingerprint(String)`, `read(String)` -> defensive `Observation(fingerprint,bytes)`,
  `snapshot()` -> sorted map. `sha256(byte[])`, `treeDigest(Map)` static.
  Default constructor `Fingerprinter(PathPolicy)` uses 16MiB/10,000 entries.
- `DiffRenderer(PathPolicy policy, DiffRenderer.Limits limits)` and default policy
  constructor. `Limits(long maxBytes, int maxLines, long maxOperations, int context)`,
  defaults 1MiB total before+after per entry, 20,000 total lines, 8,000,000 operations,
  3 context lines. `render(Fingerprint before, byte[] beforeBytes,
  Fingerprint after, byte[] afterBytes)`; `digest(List<Diff>)`.
  Policy denial occurs before byte inspection; supplied bytes must match raw
  fingerprints. Full-change single hunk with common prefix/suffix context;
  not advertised as a minimal edit script. UTF8 is strict; CR remains in patch
  payload, LF delimits lines; unterminated lines get standard no-newline markers.
  No truncated successful patch. Metadata-only add/delete/mode changes retain
  fingerprints. Binary, invalid UTF8 and limits require a separate future review
  mechanism, not blind approval.

## Reconstruction argument and pending tests

A line stores its body (including any CR) and whether it ends in LF. Therefore
line equality means exact byte equality after strict UTF8 decode. Let P and S be
the common line prefix and disjoint suffix. The single hunk retains up to context
lines on each end, deletes all old lines between P and S, and inserts all new
lines between P and S. Its old/new counts are the lengths of those respective
slices, and zero-length ranges use the line before the insertion/deletion.
Every body is emitted with one patch delimiter LF; an originally unterminated
body receives the standard marker, which removes that LF when reconstructing.
Thus replacing the old hunk slice reconstructs exact proposed bytes, including
mixed CRLF/LF and final newline changes. The test interpreter independently checks
old context/deletion bytes and both range counts rather than copying the renderer.

Tests are written, not executed: seven path-policy tests, seven fingerprinter
tests, and seven diff/contract tests. Reconstruction covers all unequal pairs of
15 text fixtures at context 0 and 3 (420 cases), plus missing/empty add/delete.
The POSIX permission-denial test explicitly skips only when a privileged process
can still read a 0000 file; its permissions are restored in `finally`.

## Ownership and integration limits

Observations detect completed swaps and cooperating-writer drift; they are not an
OS sandbox or atomic compare-and-swap against hostile external writers. Parent
ancestor probes and leaf NOFOLLOW open plus post-read revalidation reduce exposure,
but a simultaneous noncooperating ancestor swap cannot be excluded in portable JDK
path APIs. Snapshot/read require cooperating writers; apply must later acquire its
workspace lease and console-confirmed exclusive ownership. No write APIs here.
Unsupported POSIX attribute/NOFOLLOW providers fail typed; no weaker fallback.

State belongs under external `agent.home/changes/<workspaceHash>` in task13.
Managed staged versus legacy immediate selection remains tasks14/17. No existing
run/model/UI/trace files changed by task12. Suggested future nested sealed
`TraceEvent` records: `ChangeProposed`, `ChangeStateChanged`, `ChangeApprovalRecorded`,
`CheckpointPrepared`, `VerificationRecorded`; payloads should carry IDs/digests,
not source/blob/log content. Parent owns event and ToolContextKeys integration.

Maven is reserved for task11. Parent validation command:
`./mvnw -pl agent-core -am -Dtest=PathPolicyTest,FingerprinterTest,DiffRendererTest test`.
