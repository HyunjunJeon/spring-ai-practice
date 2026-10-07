# B1 delta gate review

recommendation: APPROVE
confidence: High for the reviewed B1 source and retained behavioral evidence. This was a fresh read-only artifact review, not a new execution run.
goalId: agent-evolution
blockers: []

## Original intent and desired outcome

originalIntent: Deliver all 23 approved implementation items and F1-F4 in `.omo/plans/spring-ai-agent-evolution.md`, in the shared Java workspace, preserving the official Spring AI loop, existing profiles/plugins/OAuth, ordinary turns, and the explicit safety, approval, staging and recovery contracts.

desiredOutcome: Managed delegation must share the actual parent's budget. Acknowledged child consumption must survive return, another plan, parent continuation, fresh attempts and explicit recovery. Unknown consumption must remain unknown; admitted overshoot must remain visible. Pending child work must not leave a terminal parent that later needs charges. Exact identities and scopes, terminal immutability, parent-only apply and existing cancellation behavior must remain intact.

userOutcomeReview: The original rejection was justified, but the current delta closes B1. The actual launcher journals now retain all eight child observations in the parent before completion, rather than leaving parent usage at zero. Current integration tests exercise the parent's next tool/model, a second delegation and fresh-attempt enforcement, not just a detached ledger. Source inspection connects those results to durable import, the live meter and same-parent recovery. No concrete approved criterion failure was established. This approval is the delta decision; the original full-plan report remains the context for unaffected features and its previously accepted QA.

## Criterion and source review

### B1: Durable, exactly-once live parent accounting

`mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/RunContext.java:105-163` imports an acknowledged observation, not a cumulative child result. It checks root-parent/open-attempt authority, exact child spec, parent run/conversation/cwd lineage and membership of the exact payload in the source journal. Its charge identity includes child run, child attempt, usage kind and call ID. Previous identities are loaded from the parent journal. A repeated observation returns without appending or modifying the meter, and changed consumption under an existing identity is refused.

The import computes the delta against the parent's immutable cap, appends it through the parent writer, and then absorbs it into the live meter. `FileRunStore.java:267-286` validates source sequence/CRC and projects the complete prefix; `FileRunStore.java:507-529` acknowledges append only after projection, write and force. The child wrapper queues only acknowledged appends. `ChildScopeFactory.Scope.observeUsage` drains those outside the child writer, charges the captured parent first and only then reports usage to the scheduler. The parent's synchronized accounting operations serialize the imports with its own meter operations.

`RunProjector.java:70-87` reserves the child charge shape and root-parent authority and rejects the same charge identity across parent attempts. Ordinary observations remain attempt-local. `RunContext` poisons accounting on storage failure; subsequent model/tool admission and final active-time emission refuse. `RunExecutor.execute` propagates store failures rather than converting an uncharged child into successful parent completion.

The direct core tests use real file journals and contexts to exercise repeated callbacks, two concurrent callbacks with duplicate deliveries, distinct usage kinds with the same call ID, parent-attempt-global dedup, a source/import gap, foreign/unacknowledged observations, terminal refusal and injected parent-append failure. These are observable state/accounting assertions rather than success prose.

### B1: Continued enforcement, reservations, unknown and overshoot

`UsageRecorder.java` seeds the live meter from authoritative prior usage. Its imported delta recalculates overshoot at the parent's cap; child overshoot is not copied. Unknown is sticky. Tool admission calls `beforeModel`, so configured token-cap uncertainty blocks tools as well as models. Imported child active time enters the baseline, while `activeDelta` emits only the parent's own intervals.

`DefaultDelegationRuntime.executeOutcome` selects the captured owner's live aggregate supplier and constructs an aggregate-aware `BudgetLedger`. `BudgetLedger.java:43-78,120-125` does not add child observations again to that managed aggregate. It still uses child observations to account for reservations and child-only results, subtracting observed consumption from outstanding managed reservations. The two-argument constructor preserves unowned summation. Settlement returns child usage without another parent debit.

Child model/tool boundaries recheck both scheduler and live parent authority/caps. Plan workers explicitly open `accountingOwner` during scope construction at `DefaultDelegationRuntime.java:534`; there is no assumption of inherited ThreadLocal authority. The parent budget is paused during synchronous delegation and child resume. The injected-clock regression and actual launcher distinguish imported child intervals from parent waiting.

The six current app cases in `SharedParentBudgetIntegrationTest.java` assert exact totals/reopened storage; refusal of a next parent tool and its fresh attempt; refusal of a second child allocation; sticky unknown preventing next and resumed parent models; parent overshoot of 12 for 32 tokens against cap 20; and model-driven IMPLEMENT pending/resume with the same parent and child runs, fresh attempts, full accounting and unchanged live source. The scripted resumed tool request makes the tool-cap regression meaningful.

### Items 7, 20 and 21: Nonterminal pending and exact recovery

`RunExecutor.java:445` reconciles acknowledged direct-child prefixes before new root-parent work. `RunContext.java:166-179` refuses recovery work while a child is still RUNNING or WAITING_APPROVAL under its current owner. It imports complete acknowledged observations only; it does not create measured zero, tool completion or success evidence.

`RunExecutor.java:502-509,558-566` keeps parents with nonterminal children INTERRUPTED even when an operator returns Pending or a model receives a finished delegate-tool result and then supplies a final narrative. Terminal append remains prohibited by `RunProjector`.

`ChildScopeFactory.java:145-179` loads the exact stored parent and child, checks original conversation/cwd/model/task/budget lineage and scope, and uses `RunExecutor.forAgent(parentAgent)` to reuse the current assembled executor. `RunExecutor.resumeOperation` resumes the same run through ordinary selection, binding, drift and UNKNOWN validation; it does not allocate a replacement parent or recreate a default drift gate. Reconstruction requires the captured exact active parent, and new child attempts import through that owner.

`DelegationRuntimeTest.pendingOperatorAndRestartedChildUseSameResumableParentAndDurableAggregate` checks same specs/run count, new attempts, sticky unknown and matching aggregate after retained runtime closure and explicit reconstruction. The app pending test covers the actual model/tool path. The passing OS-kill delegation recovery fixture still rejects foreign conversation, wrong goal, altered scope and missing UNKNOWN resolution before accepting exact recovery, preserves the child journal on refusal, retains null recovery-view parent metadata and does not invent verification/apply success. This process test does not itself assert final parent totals; the file-reopen accounting and current pending integration tests cover those separately.

### Items 21 and 22: Cancellation, legacy scope and read-only apply

`ChildScopeFactory.java:136-142` uses exact `runs.read(id)` with typed NOT_FOUND handling. Historical listing is not used to determine whether the supplied parent exists. Prefix reconciliation and the root parent's unfinished-child guard enumerate direct-child records for their separate recovery/completion purpose; current-plan lifecycle control remains exact and does not scan unrelated historical runs for cancellation.

All 11 original `DelegationCancelTest` cases are present, including the store wrapper that throws if lifecycle calls `list()`. Their current XML and full verification log are passing. The source still tests exact created/bound ownership, pre-bootstrap cancel, staging-wait cancel, FIFO/deferred approval, historical/foreign-owner preservation, exact resume, scope drift, UNKNOWN mutation refusal and retained stage bytes. There is no assertion removal in the inspected current file; historical byte-for-byte preservation cannot be reconstructed from an absent valid HEAD diff.

`ChildScopeFactory.inspectDrift` returns only a `DriftGate.Difference` and closes its internally constructed scope. It exposes no dispatchable scope. `StagedApplyService.java:65` now calls that inspection path while retaining parent/child/proposal/attempt/scope and exclusive apply checks. Completed-parent proposal inspection therefore does not require reopening a terminal parent or granting child execution. The existing backend/apply suite remains passing. Unowned scopes retain their former ledger behavior and the original lifecycle tests exercise that path.

## Reproduced artifact evidence

The reviewer read all three current launcher specs and full event logs under `.omo/evidence/agent-evolution/B1-live-home/runs/`:

- Parent `815006bb-e7e0-44e7-a758-d034d8b0f2db`: 14 rows. Rows 3-10 contain eight distinct reserved child charge IDs, totaling 426 known tokens, two tools and 357 child active milliseconds. Row 11 records the operator's real no-model zero observation; row 12 adds 124 parent active milliseconds. Rows 13-14 end the attempt then mark COMPLETED. Total active time is 481 milliseconds; there is no later terminal append.
- Child `1194443b-eed9-4b52-8a34-64cc831f719f`: source usage rows 3, 4, 7 and 8 contain 92 + 121 tokens, one tool and 171 milliseconds.
- Child `8a174ed1-75f9-4719-ab7f-c51cc0d3fdfa`: the same four observation categories contain 213 tokens, one tool and 186 milliseconds.

Both specs name that exact parent's run and dedicated conversation and the same cwd. The launcher log names those runs and reports real read/tool/source evidence and COMPLETE. `F4-work/source.txt` still contains `Terminal verification source.` The original rejected parent's six-row zero-token/zero-tool terminal journal in `F4-home-live` is still retained; it was not rewritten to manufacture corrected history.

`B1-budget-apply-resume-regression.log` contains 37 core and 53 app passing tests and BUILD SUCCESS. Current `B1-final-exact-parent-verify.log` ends with every reactor module SUCCESS and BUILD SUCCESS. The current XML contains the exact six app and ten core B1 case names, with zero failures/errors/skips/flakes. All 90 retained suite headers were read in five paginated searches; every header is nonempty and clean. Existing cancellation, scope, recovery, apply, budget, memory, profile and provenance suites remain included. The 3 + 6 + 1 OS-crash integration cases are also retained and passing.

Count precision: the 90 retained XML headers total 700 tests, whereas full-log module result totals sum to 712. The log explicitly executes 24 nested MemoryBudget cases, while its retained aggregate XML header reports 12. These are different retained count views, not a B1 failure or proof of missing execution. Approval relies on actual named cases and assertions, not either headline count. The expected duplicate-command startup stack trace is followed by the passing `SlashCommandConflictTest` result, not a hidden build error.

The latest `.omo/ulw-execute/ledger.jsonl:55` records parent134/136/137 exit 0, fixes and cleanup. The plain launcher and Maven log bodies do not independently encode their shell exit status; this review treats the terminal/build output as observed artifacts and the exit statuses as retained ledger evidence, not newly reproduced process exits.

## Direct programming and remove-ai-slops pass

Consulted `/Users/rascal/.omo/memory/transient-runs/mukhwk6j-20070-oy6kgf/agents/meditherapy-brain-cbe2022b/repo/skills/programming/SKILL.md`. It supplies minimal, type-strict, async-correct and honest-architecture criteria; its language index contains no Java-specific reference. `remove-ai-slops` was unavailable in the searched skill locations, so the supplied criteria were applied directly.

The current nine production files, new app/core tests and changed runtime tests were reviewed directly. There is no valid HEAD diff, so this is a current-source delta review using the contract's changed-path inventory, not an invented Git patch review.

- Excessive or useless tests: The new layers cover distinct storage, concurrent admission, live scheduler, official advisor and continuation/recovery boundaries. Some assertions overlap intentionally across layers, but no excessive suite or behavior-free test was identified.
- Deletion-only/removal tests: None identified. Refusal tests check journal, state, meter, source bytes or real effect counts; they do not merely assert disappearance of a requested API/string.
- Tautological or implementation-mirroring tests: The core tests use real persisted events and independent reopened stores. The app tests exercise actual Spring assembly, delegation and file tools. They can fail when durable records and the live meter disagree. They do not compute the expected result by invoking the production aggregation being tested.
- Unnecessary extraction, parsing or normalization: Source membership/CRC checks, reserved dedup identity, exact lookup and read-only drift inspection serve explicit accounting, durability and authority contracts. No new parsing/helper layer unrelated to B1 was established. There is no new application runtime/module/dependency introduced in the reviewed delta.
- Async discipline and maintenance: New concurrency tests use preinstalled bounded latches/futures; the inspected new/changed tests contain no sleep/poll timing pattern. Captured parent ownership, journal-first import and explicit reconstruction avoid hidden ThreadLocal inheritance or success-shaped recovery. No maintenance or scope finding rises to a failed approved criterion.

Report coverage was checked rather than inferred. The original `.omo/evidence/agent-evolution-gate-review.md` explicitly records the programming perspective and all six overfit/slop categories, and identifies the original parent-boundary false confidence. `.omo/drafts/agent-evolution-review-notes.md` is a planning review, not current code QA. `B1-results.md` and the fix contract do not contain a separate named delta skill-pass report. This is a coverage NOTE, not an approved-plan blocker; the direct current-tree pass above is this review's own evidence and does not rely on the earlier report as a substitute.

## Checked artifact paths and exact limits

Contract and evidence read:

- `.omo/plans/spring-ai-agent-evolution.md`.
- `.omo/evidence/agent-evolution-gate-review.md`.
- `.omo/drafts/shared-parent-budget-fix-contract.md`.
- `.omo/drafts/agent-evolution-review-notes.md`.
- `.omo/evidence/agent-evolution/B1-results.md`.
- `.omo/ulw-execute/ledger.jsonl:53-55`.
- `.omo/evidence/agent-evolution/{B1-budget-apply-resume-regression,B1-final-exact-parent-verify,B1-live-launcher}.log` (relevant result sections and full launcher output).
- All three named `B1-live-home/runs/<id>/{spec.json,events.log}` files.
- `.omo/evidence/agent-evolution/F4-work/source.txt` and the original rejected `F4-home-live/runs/d68c634e-bf61-4f71-9208-36d032607262/events.log`.
- All 90 current `mini-agent/**/target/surefire-reports/TEST-*.xml` suite headers and the B1 case entries.

Production read completely, relative to `mini-agent/agent-core/src/main/java/com/example/miniagent/core/`:

- `run/{RunContext,UsageRecorder,RunProjector,RunExecutor}.java`.
- `delegation/{BudgetLedger,ChildScopeFactory,DefaultDelegationRuntime,DelegationController,StagedApplyService}.java`.

Supporting source read: `run/FileRunStore.java` source-prefix validation, process-loss initialization and durable append sections. Test files read completely: core `SharedParentBudgetTest`, `DelegationRuntimeTest`, `DelegationCancelTest`; app `SharedParentBudgetIntegrationTest`, `DelegationRecoveryIntegrationTest`. The affected delegation recovery sections of `CrashRecoveryProcessFixture` were read, not its entire unchanged implementation. Scoped `mini-agent/AGENTS.md` and the programming skill were also read.

No build/test/provider/launcher/browser process or diagnostics was run by this reviewer, as explicitly required. Journal sums, identities/scopes and row ordering were inspected from the retained files; SHA/CRC values were not independently recomputed by a new executable. Relevant clean Java diagnostics are reported by the parent, but a complete retained diagnostic export was not audited. The original full-plan manual QA was not repeated. There is no valid committed HEAD diff, and no claim of historical byte-for-byte test preservation is made.

The two run journals are not atomic. Acknowledged source-prefix reconciliation is the documented recovery boundary; neither absent consumption nor tool success is fabricated. Old terminal parents remain immutable. Paid billing ceilings, power-failure atomicity and unrelated future hardening are not approval requirements.

No JS eval/status tool is available in this child. The explicit task fixes the authorized new report path to `.omo/evidence/agent-evolution-gate-review-B1.md`; no attempt-directory lookup or alternative write was made. This report is the only write. No source changes, private-auth inspection, external action or additional reviewer occurred.

## Verdict

APPROVE the B1 delta. There are no evidence-bound blockers against the approved shared-budget, exact recovery, pending lifecycle, terminal immutability or affected cancellation/apply contracts. The original rejection remains intact as historical evidence.
