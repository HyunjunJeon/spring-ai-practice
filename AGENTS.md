# PROJECT KNOWLEDGE BASE

**Generated:** 2026-10-06
**Commit:** unavailable (workspace has no Git metadata)
**Branch:** unavailable

## OVERVIEW
Spring AI research workspace and a runnable Java agent learning project.
The application uses Java 21, Spring Boot 4.1.1, and Spring AI 2.0.1.

## STRUCTURE
```text
spring_ai_test/
|-- mini-agent/       Maven application and its own AGENTS.md
|-- research_notes/   Detailed research inputs grouped by topic
|-- reports/          Synthesized Spring AI report
|-- hn.json           Saved JSON research artifact
`-- hn2.json          Additional saved JSON research artifact
```

## WHERE TO LOOK
- Application development: `mini-agent/`; follow its scoped guidance.
- Plugin implementations: `mini-agent/plugins/AGENTS.md`.
- Build coordinates: `mini-agent/pom.xml`.
- Runnable entry point: `mini-agent/agent-app/src/main/java/com/example/miniagent/app/MiniAgentApplication.java`.
- Launch and provider selection: `mini-agent/agent.sh`.
- Learning exercises: `mini-agent/docs/learning-path.md`.
- Final research synthesis: `reports/Spring AI 최신 버전 심층 조사.md`.
- Topic evidence: `research_notes/Spring AI 최신 버전 심층 조사/`.
- Research topics: `version_status_compatibility.md`, `core_api_features.md`,
  `mcp_agents_providers.md`, `migration_breaking_changes.md`, `ecosystem_adoption.md`.

## CODE MAP
Types below live in `mini-agent/agent-core/src/main/java/com/example/miniagent/core/`.
AST-derived import counts are not call/reference counts; centrality is unmeasured.
Java LSP was unavailable during generation.
- `Agent` (`Agent.java`, class): 5 core-type imports; one user turn through ChatClient.
- `AgentConsole` (`AgentConsole.java`, class): 14 imports; shared console boundary.
- `AgentPlugin` (`AgentPlugin.java`, interface): 12 imports; plugin descriptor.
- `TraceEvent` (`TraceEvent.java`, interface): 10 imports; shared trace event contract.
- `SlashCommand` (`SlashCommand.java`, interface): 7 imports; command extension point.
- `ToolInterceptor` (`ToolInterceptor.java`, interface): 4 imports; tool execution hooks.
- `AgentAutoConfiguration` (`AgentAutoConfiguration.java`): assembles contributed beans.

## CONVENTIONS
- The workspace root is not a Maven module; application commands run in `mini-agent/`.
- Research distinguishes GA versions, milestone-only features, and community projects.
- The research report dates its evidence to 2026-10-01; preserve that context.
- Release dates identify Maven Central upload versus GitHub release dates; do not merge them.
- Source comments and project documentation commonly use Korean.

## ANTI-PATTERNS (THIS PROJECT)
- Do not treat research recommendations as application configuration; inspect the POM.
- Do not conflate milestone capabilities with the application's pinned GA release.
- Do not invent Git commit or branch metadata for this workspace.

## UNIQUE STYLES
- Research notes and final synthesis are separate from runnable application code.
- Application learning material organizes features by Spring AI extension point.

## COMMANDS
```bash
cd mini-agent
./mvnw test
./agent.sh "hello"
```

## NOTES
- Existing root and application guides were updated; plugin guidance is nested under the application.
- The source inventory excludes generated Maven `target/` content.
- Snapshot Git fields may be unavailable until this workspace becomes a repository.
- Java LSP is not installed; the code map was refreshed with the local ast-grep CLI.
- Research cutoff remains 2026-10-01 even though guidance was regenerated later.
