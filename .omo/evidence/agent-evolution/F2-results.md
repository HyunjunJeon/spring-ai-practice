# Full verification

Parent119: `./mvnw clean verify`, Java 21, exit 0.
Actual Surefire XML: 87 suites, 678 tests, 0 failures, 0 errors, 0 skips.

Parent120: `AGENT_PROFILE=fake AGENT_UI=plain bash ./agent.sh
--agent.home=<temp-home> --agent.cwd=<temp-workspace> hello`, exit 0.
The actual response identified `fake/fake-scripted` and the USER `hello`.
Both shell-owned temporary directories were removed by the exit trap.

Every source/test Java file was individually checked: 281 files. Two fresh
diagnostic timeouts were rechecked successfully. No Java errors remain.
The core's existing five Spring AI deprecation warnings and the turn-binding
test's two deprecated builder calls remain visible. The unused TUI runner
field introduced by earlier control migration was removed, not suppressed.
JDK/Mockito dynamic-agent warnings remain visible in the Maven output.

Whole-suite findings fixed:
- Core-only configuration needed RunProperties for its new delegate command.
  Registered the real configuration properties; retained the existing test.
- The disabled fs/bash plugin test omitted the three new core tools.
  Updated its exact machine tool list, retaining disabled plugin assertions.

Included OS-boundary suites passed: run crash 3, change crash 6, child recovery 1.
This is the parent119/120 checkpoint. Later final-tree verification and
completed terminal/launcher QA are recorded in F4-results.md: parent129,
88 suites, 680 tests, zero failures/errors/skips, exit 0. The single final
gate audit is tracked separately from these executable results.
