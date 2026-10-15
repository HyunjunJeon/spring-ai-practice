# AGENT-FORGE KNOWLEDGE BASE

**Generated:** 2026-10-15
**Commit:** 655bf9618b643b65c01fa0b0f3a2f98f88fd6b78
**Branch:** main

## OVERVIEW
agent-forge is a Spring AI agent application with research and learning material.
The application uses Java 21, Spring Boot 4.1.1, and Spring AI 2.0.1.

## STRUCTURE
```text
spring_ai_test/
|-- pom.xml           Maven reactor and pinned dependency versions
|-- agent-core/       Execution, SPI, durable runs, staging, and delegation
|-- agent-app/        Executable composition and provider profiles
|-- plugins/          Eleven extension modules
|-- docs/             Learning material
|-- agent.sh          Launcher; invocation directory is the agent workspace
|-- scripts/verify.sh Unified verification command (compile, tests, ArchUnit rules)
|-- harness/          Improvement log for coding-agent harness failures and fixes
|-- reports/
|   |-- Spring AI 최신 버전 심층 조사.md    Research synthesis
|   `-- research_notes/                  Topic evidence
|-- hn.json           Saved research artifact; contents not fully verified
`-- hn2.json          Additional saved research artifact
```

## WHERE TO LOOK
- Application development and execution: `README.md`.
- Core execution and durable state: `agent-core/AGENTS.md`.
- Executable composition and integration regressions: `agent-app/AGENTS.md`.
- Plugin implementations: `plugins/AGENTS.md`.
- Build coordinates: `pom.xml`.
- Runnable entry point: `agent-app/src/main/java/com/example/miniagent/app/MiniAgentApplication.java`.
- Launch and provider selection: `agent.sh`.
- Learning exercises: `docs/learning-path.md`.
- Bundled code tour: `.mini-agent/skills/code-tour/SKILL.md`.
- Coding-agent failures and the harness changes they caused: `harness/improvement-log.md`.
- Final research synthesis: `reports/Spring AI 최신 버전 심층 조사.md`.
- Topic evidence: `reports/research_notes/Spring AI 최신 버전 심층 조사/`.
- Research topics: `version_status_compatibility.md`, `core_api_features.md`,
  `mcp_agents_providers.md`, `migration_breaking_changes.md`, `ecosystem_adoption.md`.

## CODE MAP
Types below live in `agent-core/src/main/java/com/example/miniagent/core/`.
Paths below are relative to that directory. Local AST extraction supplied the map;
reference centrality is unmeasured, and import counts are not reference counts.
- `Agent` (`Agent.java:47`, class): synchronous and streaming turn execution.
- `AgentAutoConfiguration` (`AgentAutoConfiguration.java:65`, class): bean assembly.
- `AgentPlugin` (`AgentPlugin.java:18`, interface): plugin descriptor.
- `ToolInterceptor` (`ToolInterceptor.java:16`, interface): tool execution hooks.
- `SlashCommand` (`SlashCommand.java:10`, interface): operator command extension.
- `TraceEvent` (`TraceEvent.java:19`, sealed interface): shared execution events.
- `RunExecutor` (`run/RunExecutor.java:30`, class): run lifecycle and accounting.
- `DefaultDelegationRuntime` (`delegation/DefaultDelegationRuntime.java:29`, class):
  bounded child dependency execution.

## CONVENTIONS
- The workspace root is the Maven reactor; run application commands here.
- Project identity is `agent-forge`; the executable is `agent-app/target/agent-forge.jar`.
- Keep the existing Java namespace, `.mini-agent` state/skill paths, and `agent.*` configuration compatible.
- Application versions come from the POM, not research recommendations.
- Research distinguishes GA versions, milestone-only features, and community projects.
- The research report dates its evidence to 2026-10-01; preserve that context.
- Release dates identify Maven Central upload versus GitHub release dates; do not merge them.
- Source comments and project documentation commonly use Korean.
- Keep compiler parameter names enabled: `@Tool` JSON schemas use them.
- Plugins contribute Spring beans; `AgentPlugin` is descriptive, not an installer.
- Register auto-configuration in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- New plugins require root modules/dependencyManagement entries and an `agent-app` dependency.
- Core gathers ordered providers, callbacks, advisors, commands, and prompt contributors.
- Attach plugin tools as ChatClient defaults; resolution fallback is disabled.
- Wrap dynamic ToolCallbackProviders without eagerly expanding their tool lists.
- Advisor placement matters: turn trace +100, memory +200, tool loop +300, round trace +400 relative to HIGHEST.
- Trace and TUI communicate through core TraceEvent types, not each other's implementations.
- Tests use synthetic models, temporary cwd/home, local HTTP fixtures and observable durable state.
- Claude OAuth and Messages tests use Java local HTTP servers and synthetic credentials.
- Tool results reach models and tool tests as JSON-serialized strings.
- Provider profiles set `agent.models.default`; model factories isolate provider configuration.
- Claude subscription authentication and Messages calls are Java-owned; senpi is a protocol reference only.

## ANTI-PATTERNS (THIS PROJECT)
- Do not treat research recommendations as application configuration; inspect the POM.
- Do not conflate milestone capabilities with the application's pinned GA release.
- Do not treat launcher-triggered builds as evidence that tests passed; the exit code of `scripts/verify.sh` is.
- Do not implement a second tool loop in Agent or ChatModel.call(), or register two ToolAdvisors.
- Do not omit ChatMemory.CONVERSATION_ID from per-turn advisor context.
- Do not swallow one-shot failures; `AgentRepl` maps them to exit code 1.

## UNIQUE STYLES
- Research notes and final synthesis are separate from runnable application code.
- Application learning material organizes features by Spring AI extension point.
- Plugins contribute to one Spring AI tool loop; provider protocols remain Java-owned.

## COMMANDS
```bash
bash ./scripts/verify.sh
bash ./scripts/verify.sh -pl agent-app -am -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw install -DskipTests
bash ./agent.sh
bash ./agent.sh "hello"
AGENT_UI=repl bash ./agent.sh
AGENT_PROFILE=codex bash ./agent.sh
AGENT_PROFILE=claude bash ./agent.sh
```

## NOTES
- JDK 21 is required. `scripts/verify.sh` and `agent.sh` fall back to Homebrew `openjdk@21`; set JAVA_HOME when invoking the Maven wrapper directly. `/usr/libexec/java_home -v 21` does not find the Homebrew JDK.
- The launcher defaults to profile `default` and TUI; piped input uses REPL.
- The default model configuration selects `fake/fake-scripted`.
- The launcher rebuilds a missing or stale JAR with tests skipped.
- Inventory excludes generated/vendor directories and IDE metadata.
- Existing research moves may appear as deleted/untracked paths; preserve them.
- Research cutoff remains 2026-10-01 even though guidance was regenerated later.
