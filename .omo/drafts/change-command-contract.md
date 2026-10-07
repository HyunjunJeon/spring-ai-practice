# Task17 core command ABI

Package: `com.example.miniagent.core.change.command`.

Operator-only SlashCommand beans: `ChangesCommand`, `ApplyCommand`,
`VerifyCommand`, `UndoCommand`; registered by `ChangeCommandAutoConfiguration`.
No model/tool registration or synthetic RunContext.

Constructors use lazy suppliers and an explicit `ApplicationEventPublisher`:

- `ChangesCommand(Supplier<ChangeStore>, Supplier<RecoveryScanner>, ApplicationEventPublisher)`
- `ApplyCommand(Supplier<ChangeStore>, Supplier<ApplyEngine>, ApprovalBroker, Clock, ApplicationEventPublisher)`
- `VerifyCommand(Supplier<ChangeStore>, Supplier<VerificationRunner>, Duration, int, ApplicationEventPublisher)`
- `UndoCommand(Supplier<ChangeStore>, Supplier<ApplyEngine>, Supplier<UndoEngine>,
  Supplier<RecoveryScanner>, Supplier<LegacyUndo>, ApplicationEventPublisher)`

Exact legacy SPI (one bean contributed by FS, no FS SlashCommand):

```java
@FunctionalInterface
public interface UndoCommand.LegacyUndo {
    boolean undo(SlashCommand.CommandContext context);
}
```

No-argument `/undo` invokes this SPI without resolving any managed supplier.
Missing SPI returns false and an operator error. UUID `/undo` invokes managed
engines with separate console-issued ownership proof and recovery classification.

`/changes [UUID]` reports authoritative owner, digest, status, full diff endpoints,
checkpoints, evidence (actual cwd separate from sourceCwd), and read-only recovery.
`/apply UUID` displays the full proposal before requesting shared broker approval;
the callback durably decides exact snapshot owner/id/digest before future completes.
Exclusive proof is separately issued by ApplyEngine after approval.
`/verify UUID <JSON argv array>` preserves string boundaries without shell parsing.
Timeout uses `agent.changes.verification-timeout` (default 30m); log bound uses
`agent.changes.verification-max-output-bytes`, falling back to existing
`agent.plugins.bash.max-output-bytes` (default 1048576).

Required parent-owned API: `boolean ApprovalBroker.cancel(ApprovalRequest request)`.
This cancels only that exact full identity, including queued/deferred requests,
durably invokes CANCELLED before completing, dismisses the exact displayed
question and pumps the shared queue. It must not cancel the proposal's historical
run or append run events. ApplyCommand uses it only on interrupted waiting.

Every command returns false for rejected/deferred/cancelled/refused/partial
outcomes. Verification uses `Result.accepted`, never exit-zero alone.
Startup does not dereference managed suppliers. Parent owns busy input routing,
trace/UI adapters, FS SPI integration, app E2E and Maven validation.

Every managed boundary publishes the parent-owned
`TraceEvent.ChangeStateChanged`. Pending, durable approval and final apply/undo
states come from returned snapshots; failure paths reread the authoritative
store with the interrupt flag cleared only during observation and restored.
Verification publishes the actual runner code and retained evidence UUID
alongside a fresh persisted snapshot. No predicted APPLYING/UNDOING events.
`CommandObservation.java` is the only additional package-private support file,
shared by all four commands.

Unit tests are in
`mini-agent/agent-core/src/test/java/com/example/miniagent/core/change/ChangeCommandUnitTest.java`.
They use actual temp storage, retained staging, broker, apply/undo/recovery and
verification engines. Process tests use a deliberately fixture-only launch
policy to exercise observed exits/argv/clone cleanup; they do not claim OS
sandbox enforcement. The production runner still requires the parent/plugin
LaunchPolicy. All tests and Maven compilation remain explicitly UNRUN here.

Final static evidence: LSP inspected all six production Java files with
severity `all`: zero diagnostics, zero file failures and no truncation.
The 13-test `ChangeCommandUnitTest.java` likewise returned zero diagnostics
after its final edit. These are static checks, not executed test evidence.
Parent validation command (UNRUN here), from `mini-agent/`:

```sh
./mvnw -pl agent-core -am -Dtest=ChangeCommandUnitTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The only pre-existing source file edited by this unit is
`agent-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
with one added command-configuration line. Existing engine, approval, run,
console/UI, FS/bash, app tests, POM, plan and ledger are untouched by this unit.
