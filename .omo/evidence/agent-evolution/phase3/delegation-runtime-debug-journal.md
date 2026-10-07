# Runtime20 actual parent follow-up

First parent runtime run exit1: scope11/budget6/lock2 passed; runtime16
has13 passed and3 failed. No runtime completion checkbox marked.

- Dependency case returns topological [a,independent,b] rather than requested
  [a,b,independent], and model error produces sticky unknown usage which correctly
  prevents another model call under a finite parent cap. That new fixture must
  exercise an independent, known-usage failed child instead.
- Queue-discovery case observes two running children but no queued child.
- Abnormal worker case does not observe both model entries within its bounded
  event wait.

Distinguishing concurrent hypotheses: short store lease rejects simultaneous
stage preparation; worker admission rejects a submission; or one model boundary
sets unknown parent usage before the other starts. Actual attempt errors and
snapshot statuses distinguish them.

Temporary probe: stderr line in DefaultDelegationRuntime.attempt catch, reporting
child ID and exception class/message in fake-only targeted tests. Remove before
completion. No debugger or temporary directory created. Persistent probe log is
delegation-runtime-probe.log. Parent owns all follow-up; completed worker is not
resumed.

Fake-only probe95 reproduced `ChangeStore.Failure: Workspace already leased`
for child b during concurrent bootstrap. Dependency fixture also observed
`UNKNOWN_USAGE` for independent after the raw failed model round, as designed.
The temporary stderr probe is removed.

Parent introduced acquireStaging: stage-only journal metadata waits on exact
in-JVM lease release notification, interruptibly. Cross-process refusal and
ordinary acquire's live apply/undo LOCKED behavior are unchanged. Stage creation
and proposal recording use this entry point; no model or live filesystem apply
runs under it. JVM release notifies waiters, without sleep/polling.

Runtime result projection now follows requested plan order, not scheduling
topological order. Newly authored dependency fixture uses a known-usage denied
path request instead of an unknown failed model response; all failed/skipped/
independent success and zero-effect behavior remains asserted, and separate
unknown-cap regressions stay intact.

Runtime/store/test LSP zero. Actual scope, runtime, budget, lock and previous
store/apply/undo/stage tests are subscribed as bash_96, with
delegation-runtime-concurrent-parent-test.log. Completion pending. Parent pause,
durable lineage and creation publication hooks still need in-scope integration;
no task20 completion checkbox is claimed.

bash_96: all80 runtime/scope/budget/lock/store/apply/undo/stage tests passed,
zero failures/errors/skips, exit0. Probe line removed and no probe remains.

Parent added RunContext.usage from its existing meter and pauses/resumes only
the exact captured parent's budget around synchronous runtime coordination.
Live meter usage, not a stale journal wall snapshot, is the authority when
managed. A controlled fake clock advances 20 seconds during delegation and
proves only3 prior seconds plus2 subsequent seconds are charged. No fixed sleep.
bash_97: runtime17 plus scope11/budget6/lock2 all36 passed, exit0.

Parent registered DelegationProperties, SessionRunLock and lazy runtime in
existing core Spring configuration. New app regression actually executes two
independent children, preserves session, plan order and refuses to claim
verification from narrative. Runtime/app/config LSP: no new errors; existing
five SpringAI deprecation warnings retained. Full real Spring rerun is pending
as bash_98, delegation-spring-parent-test.log. Durable parent lineage and
pre-capture publication are task21 bridges, not claimed as completed here.
