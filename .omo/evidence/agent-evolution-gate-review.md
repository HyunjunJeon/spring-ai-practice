# Final gate review

recommendation: REJECT
confidence: High for the blocking finding; this is a source and retained-artifact audit, not a new execution run.
goalId: agent-evolution
blockers:
- id: B1
  violatedCriterion: Plan item 20 and the common run/delegation shared-parent-budget contract.
  evidencePointer: mini-agent/agent-core/src/main/java/com/example/miniagent/core/delegation/DefaultDelegationRuntime.java:148; mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/RunContext.java:77; .omo/evidence/agent-evolution/F4-home-live/runs/d68c634e-bf61-4f71-9208-36d032607262/events.log:3-6.
  observation: Child consumption is confined to a new ledger per delegation execution and never charges the parent's live meter or authoritative usage. Returning to the parent, starting another plan, or resuming the parent therefore forgets the children's consumption and uncertainty.

## Original intent and desired outcome

originalIntent: Implement the approved `.omo/plans/spring-ai-agent-evolution.md` in the shared workspace: all 23 implementation items and F1-F4, covering runtime model selection, durable managed execution, staged changes and verification, and scoped delegation.

desiredOutcome: A usable Java 21 / Spring Boot 4.1.1 / Spring AI GA 2.0.1 CLI and services, with immutable turn bindings, safe explicit recovery, exact shared approvals, a shared parent/child budget, stage-only child writes, exclusive parent publication, conflict-preserving undo, and evidence-backed delegation results. Ordinary legacy turns, plugins/profiles, memory, permissions, trace, and Java-owned OAuth must remain compatible. No paid calls, commits, deployment, external publishing, new Maven modules, diff dependency, or application Node/Bun/senpi runtime.

userOutcomeReview: Most of the shipped behavior has substantial current source, passing regression artifacts, actual process-boundary tests, and real terminal captures behind it. It is not merely a collection of DTOs or disconnected services. Approval is blocked by the shared-budget behavior, which is expressly required and cannot be inferred from the passing counts. A successful single delegation does not establish that the parent continues enforcing the total budget after its children finish.

## B1: Parent budget does not retain child consumption

The plan requires a single parent/child RunBudget, reservations within the parent's remaining limits, parent limits taking precedence, and preservation of unknown usage and overshoot. Item 20 explicitly calls for shared reservations and aggregation using the existing run budget.

The current code has the following sequence:

1. `DefaultDelegationRuntime.java:143-151` takes the captured owner's `usage` supplier and creates a new `BudgetLedger` for each `executeOutcome` call.
2. Its child boundary callback at `DefaultDelegationRuntime.java:603-614` observes durable child usage into that ledger only. `finish` at lines 625-631 settles the ledger into the `ChildResult`.
3. The execution return/finally at lines 166-173 resumes the parent meter without charging child deltas. The controller and `DelegateTool` return the result without any parent accounting hook.
4. `RunContext.java:54-77` enforces the parent's own `UsageRecorder`. The meter is initialized from the parent's authoritative record at line 35. Its APIs account for its own model/tool/time activity, not child consumption. Merely appending an external `UsageRecorded` event would also be insufficient: `recordUsage` at lines 84-94 appends the event but does not update the live meter.

There is retained observable evidence, not just a hypothetical missing branch:

- `.omo/evidence/agent-evolution/F4-home-live/runs/d68c634e-bf61-4f71-9208-36d032607262/spec.json:1` identifies the actual managed parent with tool cap 50.
- The specs for child `e5b33021-8949-4da6-921b-97e9cc788c21` (beta) and `ea216c82-1d66-4a89-9498-5596be488a41` (alpha) both name that exact `parentRunId`.
- Each child `events.log:3,4,9,10` records 92 + 121 = 213 known tokens, one tool, and 115/111 active milliseconds. Combined child usage is 426 tokens, two tools, and 226 active milliseconds.
- The parent `events.log:3-6` finishes with only `operator-no-model` zero usage and its own 56 active milliseconds, then COMPLETED. It contains no child charge.
- `F4-delegation-complete.png` independently displays the child aggregate of 426 tokens and two tools. The children did execute; their usage was not zero.

Impact: Within one plan the ledger gates children correctly, but after returning the parent's next model/tool boundary uses an uncharged meter. A second delegation starts a fresh ledger from that same parent-only usage. For example, after a delegate call and one child's tool consume a total tool cap of two, the parent still sees only its own one tool attempt and admits another tool. Child token usage that is unknown with a configured cap likewise does not stop a later parent model call. Parent restart/resume seeds from the undercounted durable parent record. The retained example proves accounting loss; the exact cap-overrun example follows from the current source and was not newly executed in this read-only review.

Action: Make acknowledged child deltas participate exactly once in shared, durable parent accounting and in its live enforcement, across completed/pending/failed/cancelled plans and explicit resume. Preserve parent-only versus child-only display fields if needed, but use the aggregate for the shared limit. Avoid summing both cumulative child results and their durable deltas. Add an actual managed-parent regression that performs delegation and then a parent model/tool or second plan, asserting refusal at the shared cap; cover sticky child unknown usage and reconstruction after resume. The existing isolated ledger tests are not sufficient for this boundary.

## QA audit

- F1: Read the actual `AgentEvolutionIntegrationTest.java`. It uses official OpenAI-compatible providers with port-zero HTTP, different synthetic keys and paths, three memory placements, real scoped research/write/VERIFY subprocess, exact broker approval, console exclusive publication, verification, and undo. It asserts actual workspace bytes and source/log evidence. Current XML has three passing cases. Its generous default budgets and one-plan shape do not detect B1.
- F2: Inspected all 88 current `TEST-*.xml` suite headers under `mini-agent/**/target/surefire-reports/`, using paginated tool searches. Every header has tests greater than zero and zero failures/errors/skips/flakes. Read the current `F4-final-console-clean-verify.log` reactor completion: all modules SUCCESS and BUILD SUCCESS at line 873. It retains the expected negative-startup ERROR inside the passing `SlashCommandConflictTest`; that is not treated as a build failure. Read the exact fake-launcher output in `F2-launcher.log`.
- F3: Read all three crash integration classes and the complete `CrashRecoveryProcessFixture.java`. GO is sent after READY/DONE/onExit subscriptions exist. STARTED/PREPARED are real acknowledged engine boundaries; the parent forcibly kills the JVM and launches another. Lock refusal/release, stage effects, UNKNOWN refusal and explicit SKIP, new attempts/bindings, CRC/tail preservation, partial apply, and human-edit preservation have concrete assertions. All ten cases are present and passing in current XML. This evidence does not claim torn-sector/power-failure behavior inside an unacknowledged force.
- F4: Personally viewed all seven designated final PNGs. Wide dark/light show captured model and exact approval/run/attempt identifiers; narrow dark/light show wrapping, separate input, and rejected foreign UUID; cancelled capture shows durable CANCELLED and a tool that did not execute. FIFO second capture shows the entered beta UUID and next alpha UUID. Completion capture shows actual typed source/tool proof and COMPLETE. Busy refusal is attributed to backend tests, not pixels. Old partial-paint/background-only palette captures and earlier failing EOF logs were not counted as PASS. Read final one-shot/REPL pending logs; they show WAITING_APPROVAL and pending identifiers, not automatic approval.
- The launcher and pending log files contain the application output, not embedded shell exit assertions. Exit 0/1 is recorded in the ledger and QA summaries. No new execution was performed to independently reproduce those process exit statuses.

## Direct programming and overfit/slop pass

Consulted the available programming skill at `/Users/rascal/.omo/memory/transient-runs/mukhwk6j-20070-oy6kgf/agents/meditherapy-brain-cbe2022b/repo/skills/programming/SKILL.md`. It supplies shared type-strict, async-correct, minimal-code and honest-architecture criteria; there is no Java-specific reference in that index. `remove-ai-slops` was not found in the searched local skill/runtime roots, so its supplied criteria were applied directly.

The direct pass inspected current production and representative tests, rather than accepting a report:

- Excessive/useless tests: Parameterized path/schema cases exercise actual persistence and rejection boundaries. Layered unit/process/HTTP tests address distinct seams. Counts were not used as proof of completeness.
- Deletion-only or removal-verification tests: None identified in the inspected tests. Negative absence checks for ToolStarted/ToolFinished accompany refusal/state/byte assertions; these verify that effects did not occur, not a cosmetic requested deletion.
- Tautological or implementation-mirroring tests: The real process fixture, local HTTP workflow, source-byte checks, independent reopened stores and exclusive capability path can fail through actual integration. Pure ledger tests do leave the parent-continuation boundary uncovered, producing the false confidence identified in B1. FileSessionModelStoreTest's raw secret-name absence assertion is somewhat redundant beside its exact parsed field schema, but is a note, not a plan violation.
- Unnecessary production extraction/parsing/normalization: Versioned stores, digests, scoped callback snapshots, and codecs correspond to explicit durability/scope contracts. No specific out-of-scope extraction was established. Canonical paths serve realpath boundaries rather than superficial text normalization.
- Maintenance/scope drift: No new modules or diff dependency appear in the root POM. The official tool advisor owns the loop. Child factories explicitly assemble reduced clients and reuse run/change/approval storage. The budget split in B1 is an actual correctness and maintenance burden, not an architecture preference.
- Nondeterminism: Search of test sources found no Thread.sleep/TimeUnit sleep calls. The inspected asynchronous tests use bounded futures, queues, process exit and subscribed stdout tokens. No tests were edited or weakened in this review.

Report coverage: `.omo/drafts/agent-evolution-review-notes.md` is an earlier planning review, explicitly not a final code/QA report. Neither it nor the inspected final evidence summaries explicitly records the same programming/remove-ai-slops coverage. Final independent code-review-report coverage therefore cannot be confirmed. This is an evidence/process NOTE, not a separate blocker: the normative implementation plan does not require that named report, and this gate performed its own direct pass.

## Checked artifact paths

Contract/provenance:
- `.omo/plans/spring-ai-agent-evolution.md` (complete, all 27 checked entries).
- `.omo/ulw-execute/ledger.jsonl` (complete through final F4 entry).
- `.omo/boulder.json`.
- `.omo/evidence/agent-evolution/implementation-result.md`.
- `.omo/drafts/agent-evolution-review-notes.md`.
- `.omo/drafts/crash-recovery-qa-contract.md`.
- `.omo/evidence/agent-evolution/{F2-results,F4-results}.md`.
- `.omo/evidence/agent-evolution/{F2-launcher,F4-one-shot-pending,F4-repl-terminal-final,F4-final-console-clean-verify}.log`.
- `.omo/evidence/agent-evolution/F4-{wide-dark-approval-final,wide-light-palette-final,narrow-dark-stale,narrow-light-stale,narrow-cancelled,fifo-second,delegation-complete}.png`.
- The three parent/child specs and logs named in B1.
- All 88 current Surefire XML suite headers.

Production inspected (paths relative to `mini-agent/`):
- `pom.xml`, `AGENTS.md`, and inherited plugin guidance.
- `agent-core/src/main/java/com/example/miniagent/core/Agent.java` and AgentAutoConfiguration wiring search.
- `core/model/{ModelRegistry,ModelBinding,ProviderPrivateFieldFilter,ProvenanceChatModel,AgentChatClientFactory}.java`.
- `core/run/{FileRunStore,RunExecutor,RunContext,RunEvent,UsageRecorder,ToolJournalInterceptor,RunBudgetInterceptor}.java`; FileRunStore read through line 500, including writer initialization/force/recovery; its final append/close implementation was not fully read.
- `core/approval/ConsoleApprovalBroker.java`.
- `core/change/{FileChangeStore,PathPolicy,ApplyEngine,UndoEngine,VerificationRunner,StagingToolInterceptor}.java`.
- `core/delegation/{ChildScopeFactory,DefaultDelegationRuntime,DelegationController,DelegationEvidenceCollector,DelegateTool,ScopedToolInterceptor,StagedApplyService,BudgetLedger}.java`; DelegationPlanValidator reservation/DAG section.
- `agent-app/src/main/java/com/example/miniagent/app/AgentRepl.java` and `app/model/OpenAiCompatibleModelProvider.java`.
- `plugins/plugin-memory/.../MemoryPluginAutoConfiguration.java`, `plugins/plugin-permission/.../ConfirmToolInterceptor.java`, and `plugins/plugin-tui/.../TuiAutoConfiguration.java`.

Tests inspected:
- `agent-app/src/test/java/com/example/miniagent/app/{AgentEvolutionIntegrationTest,RunCrashRecoveryIntegrationTest,ChangeCrashRecoveryIntegrationTest,DelegationRecoveryIntegrationTest,CrashRecoveryProcessFixture,OneShotExitCodeTest}.java`.
- `agent-core/src/test/java/com/example/miniagent/core/model/FileSessionModelStoreTest.java`.
- `agent-core/src/test/java/com/example/miniagent/core/delegation/BudgetLedgerTest.java` and DelegationRuntimeTest parent-active-time case/test inventory.
- Searches of current test sources for sleeps and negative absence assertions.

## Exact evidence gaps and review limits

No valid committed HEAD diff exists; current files and the per-item ledger were used without fabricating a diff. This review is representative end-to-end source inspection, not a claim to have read every production/test line or to have reconstructed every preexisting assertion from an unavailable baseline. Existing-test fixture migrations are documented in the ledger; their historical byte-for-byte preservation was not independently provable here.

No builds, tests, providers, launchers, browser fixtures, or new diagnostics were run by this reviewer. The parent reports clean Java LSP, but a retained complete machine-readable diagnostic export was not found in the inspected evidence. QA JS/HTML LSP remains explicitly unavailable. Paid provider compatibility beyond fake/local HTTP and actual power-failure guarantees are not claimed or required for this delivery. Terminal automatic theme selection is not a requirement; lower-contrast default dim informational text is a disclosed note.

The supplied JS eval/status API is not an available tool in this child. `.omo/boulder.json` identifies direct `agent-evolution` execution and no ULW-loop attempt directory. This report therefore uses the permitted fallback `.omo/evidence/agent-evolution-gate-review.md`. This report is the reviewer's only write. No credentials were inspected and no external action or additional review agent was used.

## Verdict

REJECT until B1 is fixed and the parent-continuation/shared-budget boundary is verified. The existing green tests and manual QA remain valid evidence for the behaviors they cover; they do not satisfy the missing shared-budget behavior.
