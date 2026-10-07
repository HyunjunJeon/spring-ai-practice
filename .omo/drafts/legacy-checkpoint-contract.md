# Legacy checkpoint bridge ABI (tasks15/17)

Package `com.example.miniagent.plugin.fs`, class `FileCheckpoints`.
Published before implementation. Parent owns registry, `/undo` display and all Maven runs.

```java
record Change(Path file, @Nullable Path backup) // unchanged public ABI
enum ConflictCode { UNKNOWN_COMPLETION, AFTER_CHANGED, UNSAFE_PATH, BACKUP_CHANGED }
record Conflict(String turnId, Change change, ConflictCode code, String detail)
record UndoOutcome(@Nullable String turnId, List<Change> restored, List<Conflict> conflicts)
void beforeWrite(String sessionId, String turnId, Path file)
void afterWrite(String sessionId, String turnId, Path file)
List<Change> undo(String sessionId) // compatibility: only actually restored entries
UndoOutcome undoWithOutcome(String sessionId)
List<Conflict> conflicts(String sessionId) // retained conflicts across pending turns
UndoOutcome lastUndoOutcome(String sessionId) // includes successes before an I/O failure
```

Parent `/undo` should use `undoWithOutcome`, display each restored entry as before,
and display each conflict's `change().file()`, `code()` and `detail()`. An empty
restored list with nonempty conflicts is NOT "nothing to undo" or a restored turn.
Queries return immutable snapshots. Conflicts and original backups remain until
successful restoration or close; a conflicted last turn blocks undo of older turns.
Unrelated matching files in that last turn can restore.

`beforeWrite` retains the first original per turn but invalidates any previous
successful-after proof for that entry on every new write attempt. `afterWrite`
is called immediately after successful publication by both legacy FileTools
mutation methods, never on failure or in a staged context. It records observed
actual bytes (SHA-256/size), POSIX permissions where available, leaf no-follow
regular-file identity and all parent directory identities. Before alone never
proves completion. Unknown completion, missing/replaced entries, mode/content
drift, symlinked/replaced ancestors and changed backups never authorize restore.

Restoring one turn publishes a new inode. An older same-file checkpoint's
identity may be advanced only when its recorded successful after exactly equals
the restored turn's captured before (including identity), and the newly restored
content/mode matches. This preserves consecutive legacy turn undo without
accepting arbitrary equal-content external replacement.

This is explicitly non-managed, process-local legacy mode: immediate writes,
temporary original backups, no durable journal, exclusive ownership, hostile
writer CAS, disk synchronization or multi-file atomicity guarantee. Parent's
managed engines remain separate. Portable path probes assume cooperating writers.
Existing internal symlink aliases are resolved by FileTools before checkpointing;
the alias remains intact, but replacement of the physical checkpoint path is refused.

## Delivered source and evidence

Changed only:

- `mini-agent/plugins/plugin-fs/src/main/java/com/example/miniagent/plugin/fs/FileCheckpoints.java`
- `mini-agent/plugins/plugin-fs/src/main/java/com/example/miniagent/plugin/fs/FileTools.java`
- `mini-agent/plugins/plugin-fs/src/test/java/com/example/miniagent/plugin/fs/FileToolsTest.java`
- `mini-agent/plugins/plugin-fs/src/test/java/com/example/miniagent/plugin/fs/FileCheckpointsTest.java`
- this contract.

The new test class has 15 test methods / 16 total cases, including
`humanEditAfterLegacyWriteIsNotClobbered`, repeated write/edit completion,
failed first/repeated writes, before-only completion, mode restoration, new-file
deletion, mixed restoration/conflicts, equal-content inode replacement,
mode-only drift, leaf/parent symlinks, missing entries, human-added directory
content, corrupted backup links and existing state-directory aliases. Tests use
real temporary files, no mocks, sleeps, polling, new skips or dependencies.
The existing consecutive-turn undo test still asserts restoration of both turns.

Two formerly intended behaviors were made stale by the requested safety rule:

1. `partialUndoRetriesOnlyPendingChanges` formerly recreated a deleted second
   file after replacing its parent. The renamed test now requires a typed conflict,
   restoration of unrelated first/third files, preservation of later human
   replacement bytes and retention of the conflicted turn/backups. Its separate
   session manual write now calls `afterWrite` explicitly.
2. `backupCleanupFailureDoesNotRequeueCommittedDestination` formerly treated a
   before-only directory copy as a completed mutation and restored it. The renamed
   test now requires UNKNOWN_COMPLETION, preservation of human-added children,
   independent restoration of any completed regular file, and no silent
   advancement to the older turn. Directory publication is not a FileTools operation.

Undo still removes a committed entry before temp/backup cleanup; cleanup failure
raises an I/O error rather than silently succeeding or requeueing a restored file.
`lastUndoOutcome` retains any already committed successes on that error.
Close deletes its private tree without following backup symlinks and reports errors.
Filesystems without identifiable no-follow entries fail closed; POSIX permissions
are recorded when the provider exposes them. The mode tests target this POSIX host.
Known state-root aliases (including macOS temporary-directory aliases) are resolved
once at construction, not accepted dynamically at undo.

Final Java LSP inspection of both production and both test files returned zero
diagnostics (errors and warnings). This is static evidence, not a runtime test pass.
The contract's LSP check reported no configured server for `.md`; it was inspected
as source text instead.
Maven and runtime tests: explicitly UNRUN in this lane; parent owns the build window.
Suggested parent validation from `mini-agent/`, not executed here:

```bash
./mvnw -pl plugins/plugin-fs -am -Dtest=FileToolsTest,FileCheckpointsTest -Dsurefire.failIfNoSpecifiedTests=false test
```
