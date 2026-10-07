# Actual terminal QA

Parent drove the packaged Java app through a real 120x32/80x24 PTY, rendered
unchanged ANSI output with xterm 5.5.0 in a task-owned Bun WebView, and inspected
the screenshots. Bun/xterm are QA-only, not application dependencies.
Java runtime, fake provider only; no paid provider requests.

## Passed observations

- Wide dark: captured fake/fake-scripted model, independent managed turn,
  actual write_file waiting for approval, request/run/attempt UUIDs and reply
  mode. `F4-wide-dark-approval-final.png`.
- Wide light and narrow light: same original ANSI stream, explicitly configured
  light ANSI palette. `F4-wide-light-palette-final.png`,
  `F4-narrow-light-stale.png`.
- Narrow dark: 80x24, CJK, wrapped identifiers, truncated status line, isolated
  input region, foreign UUID rejected without executing the write.
  `F4-narrow-dark-stale.png`.
- Esc at the unanswered managed write: final state CANCELLED,
  USER_CANCELLED, tool not executed; source unchanged.
  `F4-narrow-cancelled.png`.
- Two independent RESEARCH children: beta question shown first; only its exact
  UUID answered; alpha question shown next. Parent then accepted both genuine
  READ_ONLY journal/file-hash proofs and returned COMPLETE, not a narrative-only
  success. `F4-fifo-first.png`, `F4-fifo-second.png`,
  `F4-delegation-complete.png`. Child scheduling order is intentionally not fixed.
- Actual one-shot launcher: unanswered write left WAITING_APPROVAL/pending and
  exited 1. `F4-one-shot-pending.log`.
- Actual REPL EOF initially incorrectly returned 0 despite WAITING_APPROVAL.
  Fixed noninteractive REPL to retain failure/pending exit 1 through EOF and
  later successful inputs; added OneShotExitCodeTest regression.
  `F4-repl-eof.log` is the BEFORE record; final retest is required below.
- Backend busy rejection is covered by the real Spring integration
  DelegateCommandTest#applyWhileBusyReportsSessionBusy and
  ConsoleApprovalRoutingTest, not inferred from screenshots.

## Found and fixed

Managed parent conversations intentionally differ from the original UI session.
The TUI listener filtered their model/turn/tool events out, leaving the prior
graph visible. Now only a root RunContext whose recorded parentConversationId
matches the UI can route those events; child/foreign conversations stay excluded.
Actual fresh screenshot above and TuiTraceRoutingTest prove this boundary.
Related package tests: 41 passed, zero failure/error/skip, parent123 exit 0.

## Capture caveats

`F4-fifo-first.png` caught a partial paint. The final first-question text was read
after reply mode appeared and the second-question screenshot is complete.
Initial screenshots and `F4-wide-light-approval.png` are retained findings,
not final PASS evidence. A background-only switch keeps ANSI white/yellow
nearly invisible on white; use a light terminal ANSI palette, not just a white
background. The app does not auto-select the terminal palette. Default dark
dim informational text retains the existing terminal palette's lower contrast.

The terminal HTML/MJS helpers were executed in Bun/browser, but JS/HTML LSP was
unavailable (no repository TypeScript/Biome installation). Java production/test
diagnostics were checked without errors. No JavaScript dependency was installed.

## Final retest

Parent129 latest `./mvnw clean verify`: BUILD SUCCESS, exit 0. Parent directly
read all 88 Surefire XML headers: 680 tests, zero failures/errors/skips.
Parent130 exact `AGENT_PROFILE=fake AGENT_UI=plain bash ./agent.sh` with piped
REPL input: WAITING_APPROVAL/pending, REPL_EXIT=1, shell assertion exit 0.
`F4-repl-terminal-final.log` is the final passing artifact; the earlier
`F4-repl-eof-final.log` is a retained failed retest, not success evidence.
The remaining cause was PlainConsole's default interactive=true despite
piped stdin. AgentRepl now derives that setting from System.console() before
starting the fallback REPL; the regression mock preserves that state transition.
Final affected Java source/test LSP: no diagnostics.
