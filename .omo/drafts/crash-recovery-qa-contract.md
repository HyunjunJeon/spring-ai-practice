# F3 crash recovery QA contract

Status: parent119 full clean verify passed. Actual Surefire XML: run crash 3,
change crash 6, child recovery 1; all 10 passed, zero failure/error/skip.
Parent directly reviewed assertions and subscription/kill/relaunch boundaries.
No Maven command, commit, paid call, or production edit was performed by this
test-authoring track.

## Exact paths and ABI

All Java files are new, under
`mini-agent/agent-app/src/test/java/com/example/miniagent/app/`:

- `RunCrashRecoveryIntegrationTest.java`: package-private JUnit Jupiter class,
  three tests: actual forced JVM loss and explicit UNKNOWN resolution; known
  partial run tail; complete CRC corruption preserved.
- `ChangeCrashRecoveryIntegrationTest.java`: package-private JUnit Jupiter class,
  six tests: PREPARED crash; two-file partial apply; human edit preservation;
  partial change journal; complete bad CRC; exact approval digest and STALE
  baseline refusal.
- `DelegationRecoveryIntegrationTest.java`: package-private JUnit Jupiter class,
  one test: actual captured child crash and exact scoped recovery.
- `CrashRecoveryProcessFixture.java`: public final Java entry point,
  `public static void main(String[] args) throws Exception`.
  Arguments are `mode agentHome workspace`. FQCN:
  `com.example.miniagent.app.CrashRecoveryProcessFixture`.

Fixture modes: `run-crash`, `run-recover`, `run-tail`, `run-corrupt`,
`delegation-crash`, `delegation-recover`, `change-prepared`, `change-partial`,
`change-before`, `change-after`, `change-drift`, `change-tail`, `change-corrupt`.

The fixture uses the parent's Java executable and asserts Java feature version
21. It uses `surefire.test.class.path`, falling back to `java.class.path`;
classpath entries become absolute before changing subprocess cwd. No nested
Maven build, external provider, Spring context, or runtime dependency is needed.
All actual stores, stages and processes use the caller's `@TempDir` home/cwd.
The subprocess environment is cleared.

## True process boundary

The parent creates stdout READY/DONE futures, its reader, and `Process.onExit()`
before sending exact stdin `GO`. The subprocess cannot enter any engine before
GO. It emits exact stdout `READY` and flushes only after the relevant durable
acknowledgement, then blocks on stdin while its real writer/lease remains held.
The parent awaits READY with a 30-second bound, validates the live lock/effect,
subscribes descendant exits, calls `destroyForcibly`, and awaits exits and stdout
drain with bounds. Try-with-resources enforces the same cleanup on assertion
failure. No sleeps, polling loops, timing assumptions, reflection, skipped tests
or authority bypasses are present.

Run STARTED comes from the real `ToolJournalInterceptor` called by the common
`RunExecutor` and official single tool advisor. The raw callback asserts the
acknowledged unfinished mutating entry, changes the actual attempt stage, then
signals READY. Its live source is unchanged. The callback throws if called
during recovery, detecting any automatic mutation retry.

Change PREPARED comes from `ApplyEngine.BoundaryHook`, after the real lease
prepares backup and proposed blobs and forces the shared journal. The partial
case lets the engine finish the first file, then blocks at the second file's
PREPARED hook. No invented mutation engine or checkpoint metadata is used.
The engine obtains the genuine exclusive capability through the console's exact
`exclusive <id> <digest>` response. No token construction/reflection is used.

Delegation starts a genuine parent with `RunExecutor.startOperation` and invokes
a genuine `ChildScopeFactory.Scope.call()` with independent model binding,
conversation, attempt, stage, interceptors and durable child lineage. This is
an actual Java scoped child in the killed JVM, not prewritten metadata. Restart
constructs `DefaultDelegationRuntime` plus the real evidence collector and
`DelegationController`; `recover` invokes the real reconstructed `Scope.resume`.
It rejects ordinary parent-executor child resume, a foreign original
conversation, altered goal, altered path authority, and unresolved UNKNOWN.
Exact recovery with an explicit SKIP preserves the original run/spec/conversation
and creates a fresh attempt and binding. Narrative-only completion does not
become verified delegation COMPLETE and does not apply anything.

## Assertions after distinct JVM relaunch

- Released run/workspace locks are exercised by actual fresh-JVM writer/lease
  acquisition; abandoned active runs project INTERRUPTED/PROCESS_LOST.
- Unfinished mutations stay UNKNOWN until an exact operator resolution. The
  rejected resume does not change journal bytes. Resolved resume uses a new
  binding and attempt on the same run, with no extra STARTED or live apply.
- Run incomplete known-schema tails are read without mutation and repaired
  only by real writer acquisition/append. Complete CRC damage is refused by
  read and writer reopen without changing bytes.
- Change recovery reads the shared journal, hashes the real backup blobs,
  classifies actual before/after/OTHER source fingerprints, preserves incomplete
  PREPARED checkpoints and first-file APPLIED truth, and refuses automatic apply.
- Change tails remain TRUNCATED and preserved; complete bad CRC remains CORRUPT
  and preserved. This intentionally differs from run-tail writer recovery.
- Human edits remain HUMAN_EDIT/OTHER and byte-identical. Approval requires the
  exact original digest; a real exclusive proof cannot apply after baseline
  drift, which records STALE without any PREPARED writes.

## Limitations and parent QA

The actual OS kill boundary is acknowledged STARTED/PREPARED, not a simulated
power failure inside `FileChannel.force`, atomic move, or a kernel partial write.
Known incomplete tail and bad CRC bytes are injected and forced by the parent
after real process death, then consumed by a distinct JVM. Existing stores do
not expose a public mid-frame force hook; proving those narrower boundaries
would require an explicitly approved production fault seam. Do not claim this
suite manufactures naturally torn sectors.

The approval digest/STALE test is an in-process engine test accompanying the
five real change subprocess tests, not an approval-request UI restart test.
Delegation crash executes the real scope directly inside a real parent
operation; recovery uses the real controller/runtime. This does not exercise
the runtime worker queue or TUI approval lifecycle. F1 and existing scheduler
tests remain the parent's evidence for those separate behaviors.

Parent must run the approved F3 commands once after the coherent handoff:

```bash
cd mini-agent
./mvnw -pl agent-app -am test \
  -Dtest=RunCrashRecoveryIntegrationTest,ChangeCrashRecoveryIntegrationTest,DelegationRecoveryIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl agent-core -am test \
  -Dtest=UndoEngineTest#humanEditAfterApplyIsNotClobbered \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Capture F3-ok/F3-fail logs and actual agent-app Surefire XML for all three
classes. This authoring task supplies source assertions; no execution log/XML
or subprocess runtime success is claimed. Check Java 21 selection and the
reactor classpath in the parent run, and retain any failure as a finding.
