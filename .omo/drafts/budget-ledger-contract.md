# Parent task20 budget preparation

Package `com.example.miniagent.core.delegation`.

`BudgetLedger(RunBudget parent, Supplier<RunUsage> parentUsage)` samples existing
parent usage and holds reservations in the same RunBudget units. The supplier
must exclude child deltas until the ledger is finished; committing child usage
to the parent and then sampling it again would double count.

- `reserve(Map<String,RunBudget>)` admits the complete batch or nothing.
- `beforeModel(String)` and `beforeTool(String)` check observed parent/child
  authority, including sticky unknown tokens and exhausted units.
- `observe(String,RunUsage)` accepts one durable delta, never a cumulative
  writer snapshot or final loop total. Scheduler boundaries must convert
  cumulative observations using monotonic previous counters/sequence.
- `remaining(String)` keeps retries inside the initial total reservation.
- `settle(String)` closes once and releases only unused reservation capacity.
- `usage()` preserves real known/unknown/overshoot totals.

Active reservations account for max(reservation, observed usage), so in-flight
overshoot cannot create fictitious capacity. Settled reservations retain actual
consumption. The existing child RunContext meter still checks actual active
time and serializes its own attempted tool caps. The scheduler must connect
the parent's admission checks before calls and observations after durable usage,
including summary calls, rather than treating this helper as a complete runtime.

Six deterministic tests are authored and LSP-clean. The first Maven attempt
in `budget-ledger-parent-test.log` exited 1 before executing tests because the
active task19 publication lacked ChildScopeFactory's ChatClient import. The
worker corrected that import while still owning the file; no parent edit.
The subsequent parent `child-scope-parent-test.log` executed all six budget
tests and both SessionRunLock tests with zero failures/errors/skips. Task19's
scope tests failed independently at bind before the memory/app reactor modules.
This is a verified budget helper, not a completed or verified scheduler.
