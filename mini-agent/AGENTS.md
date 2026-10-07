# MINI-AGENT KNOWLEDGE BASE

## OVERVIEW
Distinct application domain, score 10: >20 files (+3), >5 immediate directories (+2), >70% code/config (+2), own POM (+1), >30 AST symbols (+2).

## STRUCTURE
```text
mini-agent/
|-- agent-core/    Shared SPI, request assembly, input handling
|-- agent-app/     Executable app, provider profiles, integration tests
|-- plugins/       Eleven plugin modules; follow plugins/AGENTS.md
|-- docs/          Learning path
|-- .mini-agent/   Workspace skill content
`-- .mvn/          Maven wrapper configuration
```

## WHERE TO LOOK
Java paths below are relative to each module's `src/main/java/com/example/miniagent/`.
- Core assembly: `agent-core/.../core/AgentAutoConfiguration.java`.
- Per-turn session and tool context: `agent-core/.../core/Agent.java`.
- REPL/front-end dispatch: `agent-app/.../app/AgentRepl.java`.
- Provider settings: `agent-app/src/main/resources/application-*.yml`.
- Tool examples: `plugin-fs` (`@Tool`) and `plugin-bash` (`FunctionToolCallback`).
- Plugin implementation and safety contracts: `plugins/AGENTS.md`.
- Bundled runtime skill: `.mini-agent/skills/code-tour/SKILL.md`.
- End-to-end loop tests: `agent-app/src/test`, `AgentLoopTest` and `PluginVariationsTest`.
- Provider profiles: `application-{codex,claude,zai,anthropic,openai,ollama}.yml`; `application-mcp.yml` adds MCP.
- Z.ai reuses official OpenAiChatModel with ZAI_API_KEY/ZAI_BASE_URL/ZAI_MODEL; GLM reasoning state must survive tool and memory roundtrips.
- Claude subscription: `plugin-claude-model` implements OAuth PKCE, token storage/refresh, and Anthropic Messages calls in Java. senpi is a protocol reference only, never a runtime dependency.

## CONVENTIONS
- Keep Spring AI pinned to 2.0.1 through the parent BOM; POM comments forbid automatic milestone promotion.
- Keep compiler parameter names enabled: `@Tool` JSON schemas use them.
- Plugins contribute Spring beans; `AgentPlugin` is descriptive, not an installer.
- Register auto-configuration in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- New plugins require parent modules/dependencyManagement entries and an `agent-app` dependency.
- Core gathers ordered providers, callbacks, advisors, commands, and prompt contributors.
- Attach plugin tools as ChatClient defaults; resolution fallback is disabled.
- Wrap dynamic ToolCallbackProviders without eagerly expanding their tool lists.
- Advisor placement matters: turn trace +100, memory +200, tool loop +300, round trace +400 relative to HIGHEST.
- Trace and TUI communicate through core TraceEvent types, not each other's implementations.
- Tests use the fake model, temporary cwd/home, and observable behavior without provider credentials.
- Claude OAuth and Messages tests use Java local HTTP servers and synthetic credentials; no Bun, senpi, or real login is required.
- Tool results reach models (and tool tests) as JSON-serialized strings; assert on that form.

## ANTI-PATTERNS
- Do not implement a second tool loop in Agent or ChatModel.call().
- Do not register two ToolAdvisors.
- Do not omit ChatMemory.CONVERSATION_ID from per-turn advisor context.
- Do not swallow one-shot failures; `AgentRepl` maps them to exit code 1.

## COMMANDS
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./mvnw test
./mvnw install -DskipTests
AGENT_UI=repl ./agent.sh
AGENT_PROFILE=codex ./agent.sh
```

## NOTES
- agent.sh rebuilds stale source/POM changes with tests skipped; run tests separately.
- Working directory is where agent.sh is invoked, not necessarily the script directory.
- Piped input uses REPL rather than the full-screen frontend.
- AST measurements add symbol-density credit; reference centrality remains unmeasured.
- Core and individual plugin modules remain below 8; plugins collectively score 9.
