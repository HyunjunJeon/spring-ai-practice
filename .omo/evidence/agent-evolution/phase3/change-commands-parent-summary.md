# Task 17 parent integration evidence

The real Spring E2E and neighboring suites in
`change-commands-e2e-parent-test.log` exited zero: core 26, TUI 10, app 31,
67 tests total, zero failures/errors/skips. Seven actual app cases cover
managed turn -> persisted changes -> shared approval -> exclusive apply ->
real macOS isolated verification -> conflict-preserving undo, unattended pending,
reject, busy controls, human edits, actual nonzero verification, and legacy undo.
Thirteen command-unit cases also exercise the actual engines and durable queue.

The previous legacy/trace parent run exited zero with 59 tests. Busy, exact
queued cancellation and delegation contract/role checks exited zero with 38.
The existing five Spring AI deprecation warnings remain; TuiFrontEnd's existing
unused runner field warning remains. No warnings or errors were suppressed.

The parent wired FS as UndoCommand.LegacyUndo, removed its duplicate slash
command, added input atomic busy gating and read-only changes/run controls,
and restricted TUI executor cancellation to the actual submitted managed run.
The sealed change event carries stored owner/digest/status and actual verifier
code/evidence ID. Both trace renderers consume it; the next binding clears it.

Actual PTY sessions:
- bash_78 used an incorrect probe-only fake/scripted ref and showed UNKNOWN_MODEL.
  It was killed; this was a probe command error, not production model failure.
- bash_79 used confirmed fake/fake-scripted, executed a staged write and changes
  query, and exited zero. The live preview.txt remained absent.
- Durable proposal 86132f69-8b1e-4ec3-b3d2-4e37fbfe9633 recorded at
  /private/tmp/mini-agent-change-ui.OIPjUf/home/changes/
  3e00f0f3c6df118f4c57aa618370d68a7a473d20a0cc12c20bb068fe1eb9b4f6/.
  A pre-action monitor confirmed a nonempty journal, not a model success claim.
- Fresh build bash_82 displayed the stored complete diff, source owner, baseline
  absence and PENDING_APPROVAL at 120 columns and 70 columns. Sidebar collapsed
  at 70 and the status, input and borders remained visible.
- The first 70-column view exposed a cut footer. Parent changed optional hints
  to display only when they fit, rebuilt successfully in
  change-ui-width-package.log, and inspected both widths on the fresh build.
  At 70 the complete change ID and PENDING_APPROVAL remain, without cut hints.
  bash_82 exited zero after /quit. Cleanup of the owned temporary directory
  /tmp/mini-agent-change-ui.OIPjUf completed as bash_83, exit zero,
  UI_TEMP_REMOVED.

These are real rendered PTY snapshots, not PNG/theme or independent-review
evidence. The full visual QA matrix and independent review belong to F4 and
remain unrun. Do not claim the complete visual gate from this checkpoint.

Task 18 contracts are implemented and 26 tests passed in the final parent run,
but its verifiedSuccess currently requires each child to own VerificationEvidence.
Before runtime integration, assess how actual research and implement evidence
will satisfy the required research -> implement -> verify scenario without
fabricating test exits. Parent now preserves VerificationRunner outcome codes
in ChildEvidence.Verification and rejects an honest stale exit zero in aggregation.
No runtime is shipped yet.
