# Task 10 journal API

All types are in `com.example.miniagent.core.run`.

`RunExecutor` retains its seven/eight argument constructors. The new nine argument
constructor adds `DriftGate` after the shared `ApprovalBroker`. Spring callers
should pass the registered gate, so trusted built-in declarations and configuration
participate in snapshots. Every constructor installs journaling on the executor
side; actual tool interception is registered by `RunJournalAutoConfiguration`.
The gate and `RunRequestStore` must use the same private home. Seven/eight argument
callers use conservative classification (all tools mutating); automatic safe
read retry needs the nine argument constructor with trusted policies. A custom
Agent assembled without the application's interceptor chain must wrap its
callbacks with `ToolJournalInterceptor` itself.

```java
record RunExecutor.ResumeOptions(
    List<RunExecutor.ToolResolutionInput> resolutions,
    DriftGate.Acknowledgement drift) {
    static ResumeOptions ordinary();
}
record RunExecutor.ToolResolutionInput(String oldAttemptId, String toolCallKey,
    RunEvent.Resolution decision, String operatorId, String evidenceId) {}
Result resume(RunId id, ModelSelection selection, Path cwd, ResumeOptions options);
DriftGate.Difference inspectDrift(RunId id);
record DriftGate.Difference(String expectedDigest, String actualDigest) {}
record DriftGate.Acknowledgement(String expectedDigest, String actualDigest,
    String operatorId, String evidenceId) {}
```

These are operator APIs, not model tools. Exact before/after snapshot digests are
required; there is no accept-all flag. Cwd/ref/options/wire incompatibility cannot
be acknowledged. `READ_RETRY`, `MUTATING_RETRY`, and `SKIP` acknowledge an old
UNKNOWN key, never append a synthetic ToolFinished. Automatic READ_RETRY requires
the attempt's authoritative trusted declaration, not its name or model metadata.
SKIP records that the effect remains unverified. Terminal and active runs refuse.

The attempt snapshot freezes definitions and effect policy. Unclassified tools
are mutating. Permission at HIGHEST+20 precedes journal at +30; future role/path
gates must precede budget at +10. Journal keys are UUIDs, not permission names.
STARTED append returns only after force. A private versioned content-addressed
result artifact is forced before FINISHED. Results contribute bounded text only
to fresh attempts; no raw incomplete call pairs are replayed. Matching completed
mutations are blocked from automatic repetition, without claiming exactly-once
execution. Exceptions and cancellation without verified results remain UNKNOWN.

Workspace fingerprints use real cwd, not Git/HEAD. Secret/auth/key/certificate,
generated, state, and Git paths are excluded before reading; escaping symlinks
are rejected. Configuration is represented by hashes, never environment dumps.
The agent owns private POSIX state; arbitrary tool output is not claimed secret
free. Phase 3 staging and phase 4 role isolation remain separate contracts.

The initial baseline digest is bound to the private request companion, while
ToolStarted references the forced attempt snapshot. Both contain hashes of
source/configuration and tool definitions, not source contents or raw arguments.
Legacy ToolStarted rows without snapshotDigest remain readable and uncertain;
they cannot acquire automatic READ_RETRY authority. Requests lacking an owner
baseline refuse resume and require a new run, rather than guessing a baseline.
Known unterminated tails remain recoverable; complete-row corruption and future
schemas remain preserved refusals.

Resume verifies each ToolFinished's content-addressed artifact and owner against
the log. Its text excerpt is at most 2048 characters per result with a truncation
marker; total journal summary is bounded at 32768 characters. SKIP is explicitly
unverified, and the journal blocks automatic repetition of its matching key's
tool/argument digest. Successful mutations are also blocked on matching
tool/argument digest, not treated as general exactly-once deduplication.

The runtime registry is keyed by the captured RunContext object, with immutable
attempt bindings and cleanup in executor finally. It supplies the corresponding
writer/evidence to async callbacks; it is not a current-run or model selection.
Future role/path interceptors must remain outside this chain, and staged/child
scope must intersect existing permission rather than replace it.

## Verified task 10 receipt

The requested Java 21 Maven command completed with exit 0 and BUILD SUCCESS.
Actual Surefire XML, read after completion, reports 94 tests, zero failures,
errors, skips, or flakes across all eight requested suites:
SafeResumeTest 9; ToolJournalTest 7; FileRunStoreTest 32;
SafeResumeIntegrationTest 3; RunExecutorTest 30; ConsoleApprovalRoutingTest 8;
RunBudgetIntegrationTest 3; AgentTurnBindingTest 2.
Log: `.omo/evidence/agent-evolution/phase2/journal-test.log`.

The new tests exercise real temporary filesystem effects, durable STARTED visibility
before action, hashed/private successful results, exception and cancellation UNKNOWN,
trusted READ_RETRY, explicit MUTATING_RETRY/SKIP, terminal refusal, exact source/tool/
configuration acknowledgement, hard cwd/wire refusal, schema/corruption preservation,
frozen lazy-tool schemas, captured async writer identity, bounded verified text,
secret exclusions before content reads, and completed-mutation repetition refusal.
Spring integration uses actual Agent, RunExecutor, shared broker, budget, permission,
and built-in file callbacks. Permission resolves before STARTED; rejected/deferred
permission and exhausted budget do not create STARTED.

A bounded child JVM test emits STARTED_FORCED after append acknowledgement, blocks
on controlled stdin, is killed by its parent, and reopens with PROCESS_LOST and
UNKNOWN preserved. This is a store crash fixture, not the parent's F3 full
application subprocess proof. Child termination and application test workers/
pending approval cleanup are asserted. All Maven monitor sessions have completed;
the Maven window is released with this receipt.

The initial new cancellation test inspected files with its deliberately preserved
interrupt flag and encountered ClosedByInterruptException. It now asserts the
preserved flag, clears it for inspection, and keeps all UNKNOWN assertions.
Production cancellation behavior was not weakened. The second run passed 91 tests;
the final run passed 94 after adding schema-tail, lazy-definition, and async-summary
coverage and addressing the complete-prefix evidence-reader boundary.

Owned production and test files have zero LSP diagnostics. Existing removal
warnings in AgentAutoConfiguration and AgentTurnBindingTest remain unchanged and
are visible in the build log. No existing test was edited. No paid model call,
commit, dependency addition, auth-store read, or secret-file modification occurred.

The decision is explicit acknowledgement of UNKNOWN, rather than pretending an
old attempt completed. Exact drift digests are required, rather than a blanket
accept-drift flag. Assumptions: Java contributions are trusted, the application
owns private POSIX state, and external writers cooperate. Fingerprints are
observations, not filesystem transactions; unrelated concurrent editor changes
during a tool can be indistinguishable from its changes. General semantic
deduplication, staging, role/path isolation, commands, and F3 full application
crash testing remain the parent's next scopes. Arbitrary tool results can contain
sensitive text; artifacts are private, not a claim of universal output sanitization.

Exact files authored or changed (workspace-relative):

- `.omo/drafts/tool-journal-contract.md`
- `.omo/evidence/agent-evolution/phase2/journal-test.log`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/ToolJournalInterceptor.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/SideEffectClass.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/DriftGate.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/WorkspaceFingerprint.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/ToolEvidenceStore.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/JournalResumeContributor.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/RunJournalAutoConfiguration.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/RunEvent.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/RunProjector.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/FileRunStore.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/RunExecutor.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/run/RunRequestStore.java`
- `mini-agent/agent-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `mini-agent/agent-core/src/test/java/com/example/miniagent/core/run/SafeResumeTest.java`
- `mini-agent/agent-core/src/test/java/com/example/miniagent/core/run/ToolJournalTest.java`
- `mini-agent/agent-app/src/test/java/com/example/miniagent/app/SafeResumeIntegrationTest.java`
