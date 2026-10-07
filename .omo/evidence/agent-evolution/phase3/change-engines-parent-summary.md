# Parent validation checkpoint

Task 15 backend and task 16 runner are verified. Task 17 command wiring and
the legacy FileCheckpoints bridge remain in progress, not delivered.

The parent Java 21 Maven run in `change-engine-parent-test.log` exited zero:
52 tests, zero failures/errors/skips. It includes actual apply, conflict-preserving
undo, interrupted boundary recovery, staged workspaces, metadata storage and
Spring configuration. Apply/undo use the nonconstructible, one-use console
ownership capability, not a model/tool boolean. Recovery is read-only.

The parent Java 21 Maven run in `verification-process-parent-test.log` exited
zero: 46 tests, zero failures/errors/skips across core and bash modules. Four
core and ten real macOS process tests cover exact argv, actual canonical clone
cwd, framed binary logs, real exits, cancellation/interruption/timeout cleanup,
generated output, source/live/auth denial, source drift and output limits.
Existing BashTool and storage regressions pass.

Parent integration separates actual execution `VerificationEvidence.cwd` from
owner-bound `sourceCwd`, updates store validation, tests round-trip persistence
and rejection of the wrong source without journal writes, and contributes lazy
apply/undo/recovery/verification beans. The original evidence constructor still
defaults sourceCwd to cwd. Acceptance requires Result.accepted, including its
observed outcome code: stale results can truthfully retain exit zero.

Changed foundation/store/test files have clean LSP diagnostics. Core assembly
retains its five existing Spring AI deprecation warnings and has no errors.

Task 17 now adds requestChange to the existing approval broker queue without
inventing an active RunContext for a completed run. Durable decision callbacks
must complete before the future; noninteractive changes defer; failed storage
does not approve. Its core broker run in `change-approval-parent-test.log` exited
zero: 11 tests, zero failures/errors/skips. The run/executor command tests live
in agent-app, not core; their follow-up in
`change-approval-app-regression.log` exited zero: 11 core broker and 41 app
run/executor/control tests, zero failures/errors/skips.

Task 18's independent contract/validator/role lane is running as st_01a110e0;
parent owns its subsequent checks. No commits, paid calls or external writes.

The legacy FileCheckpoints/FileTools conflict-preserving after-evidence bridge
is running independently as st_01a110e5. Parent owns /undo registration and
display; the new command registry must not collide with the existing fs undo.
Its ABI file is subscribed before integration. No change command classes or
trace/frontend integration have yet been delivered.
