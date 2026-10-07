# PLUGIN KNOWLEDGE BASE

## OVERVIEW
Eleven extension modules, score 9: >20 files (+3), >5 child directories (+2), >70% code/config (+2), >30 AST symbols (+2).

## STRUCTURE
```text
plugins/
|-- plugin-codex-model/   Responses wire adapter, OAuth, model selection
|-- plugin-claude-model/  Java Claude subscription OAuth + Messages; no internal tool execution
|-- plugin-fake-model/    Scripted model for offline learning and tests
|-- plugin-fs/            Bounded file tools and per-turn checkpoints
|-- plugin-bash/          Shell callback and macOS sandbox
|-- plugin-permission/    Tool confirmation interceptor
|-- plugin-memory/        JSONL history and model-driven compaction
|-- plugin-trace/         Turn/round/tool events and console rendering
|-- plugin-tui/           Full-screen frontend and trace graph
|-- plugin-skills/        Skill discovery and on-demand loading
`-- plugin-mcp/           MCP tool-provider descriptor
```

## WHERE TO LOOK
Java files are under each module's `src/main/java/com/example/miniagent/plugin/`.
- Codex protocol: `plugin-codex-model/.../codex/CodexResponses.java`, `CodexResponsesClient.java`.
- Credential ownership: `plugin-codex-model/.../codex/CodexAuth.java`.
- Claude subscription: `plugin-claude-model/.../claude/{ClaudeOAuth,ClaudeAuth,ClaudeMessagesClient,ClaudeChatModel}.java`.
- Files and undo: `plugin-fs/.../fs/FileTools.java`, `FileCheckpoints.java`.
- Sandbox: `plugin-bash/.../bash/BashSandbox.java`, `BashPluginAutoConfiguration.java`.
- Permission decisions: `plugin-permission/.../permission/ConfirmToolInterceptor.java`.
- Session records: `plugin-memory/.../memory/JsonlChatMemoryRepository.java`.
- Context compaction: `plugin-memory/.../memory/JsonlChatMemory.java`, `ChatModelSummarizer.java`.
- Event publication: `plugin-trace/.../trace/ToolTraceInterceptor.java`.
- Graph layout and frontend: `plugin-tui/.../tui/TraceGraph.java`, `TuiFrontEnd.java`.
- Skill discovery: `plugin-skills/.../skills/SkillRegistry.java`.
- Tests live in each module's `src/test`; Codex has protocol/auth/client tests, TUI has `TraceGraphTest`.

## CONVENTIONS
- Enablement normally uses `agent.plugins.<id>.enabled`, enabled when missing.
- File tools allow cwd, `~/.mini-agent/skills`, and configured allowed roots; resolve symlinks to real paths.
- `write_file`/`edit_file` checkpoint originals by `ToolContextKeys.TURN_ID`; `/undo` excludes bash changes.
- Memory appends messages and `"type":"summary"` records; `findByConversationId` returns messages, `read` also returns the latest summary.
- Repository `saveAll` implements full replacement; the runtime memory uses append instead.
- Ignore an incomplete final JSONL fragment; malformed complete records raise errors.
- Tool exceptions publish `TraceEvent.ToolFailed`; denied tools do not execute.
- TUI logs go to `~/.mini-agent/logs/tui.log`.

## ANTI-PATTERNS
- Never refresh or overwrite `~/.codex/auth.json`; refresh only the application's own login token.
- Do not assume one-shot mode can confirm tools; tools outside `auto-allow-tools` are denied without a console.
- Do not retry a denied tool.
- Do not widen file access to all of `~/.mini-agent`; `auth/` holds tokens.
- Do not use `workspace-write` sandbox for builds without granting required cache roots such as `~/.m2`.
- Do not replace complete session history during normal memory updates.
- Do not return plain `ChatOptions` from a tool-capable custom model; use `ToolCallingChatOptions`.
- Claude subscription authentication is owned by the Java plugin. Store only its own tokens under mini-agent auth; do not read or mutate senpi/Claude Code credentials, and do not silently use API keys.
