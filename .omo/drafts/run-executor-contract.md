# Task 7 executor integration contract

Package `com.example.miniagent.core.run`; synchronous, no Spring wiring.

```java
new RunExecutor(Agent agent, ModelRegistry registry, SessionModelStore selections,
    RunStore runs, RunRequestStore requests, Clock clock,
    List<RunResumeContributor> contributors);
RunExecutor.Result start(String goal, String parentConversationId,
    ModelSelection selection, Path cwd, RunBudget budget) throws IOException;
RunExecutor.Result resume(RunId id, ModelSelection expectedSelection,
    Path expectedCwd) throws IOException;
RunRecord cancel(RunId id) throws RunStoreException;
record Result(RunRecord record, Agent.AgentReply reply) {} // reply null when stopped
```

`Failure extends IOException` has `code()` and `uncertainTools()`; codes distinguish
terminal/not-resumable, identity drift, corrupt private request, uncertain effects,
and unavailable binding (cause retains ModelUnavailableException).
RunStoreException is propagated unchanged. There is no automatic resume.

RunRequestStore(Path agentHome) persists a versioned private
`run-requests/<conversationId>.json` with run/conversation identity, spec digest,
goal/input digest, and allowed ModelSelection only. `save(spec,goal,selection)` and
`load(spec)` use strict JSON trees, bounded reads, private POSIX permissions, force,
atomic rename and directory force. Missing/corrupt data is never defaulted.
SessionModelStore separately saves the same selection for the dedicated conversation.
Resume validates both stores against the spec and caller, real cwd and current tool
definition digest, and wire family before writer acquisition or model dispatch.
Binding is fresh, captured once, and unavailable binding leaves the log untouched.

`RunContext(spec,attemptId,writer,turnContext,clock)` exposes `current()`, `open()`,
`fromToolContext(ToolContext)` and `TOOL_CONTEXT_KEY = "agent.runContext"`.
Scopes are nested, thread-confined and must close in order. **Parent hook required:**
in Agent.run(input,TurnContext), capture the matching RunContext into the ephemeral
ToolContext/advisor map under this key. Async interceptors must retrieve this captured
object, explicitly open its scope on their execution thread, and carry the captured
TurnContext too; ThreadLocal does not propagate to callbacks.

Task 8 usage extension: call `context.recordUsage(kind,callId,delta)` for rounds,
compaction, tools and active time. A MODEL_ROUND observation replaces executor loop
aggregate accounting entirely, including on failure. Without round observations the
executor records one final loop aggregate, or unknown on interrupted/failed dispatch.
The executor adds ACTIVE_TIME only if nobody recorded it. No measured usage is
inferred from absent metadata. Recorder calls must finish before synchronous dispatch
returns. Budget enforcement and approval waiting-time exclusions belong to task 8.

`RunExecutor.Stop extends RuntimeException` with `Kind.INTERRUPTED`,
`BUDGET_EXHAUSTED`, `DEFERRED` is an explicit future interceptor/broker signal,
not an implementation of those features. Interrupted and budget stops end their
attempt then persist INTERRUPTED with distinct reasons. Deferred stops persist
WAITING_APPROVAL and retain the open attempt; resume refuses it until task 9
resolves approval and closes the old attempt. The executor retains that writer
lease (cancel releases it); there is no approval implementation or automatic retry.
Task 9 must supply lifecycle ownership for deferred approval resolution.
User cancellation durably records
INTERRUPTED/USER_CANCELLED before interrupting the dispatch thread, then cleanup
records usage, AttemptEnded, and CANCELLED, preserving the interrupt flag.

RunResumeContributor contributes verified **text only** via
`verifiedEvidence(RunRecord,String originalGoal) throws IOException`; it cannot
inject raw assistant/tool messages or resolve UNKNOWN effects. Default resume
describes an interrupted goal with no claims of completed effects. Any uncertain
ToolStarted (including read-only for now) is a structured refusal: task 6 cannot
finish an old-attempt tool, and task 10 must add an explicit safe resolution event.
No digest is treated as exactly-once execution or completed-effect deduplication.

The parent released the Maven window after task 5. Targeted verification command:

```bash
cd mini-agent
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./mvnw -pl agent-app -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=RunExecutorTest test
```

Only the four new production files, RunExecutorTest and this contract are owned by
this unit. Parent owns wiring, tool capture, budgets, approvals, journal and commands.

The injected Clock must be monotonic across journal events; timestamp regressions
are rejected by RunStore, not silently corrected. Request persistence failures
before dispatch leave a CREATED run (cancellable, not implicitly resumable).
Checked RunStoreException causes from interceptors propagate as checked failures,
release the writer and leave process-lost recovery to the authoritative store.

A deferred stop retains the writer rather than closing it and falsely labeling the
run PROCESS_LOST. Task 9 must resolve or cancel that ownership explicitly; there is
no executor-managed approval queue. Direct cancellation calls and synchronous
dispatch cleanup serialize on the same per-run monitor so cancellation intent is
durable before the interrupt and terminal always follows usage/attempt end.

## Unit verification receipt

The targeted command above passed with exit 0: 30 tests, zero failures/errors/skips,
2.288 seconds of test execution. Maven monitor `mon_2BWEH6RJ9SJ9QMX6` / `bash_7`.
Evidence already generated by Maven:
`mini-agent/agent-app/target/surefire-reports/TEST-com.example.miniagent.app.RunExecutorTest.xml`
and `com.example.miniagent.app.RunExecutorTest.txt` in that same directory.
The initial run had a numeric boxed-type assertion failure (41L versus 41);
the corrected run includes checked-store-error and absent-versus-measured-zero cases.
All four new production files and the new test have zero LSP diagnostics.
The first run reported pre-existing deprecated APIs in AgentAutoConfiguration and
AgentTurnBindingTest; this unit's callback registration now uses defaultOptions.

Tests execute actual Agent, ModelRegistry factories, ToolCallingAdvisor and private
temporary files. They cover dedicated identities and mutable parent selection,
fresh explicit resume, unavailable and terminal refusal, UNKNOWN mutating effects,
strict request corruption and drift, usage ordering/no duplicate round aggregation,
explicit cancellation intent before interruption, preserved interrupt flag,
deferred writer ownership/cancel, budget interruption, explicit cross-thread context
capture, filesystem path refusal and checked store failure recovery.

No Maven process remains active. Deferred test ownership is cancelled and released;
cancel test threads finish via bounded completion signals. There are no commits,
remote calls or new dependencies. Parent still owns source audit, merged verification,
phase2 evidence archival and Agent/Spring integration; this is a verified code-unit
receipt, not a claim that the overall managed-run feature is complete.
