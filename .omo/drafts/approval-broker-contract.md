# Task 9 shared approval contract

All approval types live in `com.example.miniagent.core.approval`.

```java
enum ApprovalDecision { APPROVED, REJECTED, DEFERRED, CANCELLED }
record ApprovalRequest(UUID requestId, RunId runId, String attemptId,
    String planId, String childId, String toolCallKey,
    String argsDigest, String diffDigest)
interface ApprovalBroker {
    CompletableFuture<ApprovalDecision> request(ApprovalRequest request, RunContext context);
    boolean respond(ApprovalRequest request, ApprovalDecision decision);
    boolean resolveDeferred(ApprovalRequest request, ApprovalDecision decision);
    void cancelRun(RunId runId);
    void abandonRun(RunId runId, Throwable failure);
    List<ApprovalRequest> pending();
    void restore(RunId runId, String authoritativeEvents);
}
new ConsoleApprovalBroker(AgentConsole console);
new RunExecutor(...existing seven arguments..., ApprovalBroker broker);
RunRecord RunExecutor.resolveApproval(ApprovalRequest request, ApprovalDecision decision);
```

The seven-argument executor constructor remains compatible; managed approval users
must supply the same Spring-registered broker through the eight-argument constructor.
Task 11 owns executable run commands and their wiring.

Request UUIDs never recur. Responses must match the entire request, including
run/attempt, tool key, optional plan/child, args and diff SHA-256. Pending requests
are FIFO; one transport question at a time. The broker pauses active budget time
on admission and resumes when the waiting future settles. Terminal events are
never followed by approval appends.

`DEFERRED` resolves the waiting future but retains request ownership, removes the
question from the screen and leaves the executor writer/attempt in WAITING_APPROVAL.
Only `RunExecutor.resolveApproval` may resolve deferred requests. It records the
decision in the old attempt, ends that attempt, transitions to INTERRUPTED and
releases the writer. It executes nothing. CANCELLED cancels the run instead.
CLI resume is a separate explicit action, with a fresh attempt/binding.

Only exact decisions from an explicitly resolved deferred request are replayable:
run ID, tool key, args and diff must match. Replay is consumed once. PROCESS_LOST
invalidates replay authority; stale screen IDs are never restored. Restoration
reads authoritative frames, never model claims. Ordinary approvals are not
effect deduplication and do not authorize future retries.
`abandonRun` invalidates screen/queue authority after a writer failure, without
inventing CANCELLED or appending to a lost writer; waiting futures fail.

Permission uses the tool name as approval tool key, distinct from task 10's
unique execution journal key. Budget runs at HIGHEST+10; permission at +20.
Role/path policy must deny before admission (task 19 scope interceptor), and no
allowlist or remembered approval bypasses that outer gate. Managed `/always`
is limited to the exact run, tool and argument/diff digest; legacy ordinary
turn behavior remains unchanged.

Console transport requires `<request UUID> y|n|a|defer|cancel`, including in TUI.
Bare answers and old UUIDs cannot settle a new question. EOF is DEFERRED.
TUI dismissal invalidates the callback and removes pending input. There is no
answer queue shared between generations. Plain console serializes all readers.
Parent added CANCELLED to parent-owned `RunEvent.Decision` for the authoritative
resolution payload; its existing projector already removes non-DEFERRED requests.
No STARTED/FINISHED is fabricated for permission refusal.

## Verified task 9 receipt

The specified Java 21 focused Maven command passed, exit 0, 80 tests with no
failures/errors/skips. Evidence: `.omo/evidence/agent-evolution/phase2/approval-test.log`.
Counts from actual Surefire XML: ApprovalBrokerTest 8; ConfirmToolInterceptorTest 4;
TuiCancellationTest 7; ConsoleApprovalRoutingTest 8; RunExecutorTest 30;
RunBudgetIntegrationTest 3; ModelCommandTest 18; ModelDisplayTest 2.
The first identical run also passed all 80 tests but its shell pipeline exited 1
because tee's evidence directory was missing. Creating that path and repeating
the command produced the clean recorded receipt; no source change was needed.

Manual CLI proof used Java 21 JShell with the compiled local fixture and actual
Spring application, Agent, permission interceptor, broker, FileRunStore and
RunExecutor, not a paid provider or a mocked permission layer. Its observed values:

```text
CLI_DEFERRED=WAITING_APPROVAL file=unchanged
CLI_RESOLVED=INTERRUPTED file=unchanged
CLI_RESUMED=COMPLETED file=changed
CLI_CANCELLED=CANCELLED file=changed pending=0
CLI_CLEANED=true
```

JShell exited 0. The Spring context closed and the temporary fixture tree was
deleted. Tests close writer leases and terminate their workers with bounded
completion signals. No Maven or CLI session remains running.

New approval production/test files and changed console, permission and executor
files have zero LSP diagnostics. Existing AgentAutoConfiguration deprecation
warnings and TuiFrontEnd's unused runner-field warning remain; they are not
suppressed. No commits, new dependencies, auth-store/.env edits or remote model
calls were made. `agent.sh` was not exercised: the CLI proof used compiled Java
directly to avoid its real `.env` import and automatic packaging build.

F4 visual PTY captures at widths/themes remain the parent's work; deterministic
TUI event tests cover correlation, stale drafts, late IDs and existing busy/cancel
behavior but are not screenshots. Plain stdin readers are serialized; invalidated
answers cannot settle a later approval. An externally cancelled physical plain
stdin read finishes on the next input/EOF, unlike the immediately dismissible TUI
callback. Broker futures and run writers release independently of that physical
read. Future role/path policy must remain an outer gate before broker admission;
task 9 does not implement task 19's role scheduler/policy or task 10's journal.

Decision: require explicit UUID-bearing answers, rather than assigning unlabelled
input to whichever prompt happens to be current. Replay only consumes exact
durably resolved deferred decisions on explicit resume, never automatic execution
or a claim that a side effect completed. The eight-argument executor must use the
same broker as permission. Plan-bearing future change approvals are not restored
as permission grants from task 6 frames, which do not persist plan IDs.

### Exact files authored or modified by this unit

Paths below are relative to the workspace root. Parent's RunEvent enum edit is
not an authored file in this list.

- `.omo/drafts/approval-broker-contract.md`
- `.omo/evidence/agent-evolution/phase2/approval-test.log`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/approval/ApprovalBroker.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/approval/ApprovalRequest.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/approval/ApprovalDecision.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/approval/ApprovalAnswer.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/approval/ConsoleApprovalBroker.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/AgentConsole.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/AgentAutoConfiguration.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/RunExecutor.java`
- `mini-agent/plugins/plugin-permission/src/main/java/com/example/miniagent/plugin/permission/ConfirmToolInterceptor.java`
- `mini-agent/plugins/plugin-permission/src/main/java/com/example/miniagent/plugin/permission/PermissionPluginAutoConfiguration.java`
- `mini-agent/plugins/plugin-tui/src/main/java/com/example/miniagent/plugin/tui/TuiConsole.java`
- `mini-agent/plugins/plugin-tui/src/main/java/com/example/miniagent/plugin/tui/TuiFrontEnd.java`
- `mini-agent/agent-core/src/test/java/com/example/miniagent/core/approval/ApprovalBrokerTest.java`
- `mini-agent/agent-app/src/test/java/com/example/miniagent/app/ConsoleApprovalRoutingTest.java`
- `mini-agent/plugins/plugin-tui/src/test/java/com/example/miniagent/plugin/tui/TuiCancellationTest.java`
