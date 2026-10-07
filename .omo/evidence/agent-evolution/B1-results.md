# Shared parent budget correction

Original final gate B1 was valid: a completed managed parent retained zero
usage despite 426 known child tokens and two tools. Its old records remain
immutable. See `../agent-evolution-gate-review.md` for the rejection.

## Current implementation

Complete source/ABI and durability reasoning:
`.omo/drafts/shared-parent-budget-fix-contract.md`.

Acknowledged child observations are imported journal-first, then into the live
parent meter. Identity includes child run/attempt/kind/call; parent attempts
cannot charge it twice. Managed ledgers do not add the same observations to the
already aggregated parent again. Parent-cap unknown, overshoot and active time
remain distinct. Child waiting does not double-charge parent active intervals.

Pending operator or model-driven child work keeps the same parent nonterminal.
Exact explicit child recovery reopens that same parent run under a fresh
attempt, preserves original policy/configuration, reconciles acknowledged
prefixes, and resumes the same child. No replacement parent or unsafe terminal
append is permitted. Read-only apply inspection returns a drift result, not a
dispatchable child scope.

## Executed checks

Parent134: 37 core + 53 app tests, failures/errors/skips 0, BUILD SUCCESS,
exit 0. `B1-budget-apply-resume-regression.log`.

The six actual Spring/advisor/file-tool integration regressions prove:

- Exact parent model plus child known tokens and tool counts, reopened store.
- Post-delegate parent tool refusal and preserved cap under a fresh attempt.
- A second delegation cannot forget the first child's consumption.
- Sticky child unknown blocks parent models and resumed parent models.
- Parent-cap overshoot differs from child-cap overshoot (32 total, cap 20,
  parent overshoot 12).
- Model-driven child write pending -> exact approval resolution -> same parent
  and child IDs/fresh attempts, cumulative accounting, original source unchanged.

Existing genuine child restart, mixed read/stage/VERIFY/parent-exclusive-apply,
RunExecutor, and model-delegate cases pass. Core regressions exercise journal
gap/dedup/poisoned accounting, concurrent callbacks and exact captured authority.

## Findings during integration

Earlier logs are retained failures, not successful evidence. Parent fixed
missing captured parent scope on the child-construction worker, canonical
macOS fixture paths, a contradictory new uncapped reservation fixture,
read-only apply accidentally using dispatch authority, and a new scripted
resume fixture that returned harmless narrative rather than attempting a tool.
Original apply/lifecycle assertions were not removed or weakened.

Parent135 whole regression found a real prohibited historical-list scan in
parent lookup, caught by all 11 original DelegationCancelTest cases.
Replaced lookup with exact RunId read and typed NOT_FOUND handling; the original
no-scan tests are unchanged. Parent136 full `./mvnw clean verify` passed,
exit 0. Parent read every current XML header: 90 suites, 700 tests,
zero failures/errors/skips.

## Remaining verification

Parent137 exact fake plain `bash ./agent.sh` two-child launcher passed exit 0:
`B1-live-launcher.log`. Parent directly read all three authoritative journals
under `B1-live-home/runs/`. Parent `815006bb-e7e0-44e7-a758-d034d8b0f2db`
ends COMPLETED with 426 known tokens, two tools, 481 active milliseconds.
Children `1194443b-eed9-4b52-8a34-64cc831f719f` and
`8a174ed1-75f9-4719-ab7f-c51cc0d3fdfa` each consume 213 known tokens/one tool,
with 171 and 186 active milliseconds. Eight unique acknowledged source charges
appear exactly once in the parent; its own active interval adds 124 milliseconds.
The original source remains `Terminal verification source.`.

Fresh delta gate completed APPROVE, high confidence, no blockers:
`../agent-evolution-gate-review-B1.md`. It inspected current source, the actual
three journals, named regression cases, all 90 XML headers and build evidence;
it ran no processes. Relevant Java LSP is clean.

The retained XML count is 700; module result totals in the full execution log
are 712. Nested MemoryBudget executes 24 cases but retains a 12-case aggregate
XML header. Both count views are clean; the gate relies on named behavioral
cases, not the headline count.
No paid provider call, commit, deployment, or JavaScript app dependency.
