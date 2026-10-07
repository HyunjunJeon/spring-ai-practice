# Ultrawork Notepad - Four ordered mini-agent improvements
Started: 2026-10-06

## Plan (exhaustively detailed)
1. Read current implementation, tests, memory, Git availability and mass-ulw contracts.
2. Resolve budget/recovery contracts from discovery before dispatch. Planning child is warranted only for summary whole-turn versus hard-budget policy and bash capped-output mechanism.
3. Run stage 1 producer -> verifier DAG: change FileCheckpoints undo retry state and regression tests; verifier executes plugin-fs tests. Lead audits diff and runs C1.
4. Run stage 2 producer -> verifier DAG: atomic new-file publication and regression tests; verifier executes plugin-fs tests. Lead audits diff and runs C2.
5. Run stage 3 producer -> verifier DAG: bash positive timeout/max timeout and captured-byte bounds with cleanup, tests/config/docs. Lead audits diff and runs C3.
6. Run stage 4 producer -> verifier DAG: bounded summary input/output and complete final context budget with whole tool groups, tests/config/docs. Lead audits diff and runs C4.
7. Lead builds production-class QA driver outside application modules; nodes may not touch it. Capture logs and state predicates, with cleanup in finally for every temporary directory/process.
8. Run final Maven verify, changed-file LSP diagnostics and C5 actual packaged REPL; capture actions, filesystem outcomes and exit code.
9. Tear down all QA resources, inspect only intended source changes, record self-review, update memory and close goal.

## Success criteria + QA scenarios
HEAVY: filesystem recovery and session/process handling. No ulw-plan artifact; self-review, not plan-reviewer.
Ideal end state: all four improvements work through production classes and packaged CLI without losing files/history, exceeding configured resource bounds, or breaking existing plugin behavior.
C1 `bash .omo/qa/run-qa.sh undo`: real FileCheckpoints partial failure then retry, preserving remaining backup and not overwriting completed recovery. PASS predicates/log: `.omo/evidence/improvements/undo.log`.
C2 `bash .omo/qa/run-qa.sh atomic`: real FileTools Unicode/CRLF creation, existing permission preservation, failed publication cleanup. PASS predicates/log: `atomic.log`.
C3 `bash .omo/qa/run-qa.sh bash`: real callback rejects invalid/oversized timeout before execution, caps flooding output, reaps descendants, normal command works. PASS predicates/log/PID receipt: `bash.log`.
C4 `bash .omo/qa/run-qa.sh summary`: real memory/summarizer with deterministic recording model; all configured input/output/context limits hold, tool pairs/history stay intact, empty/malformed configuration is explicit. PASS data/log: `summary.log`.
C5 actual PTY command `java -jar mini-agent/agent-app/target/mini-agent.jar --agent.ui=repl --agent.plugins.memory.enabled=false --agent.plugins.permission.allow-non-interactive=true` from isolated cwd/home; write_file qa.txt=hello, /undo, bash printf qa-ok, /exit. PASS create/delete/output/exit=0. Capture `cli.log` and cleanup receipt.
Final `cd mini-agent && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw verify`: exit 0, no failed/skipped tests; changed Java LSP diagnostics no errors.
STOP: all C1-C5 and regressions pass with captured evidence and cleanup, then deliver final summary immediately.

## Now
Bootstrap discovery complete; finalize concrete recovery and budget contracts.

## Todo
- Stage 1 implementation/test DAG and lead C1.
- Stage 2 implementation/test DAG and lead C2.
- Stage 3 implementation/test DAG and lead C3.
- Stage 4 implementation/test DAG and lead C4.
- Final suite, LSP, packaged CLI, cleanup and self-review.

## Findings
- FileCheckpoints.java:79 removes the checkpoint before restoration; :100 removes completed backups. Retry must preserve only still-pending changes.
- FileTools.java:203 writes new files directly; existing-file path uses same-directory temporary atomic move and preserves POSIX permissions.
- BashTool.java:62 accepts unbounded caller timeout; :73 spools all output to disk and truncates only when reading back.
- ChatModelSummarizer.java:49-52 appends unbounded prior summary and total transcript; JsonlChatMemory.java:84 adds summary outside recent-window budget.
- Existing JsonlChatMemoryTest explicitly permits the whole last turn over budget; hard final budget requires replacing this stale policy test with behavior preserving pairs, not weakening coverage.
- Git history probe exited 128: no Git metadata. No repository creation or commits authorized beyond available metadata; no fabricated commit list.
- Memory consulted: reference/projects/mini-agent.md records small core, pinned Spring AI, full-history separation and fake-model limitations. Current request approves these four new improvements only.
- JDT LS is installed and configured in ~/.omo/lsp-client.json; use actual LSP tools.

## Learnings
- Skills used: mass-ulw (ordered per-stage DAGs), memory-discipline (durable facts), debugging (reproduce failures before fixes), bun-1-4 (eval/async process execution).
- Skill survey: git-master is not executed because Git metadata is absent; programming/refactor/frontend are not applicable to these Java behavioral fixes; visual-qa/browser are needed only if a full-screen TUI is changed (planned proof is REPL/data-shaped).
- Delegation topology: one producer and one dependent verifier per stage, new workflow run for each stage, serialized stage order respects requested priority and shared filesystem tests. Producers use unspecified-low/deep-low only for non-mechanical recovery/resource/session contracts; verifiers use quick. Lead owns cross-stage contracts, QA driver, real-surface proofs, LSP and final suite. No team needed: no concurrent overlapping writes.
- No external model calls; recording local ChatModel proves budget mechanics, not live-provider reliability.

## Bootstrap evidence
- Baseline source text for 13 in-scope files retained in eval `ulwBaseline`; no Git metadata.
- QA helper `.omo/qa/ImprovementQa.source` is a Java source-launcher input compiled with explicit production classpath (`java --source 21`). It is outside Maven modules and is not shipped/test code. JDT LS initially reported unresolved imports for scratch `.java` outside any Maven classpath; runtime compilation uses the exact classpath, not disabled compiler checks.
- Runtime artifacts registered for cleanup: `.omo/qa/runtime/lib` (extracted packaged dependencies), QA helper and script; Java temporary fixtures clean in finally. Evidence logs and this notepad are retained.
- RED C1 `bash .omo/qa/run-qa.sh undo`, exit 1: first file restores; second parent failure is caught; retry returns no pending restore because checkpoint was discarded. Exact failing predicate: `AssertionError: retry restores only the pending file`. Evidence `.omo/evidence/improvements/undo-before.log`.
- Cleanup RED C1: deleted fixture `/var/folders/dj/g3hmbb2j11gdpwyh6bjxstvc0000gn/T/mini-agent-improvement-qa-13360062653657986407`, verified absent.
- User approval saved to memory `reference/projects/mini-agent.md` (local memory commit 3c880c9; not a project commit).

## Contract decisions
- Planning child st_01a10ee7 completed read-only. Its contracts were audited against the registered goal.
- Undo: peek newest checkpoint; consume only each successfully restored/deleted change; retain failed/unattempted changes and backups; retry must not overwrite completed restores. Preserve ordinary return semantics and session/turn grouping.
- Atomic creation: use sibling temp+ATOMIC_MOVE for both creation and overwrite; new POSIX files private 0600; existing permissions unchanged; unsupported atomic move fails explicitly. Failure cleanup must not mask the primary exception.
- Bash: default timeout 30s; new max-timeout 300s; caller timeout must be positive and <= maximum. Keep max-output-chars 20000 for model-visible prefix. LEAD OVERRIDE of planning suggestion: add max-output-bytes=1MiB and terminate on output overflow, because C3 explicitly requires a captured-byte budget and process cleanup. Drain via bounded buffers, no unbounded spool. Graceful semantics must cover pipe EOF held open by descendants and interruption.
- Summary: new max-summary-request-chars=32000 counts instructions+headers+payload; max-summary-chars=8000; validate positive/usable settings. Budget accepted summary before persistence and bound legacy summaries.
- Final context: max-messages100/max-context-chars200000 include summary; select whole USER groups newest suffix, never split tool pairs. Oversized stored turn omitted whole; mandatory active/system context oversized is explicitly refused before provider invocation. A plugin-memory-owned final-round advisor may enforce total prompt bounds; do not create another tool loop or modify core assembly. Character approximation excludes tool schemas/wire bytes and is not tokenizer-exact.
- Stale test policy `latestTurnIsSentWholeEvenWhenItIsOverBudget` must become hard-budget omission/refusal coverage; ordinary history, cancellation and pairing tests retained.
- Routing: stage1 unspecified-low (coherent mutable checkpoint plus partial-failure test); stage2 unspecified-low (atomic write lifecycle and deterministic failure checks); stage3 deep-low (pipe/deadline/descendant interactions); stage4 unspecified-high (summary, memory selection, advisor and app integration, one tightly coupled plugin contract). Each dependent quick verifier executes real Maven tests. No parallel producers: user requested strict order and fs stages share test scope.

## Now
Contract resolved. Start stage 1 implementation -> verifier DAG; lead owns subsequent C1.

## Todo
1. Stage 1 producer/verifier and C1.
2. Stage 2 producer/verifier and C2.
3. Stage 3 producer/verifier and C3.
4. Stage 4 producer/verifier and C4.
5. Final suite/LSP/C5 and teardown of QA driver/runtime classpath.

## QA preparation findings
- Stage1 DAG started: dag_1a41a48b-510a-421e-a3d6-2329aa2ba91f (undo -> verify). SDK builder uses `.definition()`/`.start()`, not `.build()`; initial API error launched no workflow.
- Initial atomic driver failed compilation before fixture creation: FileTools has only `(List<Path>, long, nullable FileCheckpoints)`, not two arguments. Corrected driver to pass null checkpoints; no application source change and no runtime fixture to clean.
- RED C2 `bash .omo/qa/run-qa.sh atomic`, exit1 after valid compilation: Unicode/CRLF bytes correct, new POSIX file not private 0600. This fails a newly chosen explicit permission contract, not a pre-existing privacy claim. Evidence `.omo/evidence/improvements/atomic-before.log`.
- Cleanup RED C2: fixture `/var/folders/dj/g3hmbb2j11gdpwyh6bjxstvc0000gn/T/mini-agent-improvement-qa-13447455483716373738` removed and verified absent.

## Stage 1 lead audit
- DAG dag_1a41a48b-510a-421e-a3d6-2329aa2ba91f settled with both nodes completed. Reconstructed owned scope: FileCheckpoints.java and plugin-fs tests only, pending state/cleanup semantics, deterministic regressions, diagnostics and Maven tests.
- Lead read actual FileCheckpoints.java and complete added FileToolsTest cases. No FileCheckpointsTest was created (optional path absent, not missing deliverable).
- Actual production code now uses peekLast, iterator consumption immediately after successful destination commit, pending retention and pop only when empty. Cleanup failures cannot requeue completed destinations.
- Added deterministic tests include second-parent failure, later pending change, preserving human edits/older checkpoints/session isolation, and backup cleanup failure on both final/pending change cases. No existing test weakened.
- Lead LSP diagnostics on both changed Java files: no errors.
- Lead actual run started: `cd mini-agent && ./mvnw -pl plugins/plugin-fs -am test` captured in stage1-lead-tests.log, followed by `bash .omo/qa/run-qa.sh undo`. Pending; not yet PASS.

## Now
Await lead stage1 tests/C1 production-path results, then stage2 new DAG.

## Stage 1 PASS
- Lead Maven plugin-fs reactor command exit0, 14 tests, 0 failures/errors/skips. Evidence stage1-lead-tests.log.
- C1 exact invocation `bash .omo/qa/run-qa.sh undo` exit0. Evidence undo.log: partial failure first restores correctly; retry only one pending file; human edit survives; pending original restored; checkpoint consumed and all backups cleaned.
- Cleanup C1: fixture `/var/folders/dj/g3hmbb2j11gdpwyh6bjxstvc0000gn/T/mini-agent-improvement-qa-4480817062299029565` removed and verified absent.
- Stage1 implementation and real QA todos completed; no commits (Git unavailable).

## Now
Stage2 FileTools atomic publication producer -> verifier DAG.

## Todo
1. Stage2 producer/verifier and C2.
2. Stage3 producer/verifier and C3.
3. Stage4 producer/verifier and C4.
4. Final suite/LSP/C5 and complete teardown/self-review.

## Stage 2 / 3 preparation
- Stage2 producer->verifier run started: dag_ad39d092-bdd5-468a-b7b9-fbf777d821ac; scope FileTools + FileToolsTest only, prior undo changes protected.
- C5 exact properties confirmed from source: TuiAutoConfiguration uses `agent.ui=repl`; PermissionPluginAutoConfiguration uses `agent.plugins.permission.allow-non-interactive`. No extra UI flag needed. Initial filename lookup used nonexistent TuiPluginAutoConfiguration; literal configuration search located actual TuiAutoConfiguration.
- C3 helper uses actual BashPluginAutoConfiguration in AnnotationConfigApplicationContext with Boot conversion service, not mocks. Properties timeout1s/max-timeout2s/max-output-chars64/max-output-bytes128/sandboxoff.
- RED C3 `bash .omo/qa/run-qa.sh bash` exit1: timeoutSeconds0 was not rejected before launch. Evidence bash-before.log. Fixture `/var/folders/dj/g3hmbb2j11gdpwyh6bjxstvc0000gn/T/mini-agent-improvement-qa-2804138545748095885` removed, no live fixture process.
- QA helper now has finally-based cleanup of flood fixture child/shell PIDs in addition to its own fixture directory, so failed future assertions cannot leak a QA process.

## Stage 2 lead audit and PASS
- Reconstructed scope: FileTools/FileToolsTest only, atomic sibling temp creation+replacement, private new permissions/existing permissions, primary-error-preserving cleanup and regressions.
- Lead read actual write implementation and added regressions. Production change is one write lifecycle and imports/comments; no new hooks or generic layer. All earlier undo tests preserved.
- Lead LSP errors: none on both changed files. Lead Maven run exit0: 20 tests, 0 failed/errors/skipped; evidence stage2-lead-tests.log.
- First C2 lead run reached directory guard, not move: `IllegalArgumentException: Is a directory`. This was a QA injection mismatch, not product failure. Fixture removed. Fixed helper to separately assert public boundary rejection and invoke actual private atomic writer reflectively against nonempty directory to produce a real filesystem move failure (same production writer used by tool).
- C2 `bash .omo/qa/run-qa.sh atomic` exit0: UTF8/CRLF bytes, new0600, existing permissions/CRLF, directory guard, actual failed atomic publication with unchanged destination, no sibling temp. Evidence atomic.log.
- Cleanup C2: fixture `/var/folders/dj/g3hmbb2j11gdpwyh6bjxstvc0000gn/T/mini-agent-improvement-qa-13899905353501431316` removed and verified absent.
- C3 flood fixture uses FIFO readiness: child cannot emit before shell/child PID files are written. No sleep or timing-luck launch.

## Now
Stage3 bash timeout/output cap producer -> verifier DAG.

## Todo
1. Stage3 implementation/verification and C3.
2. Stage4 implementation/verification and C4.
3. Final suite/LSP/C5, cleanup and self-review.

## Stage 3 / 4 preparation
- Stage3 run started: dag_458cfbe2-121c-430b-9a2f-8efa1837f09d (bash -> verify). An initial JS template quoting error launched no workflow; corrected before start.
- C4 helper uses actual MemoryPluginAutoConfiguration/ChatMemory/JSONL repository and recording local ChatModel returning oversized5000-char summary, with complete valid historical tool call/result group and latest small group.
- QA API fixes: use ToolResponseMessage.builder (constructor is protected); register actual AgentProperties with fixture cwd/home (memory config requires it). First failure was compilation (no resources); second isolated context failure cleaned fixture `/var/folders/dj/g3hmbb2j11gdpwyh6bjxstvc0000gn/T/mini-agent-improvement-qa-14360066093535019724`. These are harness setup corrections, not production defects.
- RED C4 `bash .omo/qa/run-qa.sh summary` exit1: actual summary model called once but entire Prompt exceeded max-summary-request-chars2000; current persistence also stored5000 chars despite configured128 (observed notice, later assertion not reached). Evidence summary-before.log.
- Cleanup RED C4: fixture `/var/folders/dj/g3hmbb2j11gdpwyh6bjxstvc0000gn/T/mini-agent-improvement-qa-11748832941566773777` removed and verified absent.

## Stage 3 lead audit
- DAG settled with producer and verifier completed; reconstructed scope BashTool/BashPluginAutoConfiguration/BashToolTest plus bash YAML/README sections only.
- Lead read complete production BashTool in bounded chunks: callback validates positive whole-second durations and override max; bounded Capture uses fixed8192-byte read buffer + finite prefix; overflow raw-byte counter terminates. EOF/process completion share deadline. Cleanup snapshots descendants deepest-first, kills/reaps before shell, closes streams and joins reader within5s; interruption restored and cleanup failure explicit. No spool.
- Lead read regression cases: no-launch invalid overrides, config limits, exact max, UTF8/stderr byte boundaries, surrogate-safe prefix, flood beyond pipe, pipe-not-file proof, child overflow/timeout/interruption, inherited-pipe upper bound. Readiness FIFO/onExit used; sleeps only in explicit timeout/process-lifetime fixtures. No prior test weakened or skipped on host.
- Lead checked actual YAML/README edits against baseline; only bash limits/behavior docs changed. Defaults300s/1048576 bytes and positive validation; character prefix20000 retained. Detached/reparented-process limitation documented.
- LSP no errors on all3 changed Java files. Lead own Maven plugin-bash run and C3 Spring callback QA started; results pending.

## Now
Await lead stage3 Maven/C3 result before stage4 dispatch.

## Stage 3 PASS
- Lead own Maven plugin-bash reactor exit0: 14 tests, no failures/errors/skips. Evidence stage3-lead-tests.log.
- First lead C3 attempt exposed QA fixture portability bug: macOS bash3.2 has no BASHPID, so bash.pid was empty and QA finally parsing masked the original exception. This did not establish a production failure. Corrected fixture to portable $$, print fixture path, expose primary failure, and assert production cleanup status.
- First cleanup attempt used Bun.$ builtin rm with --; failed `rm: illegal option -- -`, actual existence check caught failure (never recorded as cleaned). Switched to explicit /bin/rm; exit0, verified old fixture `.../mini-agent-improvement-qa-18205952684337112900` absent and child67291 dead.
- C3 exact `bash .omo/qa/run-qa.sh bash` exit0: timeout0/-1/3 rejected without launch; exactmax2s normal command works; merged stdout/stderr; flooding prefix bounded; byte overflow explicitly reported; no cleanup failure; shell72149/child72154 dead and reaped; no spool.
- C3 evidence bash.log. Cleanup fixture `.../mini-agent-improvement-qa-4469508407915558069` removed and verified; both PIDs confirmed dead in main and finally. No runtime resources remain from C3.

## Now
Stage4 summary/final-context budget producer -> verifier DAG.

## Todo
1. Stage4 implementation/verification and C4.
2. Final suite/LSP/C5.
3. QA teardown and self-review.

## Final CLI evidence preparation
- Stage4 run started: dag_c6905d30-7f7b-4c93-b233-de274a8e0cdd (memory -> verify). Producer owns plugin-memory plus narrowly scoped app budget tests and memory config/docs; no core changes permitted.
- Final C5 will use real PTY/REPL recording via macOS `script -q <cli.raw> java ...` in isolated cwd/home, then replay that real ANSI stream in xterm.js inside owned Chrome and capture screenshot/transcript. The packaged script/qa/web-terminal-visual-qa.mjs is not installed here, so equivalent xterm evidence is required.
- Skills additionally used: browser (owned Chrome with throwaway profile, omowright connectPipe; OMO_BROWSER_ENGINE unset), visual-qa (actual terminal rendering/screenshot). This supersedes initial assumption that REPL evidence needed no visual skill. User main browser/profile untouched.
- Chrome binary and skill omowright loader exist. No browser/server/profile spawned yet; teardown will be registered with actual run.

## Continuation progress
- Live cursor reread: first3 improvements and real QA complete; stage4 producer st_01a10efe still running, verifier pending. One midpoint snapshot confirmed active resumption, not a completion claim.
- Loaded omowright owned-engine API; native screenshot accepts a path and saves PNG as runtime evidence. No user/main-browser context is involved.
- Prepared `.omo/qa/terminal.html` using real xterm.js5.5.0 and recorded ANSI replay; pinned JS/CSS downloaded to eval memory from jsdelivr with successful HTTP responses. No package installed, no browser/server/profile started yet.
- Added explicit teardown todo for eventual Chrome/server/profile. Final QA helper/html/runtime files will be removed; evidence PNG/raw/transcripts/notepad retained.
- Pending C4 requires augmenting actual memory helper after producer API/guard artifact is known (final provider-request refusal, legacy summary and malformed metadata), then run ourselves.

## Stage 4 verification FAIL and repair
- Stage4 generation1 settled; verifier explicitly returned FAIL despite Maven exit0/122 tests. It lacked successful >=3 model rounds in both memory placements, character-only history eviction, exact live-tool boundary coverage; JsonlChatMemoryTest had unused MessageType import warning.
- Lead read actual MemoryBudgetTest refusal scenarios and confirmed gaps, rather than treating child completion as PASS.
- Sending correction to settled memory node returned node_not_continuable. Recovery: amend same stage4 definition with precise missing criteria for producer/verifier; generation2 running, both invalidated and re-executed. No extra implementation phase or abandoned criteria.
- Lead read actual production MemoryBudget (accounting/ID validation), MemoryBudgetAdvisor (capture/preflight/final hooks), ChatModelSummarizer (incremental completePrompt limits), JsonlChatMemory (whole-group selection, coverage validation, legacy cap/cancellation), MemoryPluginAutoConfiguration (ordering/binding) and actual test additions. Three guard hooks are plugin-owned and necessary to refuse intrinsic overflow before compaction and preserve live tool rounds when memory owns history; core/tool loop unchanged.
- Updated C4 auxiliary driver invokes actual ChatModelSummarizer with huge previous summary, loads huge legacy summary, handles misaligned covers and invalid tool-ID historical group, and uses actual ChatClient+FunctionToolCallback for successful three provider dispatches in both memory placements plus zero-call active overflow. Model is local deterministic recording implementation with ToolCallingChatOptions; no JUnit or external calls.
- C4 runtime execution waits until amended children reach terminal status; no unproven completion.

## Final stage4 certification bookkeeping
- Explicit-context rerun exit0:24 tests, ignored default nested-configuration warning absent. Annotation/import/assertion behavior preserved; no warning suppression.
- Generation3 verifier again returned an intermediate "3/5 running" report despite a completed DAG. This was NOT approval. Current verifier log contains BUILD SUCCESS/128 tests, but final verdict/exit acknowledgement still required.
- Added a separate certification closure todo and revived the same underlying verifier task st_01a10f15 (epoch2) to read its monitor completion and return FINAL PASS/FAIL without gratuitous reruns. Root implementation/C4/warning tasks have independent completed evidence; final phase is held behind certification closure.
- Actual filesystem scope audit:80 source Java files; no unowned Java file modified after stage4 start. Only the manifest-listed stage4 paths changed.
- C5 resource planning: FsPluginAutoConfiguration creates `mini-agent-checkpoints-*` in java.io.tmpdir. Packaged CLI will set java.io.tmpdir inside its owned fixture alongside isolated HOME/user.home/agent.home so cleanup can be verified without touching another session.

## Now
Await revived verifier final result; then final Maven verify + C1-C4 rerun and packaged CLI/xterm C5.

## Final verification
- Revived verifier FINAL PASS with monitor exit0/128 tests. Root accepted only after all actual owned artifacts/commands had independently been checked.
- Root final exact Java21 ./mvnw verify exit0, all128 tests pass/no skips. final-verify.log.
- Root exact run-qa.sh undo, atomic, bash, summary all PASS and chain exit0. Logs overwritten with current final receipts; bash PIDs49504/49508 confirmed dead; all4 fixture directories removed.
- Final15 changed Java diagnostics:13 immediately clean; FileToolsTest/MemoryBudgetTest transient fresh-diagnostic timeout, then sequential retries both clean. No errors/warnings suppressed.
- Actual packaged CLI bash_24 via BSD script PTY. Owned cwd/home/tmp fixture mini-agent-cli-qa.cjaB6kEuGz; JVM user.home/java.io.tmpdir and agent.home isolated, HOME passed. Invocation uses requested --agent.ui=repl --agent.plugins.memory.enabled=false --agent.plugins.permission.allow-non-interactive=true. Wrapper records QA_JAVA_EXIT. cli.raw persists full stream.
- Sent requested write_file command plus y approval (interactive gate preserved). Native creation watch armed before trigger. Undo/bash/exit and resource cleanup pending.

## C5 final evidence and cleanup
- Native creation event observed; read exact hello, mode384 decimal/0600 octal. /undo submitted after observing creation; deletion monitor confirmed absence.
- Sent exact bash printf qa-ok, interactive y approval, /exit. Actual raw response `"qa-ok\n(exit code 0)"`, QA_JAVA_EXIT=0, PTY script exit0. No provider credentials/network generation.
- cli.raw full real ANSI stream; cli.log records filesystem and cleanup receipts; cli.png replays that raw stream through xterm. Lead viewed screenshot: all commands, creation/deletion, actual output and exit marker visible with readable Korean and no overlap/clipping.
- Java tmp directory empty after exit: checkpoint resources closed by app.
- Browser close awaited; HTTP server stop awaited and fetch connection refused; owned Chrome profile and complete CLI cwd/home/tmp fixture removed, absence verified.
- Temporary runtime/lib extraction deleted. Live .omo/qa is empty. QA driver/script archived under evidence/improvements for reproducibility; archived script bash -n passed. The move generated a stale old-path LSP ENOENT notice, not a source diagnostic.
- All4 DAG runs and their8 nodes terminal completed; revived stage4 verifier also returned final PASS. All started monitors/PTY processes completed; no QA wait/process remains.

## Final self-review and completion audit
Goal: exactly four ordered recovery/atomic/process/memory improvements plus C1-C5, adjacent regressions, clean changed-Java diagnostics, and resource cleanup.
1. Retryable partial /undo: FileCheckpoints and FileToolsTest; undo.log actual failure/repair/retry proves pending-only recovery, first recovered file not overwritten, backups/checkpoint consumed only after success.
2. Atomic new publication: FileTools and FileToolsTest; atomic.log proves Unicode/CRLF, new0600 and existing mode retention, publication error without partial destination/sibling temp. Adjacent editFile/access-root cases green in final verify.
3. Bash budgets: BashTool/auto-config/tests + matching YAML/README; bash.log actual FunctionToolCallback rejects0/-1/overmax before launch, bounds flooding bytes, returns ordinary output and records shell/child49504/49508 reaped. Interrupt cleanup regression green.
4. Memory budgets: five plugin main classes, three unit tests, MemoryBudgetTest + memory YAML/README; summary.log proves bounded complete summary request and persisted output, bounded final context in both3-dispatch placements, complete ID pairs, empty/malformed/legacy cases and append-only original history. Character-only in-loop context-loss regression fixed; exact boundary/one-over tests green.
5. CLI: actual packaged Java21 PTY invocation and all4 requested commands passed, as above.
6. Full exact Java21 ./mvnw verify exited0:128 tests, zero failures/errors/skips. final-verify.log includes TuiCancellationTest/OneShotExitCodeTest; all15 changed Java files have zero LSP errors/warnings after retrying only two diagnostic timeouts.
7. All changes are owned plugin/config/doc/test paths; stage4 filesystem audit detected no unowned Java modification. Small core/dependencies/Java21/SpringAI2.0.1 and prior user changes preserved. No skill/streaming/cancellation-race work, Git initialization or project commit.
8. Self-review done, no ulw-plan reviewer gate. All requested artifacts retained; runtime resources deleted. No uncovered acceptance criterion.
Residual notes: expected invalid-config startup rejection WARNs and pre-existing Mockito/Byte Buddy/CDS warnings remain visible; no suppression. Character accounting is UTF16 text/tool-data approximation, not provider tokens/schema/wire allocation. External providers intentionally uncalled.

## Now
Await stage4 generation2; audit delta and run lead tests/C4.

## Stage 4 lead proofs
- Generation2 added a real fix: retain/rebudget previously dispatched bounded optional context when in-loop memory temporarily supplies no raw history. The new character-only eviction test exercises that loss path; successful3-dispatch and exact-boundary tests now exist in both placements. Actual delta read by lead.
- Lead memory Maven command exit0:21 tests. Lead selected app command exit0:26 tests. Evidence stage4-lead-memory-tests.log/stage4-lead-app-tests.log.
- Lead LSP severity all: zero errors and warnings on all9 changed Java files.
- C4 exact `bash .omo/qa/run-qa.sh summary` exit0. Evidence summary.log records all request/output/context predicates, huge prior/legacy summaries, full-history retention, malformed metadata/IDs, empty/small conversations, both real tool-loop placements3 dispatches/pair completeness and zero-call active overflow.
- Cleanup C4 fixture `.../mini-agent-improvement-qa-8475436161362859755` removed and verified absent; Spring contexts closed, no external provider/resource.
- Generation2 verifier returned FAIL for missing producer manifest/output, all runtime WARN lines and a newly invented fourth-dispatch requirement. Root supplied reconstructed owned-file/scope manifest and direct lead evidence in stage4-lead-audit.md; the registered scenario requires3 provider dispatches, not3 tool cycles.
- Warnings classified: invalid-config startup rejection WARN is intended evidence; Byte Buddy/CDS warnings pre-existing. Newly created Spring default nested-test-config warning is in scope and was corrected with explicit MiniAgentApplication classes in all4 nested tests, imports/assertions preserved. Selected24-case app rerun is pending.

## Now
Confirm explicit-context rerun, recertify stage4 verifier with actual manifest/evidence, then final verification/CLI.
