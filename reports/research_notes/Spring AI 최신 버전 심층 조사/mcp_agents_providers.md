# Spring AI: MCP, Agentic Capabilities, and Model Provider Integrations (latest GA as of 2026-10-01)

Shorthand for the URLs below:
- `DOCS@2.0.1` = `https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/` (reference docs source pinned to the v2.0.1 GA tag; the same content is published at docs.spring.io/spring-ai/reference/)
- Every link points to a primary source: a GitHub tag, a GitHub release, Maven Central, the spring.io API, or the spring.io blog.
- Labels: **[GA 2.0.1]** = latest GA. **[2.0.0]** = introduced in 2.0.0 GA and still in 2.0.1. **[M 2.1.0-M1]** = milestone only, not GA. **[COMMUNITY]** = spring-ai-community or a third party, not shipped by Spring AI.

## Q0. Version baseline: what is the latest Spring AI GA?

### Takeaway
As of 2026-10-01 the latest GA is **Spring AI 2.0.1** (released 2026-08-21). 2.0.0 went GA on 2026-06-12. **2.1.0-M1** (2026-09-25) is a milestone, and 2.0.2-SNAPSHOT is in development. Maven Central's `<release>` tag reads 2.1.0-M1 only because it means "latest non-SNAPSHOT version". It does not mean 2.1.0-M1 is GA. Free (OSS) support for the 1.0.x and 1.1.x lines ended on 2026-06-30.

### Cited Findings
- spring.io project API: `2.0.1 GENERAL_AVAILABILITY current=True`, `2.1.0-M1 PRERELEASE`, `2.0.2-SNAPSHOT SNAPSHOT`, `1.1.8 GA`, `1.0.9 GA`. — [api.spring.io releases](https://api.spring.io/projects/spring-ai/releases)
- Support generations from the same API:
  - 1.0.x: OSS support ended 2026-06-30, commercial support until 2027-06-30.
  - 1.1.x: OSS support ended 2026-06-30, commercial support until 2032-06-30. That date is copied exactly from the API and looks unusual.
  - 2.0.x: first released 2026-06-30 per the API (the GitHub release is dated 2026-06-12). OSS support until 2027-07-31, commercial support until 2028-07-31.
  - No 2.1.x generation is listed yet.
  — [api.spring.io generations](https://api.spring.io/projects/spring-ai/generations)
- The spring.io project page shows 2.0.1 as the current version. The rendered page did not expose a full versions table. — [spring.io/projects/spring-ai](https://spring.io/projects/spring-ai)
- GitHub release dates:
  - v2.1.0-M1: 2026-09-25 (pre-release)
  - v2.0.1: 2026-08-21 ("Latest")
  - v2.0.0: 2026-06-12
  - v1.1.8 and v1.0.9: 2026-06-12 (same day as 2.0.0)
  - 2.0.0-RC2: 2026-06-09. RC1: 2026-06-06. M1: 2025-12-11.
  — [GitHub releases](https://github.com/spring-projects/spring-ai/releases)
- Maven Central `spring-ai-bom` metadata: `<latest>2.1.0-M1</latest><release>2.1.0-M1</release>`. The version list ends `…2.0.0, 2.0.1, 2.1.0-M1` (lastUpdated 2026-09-24). — [Maven Central metadata](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml)
  - Dates differ by source. Maven Central artifacts are published about a day before the GitHub releases. `spring-ai-bom-2.0.1.pom` has Last-Modified 2026-08-20 12:21 GMT, while the GitHub release and blog are dated 2026-08-21. `spring-ai-bom-2.1.0-M1.pom` has Last-Modified 2026-09-24 09:12 GMT, while the GitHub release is dated 2026-09-25. — [2.0.1 pom on Central](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/2.0.1/spring-ai-bom-2.0.1.pom); [2.1.0-M1 pom on Central](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/2.1.0-M1/spring-ai-bom-2.1.0-M1.pom)
- Platform baseline of the 2.0.1 root POM: `java.version` 17, `spring-boot.version` 4.1.1, `mcp.sdk.version` 2.0.0, `anthropic-sdk.version` 2.52.0, `openai-sdk.version` 4.49.0, `awssdk.version` 2.51.2. — [pom.xml@v2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/pom.xml)
- 2.1.0-M1 root POM: Spring Boot 4.2.0-M2, MCP SDK still 2.0.0, Anthropic SDK 2.64.0. — [pom.xml@v2.1.0-M1](https://github.com/spring-projects/spring-ai/blob/v2.1.0-M1/pom.xml)
- The 2.0 GA blog says 2.0 "has been designed to be used with Spring Boot 4.0 / 4.1 and Spring Framework 7.0". It also says the codebase is fully JSpecify-annotated and moved from Jackson 2 to Jackson 3. — [Spring AI 2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- The prior line, 1.1.8, used Spring Boot 3.5.15, MCP SDK 0.18.3, external `mcp-annotations` 0.9.0, and OpenAI SDK 4.17.0. — [pom.xml@v1.1.8](https://github.com/spring-projects/spring-ai/blob/v1.1.8/pom.xml)

### Inferences
- "Latest GA" means 2.0.1. Anything that ships only in 2.1.0-M1, notably the OpenAI Responses API, must be labeled milestone.
- Users on 1.1.x no longer get free (OSS) support. 2.0.x is the only OSS-supported line.

### Gaps
- The rendered spring.io project page did not show a full Versions table. Version status was taken from the api.spring.io JSON that backs it.

## Q1. MCP in Spring AI 2.0.1: Boot starters, transports, WebMVC vs WebFlux, annotations, security, spec version

### Takeaway
In 2.0.1 there are two MCP client starters and three MCP server starters.
- The servers support STDIO, SSE, Streamable HTTP and Stateless Streamable HTTP, on either WebMVC or WebFlux.
- The default server protocol changed from SSE in 1.1.x to **STREAMABLE** in 2.0.x.
- MCP annotations (`@McpTool`, `@McpResource`, `@McpPrompt`, `@McpComplete`, plus client-side annotations) moved into Spring AI core under `org.springframework.ai.mcp.annotation`.
- The underlying MCP Java SDK is 2.0.0, which tracks the **2025-11-25** MCP spec.
- A newer MCP spec revision, **2026-07-28**, now exists. GA 2.0.1 does not support it. Support is "in progress" according to the 2.1.0-M1 announcement.
- Spring AI itself ships no authentication for MCP endpoints. OAuth2 and API-key security come from the **community** `mcp-security` project (0.1.x), which works on WebMVC only.

### Cited Findings
**Starters [GA 2.0.1]**
- Starter modules in the 2.0.1 tree: `spring-ai-starter-mcp-client`, `spring-ai-starter-mcp-client-webflux`, `spring-ai-starter-mcp-server`, `spring-ai-starter-mcp-server-webmvc`, `spring-ai-starter-mcp-server-webflux`. There is no WebMVC-specific client starter. — [starters@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1/starters)
- Auto-config modules: `spring-ai-autoconfigure-mcp-client-{common,httpclient,webflux}` and `spring-ai-autoconfigure-mcp-server-{common,webmvc,webflux}`. — [auto-configurations/mcp@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1/auto-configurations/mcp)
- Core MCP modules: `spring-ai-mcp` (mcp/common), `spring-ai-mcp-annotations`, and the transports `org.springframework.ai:mcp-spring-webflux` / `mcp-spring-webmvc`. These depend on `io.modelcontextprotocol.sdk:mcp-core` and `mcp-json-jackson3`. — [mcp@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1/mcp)

**Client starters**
- `spring-ai-starter-mcp-client` connects to one or more servers over STDIO, SSE, Streamable-HTTP and Stateless Streamable-HTTP. Its HTTP transports use the JDK HttpClient. — [DOCS@2.0.1 mcp-client-boot-starter-docs](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-client-boot-starter-docs.adoc)
- `spring-ai-starter-mcp-client-webflux` uses WebFlux-based Streamable-HTTP, Stateless and SSE transports, and also supports STDIO. The docs say: "For production deployment, we recommend using the WebFlux-based SSE & StreamableHttp connection". — [same doc](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-client-boot-starter-docs.adoc)
- Client properties (prefix `spring.ai.mcp.client`):
  - `enabled`, `name`, `version`, `initialized`
  - `request-timeout`, default 20s
  - `type`, SYNC or ASYNC. Mixing sync and async clients is not supported.
  - `root-change-notification`
  - `toolcallback.enabled`, default true. It exposes MCP tools as a Spring AI `ToolCallbackProvider`.
  - `annotation-scanner.enabled`
  - `streamable-http.connections.[name].url` / `.endpoint` (default `/mcp`), plus SSE and stdio connection maps.
  — [same doc](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-client-boot-starter-docs.adoc)
- Client extension points:
  - `McpToolFilter`
  - `McpToolNamePrefixGenerator`. The default `DefaultMcpToolNamePrefixGenerator` de-duplicates names (e.g. `alt_1_toolName`) and caps them at 64 characters.
  - `ToolContextToMcpMetaConverter`, which maps Spring AI `ToolContext` to MCP `_meta`, e.g. a progressToken.
  - `McpClientCustomizer<B>`
  — [same doc](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-client-boot-starter-docs.adoc)

**Server starters and transports**
- How to select the server type:
  - STDIO: `spring-ai-starter-mcp-server` with `spring.ai.mcp.server.stdio=true`.
  - WebMVC: `spring-ai-starter-mcp-server-webmvc`.
  - WebFlux: `spring-ai-starter-mcp-server-webflux`.
  - On both web starters, `spring.ai.mcp.server.protocol` takes `SSE`, `STREAMABLE` or `STATELESS`.
  — [DOCS@2.0.1 mcp-overview](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-overview.adoc)
- **Default protocol conflict, resolved by the code:**
  - The 2.0.1 overview tables say "`spring.ai.mcp.server.protocol=SSE` or empty" selects SSE. ([mcp-overview@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-overview.adoc))
  - The code at v2.0.1 has `private ServerProtocol protocol = ServerProtocol.STREAMABLE;`. ([McpServerProperties.java@v2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-common/src/main/java/org/springframework/ai/mcp/server/common/autoconfigure/properties/McpServerProperties.java))
  - At v1.1.8 the same field defaults to `ServerProtocol.SSE`. ([McpServerProperties.java@v1.1.8](https://github.com/spring-projects/spring-ai/blob/v1.1.8/auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-common/src/main/java/org/springframework/ai/mcp/server/common/autoconfigure/properties/McpServerProperties.java))
  - The GA blog also says "Streamable HTTP is now the default". ([2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/))
  - Conclusion: the overview table is stale.
- Server properties (prefix `spring.ai.mcp.server`):
  - `name`, `version`, `instructions`
  - `type`, SYNC (default) or ASYNC
  - `capabilities.{resource,tool,prompt,completion}`, all default true
  - `tool-change-notification`
  - `expose-mcp-client-tools`, default false. It re-exposes downstream MCP client tools from this server.
  - `tool-response-mime-type`
  - `keep-alive-interval`, disabled by default
  - `annotation-scanner.*`
  — [DOCS@2.0.1 streamable-http server](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-streamable-http-server-boot-starter-docs.adoc); [stateless server](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-stateless-server-boot-starter-docs.adoc)
- `expose-mcp-client-tools` was added in 2.0.0-M5. — [v2.0.0-M5 release](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M5)
- Server capabilities: tools, resources, prompts, completions, logging, progress and ping. A SYNC server registers only synchronous annotated methods, and an ASYNC server only asynchronous ones. — [DOCS@2.0.1 mcp-server-boot-starter-docs](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-server-boot-starter-docs.adoc)
- Spring-specific transport classes now live in Spring AI, e.g. `org.springframework.ai.mcp.server.webflux.transport.WebFluxStreamableServerTransportProvider` and `WebFluxStatelessServerTransport`. Their Maven group moved from `io.modelcontextprotocol.sdk` to `org.springframework.ai` as of 2.0. — [mcp-overview@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-overview.adoc); [v2.0.0-M3 release](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M3)
- 2.0.1 adds `maxSessions` and `sessionIdleTimeout` for server session state (#6760). — [v2.0.1 release](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1); [2.0.1 blog](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)

**Annotations [2.0.0, in core]**
- Server annotations: `@McpTool`, `@McpResource`, `@McpPrompt`, `@McpComplete`. — [DOCS@2.0.1 mcp-annotations-server](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-annotations-server.adoc)
- Client annotations: `@McpLogging`, `@McpSampling`, `@McpElicitation`, `@McpProgress`, `@McpToolListChanged`, `@McpResourceListChanged`, `@McpPromptListChanged`. — [mcp-annotations-client](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-annotations-client.adoc)
- Special parameters: `McpMeta`, `MetaProvider`, `@McpProgressToken`, `McpSyncRequestContext`/`McpAsyncRequestContext` (the unified context, recommended), `McpTransportContext`, `CallToolRequest`, `McpSync/AsyncServerExchange`. Parameter annotations are `@McpToolParam` and `@McpArg`. — [mcp-annotations-special-params](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-annotations-special-params.adoc); [mcp-annotations-overview](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-annotations-overview.adoc)
- 2.0.0-M3 moved the annotations from community `org.springaicommunity:mcp-annotations` into Spring AI:
  - New artifact: `spring-ai-mcp-annotations`.
  - Package renames: `org.springaicommunity.mcp.annotation.*` becomes `org.springframework.ai.mcp.annotation.*`, and `.provider.*` becomes `org.springframework.ai.mcp.annotation.provider.*`.
  - The OpenRewrite recipe `migrate-to-2-0-0-M3.yaml` automates the migration.
  — [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)
- 2.0.1 aligned `@McpTool` exception handling with `@Tool`:
  - `RuntimeException` (other than `McpError`) becomes an error `CallToolResult` and is sent to the model.
  - Checked exceptions, `Error`s and `McpError` propagate as hard failures.
  - `toolCallExceptionClass` is deprecated and slated for removal in 2.1.0.
  — [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)
- 2.0.1 also fixed MCP annotations under GraalVM native image (#6427) and STDIO connections without args (#6533), started applying MCP HTTP client request customizer beans (#6716), and made the Streamable HTTP WebClient transport accept SSE frames without an `event` field (#5783). — [v2.0.1 release](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1)

**Security**
- Core [GA 2.0.1]: "The MCP server transports are intentionally authentication-agnostic." By default `POST /mcp` "accepts all requests". You secure the endpoint with Spring Security or MCP Security. — [DOCS@2.0.1 mcp-server-boot-starter-docs](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-server-boot-starter-docs.adoc)
- The MCP Security doc page is labeled "(WIP)" and says: "This is a community-driven project and is not officially endorsed yet by Spring AI or the MCP project." It has three components: Server Security (OAuth2 resource server plus API key), Client Security (OAuth2 client), and an MCP Authorization Server built on Spring Authorization Server. Server security is "compatible with Spring WebMVC-based servers only". — [DOCS@2.0.1 mcp-security](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-security.adoc)
- [COMMUNITY] `spring-ai-community/mcp-security` details:
  - Latest version is v0.1.14 (2026-08-06), with artifacts `org.springaicommunity:mcp-server-security` and `mcp-server-security-spring-boot`.
  - The README says: "Versions 0.1.x … only work Spring AI's 2.0.x branch. For Spring AI 1.1.x, use version 0.0.6."
  - OAuth2 and API-key auth are WebMVC-only. Origin validation also supports WebFlux.
  — [mcp-security README](https://github.com/spring-ai-community/mcp-security)
- v0.1.13 was "tested with Spring Boot 4.1.0, Spring AI 2.0.0 and MCP SDK 2.0.0". v0.1.14 adds a 401 when the API key is missing, Origin header validation, and issuer URL validation in the CIMD flow. — [mcp-security releases](https://github.com/spring-ai-community/mcp-security/releases)
- The server OAuth2 configurer is `McpServerOAuth2Configurer.mcpServerOAuth2()`. It requires the issuer URI and can enforce the RFC 8707 `aud` claim. Individual tools can be protected with `@PreAuthorize` on `@McpTool` methods. — [DOCS@2.0.1 mcp-security](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-security.adoc)
- The GA blog lists MCP observability ("Micrometer spans and OpenTelemetry-compatible metrics") and "OAuth 2.0 and API-key security via the spring-ai-community/mcp-security project". — [2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)

**Spec version**
- The GA blog says Spring AI 2.0 ships "MCP Java SDK 2.0.0, compliant with the latest 2025-11-25 MCP specification". — [2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- SDK 2.0.0 "tracks the latest 2025-11-25 MCP specification" and adds the "2025-11-25 spec version to all transports" (#1025). — [java-sdk v2.0.0 release](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0)
- **Newer spec 2026-07-28, not in GA:**
  - The 2.1.0-M1 blog says: "Work on support for the MCP 2026-07-28 specification is in progress, both in the MCP Java SDK and in the Spring AI MCP abstractions and annotations built on top of it." — [Spring AI 2.1.0-M1 blog](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/)
  - The MCP Java SDK has open tracking issues, all opened 2026-06-06 "for the 2026-07-28 MCP spec release":
    - SEP-2575 "Make MCP Stateless" (#1011)
    - SEP-2577 "Deprecate Roots, Sampling, and Logging" (#1012)
    - SEP-2663 "Tasks Extension" (#1013)
    - SEP-1865 "MCP Apps – Interactive User Interfaces" (#999)
    - SEP-2567 "Sessionless MCP via Explicit State Handles" (#1010)
    - SEP-2322 "Multi Round-Trip Requests" (#1004)
    - SEP-2106 "Tools inputSchema & outputSchema Conform to JSON Schema 2020-12" (#1000)
    - SEP-2352 and SEP-2468 on authorization (#1007, #1008)
    - Others
    — [java-sdk issues](https://github.com/modelcontextprotocol/java-sdk/issues?q=2026-07-28); [#1011](https://github.com/modelcontextprotocol/java-sdk/issues/1011); [#1012](https://github.com/modelcontextprotocol/java-sdk/issues/1012)

### Inferences
- New Spring AI 2.0 HTTP servers get Streamable HTTP unless `protocol=SSE` is set explicitly. SSE still ships in the Spring AI starters even though SDK 2.0 deprecates the SSE transports.
- Spring AI has no WebMVC (servlet) client transport starter. A servlet application uses the JDK-HttpClient client starter or the WebFlux client starter.
- OAuth2-secured MCP servers effectively require WebMVC plus community `mcp-security` 0.1.x. Treat this as non-official.
- Some 2.0.1 docs still link 2025-06-18 spec pages, e.g. the Streamable HTTP transport link. These links are likely stale and do not mean the spec support is older.
- "Latest spec" wording needs care. The GA blog's "latest 2025-11-25" was accurate in June 2026. As of 2026-10-01, Spring AI GA is one spec revision behind (2026-07-28). Several 2026-07-28 SEPs would reshape the Spring AI MCP surface: stateless-by-default, deprecation of roots, sampling and logging, and a Tasks extension. This touches client annotations such as `@McpSampling` and `@McpLogging`, and the `root-change-notification` property. Expect changes in 2.1.x. That is an inference from the SEP titles; no Spring AI design has been published.

### Gaps
- I could not confirm the configuration property names for `maxSessions` and `sessionIdleTimeout`. They are not in `McpServerStreamableHttpProperties` or `McpServerSseProperties` at v2.0.1, so they may be transport-builder settings only.
- I did not verify whether Spring AI 2.0.1 exposes 2025-11-25 "tasks" (durable requests) or "tool calling in sampling". Neither the SDK 2.0.0 nor the Spring AI release notes mention them.

## Q2. The MCP Java SDK: current version, relationship to Spring AI, version pairing

### Takeaway
The MCP Java SDK (`io.modelcontextprotocol.sdk`) is at **2.0.1** (2026-08-19). 2.0.0 went GA on 2026-06-11, one day before Spring AI 2.0.0. Spring AI 2.0.1, and also 2.1.0-M1, still **pins SDK 2.0.0**. The SDK is the official MCP Java SDK, "maintained in collaboration with Spring AI", and the Spring team (tzolov, chemicL, Kehrlann) leads the work. Since Spring AI 2.0 the Spring WebMVC/WebFlux transports live in Spring AI, not in the SDK.

### Cited Findings
- `mcp-bom` on Maven Central ends `…1.1.4, 2.0.0-M1, M2, M3, 2.0.0-RC1, 2.0.0, 2.0.1` (lastUpdated 2026-08-19). — [Maven Central mcp-bom](https://repo1.maven.org/maven2/io/modelcontextprotocol/sdk/mcp-bom/maven-metadata.xml)
- GitHub releases: v2.0.1 (2026-08-19, Latest) shipped together with maintenance releases v1.1.4 and v0.18.4 the same day. Earlier: v2.0.0 (2026-06-11), v2.0.0-RC1 (2026-06-04), v1.1.0 (2026-03-13), v1.0.0 (2026-02-23). — [java-sdk releases](https://github.com/modelcontextprotocol/java-sdk/releases)
- The v1.0.0 release note calls it "the official Java SDK for Model Context Protocol servers and clients, maintained in collaboration with Spring AI". 1.0.0 complied with the 2025-06-18 spec and negotiated 2024-11-05, 2025-03-26 and 2025-06-18. Jackson 3 was the default with Jackson 2 supported. — [java-sdk v1.0.0](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v1.0.0)
- SDK 2.0.0 highlights. — [java-sdk v2.0.0](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0)
  - A new JSON forward/backward compatibility foundation, with lenient deserialization.
  - Spec-required fields are now enforced.
  - Tool input validation is on by default.
  - `inputSchema` changed from `JsonSchema` to a `Map`.
  - Embedded JSON Schema documents are validated against 2020-12 (SEP-1613).
  - Icons and metadata (SEP-973).
  - Elicitation: schema defaults (SEP-1034), URL elicitation (SEP-1036), and form elicitation schemas.
  - "SSE transports are now deprecated in favor of Streamable HTTP" (#950).
  - `customizeRequest()` is deprecated in favor of `httpRequestCustomizer()`.
  - Request URI is exposed in `McpHttpClientAuthorizationErrorHandler`.
  - Conformance runs against MCP-security 0.1.11, including CIMD.
- SDK 2.0.0-M1 removed sealed interfaces (`JSONRPCMessage`, `Request`, `Result`, `Content`, …) for forward compatibility. — [java-sdk v2.0.0-M1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0-M1)
- SDK 2.0.1 bounded HTTP and STDIO reads on both server and client, fixed stateless handler errors, aligned SSE event classification with the spec, stopped pagination on empty cursors, and upgraded "Spring AI conformance to 2.0.0 GA". — [java-sdk v2.0.1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.1)
- **Interop issue in SDK 2.0.0, which Spring AI 2.0.1 pins:**
  - Issue #1072 is titled "[2026-07-28] Java SDK 2.0.0 returns HTTP 500 for OpenAI server/discover requests". From 2026-07-30, OpenAI's hosted MCP client (used from the Responses API) sends `server/discover` before `tools/list`. SDK 2.0.0's stateless transport answered with HTTP 500 ("Missing handler for request type: server/discover"), and OpenAI then failed with 424 `external_connector_error`.
  - The issue was closed 2026-08-17 as resolved on main. Unknown methods now return a -32601 JSON-RPC error.
  - Companion issue #1085 lists the 2.0.x fixes as #784 (milestoned 2.0.1) and PR #1083, and asks for a backport to the 0.x and 1.x lines.
  — [java-sdk #1072](https://github.com/modelcontextprotocol/java-sdk/issues/1072); [java-sdk #1085](https://github.com/modelcontextprotocol/java-sdk/issues/1085)
  - The SDK 2.0.1 notes include "Fix error behavior on unregistered handlers in stateless server handler" (#800). — [java-sdk v2.0.1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.1)
- SDK 1.1.0 added resource subscriptions and an authorization error handler on `HttpClientStreamHttpTransport`. — [java-sdk v1.1.0](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v1.1.0)
- The SDK README (main branch) reports conformance results at suite 0.1.15: Server 40/40, Client 3/4 scenarios (9/10 checks), Auth (Spring) 12/14 scenarios (98.9% of checks). It links Spring AI MCP, MCP Annotations and MCP Security as the Spring integrations. — [java-sdk README](https://github.com/modelcontextprotocol/java-sdk). The v2.0.1 release note mentions suite 0.1.16, so the README may lag.
- Version pairing:

  | Spring AI | MCP SDK | Source |
  |---|---|---|
  | 2.0.1 GA | 2.0.0 | [pom@v2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/pom.xml) |
  | 2.1.0-M1 | 2.0.0 | [pom@v2.1.0-M1](https://github.com/spring-projects/spring-ai/blob/v2.1.0-M1/pom.xml) |
  | 2.0.0 GA | 2.0.0 ("Upgrade to MCP SDK 2.0.0" #6385) | [v2.0.0 release](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0) |
  | 2.0.0-RC1 | 2.0.0-RC1 | [v2.0.0-RC1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC1) |
  | 2.0.0-M5 | 2.0.0-M2 | [v2.0.0-M5](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M5) |
  | 1.1.8 | 0.18.3 | [pom@v1.1.8](https://github.com/spring-projects/spring-ai/blob/v1.1.8/pom.xml) |

- Spring AI 2.0 moved `mcp-spring-webflux` and `mcp-spring-webmvc` out of the SDK into Spring AI (group `org.springframework.ai`). The upgrade notes add that "the MCP Java SDK version requirement has been bumped from 0.18.x to 1.0.x" for this move. — [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)
- Spring AI MCP was originally announced as "a robust Java SDK implementation of the Model Context Protocol" and an experimental module on 2024-12-11. — [Spring AI MCP announcement](https://spring.io/blog/2024/12/11/spring-ai-mcp-announcement)

### Inferences
- The SDK's 1.x and 0.18.x maintenance releases suggest 1.1.x users can stay on SDK 1.1.4 or 0.18.4. Spring AI 1.1.8 itself pins 0.18.3.
- SDK 2.0.1 fixes are not in Spring AI 2.0.1's managed version. These include bounded reads (a resource-exhaustion hardening) and the stateless unknown-method handling, i.e. the `server/discover` HTTP 500 fix. Users can override `mcp-bom` to 2.0.1 at their own risk. The next Spring AI patch release would likely pick it up, but that is not confirmed.
- Spring AI 2.0.1 stateless MCP servers exposed to OpenAI's hosted MCP connector are probably affected by the `server/discover` HTTP 500 while they run on the default SDK 2.0.0. The issue reporter used the SDK's own `HttpServletStatelessServerTransport`. The fix is in `DefaultMcpStatelessServerHandler`, which lives in mcp-core and is presumably shared by Spring's WebMVC and WebFlux stateless transports. This was not tested.

### Gaps
- No primary source fetched in this session uses the word "donated" for the move from Spring AI to modelcontextprotocol. The 2024 announcement page did not mention the hand-over. What is verifiable is the "maintained in collaboration with Spring AI" wording.

## Q3. Agentic patterns, agent loops, frameworks, A2A

### Takeaway
Core Spring AI 2.0.1 does not ship a dedicated agent framework. Agentic support is built from three things:
- The documented "Building Effective Agents" workflow patterns.
- **Recursive advisors**. The tool loop now lives in an auto-registered `ToolCallingAdvisor` rather than inside each `ChatModel`, with configurable tool-call limits added in 2.0.1.
- `ToolSearchToolCallingAdvisor`, for progressive tool disclosure.

Higher-level agent tooling and A2A are **community** projects:
- `spring-ai-agent-utils`
- `spring-ai-a2a`, server-side only, built against 2.0.0-RC1
- `spring-ai-session`
- `spring-ai-agentcore`
- Embabel
- Mark Pollack's `agent-client`, which moved out of spring-ai-community in August 2026

An official "new agentic support" project has been **announced, not released**. It is planned for November 2026 under Spring Projects Experimental, with a merge into Spring AI 3.0 planned for mid-2027.

### Cited Findings
**Workflow patterns [GA docs]**
- The "Building Effective Agents" guide, based on Anthropic's research, covers five patterns: Chain Workflow, Parallelization Workflow, Routing Workflow, Orchestrator-Workers and Evaluator-Optimizer. Code lives in `spring-projects/spring-ai-examples/agentic-patterns`. Its "Future Work" section lists pattern composition, agent memory, and MCP integration. — [DOCS@2.0.1 effective-agents](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/effective-agents.adoc)
- The docs nav "Guides" section links external agent workshops: "Building agents with Spring AI by AWS", "Building agents with Spring AI and AgentCore by AWS", and Microsoft's "Spring AI for Beginners". — [nav.adoc@v2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/nav.adoc)

**Recursive advisors and the agent loop [2.0.0 / GA 2.0.1]**
- Recursive advisors "loop through the downstream advisor chain multiple times" using `CallAdvisorChain.copy(CallAdvisor after)`. There are two built-ins, `ToolCallingAdvisor` and `StructuredOutputValidationAdvisor`. — [DOCS@2.0.1 advisors-recursive](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/advisors-recursive.adoc)
- `ToolCallingAdvisor` behavior:
  - It is "auto-registered by DefaultChatClient whenever tools are present".
  - It loops until `ToolExecutionEligibilityChecker` reports no more tool calls.
  - It supports returnDirect.
  - It enforces a single-`ToolAdvisor` invariant.
  - It accumulates token usage across iterations.
  — [advisors-recursive](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/advisors-recursive.adoc); [tool-calling-advisor](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/tools/tool-calling-advisor.adoc)
- 2.0.0 tool-calling changes, all breaking compared with 1.1.x:
  - The internal tool-execution loop was removed from every `ChatModel`: OpenAI, Anthropic, Ollama, DeepSeek, Mistral, Bedrock and MiniMax (2.0.0-RC1).
  - `internalToolExecutionEnabled`, `SpringBeanToolCallbackResolver` and `toolNames()` were removed.
  - `ToolCallAdvisor` was renamed `ToolCallingAdvisor`.
  - New `ToolAdvisor` and `MemoryAdvisor` marker interfaces.
  - New `spring.ai.chat.client.tool-calling.enabled` property.
  - Memory advisors are now placed outside `ToolCallingAdvisor` by default.
  - Conversation history was removed from `ToolContext`.
  — [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc); [v2.0.0-RC1 release](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC1)
- Tool call limits **[GA 2.0.1, new]**:
  - `DefaultToolCallingManager` now caps calls at `DEFAULT_MAX_CALLS_PER_TOOL` (40) per tool and `DEFAULT_MAX_TOTAL_TOOL_CALLS` (150) overall. Previously there was no limit.
  - The limits are configured via `ToolCallingManager.builder().maxCallsPerTool(...)` or `spring.ai.tools.limits.*`.
  - Exceeding them raises `ToolCallLimitExceededException`.
  — [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc); [2.0.1 blog](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/) ("An agentic loop that never terminates is an expensive failure mode.")
- `ToolSearchToolCallingAdvisor` [2.0.0, core]:
  - It extends `ToolCallingAdvisor` to give "progressive tool disclosure". Only a `toolSearchTool` is sent at first, and matching tool definitions are added on demand.
  - The tool index is session-scoped, with three strategies: `VectorToolIndex` (semantic), `LuceneToolIndex` (keyword) and `RegexToolIndex`.
  - The docs motivate it with "50+ tools consuming 55,000+ tokens".
  — [DOCS@2.0.1 tool-search-tool](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/tools/tool-search-tool.adoc)
- Its starter is `spring-ai-starter-tool-search-advisor`, built on the modules `advisors/spring-ai-tool-search-advisor` and `spring-ai-tool-search-tool`. — [starters@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1/starters). It was added in 2.0.0-RC1 (#5909). — [v2.0.0-RC1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC1)
- The GA blog names the core agentic advisors: `ToolCallingAdvisor`, `ToolSearchToolCallingAdvisor` and `StructuredOutputValidationAdvisor`. — [2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)

**Official agent subproject?**
- The spring-projects/spring-ai v2.0.1 tree has no `agents` module. Root directories: advisors, auto-configurations, document-readers, mcp, memory-repositories, models, spring-ai-client-chat, … — [spring-ai@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1)
- **Announced, not released.** The 2.1.0-M1 blog says: "We plan to release the new agentic support in November 2026 as a separate project under Spring Projects Experimental, with the plan to merge it into Spring AI 3.0 mid next year." The same post says 2.1 RC1 work will refactor all `ChatModel` implementations onto message parts and bring "session management into core". — [Spring AI 2.1.0-M1 blog](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/)

**Community agent projects [COMMUNITY]** — [spring-ai-community org](https://github.com/spring-ai-community)
- `spring-ai-agent-utils` v0.12.0 (2026-08-30):
  - It offers "Claude Code-inspired tools and agent skills": FileSystemTools, ShellTools, GrepTool, GlobTool, SmartWebFetchTool, BraveWebSearchTool, TodoWrite, AskUserQuestion, Skills, sub-agents, and an A2A sub-agent module (`spring-ai-agent-utils-a2a`).
  - The GA blog describes it as "Agent Skills, a portable, Spring AI-native implementation of the AgentSkills specification".
  — [agent-utils README](https://github.com/spring-ai-community/spring-ai-agent-utils); [releases](https://github.com/spring-ai-community/spring-ai-agent-utils/releases); [2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- `spring-ai-session` v0.10.0 (2026-09-28) is an event-sourced conversation memory layer. The GA blog calls it an "event-sourced replacement for the built-in ChatMemory". — [releases](https://github.com/spring-ai-community/spring-ai-session/releases); [2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- `spring-ai-agentcore` provides "Spring Boot integrations for Amazon Bedrock AgentCore". It is active (last push 2026-09-30), but no GitHub releases were found. — [spring-ai-community org](https://github.com/spring-ai-community)
- The `agent-client`, `agent-bench`, `agent-judge`, `agent-sandbox` and `claude-agent-sdk-java` repos in spring-ai-community are now marked "No longer maintained — active development moved to https://github.com/markpollack/…" (2026-08-06). `markpollack/agent-client` is at v0.31.0 (2026-09-17). — [spring-ai-community org](https://github.com/spring-ai-community); [markpollack/agent-client releases](https://github.com/markpollack/agent-client/releases)
- Other community agent repos: `spring-mcp-agent`, an "Experimental MCP Agent framework … based on Anthropic's Building effective agents" (last push 2025-05), and `spring-ai-playground` (MCP tool sandbox, agentic chat). — [spring-ai-community org](https://github.com/spring-ai-community)

**A2A (Agent2Agent) [COMMUNITY]**
- `spring-ai-community/spring-ai-a2a` v0.3.0 (2026-06-07):
  - The release note says "Upgrade to Spring AI 2.0.0-RC1".
  - It provides **server-side** support for exposing Spring AI agents over the A2A protocol, with artifact `org.springaicommunity:spring-ai-a2a-server-autoconfigure`.
  - You supply `ChatClient`, `AgentCard` and `AgentExecutor` beans; `DefaultAgentExecutor` is provided. The README example sets `protocolVersion("0.3.0")`.
  — [spring-ai-a2a README](https://github.com/spring-ai-community/spring-ai-a2a); [v0.3.0](https://github.com/spring-ai-community/spring-ai-a2a/releases/tag/v0.3.0)
- Core Spring AI 2.0.1 has no A2A module. No a2a directory appears in the models, starters or root trees. — [starters@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1/starters)

**Embabel [THIRD-PARTY]**
- `embabel/embabel-agent`:
  - v1.0.0 shipped 2026-07-20. v1.5.0 shipped 2026-08-11. The latest, v1.5.2 (2026-09-16), moved to Boot 4.1.1 and adds agent process snapshot/restore for HITL persistence.
  - It uses GOAP planning.
  - The README says: "Spring AI exists at the level of the Servlet API, while Embabel is more like Spring MVC". It also says an LLM can be defined "for any provider for which a Spring AI `ChatModel` is available", and that Embabel can run as an MCP server over SSE.
  — [embabel-agent releases](https://github.com/embabel/embabel-agent/releases); [embabel-agent README](https://github.com/embabel/embabel-agent)

### Inferences
- In 2.0 the "agent loop" is a first-class, observable advisor rather than hidden per-model logic. It is the natural extension point for custom agents, for example by subclassing `ToolCallingAdvisor` hooks the way ToolSearch does.
- In GA 2.0.1, Spring AI's official stance is "building blocks plus patterns". Full agent runtimes (Embabel, agent-client) and A2A live outside the official project and use their own version cadences. That will change once the announced experimental agentic project ships (target November 2026) and is merged into Spring AI 3.0 (target mid-2027). Until then, any "official Spring AI agent framework" claim is forward-looking. `spring-ai-a2a` 0.3.0 targets 2.0.0-RC1, not GA. It will probably work with 2.0.1, but that was not verified.

### Gaps
- I did not find which Spring AI version Embabel 1.5.2 uses. Its POM manages it in a separate build or BOM that was not inspected.
- I found no official Spring AI A2A client support, and no A2A protocol version matrix.
- As of 2026-10-01, no released agent subproject exists under spring-projects or spring-projects-experimental. Only the 2.1.0-M1 blog announcement exists. Its repository name, artifacts and relationship to `agent-client` or `spring-ai-agent-utils` are unknown.
- This session did not re-verify that Rod Johnson authored Embabel.

## Q4. Model providers supported in the latest GA (2.0.1): added, removed, official SDKs

### Takeaway
2.0 trimmed and consolidated the providers that ship in core.
- **Dedicated chat modules in 2.0.1:** OpenAI, Anthropic, Google GenAI, Amazon Bedrock Converse, Mistral AI, DeepSeek and Ollama.
- **Other model modules:** ElevenLabs (TTS), Stability AI (image), PostgresML and ONNX Transformers (embeddings), Vertex AI (embeddings only), Bedrock (Titan/Cohere embeddings), and Google GenAI embedding and image.
- **Removed:** Azure OpenAI, `openai-sdk`, Hugging Face, MiniMax, OCI GenAI, Vertex AI Gemini and ZhiPu AI.
  - Azure OpenAI / Microsoft Foundry is now served by the OpenAI module.
  - MiniMax is served by the Anthropic module.
  - OCI GenAI is maintained by Oracle.
- **OpenAI-compatible proxies:** Groq, NVIDIA, Perplexity and Docker Model Runner are documented as proxies through the OpenAI module.
- **Official vendor SDKs** now sit under OpenAI (`openai-java`), Anthropic (`anthropic-java`), Google (`google-genai`) and Bedrock (AWS SDK). DeepSeek, Mistral and Ollama remain Spring-native HTTP clients.

### Cited Findings
- `models/` at v2.0.1: spring-ai-anthropic, -bedrock-converse, -bedrock, -deepseek, -elevenlabs, -google-genai, -google-genai-embedding, -google-genai-image, -mistral-ai, -ollama, -openai, -postgresml, -stability-ai, -transformers, -vertex-ai-embedding. — [models@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1/models)
- `models/` at v1.1.8 additionally had spring-ai-azure-openai, -huggingface, -minimax, -oci-genai, -openai-sdk, -vertex-ai-gemini and -zhipuai. (google-genai-image is new in 2.0.1.) — [models@v1.1.8](https://github.com/spring-projects/spring-ai/tree/v1.1.8/models)
- 2.1.0-M1 has the same model module set as 2.0.1. — [models@v2.1.0-M1](https://github.com/spring-projects/spring-ai/tree/v2.1.0-M1/models)
- Model starters at v2.0.1: `spring-ai-starter-model-{anthropic, bedrock, bedrock-converse, deepseek, elevenlabs, google-genai, google-genai-embedding, google-genai-image, mistral-ai, ollama, openai, postgresml-embedding, stability-ai, transformers, vertex-ai-embedding}`. — [starters@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1/starters)
- GA blog on consolidation:
  - OpenAI "went from 3 variants (Azure, HTTP, SDK) to 1 (SDK)".
  - Anthropic "went from 2 variants (HTTP, SDK) to 1 (SDK)".
  - Google GenAI "went from 2 implementations (GenAI SDK, Vertex) to 1 (GenAI SDK)".
  - Externally maintained: OCI Generative AI (Oracle) and Azure Cosmos DB (Microsoft).
  — [2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- Removal timeline:
  - **2.0.0-M3:** Hugging Face moved out of the main repo, and the Anthropic module switched to the official Anthropic Java SDK. — [v2.0.0-M3](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M3)
  - **2.0.0-M4:** Vertex AI, ZhiPu AI and OCI GenAI deprecated. — [v2.0.0-M4](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M4)
  - **2.0.0-M5:**
    - `spring-ai-azure-openai` removed.
    - `spring-ai-openai-sdk` merged into `spring-ai-openai`.
    - Vertex AI non-embedding modules removed; only `spring-ai-vertex-ai-embedding` remains.
    - ZhiPu AI and OCI GenAI removed.
    - `spring-ai-openai` now uses the official openai-java SDK for Chat, Embedding, Image, Audio and Moderation.
    — [v2.0.0-M5](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M5)
  - **2.0.0-RC1:** "Remove Minimax dedicated support in favor of Anthropic one" (#6290). — [v2.0.0-RC1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC1)
- Upgrade notes:
  - "Spring AI now uses the official `openai-java` SDK under the hood for all OpenAI models". `extraBody` maps to the SDK's `additionalBodyProperties`.
  - Azure OpenAI users "should use the `spring-ai-openai` module … removing the `Azure` prefix".
  - OCI modules are now at `oracle/spring-cloud-oracle/spring-ai-oracle`.
  - MiniMax: use Anthropic with base URL `https://api.minimax.io/anthropic`; "Embeddings are no longer supported".
  — [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)
- Stub docs pages at 2.0.1:
  - Azure OpenAI: "As of Spring AI `2.0.0-M5`, OpenAI Chat should be use[d] to access OpenAI models deployed on Azure / Microsoft Foundry." ([azure-openai-chat](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/azure-openai-chat.adoc))
  - MiniMax via Anthropic, e.g. `spring.ai.anthropic.chat.model=MiniMax-M3`. ([minimax-chat](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/minimax-chat.adoc))
  - OCI GenAI "is now maintained by the community" via oracle/spring-cloud-oracle. ([oci-genai-chat](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/oci-genai-chat.adoc))
- The Chat Models Comparison table at 2.0.1 lists: Anthropic, DeepSeek (OpenAI-proxy), Google GenAI, Groq (OpenAI-proxy), Mistral AI, MiniMax (Anthropic-proxy), NVIDIA (OpenAI-proxy), Ollama, OpenAI, Perplexity (OpenAI-proxy) and Amazon Bedrock Converse. Perplexity is marked with no tool support. — [comparison.adoc@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/comparison.adoc)
- Proxy base URLs:
  - Groq: `spring.ai.openai.base-url=https://api.groq.com/openai/v1`
  - NVIDIA: `https://integrate.api.nvidia.com`
  - Perplexity: `https://api.perplexity.ai`
  - Docker Model Runner: `http://localhost:12434/engines/v1`
  — [groq-chat](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/groq-chat.adoc); [nvidia-chat](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/nvidia-chat.adoc); [perplexity-chat](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/perplexity-chat.adoc); [dmr-chat](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/dmr-chat.adoc)
- Docs nav at 2.0.1, beyond chat:
  - Embeddings: Bedrock Cohere/Titan, Azure OpenAI, Google GenAI, Mistral, OCI GenAI, Ollama, ONNX Transformers, OpenAI, PostgresML, Vertex AI text and multimodal.
  - Image: Azure OpenAI, OpenAI, Stability, Google GenAI.
  - Transcription: Azure OpenAI, OpenAI.
  - Speech: OpenAI, ElevenLabs.
  - Moderation: OpenAI, Mistral.
  — [nav.adoc@v2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/nav.adoc)
- Official SDKs inside the 2.0.1 modules:
  - `spring-ai-openai` depends on `com.openai:openai-java-core` plus `com.azure:azure-identity` and okhttp. ([pom](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/pom.xml))
  - `spring-ai-anthropic` depends on `com.anthropic:anthropic-java-core`. ([pom](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-anthropic/pom.xml))
  - `spring-ai-google-genai` depends on `com.google.genai:google-genai`. ([pom](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-google-genai/pom.xml))
  - `spring-ai-bedrock-converse` depends on `software.amazon.awssdk:bedrockruntime`. ([pom](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-bedrock-converse/pom.xml))
  - `spring-ai-deepseek` and `spring-ai-mistral-ai` depend on `spring-webflux` and `spring-ai-retry`, with no vendor SDK. ([deepseek pom](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-deepseek/pom.xml), [mistral pom](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-mistral-ai/pom.xml))
  - Managed versions in the root POM: openai-sdk 4.49.0, anthropic-sdk 2.52.0, awssdk 2.51.2. ([pom@v2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/pom.xml))
- The Anthropic module "is now built on `com.anthropic:anthropic-java` instead of the hand-rolled `RestClient` / `WebClient` implementation":
  - `AnthropicApi` was removed.
  - `maxTokens` now defaults to 4096 instead of 500.
  - The cache classes moved to `org.springframework.ai.anthropic`.
  — [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)
- 2.0.0-RC2 made the Anthropic and OpenAI HTTP clients configurable (#6354, #6294). — [2.0.0-RC2](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC2)
- [COMMUNITY] provider integrations in spring-ai-community: `spring-ai-watsonx-ai` (described as "Official Spring AI support for latest watsonx.ai services"), `spring-ai-replicate`, `qianfan`, `moonshot` and `spring-ai-typesafe`. Moonshot and Qianfan left core back in 1.0.0-RC1. — [spring-ai-community org](https://github.com/spring-ai-community); [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)

### Inferences
- In 2.0.1, "supported" covers three tiers:
  - First-class modules: OpenAI, Anthropic, Google GenAI, Bedrock Converse, Mistral, DeepSeek, Ollama.
  - Documented proxies with no module or starter of their own: Azure/Microsoft Foundry, Groq, NVIDIA, Perplexity, DMR, MiniMax.
  - Community or vendor-maintained modules: OCI, watsonx, Qianfan, Moonshot, Replicate.
- Hugging Face and ZhiPu have no documented replacement path in core docs.
- DeepSeek is labeled "OpenAI-proxy" in the comparison table, yet a dedicated `spring-ai-deepseek` module and starter still exist. Both paths work.

### Gaps
- I did not find where Hugging Face support moved after 2.0.0-M3. The commit says only "moved out of the main Spring AI repository", and no spring-ai-community repo for it was found.
- The google-genai SDK version is not a root-POM property and was not captured.
- I did not verify whether the Azure OpenAI and OCI embedding, image and transcription nav entries are full pages or stubs.

## Q5. Notable provider-specific capabilities exposed (2.0.1 GA unless noted)

### Takeaway
In 2.0.1 the provider modules expose many vendor-native features:
- **Anthropic** has the broadest set: extended or adaptive thinking with display control, citations, prompt caching strategies with 5-minute or 1-hour TTL, JSON-schema structured output with effort control, Skills, a built-in web search tool, service tier and inference geo.
- **Google GenAI:** thinking level and budget, thought signatures, cached content, server-side Google Search with custom tools, and ToolChoice (2.0.1).
- **Bedrock Converse:** prompt caching and native structured output.
- **OpenAI** (Chat Completions): reasoning content, `promptCacheKey`, and extraBody.
- **Unified cache-token metrics** on `Usage`.

The OpenAI **Responses API** (`OpenAiResponsesChatModel`, with server-executed tools including remote MCP) is **milestone-only in 2.1.0-M1**.

### Cited Findings
- Anthropic doc sections: Extended Thinking (with "Thinking with Tool Calls"), Citations (plain text, PDF, custom content), Prompt Caching (tool results, multi-block system, conversation history), Structured Output (JSON Schema, Effort Control), Service Tier, Per-Request HTTP Headers, Raw Response, Skills (pre-built Anthropic Skills, `AnthropicSkillContainer`, downloading generated files) and Web Search (domain filtering, user location). — [DOCS@2.0.1 anthropic-chat](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/anthropic-chat.adoc)
- New Anthropic options in 2.0:
  - Thinking display `Display.SUMMARIZED` / `Display.OMITTED` on the `thinkingEnabled` and `thinkingAdaptive` builders.
  - `AnthropicServiceTier.AUTO` / `STANDARD_ONLY`.
  - `AnthropicWebSearchTool` (`spring.ai.anthropic.chat.web-search-tool.*`).
  - `inferenceGeo("us"|"eu")`.
  - Existing cache settings: `AnthropicCacheStrategy` NONE / TOOLS_ONLY / SYSTEM_ONLY / SYSTEM_AND_TOOLS / CONVERSATION_HISTORY, and `AnthropicCacheTtl` FIVE_MINUTES / ONE_HOUR.
  - `CitationDocument` was renamed `AnthropicCitationDocument`.
  — [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)
- Unified cache metrics: `Usage.getCacheReadInputTokens()` and `getCacheWriteInputTokens()`. Anthropic and Bedrock report both directions; OpenAI and Google report reads only. — [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)
- OpenAI (Chat Completions), 2.0.1:
  - Reasoning content from reasoning models, including streaming. Examples cover DeepSeek R1 and vLLM.
  - Extra parameters for OpenAI-compatible servers.
  - Tool call additional properties.
  - Structured outputs, and audio input and output.
  — [DOCS@2.0.1 openai-chat](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/openai-chat.adoc)
- OpenAI additions by release:
  - 2.0.0: `promptCacheKey`, and OpenAI tool-call additional properties are preserved. ([v2.0.0](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0))
  - 2.0.0-RC2: replay of `reasoning_content` in assistant history. ([v2.0.0-RC2](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC2))
  - 2.0.1: strict mode defaults to `false`, audio speech streaming, enriched transcription options, and PDF media mapping restored. ([v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1))
- **[M 2.1.0-M1]** OpenAI Responses API (#7006):
  - `OpenAiResponsesChatModel` is "a second OpenAI `ChatModel` alongside the Chat Completions one".
  - The docs say: "Starting with GPT-5.4, Chat Completions does not support tool calling with a reasoning effort other than `none`. Responses does."
  - Server-executed tools: "Web search, file search, code interpreter, remote MCP and image generation".
  - Reasoning summaries are returned.
  - The model is **stateless**. Memory implementations that persist message parts are "expected in 2.1.0-M2".
  - The blog adds: "`HostedTool` enables the tools OpenAI runs on its side: web search, file search, code interpreter, remote MCP, and image generation." Switching to Responses is "a single property": `spring.ai.openai.chat.api=responses`. ([2.1.0-M1 blog](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/))
  — [openai-responses-chat.adoc@v2.1.0-M1](https://github.com/spring-projects/spring-ai/blob/v2.1.0-M1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/openai-responses-chat.adoc); [v2.1.0-M1 release](https://github.com/spring-projects/spring-ai/releases/tag/v2.1.0-M1)
- Google GenAI doc sections: Gemini Developer API or Vertex AI backends, Tool Choice, Server-Side Tool Invocations combined with client-side tools, Thinking Level and Thinking Budget, Thought Signatures, multimodal input (image, audio, video, PDF), and Cached Content (cached content service, auto-caching). — [DOCS@2.0.1 google-genai-chat](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/google-genai-chat.adoc)
- Google GenAI additions by release:
  - 2.0.0-M4: Google Search alongside custom tools for Gemini 3.x. ([v2.0.0-M4](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M4))
  - 2.0.0-M1: thought signatures for Gemini 3 Pro function calling. ([v2.0.0-M1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M1))
  - 2.0.1: ToolChoice support and Google image generation. ([v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1))
- Bedrock Converse: prompt caching (system, tools, conversation history, multi-block), reasoning with tool calls, native structured output (`outputSchema`), and multimodal images, video and documents. — [DOCS@2.0.1 bedrock-converse](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/bedrock-converse.adoc)
- DeepSeek: chat prefix completion, thinking mode, reasoning effort, and multi-round reasoning. DeepSeek V4 model constants were added in RC1. — [DOCS@2.0.1 deepseek-chat](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/deepseek-chat.adoc); [v2.0.0-RC1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC1)
- Mistral: native structured output, reasoning models (reasoning effort and content), the Mistral OCR API, and moderation. — [DOCS@2.0.1 mistralai-chat](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/mistralai-chat.adoc)
- Ollama: thinking mode (thinking levels for GPT-OSS only), auto-pulling models, and JSON-schema structured output. The property was renamed `spring.ai.ollama.chat.think`. — [DOCS@2.0.1 ollama-chat](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/ollama-chat.adoc); [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)

### Inferences
- For agents built on current OpenAI flagship models (GPT-5.4+) that need reasoning together with tool calls, 2.0.1 GA has a functional gap: Chat Completions only. The fix is in 2.1.0-M1, which is not GA.
- Server-side tools now exist in several vendor modules: Anthropic web search and Skills, Google Search grounding, and in M1 OpenAI's remote MCP and web search. This complements Spring AI's own client-side MCP tool loop. Server-side tool calls bypass `ToolCallingAdvisor`.

### Gaps
- I did not check whether Anthropic's remote MCP connector or code-execution tool are exposed in 2.0.1. Neither appears in the Anthropic doc section headings.

## Q6. What is new in MCP and agent support in the latest GA (2.0.x) vs the prior line (1.1.x)

### Takeaway
Moving from 1.1.x to 2.0.x is a major change for MCP:
- MCP SDK 0.18.3 becomes 2.0.0, and the spec moves from the 2025-06-18 era to 2025-11-25. Spring AI 1.1.8 pins SDK 0.18.3. The 2025-06-18 compliance statement comes from the SDK 1.0.0 release note; 0.18.x compliance was not separately checked.
- Annotations and the Spring transports are absorbed into Spring AI core.
- The default server protocol changes from SSE to Streamable HTTP.
- Server tool input validation is on by default.
- The client customizer API is consolidated.

For agents, the tool loop moved out of `ChatModel` into an auto-registered `ToolCallingAdvisor`, with tool-search and tool-call limits added. Patch release 2.0.1 added hardening (tool-call caps, `@McpTool` exception semantics, session caps). The 2.1.0-M1 milestone adds the OpenAI Responses API and `MessagePart`. On the MCP side it adds only stateless-server customizers and docs. There is no SDK bump, and MCP 2026-07-28 spec support is still in progress.

### Cited Findings
- MCP changes from 1.1.x to 2.0.0, per the upgrade notes:
  - MCP SDK upgraded to 2.0.0.
  - Server-side tool input validation is on by default; `validateToolInputs(false)` turns it off.
  - `Tool.inputSchema` changed from `JsonSchema` to `Map<String,Object>`.
  - `Builder.customizeRequest()` was removed from the HTTP client transports.
  - Sealed MCP schema interfaces were removed.
  - Required fields are enforced: `CreateMessageResult.model` and `CreateMessageRequest.maxTokens`.
  - `McpJsonParser` was removed.
  - The elicitation API uses `ParameterizedTypeReference` instead of `TypeReference`.
  - MCP annotations were migrated into Spring AI, and the Spring transport modules were moved into Spring AI.
  - `McpAsyncClientCustomizer` and `McpSyncClientCustomizer` were replaced by `McpClientCustomizer<B>`.
  - WebMvc transport headers are normalized to lowercase.
  - Conversation history was removed from `ToolContext`.
  — [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)
- `Mcp*ServerCustomizer` interfaces were introduced in 2.0.0-M2, along with MCP auto-config fixes for non-web contexts. — [v2.0.0-M2](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M2)
- Default server protocol: `SSE` at v1.1.8, `STREAMABLE` at v2.0.1. — [v1.1.8 McpServerProperties](https://github.com/spring-projects/spring-ai/blob/v1.1.8/auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-common/src/main/java/org/springframework/ai/mcp/server/common/autoconfigure/properties/McpServerProperties.java); [v2.0.1 McpServerProperties](https://github.com/spring-projects/spring-ai/blob/v2.0.1/auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-common/src/main/java/org/springframework/ai/mcp/server/common/autoconfigure/properties/McpServerProperties.java)
- The 1.1.8 tree had `mcp/mcp-annotations-spring` built on external `mcp-annotations` 0.9.0. 2.0.1 has the in-core `mcp/mcp-annotations` plus `mcp/transport/{mcp-spring-webflux, mcp-spring-webmvc}`. — [mcp@v1.1.8](https://github.com/spring-projects/spring-ai/tree/v1.1.8/mcp); [mcp@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1/mcp); [pom@v1.1.8](https://github.com/spring-projects/spring-ai/blob/v1.1.8/pom.xml)
- Agent changes in 2.0.0:
  - Automatic `ToolCallingAdvisor` registration.
  - Removal of the internal tool loop from `ChatModel`.
  - New `ToolSearchToolCallingAdvisor` auto-configuration and starter.
  - Changed `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER`.
  - Removal of `PromptChatMemoryAdvisor`.
  - Memory advisors now require a conversation ID.
  — [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)
- Agent and MCP additions in 2.0.1:
  - Configurable tool-call limits.
  - Cumulative usage across the loop.
  - Configurable tool-resolution fallback, now disabled by default.
  - Correct parent spans for tool-call observations in blocking mode.
  - `@McpTool` exception handling aligned with `@Tool`.
  - Server `maxSessions` and `sessionIdleTimeout`.
  — [v2.0.1 release](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1); [2.0.1 blog](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)
- **[M 2.1.0-M1]** The release contains: OpenAI Responses API (#7006), `VectorStore.upsert` for pre-computed embeddings (#7002), `MessagePart` in `AbstractMessage` (#6997), and the upgrade to Spring Boot 4.2 (#7003). The 2.1.0 upgrade notes cover only "Message Parts". The MCP SDK stays at 2.0.0.
- **[M 2.1.0-M1] MCP additions:**
  - "Stateless MCP servers can now be tuned with `McpStatelessSyncServerCustomizer` and `McpStatelessAsyncServerCustomizer` beans, mirroring the customizers the stateful servers already had."
  - New docs on "client scope and session boundaries" and "MCP and local tool name collisions".
  - "Work on support for the MCP 2026-07-28 specification is in progress".
  - `AssistantMessage`, `UserMessage` and `ToolResponseMessage` now hold an ordered list of `MessagePart`: `TextPart`, `ReasoningPart`, `ToolCallPart`, `ToolResultPart`, `MediaPart` and `UnknownPart`.
  — [Spring AI 2.1.0-M1 blog](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/)
  - The customizer classes exist at `mcp/common/src/main/java/org/springframework/ai/mcp/customizer/McpStateless{Sync,Async}ServerCustomizer.java` in the v2.1.0-M1 tree and are absent from the v2.0.1 tree. — [spring-ai@v2.1.0-M1 mcp/common](https://github.com/spring-projects/spring-ai/tree/v2.1.0-M1/mcp/common/src/main/java/org/springframework/ai/mcp/customizer) — [v2.1.0-M1 release](https://github.com/spring-projects/spring-ai/releases/tag/v2.1.0-M1); [upgrade-notes@v2.1.0-M1](https://github.com/spring-projects/spring-ai/blob/v2.1.0-M1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc); [pom@v2.1.0-M1](https://github.com/spring-projects/spring-ai/blob/v2.1.0-M1/pom.xml)
- Community MCP security had to change major lines for 2.0: `mcp-security` 0.1.x is for Spring AI 2.0.x, and 0.0.6 is for 1.1.x. — [mcp-security README](https://github.com/spring-ai-community/mcp-security)

### Inferences
- Applications migrating MCP code from 1.1.x to 2.0.x should expect the following:
  - Import changes for annotations and transports. OpenRewrite recipes exist.
  - New validation errors from stricter tool-input checking.
  - A silent transport change for HTTP servers that relied on the SSE default. Clients that speak only SSE need `spring.ai.mcp.server.protocol=SSE`.
  - A new `mcp-security` version line.
- The direction of travel is clear. SSE is deprecated at the SDK level, and Spring AI now treats Streamable HTTP and Stateless as primary. SSE removal in a future Spring AI line is plausible, but no source announces it.

### Gaps
- Apart from "MCP 2026-07-28 spec support in progress", no detailed public roadmap was found for MCP in 2.1.x: no target milestone, and no target SDK version for 2026-07-28 support. The official agentic project has only a "November 2026" target and no published artifact names.
- The `toolCallExceptionClass` removal "in 2.1.0" is stated in the upgrade notes but had not happened as of 2.1.0-M1. This was not verified in code.
