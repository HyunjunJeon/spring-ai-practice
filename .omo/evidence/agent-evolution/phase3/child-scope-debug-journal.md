# Child scope binding failure

First actual parent run `child-scope-parent-test.log`: budget 6, role 11,
plan validation 15 and lock 2 passed. Child scope had 6 failures and 4 errors
in 11 tests. Memory and app modules were not reached.

All ten failing paths first hit `Scope.bind` with
`IllegalStateException: Foreign or repeated child attempt`. Tests remain intact.

Distinguishing hypotheses:

1. Canonical executor cwd differs from the captured parent cwd on macOS's
   `/var` to `/private/var` alias. Compare both paths from the failed bind.
2. The bound model is wrapped outside the scope's BoundaryModel. Compare its
   actual runtime class and binding construction.
3. Parent lineage or RUNNING transition differs from the captured metadata.
   Compare persisted parentConversationId and writer state.

Probe artifact: temporary diagnostic suffix in ChildScopeFactory.Scope.bind.
The source already rejects all these conditions; the suffix reports only paths,
class, state and IDs, no model/user payloads. Remove the suffix after observing
the probe. The bounded Maven probe is subscribed as bash_88 and writes
`child-scope-bind-probe.log`. No debugger process or temporary directory created.

Parent app integration file ChildScopeAssemblyIntegrationTest is LSP clean but
its two actual Spring tests remain UNRUN until the core failure is fixed.
Parent owns all task19 follow-up; the completed child is not resumed.

Clean probe `child-scope-bind-clean-probe.log` reproduced the first hypothesis:
actual cwd `/private/var/folders/...`, expected cwd `/var/folders/...`.
The actual model was the scope BoundaryModel, writer state RUNNING, and persisted
parent ID matched. Executor canonicalizes cwd with toRealPath; factory did not.

Parent fixed the factory's captured cwd to use the same toRealPath contract.
Foreign binding/state/parent/stage predicates remain unchanged. The temporary
diagnostic suffix has been removed. Scope source LSP has zero diagnostics.
The complete core/memory/app reactor rerun is pending as bash_90, with log
`child-scope-canonical-parent-test.log`; no passing scope result claimed yet.

bash_90 exited 1: nine scope tests passed; two further failures were exposed.
One plain client bypass has ToolCallingChatOptions with null toolContext: parent
added the explicit missing-context rejection rather than letting get() throw NPE.
The new exclusion fixture installed an escaping live workspace link even for
`.env`/auth/traversal cases, so all were rejected by WorkspaceFingerprint before
an attempt existed. Parent now creates that link only in the symlink case,
asserts IOException and no bound attempt for that startup boundary, and retains
the RuntimeException/zero tool consumption checks for real callback exclusions.
All permission/effect zero assertions are unchanged; no excluded case removed.
These newly authored task19 tests are corrected to the actual two boundaries.

Both changed Java files have zero LSP diagnostics. The full reactor rerun
`child-scope-integrated-parent-test.log` is pending as bash_91. No task19 checkbox
is marked until scope, memory and actual Spring app evidence all pass.

bash_91: all 45 core tests and both memory tests passed. The two new app tests
failed before scope creation: currentTurn is intentionally empty after a parent
run returns, since Agent removes its active display entry in finally.
Parent corrected its new app fixture to capture an explicit TurnContext and
run the real parent with it; it compares unchanged display state across the
child rather than assuming a completed parent stays active. Memory/selection,
binding identity, real stage proposal and untouched live file assertions remain.
App LSP zero. The complete final rerun is subscribed as bash_92 with
`child-scope-final-parent-test.log`; result still pending.

bash_92 core45/memory2 passed. Actual app binding now reached the real model
and failed on null empty callback options and write_file's static method wrapper
identity. Parent accepts null only as the empty advertised callback list, while
still requiring exact tool context. MethodToolCallbackProvider alone is
materialized once; all dynamic MCP providers retain identity/schema/add/remove
checks. The actual two app tests and 11 scope regressions are rerunning with
`child-scope-spring-parent-test.log` as bash_93. Scope LSP zero.

bash_93 completed exit 0, BUILD SUCCESS. Core45, memory2 and actual Spring app2
all passed with zero failures/errors/skips. Diagnostic suffix removed; no probe
process remains. JUnit owns and removes all TempDir resources. Task19 verified
and marked complete. No further validation pass required for this checkbox.
