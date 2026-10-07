# Stage 4 lead evidence and scope

## Owned files
Relative to mini-agent:
- plugins/plugin-memory/src/main/java/com/example/miniagent/plugin/memory/ChatModelSummarizer.java
- plugins/plugin-memory/src/main/java/com/example/miniagent/plugin/memory/JsonlChatMemory.java
- plugins/plugin-memory/src/main/java/com/example/miniagent/plugin/memory/MemoryPluginAutoConfiguration.java
- plugins/plugin-memory/src/main/java/com/example/miniagent/plugin/memory/MemoryBudget.java
- plugins/plugin-memory/src/main/java/com/example/miniagent/plugin/memory/MemoryBudgetAdvisor.java
- plugins/plugin-memory/src/test/java/com/example/miniagent/plugin/memory/ChatModelSummarizerTest.java
- plugins/plugin-memory/src/test/java/com/example/miniagent/plugin/memory/JsonlChatMemoryTest.java
- plugins/plugin-memory/src/test/java/com/example/miniagent/plugin/memory/MemoryBudgetAdvisorTest.java
- agent-app/src/test/java/com/example/miniagent/app/MemoryBudgetTest.java
- agent-app/src/main/resources/application.yml (memory section)
- README.md (memory/budget sections)

All files were read by the lead. Core and the Spring AI tool loop were not edited for stage4.
Generation2 delta was MemoryBudgetAdvisor, JsonlChatMemoryTest import cleanup and MemoryBudgetTest.
The lead additionally specified MiniAgentApplication explicitly in the four SpringBootTest contexts, preserving their Models imports and assertions.

## Actual lead commands and evidence
- `./mvnw -pl plugins/plugin-memory -am test`: exit0, 21 tests, no failure/error/skip. stage4-lead-memory-tests.log.
- `./mvnw -pl agent-app -am -Dtest=MemoryBudgetTest,PluginVariationsTest -Dsurefire.failIfNoSpecifiedTests=false test`: exit0, 26 tests, no failure/error/skip. stage4-lead-app-tests.log.
- `bash .omo/qa/run-qa.sh summary`: exit0. summary.log.
- Explicit SpringBootTest context rerun: stage4-explicit-context-tests.log (completion must be checked separately).
- Lead queried LSP severity all on all nine changed Java files: zero diagnostics.

## Real production-path QA
The Java source-launcher driver uses actual MemoryPluginAutoConfiguration, JSONL repository, JsonlChatMemory, ChatModelSummarizer and ChatClient+FunctionToolCallback. Its recording local ChatModel is not Mockito/JUnit and makes no network call.
Summary request2000/output128 and context6messages/800characters are respected; huge previous/legacy summaries and historical tool data are bounded, history remains on disk, malformed covers/IDs are handled and empty/small conversations remain unchanged.
Both memory placements complete three actual provider dispatches with two complete tool pairs. Every provider prompt is bounded. Oversized mandatory input causes zero additional provider calls.
Fixture cleanup is confirmed in summary.log.

## Warning classification
- Invalid-configuration context-start WARN lines are expected rejection scenarios, not startup acceptance failures. Do not mute them.
- Byte Buddy dynamic-agent/CDS warnings predate this change; do not add suppression flags.
- Newly introduced default nested test-configuration WARN was addressed by explicit MiniAgentApplication classes; verify it is absent in the rerun.
- Acceptance requires >=3 model dispatches with multiple complete pairs, not three tool cycles plus a fourth dispatch. Do not invent a broader requirement.

## Lead checks still required before final delivery
Final full Maven verify, final changed-file diagnostics, packaged REPL/xterm evidence and cleanup/self-review.
