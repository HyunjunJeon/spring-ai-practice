# Task 6 run store contract

Package: `com.example.miniagent.core.run`. No model execution, Spring wiring,
executor, approvals broker or delegation scheduler is introduced.

```java
record RunId(String value) { static RunId random(); }
record RunBudget(Duration wall, Long tokenCap, int toolCallCap) {
    static RunBudget defaults(); // active 30 minutes, null token cap, 50 tools
}
record RunSpec(int schemaVersion, RunId runId, String conversationId,
    String parentConversationId, RunId parentRunId, String childId,
    ModelRef model, String wireFamily, String cwd, RunBudget budget,
    String optionsFingerprint, String toolsFingerprint, Instant createdAt)
record Attempt(String attemptId, UUID bindingId, Instant startedAt, Instant endedAt)
record RunUsage(long knownTokens, boolean tokensUnknown, long toolCalls,
    long activeMillis, long overshootTokens) { static RunUsage empty(); }
record RunRecord(RunSpec spec, long sequence, RunState state,
    RunEvent.Reason reason, List<Attempt> attempts, RunUsage usage,
    List<RunEvent.ToolStarted> uncertainTools)
interface RunWriter extends AutoCloseable {
    RunRecord record();
    RunRecord append(RunEvent event) throws RunStoreException;
    void close() throws RunStoreException;
}
interface RunStore {
    RunWriter create(RunSpec spec) throws RunStoreException;
    RunWriter openWriter(RunId id) throws RunStoreException;
    RunRecord read(RunId id) throws RunStoreException;
    List<RunId> list() throws RunStoreException;
    OpaqueRun export(RunId id) throws RunStoreException;
    record OpaqueRun(RunId runId, String specJson, String eventsLog) {}
}
final class FileRunStore implements RunStore {
    FileRunStore(Path agentHome) throws RunStoreException;
}
```

All ID strings use ASCII alphanumeric first, then alphanumeric/underscore/hyphen,
maximum 128 characters. Parent run/child fields must be both present or both absent;
parent and child run IDs cannot equal. Conversation IDs remain distinct from run IDs.
Fingerprints are 64 lowercase hex characters. `cwd` is an absolute normalized path.
No option values, credentials, clients, headers or environment are stored.

`RunEvent` is sealed with explicit record payloads:
`StateChanged(Instant at, RunState state, Reason reason)`,
`AttemptStarted(Instant at, String attemptId, UUID bindingId)`,
`UsageRecorded(Instant at, String attemptId, UsageKind kind, String callId, RunUsage usage)`,
`AttemptEnded(Instant at, String attemptId)`,
`ApprovalRequested(Instant at, String attemptId, String requestId, String toolCallKey,
String argsDigest, String diffDigest)`,
`ApprovalResolved(Instant at, String attemptId, String requestId, Decision decision)`,
`ToolStarted(Instant at, String attemptId, String toolCallKey, String toolName,
String argsDigest, Effect effect)`,
`ToolFinished(Instant at, String attemptId, String toolCallKey, String resultDigest)`.
Reasons: NONE, PROCESS_LOST, USER_CANCELLED, BUDGET_EXHAUSTED, ERROR.
Usage kinds: MODEL_ROUND, COMPACTION, TOOLS, ACTIVE_TIME.
Effects: READ_ONLY, MUTATING. Decisions: APPROVED, REJECTED, DEFERRED.
Only digests, never raw tool args/results, enter this journal. Unknown tokens remain
unknown through aggregation. Unique kind/call IDs prevent duplicate usage recording.

Initial projection is CREATED at sequence 0. State changes obey the common plan.
An attempt starts only in RUNNING; usage precedes attempt end, and every started
attempt needs a usage observation (including explicit unknown) before ending.
Terminal state requires no open attempt. No event follows terminal. Timestamps and
sequence cannot decrease. Unfinished tools stay uncertain, not successful.
COMPLETED requires no uncertain tools. Before resuming an INTERRUPTED run, close
its old attempt. DEFERRED approval events preserve the pending request; an
APPROVED/REJECTED event removes it, and RUNNING cannot resume with pending requests.

Storage is `<agentHome>/runs/<runId>/{spec.json,events.log,writer.lock}`. Spec is private,
forced then atomically renamed and directory-forced. Each newline frame is
`schemaVersion<TAB>sequence<TAB>CRC32C-hex<TAB>JSON` with CRC covering
`schemaVersion<TAB>sequence<TAB>JSON` UTF-8 bytes. JSON is `{type,payload}`.
An append acknowledgement follows file force; callers perform side effects only
after it returns. A dedicated single writer thread owns all channel operations;
caller interruption does not cancel an accepted append or close that channel.
Append waits to learn its durable result and restores the caller interrupt flag.
Explicit close drains prior appends and releases both JVM and process locks.
The JVM lock table prevents opening/closing a second local lock descriptor, because
on POSIX that can release another descriptor's process lock. Other-process ownership
is checked by `tryLock`, never a blocking acquisition or lock stealing.

Readers never truncate. They probe the writer lock; RUNNING/WAITING_APPROVAL with
no writer projects to INTERRUPTED/PROCESS_LOST without inventing an event. Acquiring
a writer persists that interruption and closes a lost attempt with explicit unknown
usage before new work. Only an unterminated final fragment can be truncated, under
the writer lock after validating every complete frame. Future schema rejects typed
read/write with FUTURE_SCHEMA but preserves bytes for `export` and IDs for `list`.
No unknown schema is deserialized as current. Complete CRC/sequence/JSON/transition
corruption is preserved and rejected with CORRUPT.

Typed checked `RunStoreException.Code`: IO, RUN_LOCKED, CORRUPT, FUTURE_SCHEMA,
INVALID_TRANSITION, TERMINAL, CLOSED, ALREADY_EXISTS, NOT_FOUND, UNSUPPORTED_FILESYSTEM.
Original I/O causes are preserved. POSIX permissions, atomic rename and directory
force are required; no fallback and no exactly-once effect guarantee is claimed.
The application owns agent home; other processes cooperate with writer locks.
Budget reservation/enforcement belongs to tasks 8/20 using this same RunBudget.

Verification is deliberately unrun until the parent coordinates its build window:
`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -pl agent-core -am -Dtest=FileRunStoreTest test`

## Implementation lane receipt

Authored exactly this contract, the thirteen named production Java files under
`mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/`, and
`mini-agent/agent-core/src/test/java/com/example/miniagent/core/run/FileRunStoreTest.java`.
Tests use real temporary files, a separate JVM lock probe, and latches/futures
without sleeps or polling. Java diagnostics were inspected for both directories.
Maven and tests remain unrun; the parent owns coordinated executable verification.
This is the task 6 implementation receipt, not overall feature completion.
