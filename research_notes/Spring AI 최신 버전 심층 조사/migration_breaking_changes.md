# Spring AI Migration Path and Breaking Changes (upgrading into the latest GA, as of 2026-10-01)

Abbreviations used below: **GA** = general availability, **RC** = release candidate, **M** = milestone, **SNAPSHOT** = unreleased build. "UN" = the official Upgrade Notes page. Unless stated, "UN (2.0.x)" means the page at `docs.spring.io/spring-ai/reference/upgrade-notes.html` (the docs site's current version is 2.0.1), and "UN source (main)" means the AsciiDoc on the `main` branch, which is the 2.1 line.

---

## Q0. Version anchoring: what is the current latest GA, and what came before and after it?

### Takeaway
As of 2026-10-01 the **latest GA is Spring AI 2.0.1** (released 2026-08-20/21). It is a patch on the **2.0.0 GA** line (released 2026-06-12). The next line, **2.1.0-M1**, is a milestone (2026-09-24/25). **2.0.2 exists only as a SNAPSHOT.** The 1.0.x and 1.1.x lines lost OSS support on 2026-06-30, so 1.x users have to move to 2.0.x to keep getting free fixes.

### Cited Findings
- Maven Central `spring-ai-bom` metadata lists `<release>2.1.0-M1</release>`. This is **misleading**: Maven's `<release>` element just means "newest non-SNAPSHOT upload", not GA. 2.1.0-M1 is a milestone, and the newest non-milestone, non-RC version is `2.0.1`. The full list runs 1.0.0 to 1.0.9, 1.1.0 to 1.1.8, 2.0.0-M1 to M8, 2.0.0-RC1/RC2, 2.0.0, 2.0.1 and 2.1.0-M1. Metadata `lastUpdated` = 20260924091844 — [Maven Central maven-metadata.xml](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml)
- Maven Central publish dates (HTTP Last-Modified on the BOM POMs): 2.0.0 = 2026-06-12, 2.0.1 = 2026-08-20, 2.1.0-M1 = 2026-09-24, 1.1.8 = 2026-06-12, 1.0.9 = 2026-06-12, 2.0.0-RC2 = 2026-06-09 — [Maven Central spring-ai-bom directory](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/)
- GitHub releases: `v2.1.0-M1` published 2026-09-25 (prerelease=true); `v2.0.1` 2026-08-21 (prerelease=false); `v2.0.0` 2026-06-12; `v1.1.8` and `v1.0.9` both 2026-06-12; `v2.0.0-RC2` 2026-06-09; `v2.0.0-RC1` 2026-06-06; `v2.0.0-M8` 2026-05-27; `v2.0.0-M1` 2025-12-11; `v1.1.0` 2025-11-12 — [GitHub releases](https://github.com/spring-projects/spring-ai/releases)
- The spring.io project API lists `2.0.2-SNAPSHOT` (SNAPSHOT), `2.1.0-M1` (PRERELEASE), `2.0.1` (GENERAL_AVAILABILITY, **current=true**), `1.1.8` (GA) and `1.0.9` (GA) — [api.spring.io releases](https://api.spring.io/projects/spring-ai/releases)
- Support generations: **1.0.x** initial 2025-05-31, OSS end **2026-06-30**, commercial end 2027-06-30. **1.1.x** initial 2025-11-30, OSS end **2026-06-30**, commercial end 2032-06-30. **2.0.x** initial 2026-06-30, OSS end **2027-07-31**, commercial end 2028-07-31 — [api.spring.io generations](https://api.spring.io/projects/spring-ai/generations)
- The spring.io project page shows "Spring AI 2.0.1" as the current version. The version/support tables did not render for the fetch tool — [spring.io/projects/spring-ai](https://spring.io/projects/spring-ai)
- The official 2.0.0 GA announcement is dated 2026-06-12 — [Spring blog: Spring AI 2.0.0 GA Available Now](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- **Third-party date discrepancies:** byteiota's headline says "Spring AI 2.0 GA Ships May 28" (that date matches 2.0.0-M8, not GA) — [byteiota](https://byteiota.com/spring-ai-2-ga-java-production-stack/). Visual Studio Magazine reported the GA on 2026-06-29 — [Visual Studio Magazine](https://visualstudiomagazine.com/articles/2026/06/29/spring-ai-2-0-goes-ga-giving-java-developers-a-more-mature-ai-app-stack.aspx). Both conflict with the primary sources (GitHub and Maven Central: **2026-06-12**).
- The 2.1.0-M1 release notes point to the 2.1 upgrade notes: "For upgrading from 2.0.1 to 2.1.0-M1, please refer to … reference/2.1/upgrade-notes.html#upgrading-to-2-1-0" — [GitHub release v2.1.0-M1](https://github.com/spring-projects/spring-ai/releases/tag/v2.1.0-M1)
- The repository has branches `1.0.x`, `1.1.x`, `2.0.x` and `main`. On both `main` and `2.0.x` the upgrade-notes source already contains a "Upgrading to 2.0.2" section, so it is unreleased patch content — [UN source (2.0.x)](https://github.com/spring-projects/spring-ai/blob/2.0.x/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)

### Inferences
- The upgrade target for "latest GA" is **2.0.1**. A 1.x user crosses two breaking boundaries: **1.x → 2.0.0** (major) and **2.0.0 → 2.0.1**, a patch that deliberately carries breaking changes (see Q1).
- 1.0.x and 1.1.x both got final OSS releases (1.0.9 and 1.1.8) on the same day as 2.0.0 GA (2026-06-12), which lines up with the 2026-06-30 OSS end date.

### Gaps
- The spring.io HTML Versions/Support tables were not readable through the fetch tool. The JSON API above was used instead and is authoritative for the same data.

---

## Q1. What does the official Upgrade Notes page say for each transition?

### Takeaway
The official page has **no "Upgrading to 1.1.0" GA section**. For 1.1 it only has "Upgrading to 1.1.0-RC1", which covers the TTS API change. All 2.0 milestone notes were **consolidated into a single "Upgrading to 2.0.0" section**. "Upgrading to 2.0.1" lists additional breaking changes in a patch release. "Upgrading to 2.0.2" (SNAPSHOT) and "Upgrading to 2.1.0" (milestone line) already exist in the source.

### Cited Findings
**1.0 → 1.1 (labelled 1.1.0-RC1 in UN)**
- The only 1.1 section is "Upgrading to 1.1.0-RC1 → Breaking Changes → Text-to-Speech (TTS) API Migration". It removes `org.springframework.ai.openai.audio.speech.{SpeechModel, StreamingSpeechModel, SpeechPrompt, SpeechResponse, SpeechMessage, Speech}`, which are replaced by `TextToSpeechModel`, `StreamingTextToSpeechModel`, `TextToSpeechPrompt`, `TextToSpeechResponse`, `TextToSpeechMessage` and `Speech` in `org.springframework.ai.audio.tts`. The `speed` parameter changes from `Float` to `Double` — [UN (2.0.x) #upgrading-to-1-1-0-RC1](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)
- The 1.1.0-RC1 GitHub release notes list two breaking changes: the TTS migration, and "Removed deprecated APIs in preparation for the 1.1.0 release" — [GitHub release v1.1.0-RC1](https://github.com/spring-projects/spring-ai/releases/tag/v1.1.0-RC1)
- The 1.1 GA blog (2025-11-12) presents MCP as the headline feature of 1.1 — [Spring blog: Spring AI 1.1 GA Released](https://spring.io/blog/2025/11/12/spring-ai-1-1-GA-released/)

**1.1.x → 2.0.0 (GA, 2026-06-12)**
- The 2.0.0 release notes say: "For upgrading from 1.1.x to 2.0.0, please refer to the upgrade notes … #upgrading-to-2-0-0". The same release includes "Consolidate upgrade notes for 2.0.0 [#6333]" — [GitHub release v2.0.0](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0); [PR #6333](https://github.com/spring-projects/spring-ai/pull/6333)
- Top-level subsections of "Upgrading to 2.0.0", in page order: Advisors; Tool Calling (1.x→2.x); JDBC Chat Memory `sequence_id` Column; Options Immutability and Default Values; Ollama; Minimax superseded by Anthropic; JSON Utilities Refactoring; MCP Elicitation API (`TypeReference` → `ParameterizedTypeReference`); Google GenAI Embedding package change; ChatClient Tool Calling; Cloud Bindings; BeanOutputConverter JSON Schema Generation; Azure Cosmos DB Support; MCP SDK Breaking Changes (required fields); Observability; Removed Modules; Chat Memory Advisors (conversation ID required); Removed `PromptChatMemoryAdvisor`; ChatClient Options Now Require a Builder; OpenAI Java SDK Transition; MCP Java SDK Upgraded to 2.0.0; Unified Cache Usage Metrics; Anthropic Module; MCP Annotations Migrated into Spring AI; MCP Spring Transport Modules Moved; MCP Client Customizer API Consolidated; MCP WebMvc Transport Headers Normalized to Lowercase; Conversation History Removed from ToolContext; model internal methods made private; OpenSearch Dependencies Upgraded; `AbstractFilterExpressionConverter.doSingleValue` now abstract; MongoDB Chat Memory ordering fixed; Development-time Services; Default Temperature Configuration Removed — [UN (2.0.x) #upgrading-to-2-0-0](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0); [UN source (main)](https://github.com/spring-projects/spring-ai/blob/main/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)
- The detailed content of each item is in Q3 to Q5 below.

**2.0.0 → 2.0.1 (GA patch, 2026-08-20/21). The patch intentionally carries breaking changes.**
- The 2.0.1 release includes "Document additional 2.0.1 breaking changes [#6771]" — [GitHub release v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1); [PR #6771](https://github.com/spring-projects/spring-ai/pull/6771)
- Contents of "Upgrading to 2.0.1" — all from [UN (2.0.x) #upgrading-to-2-0-1](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-1):
  - **Redis chat memory auto-config renamed:** `spring-ai-autoconfigure-model-chat-memory-redis` → `spring-ai-autoconfigure-model-chat-memory-repository-redis`. Properties move from `spring.ai.chat.memory.redis.*` to `spring.ai.chat.memory.repository.redis.*`. The old module remains as a deprecated transitive bridge ("Smooth Transition").
  - **Mistral moderation:** the model moves from `mistral-moderation-2411` to `mistral-moderation-2603`. `Categories.isDangerousAndCriminalContent()`, `Categories.Builder.dangerousAndCriminalContent(boolean)`, `CategoryScores.getDangerousAndCriminalContent()` and `CategoryScores.Builder.dangerousAndCriminalContent(double)` are deprecated. New categories: `criminal`, `dangerous`, `jailbreaking`.
  - **Mistral chat enum:** `ChatModel.MAGISTRAL_MEDIUM`, `MAGISTRAL_SMALL`, `DEVSTRAL` and `OPEN_MISTRAL_NEMO` are removed, so referencing code no longer compiles. `MISTRAL_SMALL` now points to `mistral-small-2603`, `MISTRAL_MEDIUM` to `mistral-medium-3-5`, and `MISTRAL_LARGE` is an alias for `mistral-medium-3-5`.
  - **`@McpTool` exception handling now mirrors `@Tool`:**
    - A `RuntimeException` (other than `McpError`) becomes an error `CallToolResult`.
    - A declared checked exception bubbles up as `UndeclaredThrowableException`, which is a hard failure.
    - An `Error` subtype or an `McpError` bubbles up unchanged.
    - The `toolCallExceptionClass` constructor parameter on `Sync/AsyncMcpToolMethodCallback` and `Sync/AsyncStatelessMcpToolMethodCallback`, plus `AbstractMcpToolProvider.doGetToolCallException()`, are deprecated and "will be removed in 2.1.0".
  - **Tool call limits:** new defaults `DefaultToolCallingManager.DEFAULT_MAX_CALLS_PER_TOOL` = 40 and `DEFAULT_MAX_TOTAL_TOOL_CALLS` = 150. `ToolCallingAdvisor` usage is now cumulative across the loop, so tests that assert exact token counts will see higher values.
  - **OpenAI tool strict mode:** the default changes from `strict(true)` to `false`. Opt back in with `OpenAiChatOptions.builder().strict(true)`.
  - **OpenAI audio speech:** `OpenAiAudioSpeechModel` streaming now emits real audio chunks instead of a buffered whole response.
  - **`TranscriptionModel`** now extends `StreamingTranscriptionModel`. The default `stream()` returns `Flux.error(new UnsupportedOperationException(...))`.
  - **`Media.Builder.data(Object)` removed.** Use the typed overloads `data(byte[])`, `data(String)`, `data(URI)`, `data(URL)` or `data(Resource)`.
  - **Tool resolution fallback is disabled by default.** Previously, a tool call for a tool not attached to the request fell back to the application-wide `ToolCallbackResolver` (every `ToolCallback` bean). Now only tools attached via `.tools(...)`, `.defaultTools(...)` or `ToolCallingChatOptions.toolCallbacks(...)` can execute. Re-enable with `spring.ai.tools.resolution.fallback.enabled=true` or `ToolCallingManager.builder().resolutionFallbackEnabled(true)`.

**2.0.1 → 2.0.2 (SNAPSHOT only, unreleased)**
- **Neo4j chat memory:** the stale indexes `session_conversation_id_index` and `message_index_index` are replaced by `session_id_index` (on `Session.id`) and `message_idx_index` (on `Message.idx`). Users should run `DROP INDEX session_conversation_id_index IF EXISTS; DROP INDEX message_index_index IF EXISTS;` — [UN source (2.0.x)](https://github.com/spring-projects/spring-ai/blob/2.0.x/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)

**2.0.x → 2.1.0 (milestone line; see Q7)**

### Inferences
- Treat 2.0.0 → 2.0.1 as a breaking upgrade, not a drop-in patch. Two changes are silent runtime shifts with no compile error: tool-resolution fallback now off, and OpenAI strict mode now false. Two others break compilation: the `Media.Builder.data(Object)` removal and the removed Mistral constants.
- Because the milestone notes were consolidated (#6333), the upgrade notes do not attribute each 2.0 break to the milestone that introduced it. Release notes are the only per-milestone record (see Q2 and Q3).

### Gaps
- No official "Upgrading to 1.1.0" (GA) section exists beyond RC1/TTS. The exact list of "deprecated APIs removed for 1.1.0" is not enumerated by Spring; the best enumeration is Arconia's recipe description in Q6.

---

## Q2. Platform-driven breaks: Spring Boot 4 / Spring Framework 7, Java baseline, Jackson 2 → 3, JSpecify, Jakarta

### Takeaway
2.0.x requires **Spring Boot 4.0/4.1 and Spring Framework 7.0**, while 1.1.x was built on Boot 3.5. The **Java baseline stays at 17**. Spring AI's own code moved to **Jackson 3** (`tools.jackson`), but the vendor SDKs (openai-java) still pull **Jackson 2** onto the classpath. The codebase is **fully JSpecify-annotated**, which affects Kotlin callers. Surprisingly, the official upgrade notes have **no dedicated Boot 4 or Jackson 3 section**.

### Cited Findings
- Per the GA blog, Spring AI 2.0 requires Spring Boot 4.0/4.1 and Spring Framework 7.0 (paraphrased from a fetch summary, not a verbatim quote) — [Spring blog 2.0.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- 2.0.0-M1 highlights say it was "built on **Spring Boot 4.0 and Spring Framework 7.0** … major platform upgrade from Spring Boot 3.x to Spring Boot 4.0 GA" — [GitHub release v2.0.0-M1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M1)
- 2.0.0 GA upgraded to Spring Boot 4.1.0 (#6329) and MCP SDK 2.0.0 (#6385) — [GitHub release v2.0.0](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0)
- **Published POM evidence:**
  - `spring-ai-autoconfigure-model-openai` 1.1.8 declares Spring Boot `3.5.15` — [POM 1.1.8](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-autoconfigure-model-openai/1.1.8/spring-ai-autoconfigure-model-openai-1.1.8.pom)
  - 2.0.1 declares `spring-boot-starter-webclient` `4.1.1` — [POM 2.0.1](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-autoconfigure-model-openai/2.0.1/spring-ai-autoconfigure-model-openai-2.0.1.pom)
  - 2.1.0-M1 declares `4.2.0-M2` — [POM 2.1.0-M1](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-autoconfigure-model-openai/2.1.0-M1/spring-ai-autoconfigure-model-openai-2.1.0-M1.pom)
- 2.0.0-RC2 "Restore compatibility with Spring Framework < 7.0.4" (#6334), by replacing `ResponseSpec#requiredBody` with `Objects#requireNonNull` + `ResponseSpec#body` — [Issue #6334](https://github.com/spring-projects/spring-ai/issues/6334)
- **Java baseline 17:** 2.0.0-M1 notes say "ensure you have Java 21 or higher installed [to build from source] while Java 17 is still the baseline for consuming the Spring AI 2.x binaries" — [GitHub release v2.0.0-M1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M1). Verified in the 2.0.1 jar: the manifest says `Java-Version: 17`, `Build-Jdk-Spec: 17`, and the class file major version is 61 (Java 17) — [spring-ai-model-2.0.1.jar](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-model/2.0.1/spring-ai-model-2.0.1.jar)
- **Jackson 3:**
  - 2.0.0-M3 breaking changes: "Spring AI now uses Jackson 3 (`tools.jackson` package) instead of Jackson 2 (`com.fasterxml.jackson` package) which is used by default in Spring Boot 4, if you were using previous milestone make sure to refine your Jackson dependencies accordingly." The M3 summary banner lists "Jackson 2 → Jackson 3 migration" among its breaking changes — [GitHub release v2.0.0-M3](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M3)
  - The GA blog says "JSON serialization was greatly improved by the upgrade from Jackson 2 to Jackson 3", and the docs now explain "how to customize the default `JsonMapper`" plus a new `JsonHelper` — [Spring blog 2.0.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
  - `spring-ai-commons` 2.0.1 depends on `tools.jackson.core:jackson-databind` `3.1.5` (compile scope) — [spring-ai-commons-2.0.1.pom](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-commons/2.0.1/spring-ai-commons-2.0.1.pom)
- **Jackson 2 coexistence:**
  - `spring-ai-openai` 2.0.1 depends on `com.openai:openai-java-core` `4.49.0` — [spring-ai-openai-2.0.1.pom](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-openai/2.0.1/spring-ai-openai-2.0.1.pom)
  - `openai-java-core` 4.49.0 depends on `com.fasterxml.jackson.core:jackson-core/databind/annotations` `2.18.9` and `jackson-datatype-jdk8` `2.18.9` — [openai-java-core-4.49.0.pom](https://repo1.maven.org/maven2/com/openai/openai-java-core/4.49.0/openai-java-core-4.49.0.pom)
  - 2.0.0 GA includes "Use only Jackson 2 in `OpenAiChatModel`" (#6392) — [PR #6392](https://github.com/spring-projects/spring-ai/pull/6392)
  - `spring-ai-anthropic` 2.0.1 depends on `com.anthropic:anthropic-java-core` `2.52.0` — [spring-ai-anthropic-2.0.1.pom](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-anthropic/2.0.1/spring-ai-anthropic-2.0.1.pom)
- The only upgrade-notes item that names Jackson types directly is **MCP Elicitation**: `elicit(TypeReference<T>)` and `elicit(Consumer<ElicitationSpec>, TypeReference<T>)` on `McpAsyncRequestContext` and `McpSyncRequestContext` now take `org.springframework.core.ParameterizedTypeReference<T>` instead of `tools.jackson.core.type.TypeReference<T>` — [UN (2.0.x) #upgrading-to-2-0-0](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)
- **JSON utilities (2.0.0):**
  - New `org.springframework.ai.util.JsonHelper` and `JacksonUtils.getDefaultJsonMapper()`.
  - `org.springframework.ai.util.json.JsonParser` is deprecated; `McpJsonParser` is removed.
  - `ModelOptionsUtils.JSON_MAPPER`, `jsonToMap`, `jsonToObject`, `toJsonString`, `toJsonStringPrettyPrinter`, `getJsonSchema(...)` and `toUpperCaseTypeValues` are removed. Their replacements are `JacksonUtils.getDefaultJsonMapper()`, `jsonHelper.fromJsonToMap/fromJson/toJson` and `JsonSchemaUtils.getJsonSchema/toUpperCaseTypeValues` — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)
- **Third-party Jackson 3 pitfalls (Embabel, at Spring AI 2.0.0-M8 / Boot 4.0.6, wiki edited 2026-05-30)** — all from [Embabel wiki: Spring Boot 4 / Spring AI 2.0 Migration](https://github.com/embabel/embabel-agent/wiki/Spring-Boot-4---Spring-AI-2.0-Migration):
  - Packages move from `com.fasterxml.jackson.databind.*` to `tools.jackson.databind.*`, while annotations stay at `com.fasterxml.jackson.annotation.*`.
  - Mappers are immutable: no `.registerModule(...)` after construction; use `JsonMapper.builder().addModule(...).build()`.
  - JSR-310 is built into databind, so drop `jackson-datatype-jsr310`.
  - `JsonDeserializer` → `ValueDeserializer`.
  - `Jackson2ObjectMapperBuilder` is gone.
  - Kotlin data classes need explicit primitive defaults.
- **JSpecify:** the GA blog says the codebase is "fully annotated with JSpecify annotations" to prevent NPEs and provide Kotlin-idiomatic APIs — [Spring blog 2.0.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/). Embabel's Kotlin fallout:
  - K2 treats `@NullMarked` getters strictly.
  - `Generation.getResult()` and `Message.getText()` are now nullable, so callers need `!!` or `?:`.
  - Workaround: compiler flag `-Xjspecify-annotations=ignore`.
  - Source: [Embabel wiki](https://github.com/embabel/embabel-agent/wiki/Spring-Boot-4---Spring-AI-2.0-Migration)
- **Boot 4 module splits that hit Spring AI integrators (third-party, M8 era)** — [Embabel wiki](https://github.com/embabel/embabel-agent/wiki/Spring-Boot-4---Spring-AI-2.0-Migration):
  - `JacksonAutoConfiguration` moved to `spring-boot-jackson`.
  - `ObservationRegistryCustomizer` moved to `spring-boot-micrometer-observation`.
  - `SecurityAutoConfiguration` moved to `spring-boot-security`.
  - `@AutoConfigureMockMvc` moved to `spring-boot-webmvc-test`.
  - `spring-retry` had to be added as a direct dependency because Boot 4 dropped it from its BOM.
  - Micrometer Tracing no longer kebab-cases observation names (`"ChatClient"`, not `"chat-client"`).
- **Boot 4.1 / HttpClient5 5.6 forced the OpenSearch client upgrade:** OpenSearch Java Client `2.23.0` → `3.6.0`, Testcontainers `2.0.1` → `4.1.0`, and `OpensearchContainer` renamed to `OpenSearchContainer` — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)
- **Logging:** 2.0.0-RC1 "Replace SLF4J by `org.apache.commons.logging.LogFactory`" (#6280) — [GitHub release v2.0.0-RC1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC1)

### Inferences
- An app on Spring AI 1.1.x / Boot 3.5 cannot adopt 2.0.x without first (or simultaneously) moving to Boot 4.x and Framework 7. The Spring AI upgrade is therefore effectively a Boot 4 migration project.
- Expect both Jackson 2 (from openai-java) and Jackson 3 (Spring AI and Boot 4 default) on the classpath. Any custom code that touched Spring AI's JSON utilities, or customized `ObjectMapper` for Spring AI, must move to `tools.jackson` / `JsonMapper`.
- Java 17 users do not need a JDK bump to use 2.0.x binaries.

### Gaps
- The official upgrade notes have **no dedicated "Spring Boot 4" or "Jackson 2 → 3" section**. Jackson 3 is mentioned only in the M3 release notes and indirectly through the elicitation/JSON-utilities items.
- **Jakarta:** I found no Spring AI-specific Jakarta namespace changes for 1.x → 2.0. 1.x was already on the Jakarta namespace via Boot 3.
- The spring-ai-parent POM properties (java.version, spring-boot.version) could not be grepped (flattened POM). Versions were taken from dependent POMs instead.

---

## Q3. API breaks: classes/methods, ChatOptions, ToolCallback, ChatMemory, VectorStore, advisors, MCP

### Takeaway
The biggest 2.0 API shifts are:
1. **Tool execution moved out of `ChatModel`** into an auto-registered `ToolCallingAdvisor` in `ChatClient`.
2. **Immutable, builder-only options.** `ChatClient.options()` now takes a *builder*; setters, `copy()` and `fromOptions()` are gone.
3. **Chat memory requires an explicit conversation ID**, `PromptChatMemoryAdvisor` is removed, and the memory advisor precedence changed.
4. **The deprecated `FunctionCallback` API is gone.**
5. **MCP SDK 2.0**, with packages relocated into `org.springframework.ai.mcp.*`.
6. **The Anthropic and OpenAI modules were rebuilt on the official vendor SDKs**, removing the hand-rolled `*Api` classes.

### Cited Findings
**Tool calling (2.0.0)** — all from [UN (2.0.x) #tool-calling-1x-to-2x](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0):
- **The internal tool execution loop is removed from every `ChatModel`** (OpenAI, Anthropic, Ollama, DeepSeek, Bedrock, MiniMax, Mistral, Google GenAI). `ChatModel.call/stream` return raw tool-call requests. The fix is to use `ChatClient` (auto `ToolCallingAdvisor`) or drive the loop manually with `ToolCallingManager`.
- `ToolCallingChatOptions.internalToolExecutionEnabled()` and the property `spring.ai.<provider>.chat.internal-tool-execution-enabled` are removed.
- `ToolExecutionEligibilityPredicate` and `DefaultToolExecutionEligibilityPredicate` are removed. They are replaced by `ToolExecutionEligibilityChecker` (a `Function<ChatResponse, Boolean>`) set on `ToolCallingAdvisor.Builder` or provided as a bean.
- `streamToolCallResponses` is removed from `ToolCallingAdvisor.Builder`, `ToolCallAdvisor.Builder` and `ToolSearchToolCallingAdvisor.Builder`, along with the properties `spring.ai.chat.client.tool-calling.stream-tool-call-responses` and `spring.ai.chat.client.tool-search-advisor.stream-tool-call-responses`.
- `SpringBeanToolCallbackResolver` and the `toolNames()` API on chat options and `ChatClient` are removed. Tools must be explicit `ToolCallback`s or `@Tool` objects.
- `ToolCallAdvisor` is renamed to `ToolCallingAdvisor`. RC1 notes say this was done "with backward-compat shims" — [GitHub release v2.0.0-RC1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC1)
- **The deprecated FunctionCallback API is removed:**
  - `FunctionCallback` → `ToolCallback`
  - `FunctionCallback.builder().function(name, fn)` → `FunctionToolCallback.builder(name, fn)`
  - `FunctionCallback.builder().method(...)` → `MethodToolCallback.builder()`
  - `FunctionCallingOptions` → `ToolCallingChatOptions`
  - `.functionCallbacks(...)` → `.toolCallbacks(...)`
  - `ChatClient.Builder#defaultFunctions` → `defaultTools`
  - `prompt().functions` → `prompt().tools`
- **ChatClient tool calling:**
  - `ChatClient` always auto-registers `ToolCallingAdvisor`, so an explicit registration produces a duplicate. Opt out with `AdvisorParams.toolCallingAdvisorAutoRegister(false)`, or pass a `ToolCallingAdvisor.Builder` through the new 5-arg `ChatClient.builder(chatModel, observationRegistry, null, null, toolCallingAdvisorBuilder)`.
  - New marker interfaces `ToolAdvisor` and `MemoryAdvisor`.
  - `tools(Consumer<ToolSpec>)`, `defaultTools(Consumer<ToolSpec>)` and the `ChatClient.ToolSpec` interface are removed. Use `tools(Object...)` plus `toolContext()` / `defaultToolContext()`.
  - `MethodToolCallbackProvider` now throws `IllegalArgumentException` instead of `IllegalStateException` (no `@Tool` methods, or duplicate names).
- New property `spring.ai.chat.client.tool-calling.enabled` (default `true`).
- **`ToolContext`:** `ToolContext.TOOL_CALL_HISTORY` and `getToolCallHistory()` are removed, and conversation history is no longer injected. History is managed by `ToolCallingAdvisor.conversationHistoryEnabled(...)`. `disableMemory()` was renamed to `disableInternalConversationHistory()` (M3, deprecated shim) — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0); [GitHub release v2.0.0-M3](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M3)

**Options (2.0.0)** — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0):
- Options are strictly immutable: `ChatOptions#copy()` and `*Options#fromOptions(*Options)` are removed, and collections are unmodifiable (`UnsupportedOperationException`). Use `.mutate()...build()`.
- `ChatModel#getDefaultOptions()` is deprecated in favor of `ChatModel#getOptions()`. Defaults moved into options constructors and were removed from `*Properties`.
- `N()` → `n()` in options and properties builders.
- **`ChatClient.prompt().options(...)` and `defaultOptions(...)` now take `ChatOptions.Builder`**, enforced by the generic constraint `<B extends ChatOptions.Builder<?>>`. A built `ChatOptions` no longer compiles. `ChatModel.call(Prompt)` still takes a fully built options object and does **not** merge. `buildRequestPrompt` was removed from the public API.
- Setters were removed from all provider options classes across milestones: OpenAI, Mistral, MiniMax, Google GenAI, ElevenLabs, Bedrock, DeepSeek, Anthropic and Ollama in M6; builder pattern adoption for DeepSeek, Bedrock, Mistral, Azure OpenAI and Anthropic options in M3. `OpenAiConnectionProperties` was renamed to `OpenAiCommonProperties`, and OpenAI `*Properties` no longer extend `AbstractOpenAiOptions` (M6) — [GitHub release v2.0.0-M6](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M6); [GitHub release v2.0.0-M3](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M3)
- `ModelOptionsUtils.merge()` was removed from non-chat models and from several chat models. ChatClient-level options merging moved to the builder `combineWith()` (M5) — [GitHub release v2.0.0-M5](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M5)
- `internalCall` and `internalStream` on all model classes are now `private`, so call `call()` / `stream()` instead — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)

**Chat memory (2.0.0)** — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0):
- `ChatMemory.DEFAULT_CONVERSATION_ID` is removed. A conversation ID is mandatory for `MessageChatMemoryAdvisor` and `VectorStoreChatMemoryAdvisor`, and the advisor-level `.conversationId()` builder method is removed. `BaseChatMemoryAdvisor.getConversationId(Map, String)` becomes `getConversationId(Map)`.
- `PromptChatMemoryAdvisor` is removed. Use `MessageChatMemoryAdvisor`.
- `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER` changes from `HIGHEST_PRECEDENCE + 1000` to `HIGHEST_PRECEDENCE + 200`, which places memory *outside* `ToolCallingAdvisor` (`+300`). The memory advisor stores only the final user/assistant exchange. To keep memory inside the loop, set the order above `ToolCallingAdvisor.DEFAULT_ORDER` and call `.disableInternalConversationHistory()`.
- **JDBC schema change:** a new `sequence_id BIGINT` column (`NUMBER(19)` on Oracle, `INTEGER` on SQLite) and an index `SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX`. Ordering now uses `sequence_id`. Tables created by 1.x **require** the new column; a backfill SQL using `ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY "timestamp")` is provided. Retrieved messages now carry a `CONVERSATION_TS` metadata entry, so `Message.equals` against code-built messages changes.
- `MongoChatMemoryRepository` now returns messages oldest-to-newest. Remove any reversal workarounds.
- 2.0.0 GA also filters unsupported tool messages in the Cassandra, Mongo and JDBC chat memory repositories (#6398–#6400) — [GitHub release v2.0.0](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0)

**VectorStore / RAG (2.0.0)**
- `AbstractFilterExpressionConverter.doSingleValue(Object, StringBuilder)` is now abstract. Custom converters must implement it, using the helpers `emitJsonValue` and `emitLuceneString` — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)
- M2 package moves: `SemanticCache` relocated from the Redis-specific package to a generic package; Couchbase vector store classes moved to a new package — [GitHub release v2.0.0-M2](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M2)

**Structured output (2.0.0)**
- `BeanOutputConverter` delegates to `JsonSchemaGenerator`:
  - Kotlin optional properties and `@JsonProperty(required = false)` properties are no longer `required`.
  - Schemas include OpenAPI `format` hints.
  - `postProcessSchema(JsonNode)` is removed; override `generateSchema()` instead.
  - Source: [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)

**Provider modules (2.0.0)** — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0):
- **Anthropic is rebuilt on `com.anthropic:anthropic-java`:**
  - `org.springframework.ai.anthropic.api.AnthropicApi` and all nested records are removed, as is the `AnthropicChatModel(AnthropicApi, ...)` constructor. Use `AnthropicChatModel.builder().apiKey(...).defaultOptions(...)`.
  - `AnthropicCacheOptions`, `AnthropicCacheStrategy`, `AnthropicCacheTtl`, `CacheBreakpointTracker` and `CacheEligibilityResolver` move from `...anthropic.api(.utils)` to `org.springframework.ai.anthropic`.
  - `AnthropicCacheType`, `StreamHelper` and `metadata.AnthropicRateLimit` are removed.
  - `CitationDocument` is renamed to `AnthropicCitationDocument`.
  - **The default `maxTokens` goes from 500 to 4096.**
  - OkHttp is now a transitive dependency.
- **OpenAI uses the official `openai-java` SDK** for all OpenAI models. `spring.ai.openai.*` properties are preserved, and `extraBody` maps to `additionalBodyProperties`.
- Embabel (M8) reports the hand-rolled `OpenAiApi` and `OpenAiModerationApi` are deleted, with builders now taking `.openAiClient(OpenAIClient)` and `.anthropicClient(AnthropicClient)`. It also reports `completionsPath`/`embeddingsPath` are no longer honored (put the full path in `baseUrl`) and that model builders changed `.defaultOptions(ChatOptions)` → `.options(ChatOptions)` — [Embabel wiki](https://github.com/embabel/embabel-agent/wiki/Spring-Boot-4---Spring-AI-2.0-Migration). **Conflict:** the official Anthropic migration snippet still shows `AnthropicChatModel.builder().apiKey(apiKey).defaultOptions(options)` — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0). M7 also had a doc issue "Reference document still refer to removed `OpenAiApi`" (#6075) — [GitHub release v2.0.0-M7](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M7)
- Claude 3 Opus, Sonnet and Haiku model constants were removed in favor of Claude 4.x (M3). The OpenAI default chat model changed to `gpt-5-mini` (M1) — [GitHub release v2.0.0-M3](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M3); [GitHub release v2.0.0-M1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M1)
- **Default temperature removed:** Spring AI no longer sets 0.7 by default, so provider defaults apply. Set it explicitly to preserve the old behavior — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)
- `GoogleGenAiEmbeddingConnectionDetails` moved from `org.springframework.ai.google.genai` to `org.springframework.ai.google.genai.embedding` — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)
- `Usage` gained `getCacheReadInputTokens()` and `getCacheWriteInputTokens()` default methods, which return `null` if unsupported — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)

**MCP (2.0.0)** — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0):
- The MCP Java SDK goes from **1.1.x to 2.0.0**. The `spring-ai-mcp` 2.0.1 POM depends on `io.modelcontextprotocol.sdk:mcp:2.0.0` — [spring-ai-mcp-2.0.1.pom](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-mcp/2.0.1/spring-ai-mcp-2.0.1.pom)
- Server-side tool input validation is **on by default**. Disable it with `validateToolInputs(false)` on the MCP server builder. The raw upgrade notes name only this builder switch; a `spring.ai.tools.validation.enabled` property appeared only in a summarizer output and is **unverified**.
- `McpSchema.Tool.inputSchema()` and `outputSchema()` now return `Map<String,Object>` instead of `JsonSchema`.
- `Builder.customizeRequest()` is removed from `HttpClientSseClientTransport` and `HttpClientStreamableHttpTransport`. Use `httpRequestCustomizer()` / `asyncHttpRequestCustomizer()`.
- `McpSchema.JSONRPCMessage`, `Request`, `Result`, `Notification`, `ResourceContents`, `CompleteReference` and `Content` are no longer sealed, so `switch` expressions need a `default` branch.
- Required fields: `CreateMessageResult.builder(Role, response, modelHint)` (`model` is required) and `CreateMessageRequest.builder(messages, maxTokens)`. Constructors such as `new TextContent(...)`, `new ReadResourceResult(...)`, `new GetPromptResult(...)` and `new ProgressNotification(...)` are deprecated in favor of builders.
- **MCP annotations** move from `org.springaicommunity.mcp.*` (external `org.springaicommunity:mcp-annotations`) to `org.springframework.ai.mcp.annotation.*` in `spring-ai-mcp-annotations`. Sub-packages `method`, `provider` and `context` move under `...mcp.annotation.*`.
- **Spring MCP transports** move from `io.modelcontextprotocol.sdk:mcp-spring-webflux/webmvc` to the `org.springframework.ai` groupId:
  - Server transport classes move to `org.springframework.ai.mcp.server.webflux.transport` / `webmvc.transport`: `WebFluxSseServerTransportProvider`, `WebFluxStreamableServerTransportProvider`, `WebFluxStatelessServerTransport` and the WebMvc equivalents.
  - Client transport classes move to `org.springframework.ai.mcp.client.webflux.transport`: `WebFluxSseClientTransport`, `WebClientStreamableHttpTransport`.
- `McpAsyncClientCustomizer` and `McpSyncClientCustomizer` are removed in favor of `McpClientCustomizer<B>`. SDK-level `McpSync/AsyncHttpClientRequestCustomizer` beans are no longer applied (note: 2.0.1 "Apply MCP HTTP client request customizer beans" #6716 — [GitHub release v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1)).
- WebMvc transports pass **lowercased** header names to `ServerTransportSecurityValidator.validateHeaders`.
- `McpSchema.Resource` constructor gained `title`, `size` and `meta` parameters (third-party, M8) — [Embabel wiki](https://github.com/embabel/embabel-agent/wiki/Spring-Boot-4---Spring-AI-2.0-Migration)
- M7: "Deprecate SSE transports, set Streamable HTTP as default server protocol" (#5969) — [GitHub release v2.0.0-M7](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M7); [PR #5969](https://github.com/spring-projects/spring-ai/pull/5969)

**Observability (2.0.0)**
- The tool span name changes from `tool_call <name>` to `execute_tool <name>`, and `gen_ai.operation.name` from `framework` to `execute_tool`. New attributes are `spring.ai.tool.type` and `spring.ai.tool.call.id` — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)

### Inferences
- The most widespread compile-time breaks for typical apps are:
  - `ChatClient.options(builtOptions)` → `.options(builder)`
  - removal of options setters and `copy()`
  - FunctionCallback API removal
  - memory advisor `conversationId` builder removal
  - the `Media.Builder.data(Object)` removal (2.0.1)
- The most dangerous silent runtime changes are:
  - `ChatModel.call()` no longer executing tools
  - default temperature removal
  - Anthropic `maxTokens` 500 → 4096 (cost)
  - MCP server input validation on by default
  - SSE → Streamable HTTP default
  - memory precedence and order changes
  - tool-resolution fallback off (2.0.1)
  - OpenAI strict false (2.0.1)
- Because MCP Streamable HTTP is now the default server protocol, MCP clients that still connect over SSE should be checked after upgrading a server. This is inferred from #5969; exact property-level defaults were not verified.

### Gaps
- I did not fully verify whether model builders' `.defaultOptions(...)` was renamed to `.options(...)` across all providers, or whether both exist. The official notes and Embabel conflict (see above).
- I did not get the exact property or config change for the SSE → Streamable HTTP default (#5969), nor the M8 "dash-separated convention for Spring Boot properties" (#6186) impact — [PR #6186](https://github.com/spring-projects/spring-ai/pull/6186). Relaxed binding may make the latter transparent, but this is unverified.

---

## Q4. Artifact/starter renames, removed modules or providers, moved modules

### Takeaway
2.0 drops or relocates a large set of modules:
- **Removed:** Azure OpenAI, OpenAI-SDK, MiniMax, ZhiPu AI, HuggingFace, Vertex AI Gemini, SAP HANA, Spring Cloud Bindings.
- **Moved out:** OCI GenAI (to Oracle) and Azure Cosmos DB (to the Azure Cosmos DB team).
- **Renamed:** `spring-ai-advisors-vector-store` → `spring-ai-vector-store-advisor`.
- **Moved in:** MCP Spring transports (to the `org.springframework.ai` groupId) and MCP annotations (from spring-ai-community).

The 1.0-era starter naming (`spring-ai-starter-model-*`) is unchanged in 2.0.

### Cited Findings
- **The BOM diff (1.1.8 vs 2.0.1) shows these artifacts present in 1.1.8 and absent from 2.0.1:**
  - `spring-ai-advisors-vector-store`
  - `spring-ai-azure-openai`, `spring-ai-autoconfigure-model-azure-openai`, `spring-ai-starter-model-azure-openai`
  - `spring-ai-openai-sdk`, `spring-ai-autoconfigure-model-openai-sdk`, `spring-ai-starter-model-openai-sdk`
  - `spring-ai-minimax`, `spring-ai-autoconfigure-model-minimax`, `spring-ai-starter-model-minimax`
  - `spring-ai-zhipuai`, `spring-ai-autoconfigure-model-zhipuai`, `spring-ai-starter-model-zhipuai`
  - `spring-ai-huggingface`, `spring-ai-autoconfigure-model-huggingface`, `spring-ai-starter-model-huggingface`
  - `spring-ai-oci-genai`, `spring-ai-autoconfigure-model-oci-genai`, `spring-ai-starter-model-oci-genai`
  - `spring-ai-vertex-ai-gemini`, `spring-ai-starter-model-vertex-ai-gemini`
  - `spring-ai-azure-cosmos-db-store`, `spring-ai-autoconfigure-vector-store-azure-cosmos-db`, `spring-ai-starter-vector-store-azure-cosmos-db`
  - `spring-ai-model-chat-memory-repository-cosmos-db` and its autoconfigure/starter
  - `spring-ai-hanadb-store`
  - `spring-ai-spring-cloud-bindings`
  - `spring-ai-model-chat-memory`
  - `spring-ai-starter-mcp-server-common`
  - Sources: [BOM 1.1.8](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/1.1.8/spring-ai-bom-1.1.8.pom); [BOM 2.0.1](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/2.0.1/spring-ai-bom-2.0.1.pom)
- **New in the 2.0.1 BOM:**
  - `mcp-spring-webflux` and `mcp-spring-webmvc` (now `org.springframework.ai`)
  - `spring-ai-vector-store-advisor`
  - `spring-ai-tool-search-advisor`, `spring-ai-tool-search-tool(-advisor/-lucene/-vectorstore)`, `spring-ai-autoconfigure-tool-search-advisor`, `spring-ai-starter-tool-search-advisor`
  - `spring-ai-autoconfigure-model-chat-memory-repository-redis`
  - `spring-ai-google-genai-image` and its starter
  - `spring-ai-s3-vector-store` and its autoconfigure/starter
  - `spring-ai-bedrock-knowledgebase-store` and its autoconfigure/starter
  - `spring-ai-redis-semantic-cache` and its autoconfigure
  - Source: [BOM 2.0.1](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/2.0.1/spring-ai-bom-2.0.1.pom)
- The 2.0.1 BOM still lists the deprecated `spring-ai-autoconfigure-model-chat-memory-redis` alongside the new `-repository-redis` — [BOM 2.0.1](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/2.0.1/spring-ai-bom-2.0.1.pom)
- The 2.0.1 and 2.1.0-M1 BOMs have identical artifact lists (169 artifacts each); 1.1.8 has 179 — [BOM 2.1.0-M1](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/2.1.0-M1/spring-ai-bom-2.1.0-M1.pom)
- **Official rationale and destinations** — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0):
  - `spring-ai-azure-openai` users should "use the `spring-ai-openai` module … and adapt class names (by removing the `Azure` prefix for example) and configuration properties accordingly".
  - `spring-ai-openai-sdk` users should use `spring-ai-openai` and remove the `Sdk` suffix from class names.
  - **OCI GenAI** moved to [github.com/oracle/spring-cloud-oracle/tree/main/spring-ai-oracle](https://github.com/oracle/spring-cloud-oracle/tree/main/spring-ai-oracle).
  - **Azure Cosmos DB** (vector store and chat memory) is "now available as an external module maintained by the Azure Cosmos DB team" — see [azurecosmosdb.github.io/spring-ai](https://azurecosmosdb.github.io/spring-ai/docs/index.html).
  - **MiniMax:** use Anthropic support with base URL `https://api.minimax.io/anthropic`. MiniMax embeddings are no longer supported.
  - `spring-ai-hanadb-store` and `spring-ai-spring-cloud-bindings` are removed.
  - `spring-ai-advisors-vector-store` is renamed to `spring-ai-vector-store-advisor`.
- The GA blog summarizes the consolidation: OpenAI "went from 3 variants (Azure, HTTP, SDK) to 1 (SDK)", Anthropic "from 2 variants (HTTP, SDK) to 1 (SDK)", and Google GenAI "from 2 implementations (GenAI SDK, Vertex) to 1 (GenAI SDK)" — [Spring blog 2.0.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- **Milestone release notes:**
  - M5 removed Vertex AI chat and non-embedding modules (only `spring-ai-vertex-ai-embedding` remains), removed ZhipuAI, removed OCI GenAI and removed `SpringAiTestAutoConfigurations`. The MCP JSON dependency was removed from `spring-ai-client-chat`, so add it explicitly if needed — [GitHub release v2.0.0-M5](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M5)
  - M3: "Huggingface model and autoconfiguration have been moved out of the main Spring AI repository" — [GitHub release v2.0.0-M3](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M3)
  - M6 removed the SAP HANA DB and Infinispan vector store modules — [GitHub release v2.0.0-M6](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M6)
  - M4 deprecated Vertex AI, ZhiPu AI and OCI GenAI before their removal — [GitHub release v2.0.0-M4](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M4)
- **Development-time services:** MongoDB Atlas Docker Compose/Testcontainers support is now native in Spring Boot, so `org.springframework.ai:spring-ai-spring-boot-testcontainers` is no longer needed for it; `spring-boot-testcontainers` suffices — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)
- **Historical (1.0.0-M7 to RC1) starter rename pattern, still in effect in 2.0:**
  - `spring-ai-{model}-spring-boot-starter` → `spring-ai-starter-model-{model}`
  - `spring-ai-model-chat-memory-*` → `spring-ai-model-chat-memory-repository-*` (1.0.0-RC1)
  - Source: [UN (2.0.x) Common Changes / 1.0.0-RC1](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)
- **2.0.1:** Redis chat memory autoconfigure artifact renamed (see Q1) — [UN (2.0.x) #upgrading-to-2-0-1](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-1)

### Inferences
- The disappearance of `spring-ai-model-chat-memory` and `spring-ai-starter-mcp-server-common` from the 2.0.1 BOM is not described in the upgrade notes I read. This is a BOM-diff observation; users declaring them explicitly will get unresolved-version build errors.
- HuggingFace and ZhipuAI have no documented official replacement. Release notes only say "moved out" / "check for alternative community-maintained modules".

### Gaps
- I did not find the exact property-name mapping from `spring.ai.azure.openai.*` to the `spring.ai.openai.*` Azure-deployment configuration. The official notes only say to "adapt configuration properties accordingly". Oddly, the Default-Temperature section of the same 2.0.0 notes still shows `spring.ai.azure.openai.chat.temperature=0.7` as an example — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0).
- I did not confirm the new home or coordinates for HuggingFace or ZhipuAI integrations, or for Infinispan.

---

## Q5. Configuration property renames and removals

### Takeaway
The main property changes are:
- removal of the `.options` segment for non-chat models (deprecated aliases are provided)
- removal of `internal-tool-execution-enabled` and `stream-tool-call-responses`
- Ollama `think-option` → `think`
- the Redis chat memory prefix rename (2.0.1)
- removal of the 0.7 temperature default
- new switches for tool calling, tool search and tool-resolution fallback

### Cited Findings
- **`.options` flattening (2.0.0):** for Embedding, Image, Audio, Moderation, OCR and similar models, e.g. `spring.ai.openai.embedding.options.model` → `spring.ai.openai.embedding.model`. "Deprecated configuration properties are provided for the `.options` variants". `*Properties#getOptions()` and the nested `Options` classes are deprecated, and `toOptions()` moves to the root `*Properties` — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0). The GA blog phrases it as "Removed artificial `.options` segment in application property keys" — [Spring blog 2.0.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- **Removed properties:**
  - `spring.ai.<provider>.chat.internal-tool-execution-enabled`
  - `spring.ai.chat.client.tool-calling.stream-tool-call-responses`
  - `spring.ai.chat.client.tool-search-advisor.stream-tool-call-responses`
  - Source: [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)
- **Renamed:** `spring.ai.ollama.chat.think-option` → `spring.ai.ollama.chat.think`. RC2 also fixed "Binding failure for `spring.ai.ollama.chat.think`" (#4853) — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0); [GitHub release v2.0.0-RC2](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC2)
- **New properties (2.0.0):**
  - `spring.ai.chat.client.tool-calling.enabled` (default `true`)
  - `spring.ai.chat.client.tool-search-advisor.enabled=true` and `spring.ai.chat.client.tool-search-advisor.tool-index-type` = `regex` (default, no extra dependencies), `lucene` (requires `org.apache.lucene:lucene-core`) or `vector` (requires a `VectorStore` bean). This requires the opt-in starter `spring-ai-starter-tool-search-advisor`, and when enabled `ToolSearchToolCallingAdvisor` replaces the default `ToolCallingAdvisor` in the auto-configured `ChatClient`.
  - Source: [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)
- **2.0.1:**
  - `spring.ai.chat.memory.redis.*` → `spring.ai.chat.memory.repository.redis.*`
  - new `spring.ai.tools.resolution.fallback.enabled` (default `false`)
  - Source: [UN (2.0.x) #upgrading-to-2-0-1](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-1)
- **Defaults removed:** the default `temperature` 0.7 is gone from chat model autoconfiguration properties (M1). Default values in general were removed from `*Properties` and moved to options constructors. The OpenAI default chat model is now `gpt-5-mini` (M1) — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0); [GitHub release v2.0.0-M1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M1)
- **M6:**
  - `@NestedConfigurationProperty` was removed from OpenAI options fields, which may affect IDE completion and metadata.
  - "Anthropic configuration properties have been refactored".
  - Tanzu `TanzuBindingsPropertiesProcessor` was updated for the new OpenAI property structure.
  - `OpenAiEmbeddingOptions#encodingFormat` changed from String to an enum.
  - Source: [GitHub release v2.0.0-M6](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M6)
- **M8:** "Use dash-separated convention for Spring Boot properties" (#6186) — [GitHub release v2.0.0-M8](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M8)
- **2.1.0-M1:** `BedrockConverseProxyChatProperties.requestParameters` widened to `Map<String,Object>`, so YAML can nest values (e.g. `spring.ai.bedrock.converse.chat.request-parameters.output_config.effort: low`). Map keys bind literally — [UN 2.1 #upgrading-to-2-1-0](https://docs.spring.io/spring-ai/reference/2.1/upgrade-notes.html#upgrading-to-2-1-0)
- **1.1 (Arconia's recipe, not Spring docs):** these deprecated Ollama chat option properties are flagged as no longer supported: `use-n-u-m-a`, `low-v-r-a-m`, `f16-k-v`, `logits-all`, `vocab-only`, `use-m-lock`, `tfs-z`, `mirostat`, `mirostat-tau`, `mirostat-eta`, `penalize-newline` — [Arconia: Spring AI 1.1](https://docs.arconia.io/arconia-migrations/latest/spring/spring-ai/1-1/)

### Inferences
- Because `.options` aliases are deprecated rather than removed in 2.0, property flattening is a deprecation warning in 2.0.x rather than a hard break. Expect removal in a later minor release (not confirmed).

### Gaps
- I found no complete official table of the refactored **Anthropic** property names (M6) or the Azure OpenAI → OpenAI property mapping.
- The impact of #6186 (dash-separated convention) on existing keys is unverified.

---

## Q6. Automated migration tooling (OpenRewrite, Spring Boot Migrator, scripts)

### Takeaway
There are two OpenRewrite options:
- **Official (Spring), narrow, Maven-only:** `migrate-to-2-0-0-M3.yaml`, which covers only MCP annotations, MCP transports and the MCP client customizer.
- **Community (Arconia), broad, Maven/Gradle/CLI:** `io.arconia.rewrite.spring.ai2.UpgradeSpringAi_2_0` for 2.0, plus `...ai1.UpgradeSpringAi_1_1` for 1.1.

docs.openrewrite.org has no Spring AI recipe of its own, and I found no Spring Boot Migrator support. Boot 4 / Jackson 3 must be migrated with separate Boot/Framework recipes.

### Cited Findings
- **Official recipe file:** `src/rewrite/migrate-to-2-0-0-M3.yaml` is the only file in `src/rewrite` on both `main` and `2.0.x`. The umbrella recipe is `org.springframework.ai.migration.MigrateToSpringAI200M3` ("Migrate to Spring AI 2.0.0-M3"), composed of `M3MigrateMcpAnnotations`, `M3MigrateMcpSpringTransports` and `M3MigrateMcpClientCustomizer` — [migrate-to-2-0-0-M3.yaml (2.0.x)](https://github.com/spring-projects/spring-ai/blob/2.0.x/src/rewrite/migrate-to-2-0-0-M3.yaml)
- The `M3MigrateMcpAnnotations` recipe removes the `org.springaicommunity:mcp-annotations` Maven dependency. It then uses text `FindAndReplace` on `**/*.java` for `org.springaicommunity.mcp.{method,provider,context,annotation}.` → `org.springframework.ai.mcp.annotation.…` — [migrate-to-2-0-0-M3.yaml](https://github.com/spring-projects/spring-ai/blob/2.0.x/src/rewrite/migrate-to-2-0-0-M3.yaml)
- **Official command, from the upgrade notes:**
  - `mvn org.openrewrite.maven:rewrite-maven-plugin:6.32.0:run -Drewrite.configLocation=https://raw.githubusercontent.com/spring-projects/spring-ai/refs/heads/main/src/rewrite/migrate-to-2-0-0-M3.yaml -Drewrite.activeRecipes=org.springframework.ai.migration.<Recipe> -Dmaven.compiler.failOnError=false`
  - **Caveat:** if `io.modelcontextprotocol.sdk:mcp-spring-webflux/webmvc` is declared without an explicit `<version>` (BOM-managed), "Maven will refuse to parse the `pom.xml` and the recipe will never run". A `perl` pre-patch of the groupIds is provided.
  - The page refers to a `<<run-all-m3-migrations>>` anchor that does not exist in the source (dead xref).
  - Source: [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0); [UN source (main)](https://github.com/spring-projects/spring-ai/blob/main/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)
- Broadcom's KB describes the same recipe as automating "most of the required code and dependency updates", using a Maven-only command — [Broadcom KB 432597](https://knowledge.broadcom.com/external/article/432597/migrating-to-spring-ai-200m3.html)
- **Arconia Migrations** "ships an upgrade recipe for each minor release of Spring AI" (1.0, 1.1, 2.0) — [Arconia: Spring AI](https://docs.arconia.io/arconia-migrations/latest/spring/spring-ai/)
- **Arconia 2.0 recipe `io.arconia.rewrite.spring.ai2.UpgradeSpringAi_2_0`** — all from [Arconia: Spring AI 2.0](https://docs.arconia.io/arconia-migrations/latest/spring/spring-ai/2-0/):
  - Dependencies: upgrades `org.springframework.ai` dependencies to 2.0.x; removes `spring-ai-hanadb-store`, `spring-ai-spring-cloud-bindings` and the Azure OpenAI artifacts; renames `spring-ai-advisors-vector-store` → `spring-ai-vector-store-advisor`; adopts `spring-ai-mcp-annotations`.
  - Code moves: relocates Anthropic types out of `.api` and the MCP community packages; migrates OpenAI model constants to `com.openai.models.*`; relocates `GoogleGenAiEmbeddingConnectionDetails`.
  - Properties: flattens `.options.`, renames `think-option` → `think`, and comments out removed provider properties.
  - Methods: `ChatClient toolCallbacks(...)` → `tools(...)`, `getDefaultOptions()` → `getOptions()`, `JsonParser.getObjectMapper()` → `getJsonMapper()`.
  - Manual step: replacing the `AnthropicChatModel(AnthropicApi, ...)` constructor.
  - Commands: `arconia update spring-ai --to-version 2.0`; Gradle `./gradlew rewriteRun --init-script init.gradle --no-parallel -DactiveRecipe=io.arconia.rewrite.spring.ai2.UpgradeSpringAi_2_0`; Maven `./mvnw -U org.openrewrite.maven:rewrite-maven-plugin:run -Drewrite.recipeArtifactCoordinates=io.arconia.migrations:rewrite-spring:LATEST -Drewrite.activeRecipes=io.arconia.rewrite.spring.ai2.UpgradeSpringAi_2_0`.
  - The page does not mention Boot 4 or Jackson 3.
- **Arconia 1.1 recipe `io.arconia.rewrite.spring.ai1.UpgradeSpringAi_1_1`:**
  - flags the deprecated Ollama properties
  - adopts new builders for `AssistantMessage`, `EmbeddingOptions`, `QuestionAnswerAdvisor` and `ToolResponseMessage`
  - `BedrockTitanEmbeddingOptions.Builder.withInputType(...)` → `inputType(...)`
  - the TTS package/type renames
  - MCP autoconfigure dependencies renamed with a `-common` suffix plus transport-specific autoconfigure deps; `McpWebFluxServerAutoConfiguration` / `McpWebMvcServerAutoConfiguration` renamed to SSE variants
  - Source: [Arconia: Spring AI 1.1](https://docs.arconia.io/arconia-migrations/latest/spring/spring-ai/1-1/)
- **Historical:** the official upgrade notes pointed to Arconia's recipes for 1.0.0-M8 and 1.0.0-RC1, and offered a Claude Code prompt (`src/prompts/update-to-m7.txt`) for 1.0.0-M7 — [UN (2.0.x) #upgrading-to-1-0-0-RC1](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)
- **docs.openrewrite.org:** search surfaced "Migrate to Spring Framework 7.0" (`UpgradeSpringFramework_7_0`), relevant for the platform step — [OpenRewrite: Migrate to Spring Framework 7.0](https://docs.openrewrite.org/recipes/java/spring/framework/upgradespringframework_7_0). The only "spring-ai" hit was an unrelated Apache Camel URI recipe — [OpenRewrite Camel recipe](https://docs.openrewrite.org/recipes/apache/camel/upgrade/camel422/migratespringaitoolsuris)

### Inferences
- A practical sequence for 1.1.x users:
  1. Run the Boot 4 / Framework 7 / Jackson 3 migration recipes (OpenRewrite or Moderne).
  2. Run Arconia `UpgradeSpringAi_2_0`.
  3. Run the official MCP M3 recipe if the project uses MCP and Arconia didn't cover transports or customizers.
  4. Manually apply what no recipe covers: `ChatClient.options(builder)`, memory `conversationId`, tool loop changes, JDBC `sequence_id` DDL, Anthropic constructor, the 2.0.1 deltas.
- Neither recipe set documents coverage of the 2.0.1 patch-level breaks (`Media.Builder.data(Object)`, Mistral constants, Redis memory rename).

### Gaps
- **Spring Boot Migrator:** I found no evidence of Spring AI-specific support.
- I could not verify whether Arconia's 2.0 recipe covers the `ChatClient.options()` builder change, removed setters, FunctionCallback → ToolCallback, or 2.0.1 changes. The documentation summary did not list them.
- I did not check Moderne's catalog for Spring AI recipes.

---

## Q7. Breaking changes already announced for the next line (2.1.0, currently 2.1.0-M1 milestone)

### Takeaway
2.1.0-M1 (milestone, 2026-09-24/25, Spring Boot 4.2.0-M2) introduces ordered `MessagePart`s on messages and changes Anthropic thinking output to `ReasoningPart`s in a single `Generation`. It also widens Bedrock `requestParameters` to `Map<String,Object>` and preserves user `distance` metadata in PgVector. Several 2.0.x deprecations are scheduled for removal in 2.1.0, and `ChatMemory` is "planned to be replaced" by spring-ai-session in 2.1.

### Cited Findings
- 2.1.0-M1 new features are OpenAI Responses API support (#7006), `VectorStore.upsert` (#7002) and `MessagePart` in `AbstractMessage` (#6997). It upgrades Spring Boot to 4.2 (#7003) — [GitHub release v2.1.0-M1](https://github.com/spring-projects/spring-ai/releases/tag/v2.1.0-M1). The Boot dependency in 2.1.0-M1 POMs is `4.2.0-M2` — [POM 2.1.0-M1](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-autoconfigure-model-openai/2.1.0-M1/spring-ai-autoconfigure-model-openai-2.1.0-M1.pom)
- **"Upgrading to 2.1.0" message parts** — [UN 2.1 #upgrading-to-2-1-0](https://docs.spring.io/spring-ai/reference/2.1/upgrade-notes.html#upgrading-to-2-1-0):
  - `AssistantMessage` and `UserMessage` hold an ordered list of parts (`TextPart`, `ReasoningPart`, `ToolCallPart`, `ToolResultPart`, `MediaPart`, `UnknownPart`) via `getParts()`.
  - `getMedia()` and `getToolCalls()` now return **unmodifiable** lists.
  - `equals`/`hashCode` now include parts and media, and `Media` gained value equality.
  - A message with parts but no text part has empty text.
  - Deprecated for one release: the protected `textContent`, `media` and `responses` fields.
  - Streaming chunks carry `StreamingParts`.
- **Anthropic (2.1.0)** — [UN 2.1](https://docs.spring.io/spring-ai/reference/2.1/upgrade-notes.html#upgrading-to-2-1-0):
  - A non-streaming response is now a **single `Generation`** with reasoning available via `getOutput().getReasoning()`. The separate thinking and redacted-thinking generations are gone.
  - `AnthropicChatModel.AnthropicAssistantMessage` and the `anthropicThinkingContents` metadata are removed. "Messages restored from a chat memory written by an earlier version lose their thinking blocks on replay."
  - The `signature`/`data` metadata keys are no longer set on non-streaming responses.
  - Streaming metadata keys `thinking`, `thinkingText`, `signature` and `data` are deprecated for one release.
- **Bedrock Converse (2.1.0):** `BedrockChatOptions.requestParameters` changes from `Map<String,String>` to `Map<String,Object>`. This is a source break for explicitly typed variables — [UN 2.1](https://docs.spring.io/spring-ai/reference/2.1/upgrade-notes.html#upgrading-to-2-1-0)
- **PgVector (2.1.0):** a stored `distance` metadata key is no longer overwritten by the computed distance, and a WARN is logged. Use `Document.getScore()` (`1.0 - distance`) — [UN 2.1](https://docs.spring.io/spring-ai/reference/2.1/upgrade-notes.html#upgrading-to-2-1-0)
- **Scheduled removals:** the `toolCallExceptionClass` parameter and `AbstractMcpToolProvider.doGetToolCallException()` "will be removed in 2.1.0" — [UN (2.0.x) #upgrading-to-2-0-1](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-1)
- **ChatMemory direction:** "The spring-ai-session community project provides a session-aware memory implementation … It is planned to replace `ChatMemory` in Spring AI 2.1." — [UN (2.0.x) Advisors section](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0); [spring-ai-session](https://github.com/spring-ai-community/spring-ai-session)

### Inferences
- Teams that persist Anthropic conversations through chat memory should expect thinking-block loss on replay when moving to 2.1. Code reading thinking from separate generations or metadata needs changes.
- Because 2.1.0-M1 targets Boot 4.2 (milestone), production apps on Boot 4.0/4.1 should stay on 2.0.x.

### Gaps
- The 2.1 notes are SNAPSHOT/milestone content and may change before 2.1.0 GA. I did not find a 2.1 GA date in primary sources.

---

## Q8. Real-world upgrade experiences and common pitfalls

### Takeaway
Reported pain points cluster around:
- the Jackson 3 migration and Boot 4 module splits
- Kotlin + JSpecify nullness
- vendor-SDK eager validation (openai-java / anthropic-java)
- duplicate or implicit `ToolCallingAdvisor` behavior
- regressions in late milestones/RCs: forced API key, Bedrock deps, GraalVM + MCP annotations

### Cited Findings
- **GraalVM native + MCP annotations** failed at startup on 2.0.0 GA (stack in `SyncMcpToolProvider.getToolSpecifications`). It is listed as addressed in 2.0.1 — [Issue #6427](https://github.com/spring-projects/spring-ai/issues/6427); [GitHub release v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1)
- **Late-milestone regressions:**
  - "2.0.0-M7 forces API key requirement, breaking cookie/session-based authentication" (#6150, fixed in M8) — [GitHub release v2.0.0-M8](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M8)
  - "2.0.0-RC1 regression: additional dependency is needed for Bedrock SDK" (#6367, fixed in RC2) — [GitHub release v2.0.0-RC2](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC2)
  - "Restore options replacing instead of merging in `ChatModel`" (#6336) and "Always auto-register `ToolCallingAdvisor` to support runtime-injected tools" (#6349), both in RC2 — [GitHub release v2.0.0-RC2](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC2)
- **Duplicate ToolCallingAdvisor:** apps that explicitly add `ToolCallingAdvisor` get it twice after 2.0 auto-registration — [UN (2.0.x)](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)
- **2.0.1 fixes that alter behavior:**
  - "Default OpenAI tool-calling strict mode to false" (#6755): tools with optional params had been rejected with a 400 under strict mode.
  - "Make tool resolution fallback configurable" (#6751).
  - "Accumulate tool calling advisor usage" (#6424).
  - "Fix shared mutable state in chat options builder `clone()`" (#6498).
  - Source: [GitHub release v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1); [UN (2.0.x) #upgrading-to-2-0-1](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-1)
- **Embabel's migration (M8)** — all from [Embabel wiki](https://github.com/embabel/embabel-agent/wiki/Spring-Boot-4---Spring-AI-2.0-Migration):
  - openai-java 4.36 eagerly validates `baseUrl` syntax, so test sentinels need valid URLs.
  - The SDK rejects null or blank API keys, so a placeholder `"no-auth"` is needed for local servers (LM Studio, Docker).
  - anthropic-java `Usage.Builder.build()` requires every field, so `Optional.empty()` must be set explicitly.
  - The Bedrock autoconfig in 2.0 requires a `ToolCallingManager` bean.
  - Spring 7 `RestClient.body<T>()` calls `.hint(...)`, so MockK stubs need updating.
  - JUnit 6 `assertThrows<X?>` is rejected.
  - Test assertions on prompt shape had to relax because `.toolCallbacks(...)` now routes through the tool-calling advisor.
  - A startup failure with `ANTHROPIC_API_KEY` set was still open at the time.
- **Jackson 3 general pitfalls (search snippets, not fetched in full; treat as secondary):**
  - Jackson 2 → 3 was described as the change that "produced real fallout"; one migration touched about 800 files.
  - Jackson 3 flips `READ_ENUMS_USING_TO_STRING`/`WRITE_ENUMS_USING_TO_STRING` on by default.
  - The checked `JsonProcessingException` gives way to the unchecked `JacksonException`.
  - Some third-party libraries pinned to Jackson 2 compile but throw `AbstractMethodError` at runtime.
  - Source: [joaovieira.ca: Upgrading to Spring Boot 4](https://joaovieira.ca/spring-boot-4-upgrade/); [Medium: Migrating to Spring 4 – Jackson Edition](https://medium.com/javarevisited/migrating-to-spring-4-jackson-edition-40178ada4c97)
- **HeroDevs** frames the urgency: Spring AI 2.0 is built on the Boot 4.0 dependency model, and 1.x OSS support ended June 30, 2026. This is a search snippet from a commercial-support vendor; the support date itself is corroborated by the spring.io API — [HeroDevs blog](https://www.herodevs.com/blog-posts/spring-ai-2-0-is-coming-soon-your-boot-4-0-migration-does-not-have-to-start-tomorrow); [api.spring.io generations](https://api.spring.io/projects/spring-ai/generations)
- One public repo tracks a combined "migrate to Spring Boot 4.1.x / Spring AI 2.0" chore, which illustrates that the two upgrades are done together — [GitHub issue: SpringBoot-JobApplyTracker #83](https://github.com/vitorhugo-dotnet/SpringBoot-JobApplyTracker/issues/83)

### Inferences
- Upgrade straight to **2.0.1**, not 2.0.0. 2.0.1 contains the GraalVM + MCP fix, the OpenAI strict-mode 400 fix and the MCP HTTP customizer bean fix. Then review the 2.0.1 behavior changes.
- Kotlin codebases carry extra migration cost from JSpecify and K2 plus Jackson 3's Kotlin module changes.

### Gaps
- I found no high-quality Stack Overflow threads specific to Spring AI 2.0 migration. Most real-world evidence is Embabel's wiki and GitHub issues.
- Several pitfall sources are search snippets that were not fetched in full (joaovieira.ca, Medium, HeroDevs). Treat them as secondary.
