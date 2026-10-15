---
name: code-tour
description: Explain how this agent-forge project maps to Spring AI concepts. Use when the user asks where a Spring AI concept lives in this codebase or how a plugin works.
---

# agent-forge code tour

Answer by pointing to concrete files. Read the file with read_file before explaining it.

| Concept | File |
|---|---|
| Agent prototype (one ChatClient call) | agent-core/src/main/java/com/example/miniagent/core/Agent.java |
| Plugin wiring (collects beans into ChatClient) | agent-core/src/main/java/com/example/miniagent/core/AgentAutoConfiguration.java |
| Tool hook (tool_call / tool_result) | agent-core/src/main/java/com/example/miniagent/core/ToolInterceptor.java |
| ChatModel SPI | plugins/plugin-fake-model/src/main/java/com/example/miniagent/plugin/fakemodel/ScriptedChatModel.java |
| Real provider via Responses API (ChatGPT/Codex OAuth) | plugins/plugin-codex-model/src/main/java/com/example/miniagent/plugin/codex/CodexResponses.java, CodexChatModel.java |
| @Tool tools | plugins/plugin-fs/src/main/java/com/example/miniagent/plugin/fs/FileTools.java |
| FunctionToolCallback | plugins/plugin-bash/src/main/java/com/example/miniagent/plugin/bash/BashTool.java |
| Advisors inside vs outside the tool loop | plugins/plugin-trace/src/main/java/com/example/miniagent/plugin/trace/ |
| ChatMemoryRepository | plugins/plugin-memory/src/main/java/com/example/miniagent/plugin/memory/JsonlChatMemoryRepository.java |
| MCP client | plugins/plugin-mcp/ and agent-app/src/main/resources/application-mcp.yml |
| Trace events (observe vs render) | agent-core/src/main/java/com/example/miniagent/core/TraceEvent.java, plugins/plugin-trace/ |
| Full-screen TUI + trace graph sidebar | plugins/plugin-tui/src/main/java/com/example/miniagent/plugin/tui/TuiFrontEnd.java, TraceGraph.java |

Explain in the user's language. Keep it short: what the file does, which Spring AI type it uses, and one thing to try.
