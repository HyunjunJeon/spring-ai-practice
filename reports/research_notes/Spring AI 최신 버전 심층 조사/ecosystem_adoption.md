# Spring AI: Ecosystem, Adoption, Community Reception, and Competitive Position (as of 2026-10-01)

All GitHub, Maven Central, Stack Overflow and spring.io API figures below were pulled live on 2026-10-01 unless a different date is given. Each fact is tagged with the version it applies to. Release labels: **GA** = general availability, **RC** = release candidate, **M** = milestone, **SNAPSHOT** = nightly. Anything from 2025 is marked **[2025]**.

## Version anchoring (the baseline for every other section)

### Takeaway
The latest GA is **Spring AI 2.0.1**, released 2026-08-21. **2.0.0 GA** shipped 2026-06-12. **2.1.0-M1** is a milestone (2026-09-25) and **2.0.2-SNAPSHOT** is the nightly build. The 1.0.x and 1.1.x lines lost OSS support on 2026-06-30. The last OSS releases on those lines were 1.1.8 and 1.0.9, both on 2026-06-12.

### Cited Findings
- spring.io's Releases API lists **2.0.1 as GENERAL_AVAILABILITY with current=true**. It also lists 2.1.0-M1 (PRERELEASE), 2.0.2-SNAPSHOT (SNAPSHOT), and 1.1.8 and 1.0.9 (GA, current=false). — [api.spring.io/projects/spring-ai/releases](https://api.spring.io/projects/spring-ai/releases)
- The spring.io project page shows 2.0.1 as the current release. — [spring.io/projects/spring-ai](https://spring.io/projects/spring-ai)
- Support generations from spring.io:

  | Line | Initial release | OSS support ends | Commercial support ends |
  |---|---|---|---|
  | 1.0.x | 2025-05-31 | 2026-06-30 | 2027-06-30 |
  | 1.1.x | 2025-11-30 | 2026-06-30 | 2032-06-30 |
  | 2.0.x | 2026-06-30 (planned date in the table) | 2027-07-31 | 2028-07-31 |

  — [api.spring.io/projects/spring-ai/generations](https://api.spring.io/projects/spring-ai/generations)
- Spring Boot generations: 3.5.x OSS ends 2026-06-30 (commercial 2032-06-30). 4.0.x OSS ends 2026-12-31. 4.1.x OSS ends 2027-07-31. 4.2.x is planned for 2026-11-30. — [api.spring.io/projects/spring-boot/generations](https://api.spring.io/projects/spring-boot/generations)
- GitHub release dates:
  - 2.0 milestones: 2.0.0-M1 2025-12-11, M2 2026-01-23, M3 03-17, M4 03-26, M5 04-27, M6 05-08, M7 05-22, M8 05-27
  - 2.0 RCs and GA: RC1 06-06, RC2 06-09, **2.0.0 GA 06-12**, **2.0.1 GA 08-21**
  - Next line: 2.1.0-M1 09-25 (pre-release)
  - 1.x patches: 1.1.0 2025-11-12; 1.1.x through 1.1.8 (2026-06-12); 1.0.x through 1.0.9 (2026-06-12)

  — [GitHub releases](https://github.com/spring-projects/spring-ai/releases)
- Maven Central `spring-ai-bom` metadata reports `<latest>` and `<release>` = **2.1.0-M1**. The version list runs from 1.0.0-M5 to 2.1.0-M1 (lastUpdated 2026-09-24). Maven's "release" tag does **not** mean GA here: 2.1.0-M1 is a milestone. — [Maven metadata](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml)
- Dates differ by one day between sources. Artifacts reached Maven Central on 2026-08-20 (2.0.1) and 2026-09-24 (2.1.0-M1), per the parallel version-status researcher. GitHub release entries are dated 2026-08-21 and 2026-09-25. Both are correct; they measure publication to Central versus the GitHub announcement. — [GitHub releases](https://github.com/spring-projects/spring-ai/releases)
- **[2025]** Spring AI 1.0 GA was announced on 2025-05-20. — [HN post 2025-05-20](https://news.ycombinator.com/item?id=44043240)
- The 2.0 GA slipped. HeroDevs wrote that "Spring AI 2.0 is coming May 28", and a GitHub discussion asked "Spring AI 2.0 delayed?". GA actually shipped 2026-06-12. — [HeroDevs](https://www.herodevs.com/blog-posts/spring-ai-2-0-is-coming-may-28-here-is-why-that-makes-the-june-30-deadline-more-urgent-not-less); [Discussion #5427](https://github.com/spring-projects/spring-ai/discussions/5427)
- 2.0.0 GA baseline: Spring Boot 4.0/4.1, Spring Framework 7.0, Jackson 3, and full JSpecify null-safety. — [Spring AI 2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- **Java baseline (conflicting sources):** a dev.to comparison says Java 17 minimum ([dev.to, 2026-08-25](https://dev.to/jamilxt/spring-ai-vs-langchain4j-in-2026-the-choice-got-harder-here-is-how-to-decide-429n)). A besthub.dev summary of 2.0 RC lists "Java 21" as a migration cost ([besthub.dev](https://www.besthub.dev/articles/spring-ai-2-0-rc-released-migration-guide-new-features-and-langchain4j-comparison-f10aff30a6bf)). The other researchers' API and migration notes should settle this.

### Inferences
- The "latest" resolver pitfall is my inference: Maven's `<release>` tag points to the **milestone** 2.1.0-M1. Tooling or bots that pick "latest" (Dependabot or Renovate with loose rules, `LATEST`/`RELEASE` metaversions, some IDE inspections) can pull a milestone into a project that meant to stay on 2.0.1 GA.
- Anyone on Spring AI 1.x without a commercial subscription has been on an unsupported line since 2026-06-30. Spring AI 2.0.x requires Boot 4.x (2.0 GA baseline). Boot 3.5 OSS support ended separately on the same day (2026-06-30). So after that date there is no OSS-supported Boot 3.x + Spring AI combination; the only OSS-supported path is Boot 4.x + Spring AI 2.0.x.

### Gaps
- The 2.1.0 GA target date is not on spring.io's project page. A JS-rendered release calendar exists, but I could not read it.
- The coordinator relayed that an experimental "Spring AI Agents" project is slated for Nov 2026. I did not verify this here; see the version-status researcher's notes.

## Spring AI vs LangChain4j and other alternatives (Quarkus LangChain4j, Embabel, Google ADK for Java, Semantic Kernel Java, Koog)

### Takeaway
In 2026 the choice mostly turns on platform. Spring AI 2.0.x is the natural default for teams already on Boot 4, and it uses an advisor-chain design inside Spring. LangChain4j (1.20.x) is framework-agnostic. It has more providers and vector stores, a richer workflow-style agentic module, and Spring starters for **both Boot 3.5 and Boot 4**, so it is the easy path for Boot 3.x estates and for Quarkus, Micronaut or plain Java. Embabel, Koog and Google ADK compete one level higher, as agent frameworks. Embabel is built on Spring AI. Semantic Kernel Java has effectively stopped shipping releases.

### Cited Findings
**Current versions and GitHub size (2026-10-01)**

| Project | Latest release (date) | Stars | Forks | Source |
|---|---|---|---|---|
| Spring AI | 2.0.1 GA (2026-08-21) | 9,503 | 2,936 | [repo](https://github.com/spring-projects/spring-ai) |
| LangChain4j | 1.20.2 (2026-09-28) | 13,184 | 2,580 | [repo](https://github.com/langchain4j/langchain4j), [releases](https://github.com/langchain4j/langchain4j/releases) |
| Spring AI Alibaba | 2.0.0-M1.1 (see Ecosystem) | 10,952 | 2,435 | [repo](https://github.com/alibaba/spring-ai-alibaba) |
| Embabel Agent | 1.5.2 (2026-09-16) | 4,480 | 438 | [repo](https://github.com/embabel/embabel-agent) |
| Koog (JetBrains, Kotlin) | 1.3.0 (2026-09-24) | 4,596 | 482 | [repo](https://github.com/JetBrains/koog) |
| Google ADK Java | v1.10.1 (2026-09-18) | 1,741 | 431 | [repo](https://github.com/google/adk-java) |
| Quarkus LangChain4j | 1.14.1 (2026-09-30) | 321 | 248 | [repo](https://github.com/quarkiverse/quarkus-langchain4j) |
| Semantic Kernel Java | java-1.4.3 GA (2025-02-26); java-1.4.4-RC1 (2025-05-06) | 276 | 56 | [repo](https://github.com/microsoft/semantic-kernel-java) |

- Contributors (GitHub API, including anonymous): Spring AI **568**, LangChain4j **499**. — [Spring AI contributors](https://github.com/spring-projects/spring-ai/graphs/contributors); [LangChain4j contributors](https://github.com/langchain4j/langchain4j/graphs/contributors)
- LangChain4j releases often: 1.20.0 (09-04), 1.19.1 and 1.19.2 (09-07), 1.19.3 (09-15), 1.20.1 (09-25) and 1.20.2 (09-28), all in September 2026. — [LangChain4j releases](https://github.com/langchain4j/langchain4j/releases)
- Semantic Kernel Java's repo still had a push on 2026-09-28, but it has published no release since the 2025-05-06 RC. — [semantic-kernel-java releases](https://github.com/microsoft/semantic-kernel-java/releases)

**Design philosophy and features (2026 comparisons)**
- Spring AI 2.0 sends memory, RAG, tool calling and tool search through an **advisor chain**, with blocking and streaming on the same path. `ToolCallingAdvisor` has four loop extension points (for example, approval gates between tool iterations). `ToolSearchToolCallingAdvisor` does progressive tool disclosure. LangChain4j instead composes **workflows** in `langchain4j-agentic`: typed agent interfaces, sequential/loop/conditional/parallel workflows, a supervisor agent, a BDI agent pattern, tool-action compensation, crash-resilient human-in-the-loop suspend/resume, and an `AgenticScope`. — [dev.to (jamilxt), 2026-08-25](https://dev.to/jamilxt/spring-ai-vs-langchain4j-in-2026-the-choice-got-harder-here-is-how-to-decide-429n)
- Provider breadth: LangChain4j advertises "20-plus model providers and roughly 30 vector stores". Spring AI 2.0 deliberately narrowed its core. The GA blog says the core now covers **seven** providers: OpenAI (SDK-based only), Anthropic (SDK only), Amazon Bedrock, Google GenAI, Mistral AI, DeepSeek and Ollama. Azure and OCI moved to vendor-maintained community projects. — [dev.to](https://dev.to/jamilxt/spring-ai-vs-langchain4j-in-2026-the-choice-got-harder-here-is-how-to-decide-429n); [Spring AI 2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
  - Discrepancy: a search summary of the same dev.to piece said "about eight" first-party providers.
  - 2.0.1 then **deprecated the Mistral AI chat models** ([2.0.1 blog](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)).
  - Some Chinese providers were removed, so users need OpenAI-compatible adapters ([besthub.dev](https://www.besthub.dev/articles/spring-ai-2-0-rc-released-migration-guide-new-features-and-langchain4j-comparison-f10aff30a6bf)).
- Platform coupling, the deciding factor:
  - "If you're on Spring Boot 4 already, Spring AI 2.0 is a clean fit; if stuck on Boot 3.x... LangChain4j avoids the tie to your framework version." The same author says most production estates in 2026 are still on Boot 3.x. — [dev.to](https://dev.to/jamilxt/spring-ai-vs-langchain4j-in-2026-the-choice-got-harder-here-is-how-to-decide-429n)
  - LangChain4j's Spring integration builds against **both** Boot 3.5.13 and Boot 4.0.5. — [langchain4j-spring pom.xml](https://raw.githubusercontent.com/langchain4j/langchain4j-spring/main/pom.xml)
  - LangChain4j's "Not compatible with Spring Boot 4.0" bug (#4268) was opened 2025-12-22 and closed 2026-04-08. — [langchain4j#4268](https://github.com/langchain4j/langchain4j/issues/4268)
- LangChain4j 1.20.0 (2026-09-04) is closing gaps that used to favour Spring AI:
  - It adds **experimental non-blocking/reactive AI Services** (`CompletableFuture` and `Flow.Publisher`), covering tools, memory, guardrails and the retrieval graph.
  - It adds **opt-in Jackson 3** support (`langchain4j-jackson3`).

  — [LangChain4j 1.20.0 release notes](https://github.com/langchain4j/langchain4j/releases/tag/1.20.0)
- RAG: comparison blogs describe LangChain4j as giving "more control and depth" in RAG and vector stores, and Spring AI as faster for "getting a standard RAG flow running". — search summary of 2026 comparison posts ([patotski.com](https://patotski.com/blog/spring-ai-vs-langchain4j/), [rusanau.me](https://rusanau.me/blog/spring-ai-vs-langchain4j/), [springdevpro.com](https://springdevpro.com/spring-ai/comparison/spring-ai-vs-langchain4j/)); not fetched individually.
- **Embabel** (agent framework layer):
  - It is built on Spring AI and uses Goal-Oriented Action Planning (GOAP): it plans at runtime and replans after each step, and can mix GOAP with explicit state machines.
  - It routes models per action or role, across OpenAI, Anthropic, Gemini, Bedrock, Mistral, DeepSeek, Ollama and LMStudio.
  - InfoQ contrasts it with LangChain4j/LangGraph4j (graph-based, static node connections), Koog (language-feature-based), and Akka's Agentic Platform (infrastructure-focused).

  — [InfoQ, Embabel 1.0 (Aug 2026)](https://www.infoq.com/news/2026/08/embabel-1/)
- Rod Johnson's analogy: "Spring AI is like the Servlet API, and Embabel is like Spring MVC." — via search summary of [danvega.dev Embabel 1.0 GA post](https://www.danvega.dev/blog/embabel-1-0-ga) (not fetched directly)
- A 2026 three-way comparison adds **AgentScope Java** (Alibaba) as a third option alongside LangChain4j and Spring AI. — [tokensmind.ai](https://tokensmind.ai/blog/java-ai-framework-showdown-langchain4j-vs-spring-ai-vs-agentscope-java-2026/) (title only; not fetched)
- Spring I/O 2026 (April) had a session "Comparing Agentic AI Frameworks for Java" by Timo Salm. — [Spring I/O 2026 search results / foojay](https://foojay.io/today/spring-i-o-2026-field-notes-from-barcelona/) (the talk's conclusions were not retrieved)
- **[2025-era framing, still repeated in early 2026]** "LangChain4j is the most-adopted Java LLM integration library." — [Keyhole Software, Java Trends 2026](https://keyholesoftware.com/java-trends-2026/) (no data given for the claim)

### Inferences
- Rule of thumb from the sources above:
  - Spring AI 2.0.x fits Boot 4 shops that want Spring-native auto-configuration, observability and MCP server annotations.
  - LangChain4j fits Boot 3.x, Quarkus (via Quarkus LangChain4j) or plain Java, and teams that need niche providers, deeper RAG control, or workflow-style multi-agent orchestration out of the box.
  - Embabel is not an alternative to Spring AI. It sits on top of Spring AI 2.0.x for goal-driven agents.
  - Google ADK Java and Koog are vendor-ecosystem agent frameworks: Google/Gemini and Kotlin/JetBrains respectively.
  - Semantic Kernel Java is not a realistic 2026 choice, given 17 months without a release.
- Spring AI's provider trimming in 2.0 (seven in core, Mistral chat deprecated in 2.0.1) widens LangChain4j's breadth lead. Spring AI is betting on depth plus vendor-maintained community modules (Azure, OCI, watsonx and others) rather than breadth in core.
- Star counts understate Spring AI relative to LangChain4j and Alibaba, because Spring users usually arrive via start.spring.io and BOMs, not GitHub. Spring AI's higher contributor count (568 vs 499) is arguably the better health signal.

### Gaps
- **Performance:** I found no credible 2026 benchmark comparing Spring AI and LangChain4j on latency, throughput or memory. A search summary claimed "Spring AI adds ~200–400 ms to Boot startup; LangChain4j on Quarkus native starts in <100 ms". I could not tie it to a specific page with a method, so treat it as unverified.
- Timo Salm's Spring I/O 2026 framework comparison: the conclusions were not retrievable in text form.
- Google ADK Java and Koog: no head-to-head 2026 comparison with Spring AI was found beyond InfoQ's brief Koog mention.
- Quarkus LangChain4j (1.14.1): I collected only its version and GitHub size. No qualitative 2026 comparison with Spring AI 2.0 was gathered on developer experience or native-image behaviour.

## Ecosystem projects (Spring AI Alibaba, spring-ai-community, Embabel, Spring portfolio integrations, third-party integrations)

### Takeaway
The ecosystem is active. Most new agentic capability (A2A, session memory, tool search, Claude-Code-style agent utilities, MCP security, Bedrock AgentCore) lives in the **spring-ai-community** org, not in core. **Embabel 1.x** tracks Spring AI 2.0.1 GA. **Spring AI Alibaba lags**: its released line is still on Spring AI 1.1.2 / Boot 3.5.8. Its only Spring AI 2.x release is a milestone built on Spring AI **2.0.0-M1**, and an unreleased 2.x branch targets 2.0.0 GA.

### Cited Findings
**Spring AI Alibaba**
- 10,952 stars. Released versions: v1.1.0.0 (2025-12-30), v1.1.2.0 (2026-02-02), v1.1.2.1 and v1.1.2.2 (2026-03-09/10). **v2.0.0-M1.1 (2026-06-25)** upgrades to **Spring AI 2.0.0-M1** and **Spring Boot 4.0.0**. — [alibaba/spring-ai-alibaba releases](https://github.com/alibaba/spring-ai-alibaba/releases)
- `main` branch pom: revision **1.1.2.2**, `spring-ai.version` **1.1.2**, Spring Boot **3.5.8**. — [main pom.xml](https://raw.githubusercontent.com/alibaba/spring-ai-alibaba/main/pom.xml)
- `2.x` branch pom: revision **2.0.0.0-RC1**, Spring AI **2.0.0**, Spring Boot **4.1.0**. Last commit 2026-07-26. No GitHub release has been published from it. — [2.x pom.xml](https://raw.githubusercontent.com/alibaba/spring-ai-alibaba/2.x/pom.xml)
- Maven Central `com.alibaba.cloud.ai:spring-ai-alibaba-bom`: latest and release = **2.0.0-M1.1**, so no 2.0.0.0 RC or GA has been published. — [Maven metadata](https://repo1.maven.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-bom/maven-metadata.xml)
- Features:
  - Graph runtime "inspired by LangGraph", effectively a Java LangGraph ([java2ai.com docs](https://java2ai.com/en/docs/1.0.0.2/tutorials/graph/whats-spring-ai-alibaba-graph/))
  - Agent Framework with ReactAgent; multi-agent patterns (subagent, supervisor, skills/progressive disclosure, routing, handoffs); AgentScope Java integration (`spring-ai-alibaba-starter-agentscope`, v1.1.2.2); A2A with Nacos-based discovery

  — [v1.1.2.2 release notes](https://github.com/alibaba/spring-ai-alibaba/releases); [repo README](https://github.com/alibaba/spring-ai-alibaba)
- GitHub issue #3527 asks for Java 8 support ("我们需要spring ai框架支持java8", 9 reactions, 15 comments). It points to a legacy-JDK constraint in parts of the Chinese user base. — [spring-ai#3527](https://github.com/spring-projects/spring-ai/issues/3527)

**spring-ai-community GitHub org** (30 repos; stars as of 2026-10-01) — [github.com/spring-ai-community](https://github.com/spring-ai-community)
- `awesome-spring-ai` (856)
- `spring-ai-agent-utils` (647): "Claude Code-inspired tools and agent skills"
- `spring-ai-playground` (143): a local execution layer for agent tools and MCP
- `mcp-security` (115): Spring Security configuration for MCP
- `spring-ai-tool-search-tool` (78)
- `spring-ai-agentcore` (67): Amazon Bedrock AgentCore integration
- `spring-ai-session` (51): event-sourced conversation memory
- `spring-ai-a2a` (48): A2A protocol servers
- `mcp-annotations` (45)
- `spring-ai-typesafe` (42)
- `spring-ai-vaadin` (29)
- `spring-ai-watsonx-ai` (27): "Official Spring AI support for latest watsonx.ai services"
- `spring-ai-replicate`, `qianfan` (Baidu), `moonshot`, and `acp-autoconfig` (ACP Java SDK)
- The 2.0.0 GA blog names `spring-ai-session` and `spring-ai-agent-utils` as companion community projects, and says 2.0 ships the **MCP Java SDK 2.0.0** with the `@McpTool`, `@McpResource` and `@McpPrompt` annotation model. — [Spring AI 2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- **[2025]** The org was announced 2025-10-07. Agent Client and Agent Bench followed 2025-10-28. Agent Client offers a portable `AgentClient` over CLI agents (Claude Code, Gemini, Amazon Q, Amp, Codex). — [community announcement](https://spring.io/blog/2025/10/07/spring-ai-community-announcement/); [Agent Client/Bench blog](https://spring.io/blog/2025/10/28/agents-and-benchmarks/)
- On 2026-08-06 these repos were marked **"No longer maintained — active development moved to github.com/markpollack/…"**: `agent-client` (105★), `claude-agent-sdk-java` (69★), `agent-bench`, `agent-sandbox`, `agent-judge`, and two tutorials. Mark Pollack is the Spring AI lead at Broadcom. — [spring-ai-community org listing](https://github.com/spring-ai-community); [Mark Pollack, spring.io author page](https://spring.io/authors/markpollack/). I found no public explanation for the move.

**Embabel**
- **v1.0.0 GA on 2026-07-20.** RC1 came 2026-07-13, then 1.5.0 (2026-08-11), 1.5.1 (08-24) and 1.5.2 (09-16). — [embabel-agent releases](https://github.com/embabel/embabel-agent/releases). InfoQ's article is dated August 2026 and the InfoQ summary gives "August 3". GitHub is the primary source for the date. — [InfoQ](https://www.infoq.com/news/2026/08/embabel-1/)
- Embabel's `main` pom pins `openai-java-core` to "the one that spring-ai-openai **2.0.1** depends on". So it tracks the latest Spring AI GA. — [embabel-agent pom.xml](https://raw.githubusercontent.com/embabel/embabel-agent/main/pom.xml)
- Rod Johnson (creator of Spring) presented "Building Killer AI Agents on Your Spring Stack with Embabel" at Spring I/O 2026. — [foojay Spring I/O 2026 field notes](https://foojay.io/today/spring-i-o-2026-field-notes-from-barcelona/)

**Third-party and vendor integrations**
- Azure and OCI support moved out of core to vendor-maintained community projects in 2.0. — [2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- The dev.to author notes "first-party integrations from Oracle and Microsoft". — [dev.to](https://dev.to/jamilxt/spring-ai-vs-langchain4j-in-2026-the-choice-got-harder-here-is-how-to-decide-429n)
- IBM watsonx.ai is maintained in the community org. — [spring-ai-watsonx-ai](https://github.com/spring-ai-community/spring-ai-watsonx-ai)
- Vaadin published "Spring AI 2.0 in Practice", and `spring-ai-vaadin` provides UI components. — [Vaadin blog](https://blog.vaadin.com/spring-ai-2-0-in-practice-adding-llm-features-to-a-java-web-app)
- Open feature requests for new vector stores in 2.0.x: Redis 8 vectorset (#6819), S3 Vectors (#3834, 9 reactions), and PgVector query customization (#6832). — [#6819](https://github.com/spring-projects/spring-ai/issues/6819), [#3834](https://github.com/spring-projects/spring-ai/issues/3834), [#6832](https://github.com/spring-projects/spring-ai/issues/6832)

### Inferences
- Agentic features are moving outward. Core 2.0 provides the primitives (advisors, tool search, MCP), while A2A, session memory, agent utilities and CLI-agent clients incubate in spring-ai-community or Embabel. Teams evaluating "Spring AI for agents" in practice need to vet several community projects with separate release cadences, not just core.
- Spring AI Alibaba users face a version split. The supported line (1.1.2.x) sits on Spring AI 1.1.x and Boot 3.5.x, both past OSS support since 2026-06-30. The Boot 4 / Spring AI 2.x line is still milestone-only on Central more than three months after Spring AI 2.0.0 GA.
- The move of the agent-client and agent-bench family to a personal account is a governance signal worth watching. No reason was given; the repos were not archived but redirected.

### Gaps
- **Spring Modulith, Spring Cloud and Spring Batch integrations** with Spring AI 2.0.x: I found no official, first-party integration modules. One foojay article describes building "ClawRunr", an open-source agent runtime, by composing Spring AI (ChatClient/@Tool), Spring Events, JobRunr and Spring Modulith. That is a composition pattern, not an official integration, and the Spring AI version was not confirmed (search snippet only). — [foojay](https://foojay.io/today/how-we-built-a-java-ai-agent-by-connecting-the-dots-the-ecosystem-already-had/). Spring Cloud and Spring Batch: nothing found; needs a targeted docs check.
- Spring AI Alibaba's own GA plan for 2.0.0.0: no roadmap statement found.
- The spring.io GA blog has no contributor or commit statistics for 2.0.

## Adoption signals (GitHub, Maven, release cadence, case studies, conferences, surveys)

### Takeaway
Spring AI has strong institutional signals: a Broadcom-backed team, a dense 2026 release cadence, 568 contributors, and about a third of Spring I/O 2026 main-track sessions on Spring AI, MCP or agents. Grassroots signals are weaker. It has fewer GitHub stars than LangChain4j, very little Hacker News engagement, a near-dormant Stack Overflow tag, and no verifiable named enterprise case studies.

### Cited Findings
- GitHub (2026-10-01):
  - spring-projects/spring-ai has 9,503 stars, 2,936 forks and 568 contributors.
  - `open_issues_count` is 1,495, made up of **968 open issues + 527 open PRs**.
  - The repo was pushed to on 2026-09-30.

  — [repo](https://github.com/spring-projects/spring-ai); [open issues search](https://github.com/spring-projects/spring-ai/issues?q=is%3Aissue+is%3Aopen); [open PRs](https://github.com/spring-projects/spring-ai/pulls)
- Release cadence:
  - The 2.0 line went through eight milestones and two RCs in about six months (2025-12-11 to 2026-06-09), then GA on 06-12 and 2.0.1 on 08-21, about ten weeks later.
  - 1.0.x and 1.1.x shipped matching patch releases alongside the 2.0 milestones until 2026-06-12.

  — [GitHub releases](https://github.com/spring-projects/spring-ai/releases)
- 2.0.1 resolved "over 80 issues and pull requests plus 7 CVEs". — [2.0.1 blog](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)
- **Spring I/O 2026** (Barcelona, April 13–15):
  - More than 1,000 attendees; the organiser reports more than 1,300.
  - "roughly a third of the main-track sessions were about Spring AI, MCP, agents, or building on LLMs."
  - Talks included "The Spring AI Ecosystem in 2026: From Foundations to Agents" (Tzolov, Jędrzejczyk, Pollack), "From Completions to Goals: AI Agents with Spring AI" (Pollack), "From Assistants to Agents: Self-Improving Agentic Systems with Spring AI" (Tzolov), Rod Johnson on Embabel, and Timo Salm comparing Java agent frameworks.

  — [foojay field notes (Steve Poole, 2026-04-17)](https://foojay.io/today/spring-i-o-2026-field-notes-from-barcelona/); [Spring I/O session page](https://2026.springio.net/sessions/the-spring-ai-ecosystem-in-2026-from-foundations-to-agents/); [Spring team at Spring I/O 2026](https://spring.io/blog/2026/04/10/spring-io-2026-broadcom-speakers/); [Tzolov talk recording](https://www.youtube.com/watch?v=_ZZuzakjgxQ); [Ecosystem talk recording](https://www.youtube.com/watch?v=jcYKGVCVYWU)
- Foojay's take: "the shift from 'web framework' to 'agent platform' is the story this year." — [foojay](https://foojay.io/today/spring-i-o-2026-field-notes-from-barcelona/)
- Press coverage of 2.0 GA: InfoQ's Spring news roundup (June 2026) and Visual Studio Magazine ("Spring AI 2.0 Goes GA, Giving Java Developers a More Mature AI App Stack", 2026-06-29; returned 403 on fetch, so only the title was verified). — [InfoQ roundup](https://www.infoq.com/news/2026/06/spring-news-roundup-jun08-2026/); [Visual Studio Magazine](https://visualstudiomagazine.com/articles/2026/06/29/spring-ai-2-0-goes-ga-giving-java-developers-a-more-mature-ai-app-stack.aspx)
- Surveys:
  - JetBrains opened its **Developer Ecosystem Survey 2026** in May 2026. I found no published results breaking out Java AI frameworks. — [JetBrains research blog](https://blog.jetbrains.com/research/2026/05/developer-ecosystem-survey-2026-take-part-in-one-of-the-largest-developer-studies/)
  - Azul's 2026 State of Java survey reports that 62% of enterprises use Java to power AI functionality. This is not specific to any framework. — via [Keyhole Software](https://keyholesoftware.com/java-trends-2026/) (secondary)
- Stack Overflow (2026-10-01): the `spring-ai` tag has **99** questions total, 6 created since 2026-01-01. The `langchain4j` tag has **32** total, 1 since 2026-01-01. — [SO tag spring-ai](https://stackoverflow.com/questions/tagged/spring-ai); [SO tag langchain4j](https://stackoverflow.com/questions/tagged/langchain4j)
- Udemy and Inflearn course catalogues list multiple Spring AI courses ("7 Best Udemy Courses to Learn Spring AI in 2026"). — [reactjava substack](https://reactjava.substack.com/p/7-best-udemy-courses-to-learn-spring)

### Inferences
- Adoption is concentrated where Spring already dominates: enterprise Boot shops, driven by vendor and conference momentum. The low Stack Overflow and HN numbers mostly reflect the decline of those venues and the shift of support to GitHub issues and discussions. They should not be read as low usage. Support has moved to GitHub: issue #5334, an automated "Ecosystem CI Health Dashboard", has 331 comments.
- The Boot 4 requirement probably slows real 2.0 uptake through 2026, since sources say most estates are still on Boot 3.x. Boot 3.5 OSS EOL (2026-06-30) works in the other direction and pushes migrations.

### Gaps
- **Maven Central download trends** for `spring-ai-*` vs `langchain4j-*`: not publicly available. Sonatype per-artifact stats need publisher access, and no third-party 2026 figure was found.
- **Named enterprise case studies** using Spring AI 2.0.x: none verifiable found. Search results surfaced generic "production-ready" blog posts and unrelated case studies (for example, spring.new on Vertex AI, which is not shown to use Spring AI).
- SpringOne 2026 and Devoxx Belgium 2026 (October): no agenda data retrieved. Devoxx Belgium is usually held in October, so it may not have happened yet as of 2026-10-01.
- JetBrains State of Java/Developer Ecosystem 2026 figures on Spring AI usage: not published or not found. **[2025]** A "State of Java 2025" page exists ([lp.jetbrains.com](https://lp.jetbrains.com/the-state-of-java-2025/)), but I did not confirm whether it mentions Spring AI.

## Known issues and practical pitfalls in the latest GA (2.0.0 / 2.0.1)

### Takeaway
The main 2.0.x pain points are streaming combined with tool calling, reasoning-content handling, option and timeout regressions in 2.0.1, JSON-schema generation for tools and MCP, and a triage backlog (745 of 968 open issues untriaged). 2.0.1 fixed 7 CVEs. No OSS 1.x release has shipped since they were published (1.x OSS support ended 2026-06-30), and I did not verify whether 1.x code paths are affected.

### Cited Findings
**Regressions and bugs reported against 2.0.0/2.0.1 (open unless noted)**
- Removing `streamToolCallResponses` in 2.0 "broke tool calling manager" (#6614, labels: tool calling, advisors). There is a request for a replacement tool-execution callback API (#6435). — [#6614](https://github.com/spring-projects/spring-ai/issues/6614); [#6435](https://github.com/spring-projects/spring-ai/issues/6435)
- "Streaming tool calls fail with No value present in OpenAiChatModel$ChunkMerger" (2026-08-07). — [#6762](https://github.com/spring-projects/spring-ai/issues/6762)
- "Streaming + tool calling: per-round Usage is lost on 2.0.1 (OpenAI and Anthropic)" (2026-09-15). This is the token-usage reporting gap. — [#6996](https://github.com/spring-projects/spring-ai/issues/6996)
- "Since 2.0.1, OpenAI per-request timeout silently drops to 60s for any Prompt carrying options" (2026-09-03). — [#6915](https://github.com/spring-projects/spring-ai/issues/6915)
- "[openai] ChatClient.mutate() do not override the default values from application.yaml". — [#6620](https://github.com/spring-projects/spring-ai/issues/6620)
- Reasoning and thinking content:
  - Streaming `reasoningContent` is exposed only as accumulated full text, with no incremental API for SSE typewriter UIs ([#6833](https://github.com/spring-projects/spring-ai/issues/6833)).
  - Reasoning content cannot be retrieved from `OpenAiChatModel` while streaming ([#6736](https://github.com/spring-projects/spring-ai/issues/6736)).
  - Bedrock `ConverseChatResponseStream` drops `reasoningContent`, which breaks extended thinking with tools when streaming ([#6845](https://github.com/spring-projects/spring-ai/issues/6845)). The non-streaming variant ([#6413](https://github.com/spring-projects/spring-ai/issues/6413)) is closed.
- Provider quirks:
  - `GoogleGenAiChatModel` throws `NoSuchElementException` when a model omits `modelVersion` ([#6665](https://github.com/spring-projects/spring-ai/issues/6665)).
  - OpenAI embeddings are incompatible with Gemini's OpenAI-compatibility mode ([#7019](https://github.com/spring-projects/spring-ai/issues/7019)).
  - **[2025, still open]** gpt-5 returns 400 Bad Request via the OpenAI ChatClient (11 reactions, 18 comments; [#4097](https://github.com/spring-projects/spring-ai/issues/4097)).
  - **[2025, 1.0.3]** Qwen streaming tool_calls give "toolName cannot be null" ([#4790](https://github.com/spring-projects/spring-ai/issues/4790)).
- Tool and MCP schema generation:
  - MCP `generateOutputSchema` produces unsatisfiable schemas for optional or nullable fields ([#6950](https://github.com/spring-projects/spring-ai/issues/6950)).
  - Tool schemas nest `$defs` under a property while `$ref` points at the root ([#6633](https://github.com/spring-projects/spring-ai/issues/6633)).
  - `SyncMcpToolCallback` drops each content part's `type` ([#7049](https://github.com/spring-projects/spring-ai/issues/7049)).
  - Tool-call arguments arrive null or empty in 2.0 ([#6436](https://github.com/spring-projects/spring-ai/issues/6436)).
- New-advisor edge cases: `ToolSearchToolCallingAdvisor` loses discovered tools when nested memory is used and internal history is disabled. — [#7034](https://github.com/spring-projects/spring-ai/issues/7034)
- Closed 2.0.x issues worth knowing: Redis chat-memory auto-configuration failed on an unresolved jedis module ([#6584](https://github.com/spring-projects/spring-ai/issues/6584)); OpenAI streaming-mode bug ([#6441](https://github.com/spring-projects/spring-ai/issues/6441)).
- Observability gaps: no Micrometer metrics for image models ([#6799](https://github.com/spring-projects/spring-ai/issues/6799)); advisor span names contain stray spaces ([#6750](https://github.com/spring-projects/spring-ai/issues/6750)).
- Docs gap: an incorrect image in the 2.0 Upgrade Notes. — [#6745](https://github.com/spring-projects/spring-ai/issues/6745)

**Long-standing open issues by reactions** (carried into 2.0.x)
- TokenTextSplitter overlap: 52 reactions, the top issue ([#2123](https://github.com/spring-projects/spring-ai/issues/2123), 2025-01)
- A2A protocol support: 48 ([#2911](https://github.com/spring-projects/spring-ai/issues/2911)). It is partly addressed by the community `spring-ai-a2a` project.
- Agent support: 19 ([#403](https://github.com/spring-projects/spring-ai/issues/403))
- Multiple vector indexes or collections: 19 ([#468](https://github.com/spring-projects/spring-ai/issues/468))
- Claude on Vertex: 17 ([#1514](https://github.com/spring-projects/spring-ai/issues/1514))
- Multiple MCP servers per app: 16 ([#2912](https://github.com/spring-projects/spring-ai/issues/2912))
- Kotlin coroutines: 13 ([#3718](https://github.com/spring-projects/spring-ai/issues/3718))
- "Undesired and confusing logging": 13 ([#4694](https://github.com/spring-projects/spring-ai/issues/4694))
- Tool calls not persisted in chat memory: 12 reactions, 16 comments ([#2101](https://github.com/spring-projects/spring-ai/issues/2101))
- ChatClient timeout support: 10 ([#4103](https://github.com/spring-projects/spring-ai/issues/4103))
- OpenAI Realtime API: 11 ([#1464](https://github.com/spring-projects/spring-ai/issues/1464))

**Most-discussed open issues** (practical pitfalls)
- MCP client does not reconnect after a server restart ([#2740](https://github.com/spring-projects/spring-ai/issues/2740), 30 comments; [#3337](https://github.com/spring-projects/spring-ai/issues/3337))
- Deserialization error on the second MCP tool call ([#2818](https://github.com/spring-projects/spring-ai/issues/2818), 26 comments)
- Chat-memory advisor message-ordering problem with a default system message ([#4170](https://github.com/spring-projects/spring-ai/issues/4170), 20 comments)
- `block()` inside reactor-http-nio threads ([#2533](https://github.com/spring-projects/spring-ai/issues/2533))
- `@Tool returnDirect` ignored ([#3481](https://github.com/spring-projects/spring-ai/issues/3481))
- No hook to observe the actual tool invocation ([#3911](https://github.com/spring-projects/spring-ai/issues/3911))

**Open feature requests filed after 2.0 GA**
- Human-in-the-loop tool approvals ([#6916](https://github.com/spring-projects/spring-ai/issues/6916))
- First-class reranking ([#6524](https://github.com/spring-projects/spring-ai/issues/6524))
- MCP OAuth scopes ([#6740](https://github.com/spring-projects/spring-ai/issues/6740))
- A governance advisor (tool-call authorization, PII, cost budgets) ([#6683](https://github.com/spring-projects/spring-ai/issues/6683))
- Google GenAI timeouts ([#6580](https://github.com/spring-projects/spring-ai/issues/6580))

**Triage backlog and maintenance**
- **745 of 968** open issues carry the label `status: waiting-for-triage` (2026-10-01). 141 issues opened since 2.0.0 GA are **still open** (closed ones are not counted), and nearly all of the top-commented ones are untriaged. — [GitHub search: waiting-for-triage](https://github.com/spring-projects/spring-ai/issues?q=is%3Aissue+is%3Aopen+label%3A%22status%3A+waiting-for-triage%22); [issues created since 2026-06-12](https://github.com/spring-projects/spring-ai/issues?q=is%3Aissue+is%3Aopen+created%3A%3E%3D2026-06-12)

**Behavioural changes in 2.0.1 that can surprise upgraders from 2.0.0**
- OpenAI tool-calling **strict mode now defaults to false**.
- **Mistral AI chat models deprecated**.
- The **Redis chat memory auto-configuration module was renamed**.
- The DeepSeek API was revised.
- The Couchbase vector store now uses the Spring Boot-managed client.
- New tool-call limits guard against infinite agent loops.

— [2.0.1 blog](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)

**Security (2.0.1)**
- 7 CVEs were fixed:
  - CVE-2026-47851: PDF outline recursion
  - CVE-2026-47852: predictable ONNX model cache directory
  - CVE-2026-59279: MCP session allocation DoS
  - CVE-2026-59294: path traversal in `ResourceCacheService`
  - CVE-2026-59308: semantic-cache cross-tenant bypass via SHA-256 truncation
  - CVE-2026-59319: RediSearch tag injection in `RedisChatMemoryRepository`
  - CVE-2026-59318: `DefaultToolCallingManager` dispatching tools that were never advertised

  — [2.0.1 blog](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)
- No OSS 1.x release followed 1.1.8 and 1.0.9 (2026-06-12), and 1.x OSS support ended 2026-06-30. The dev.to author states that fixes for 1.x are "Enterprise Support only". — [GitHub releases](https://github.com/spring-projects/spring-ai/releases); [api.spring.io generations](https://api.spring.io/projects/spring-ai/generations); [dev.to](https://dev.to/jamilxt/spring-ai-vs-langchain4j-in-2026-the-choice-got-harder-here-is-how-to-decide-429n)

**Migration-cost pitfalls** (detail is out of scope here and covered by other researchers)
- Hard requirement on Boot 4 and Framework 7: "quietly turned a dependency choice into a platform migration question." — [dev.to](https://dev.to/jamilxt/spring-ai-vs-langchain4j-in-2026-the-choice-got-harder-here-is-how-to-decide-429n)
- Jackson 3, builder-only setters, and removed Chinese providers mean full regression testing. — [besthub.dev](https://www.besthub.dev/articles/spring-ai-2-0-rc-released-migration-guide-new-features-and-langchain4j-comparison-f10aff30a6bf)

### Inferences
- The cluster of streaming and tool-calling issues (#6614, #6435, #6762, #6996, #6833, #6845) suggests the 2.0 refactor that unified blocking and streaming behind `ToolCallingAdvisor` is the least mature area in 2.0.x. Teams that depend on streaming agents with accurate token accounting should test this on their specific provider before rollout, or wait for 2.0.2 or later.
- The 2.0.1 timeout regression (#6915) and the strict-mode default flip mean that even the patch release changes behaviour. Pin the version and run integration tests on every Spring AI patch bump.
- With 77% of open issues untriaged, community reports can sit unacknowledged. Organisations needing SLAs effectively need Broadcom/Tanzu commercial support.
- Running Spring AI 1.x in production after 2026-06-30 without a commercial subscription leaves the seven August 2026 CVEs unpatched, if the 1.x code paths are affected. That last point is my inference: the advisories' affected-version ranges were not checked.

### Gaps
- I did not retrieve the per-CVE affected-version ranges, i.e. whether each CVE affects 1.0.x and 1.1.x. Check the spring.io security advisories pages.
- Performance concerns, such as advisor-chain overhead or memory use in streaming: no measured 2026 data found.
- Documentation gaps beyond #6745 were not assessed systematically.

## Community sentiment (Reddit, Hacker News, Stack Overflow, blogs)

### Takeaway
2026 blog and press sentiment is broadly positive about the maturity of 2.0: null-safety, the advisor architecture, MCP and structured output. Recurring criticism targets the Boot 4 lock-in, provider trimming, migration cost, noisy logging, and slow issue triage. Grassroots discussion volume on HN and Stack Overflow is very low. Reddit could not be accessed.

### Cited Findings
- Positive press:
  - Visual Studio Magazine frames 2.0 GA as "giving Java developers a more mature AI app stack" (2026-06-29; headline only, the fetch was blocked).
  - A search summary credits the release with "unified tool calling, progressive tool discovery and self-correcting structured output".

  — [Visual Studio Magazine](https://visualstudiomagazine.com/articles/2026/06/29/spring-ai-2-0-goes-ga-giving-java-developers-a-more-mature-ai-app-stack.aspx)
- Comparison bloggers in 2026 agree that both frameworks are "production-ready and actively maintained". The common advice is Spring AI for Spring-convention-heavy teams and LangChain4j for flexibility, provider breadth or multi-step agents. — search summary of 2026 comparison posts ([prodevaihub.com](https://prodevaihub.com/spring-ai-vs-langchain4j-2026/), [patotski.com](https://patotski.com/blog/spring-ai-vs-langchain4j/), [javascript.plainenglish.io](https://javascript.plainenglish.io/spring-ai-vs-langchain4j-which-java-ai-framework-to-choose-in-2026-fd93bb7aa653?gi=f15b870ac374))
- Critical framing:
  - "The choice got harder." A Boot 4 hard dependency and "most production estates in 2026 still are [on Boot 3.x]". — [dev.to, 2026-08-25](https://dev.to/jamilxt/spring-ai-vs-langchain4j-in-2026-the-choice-got-harder-here-is-how-to-decide-429n)
  - HeroDevs (a vendor selling extended Boot 3 support) argued that Boot 4 migration need not be rushed for Spring AI 2.0. — [HeroDevs](https://www.herodevs.com/blog-posts/spring-ai-2-0-is-coming-soon-your-boot-4-0-migration-does-not-have-to-start-tomorrow)
- Hacker News (Algolia API, 2026-10-01):
  - "Self-Correcting Structured Output in Spring AI 2.0": 2 points, 0 comments (2026-06-23) ([HN](https://news.ycombinator.com/item?id=48648152)).
  - "Stateful agent workflows for Spring AI (graph-based…)": 1 point (2026-05-01) ([HN](https://news.ycombinator.com/item?id=47974827)).
  - **[2025]** The 1.0 GA posts drew about 3 points each ([HN](https://news.ycombinator.com/item?id=44043240)).
  - The best-performing Spring AI story was **[2025]** "Understanding Tool Calling in LLMs – Step-by-Step with REST and Spring AI": 101 points, 26 comments ([HN](https://news.ycombinator.com/item?id=44548906)).
- Stack Overflow: `spring-ai` has 99 questions total, 6 in 2026. — [SO](https://stackoverflow.com/questions/tagged/spring-ai)
- GitHub-native sentiment:
  - Logging complaints: "Undesired and confusing logging", 13 reactions ([#4694](https://github.com/spring-projects/spring-ai/issues/4694)).
  - Discontent over the delay: "Spring AI 2.0 delayed?" ([Discussion #5427](https://github.com/spring-projects/spring-ai/discussions/5427)).
  - Demand for Kotlin coroutines ([#3718](https://github.com/spring-projects/spring-ai/issues/3718)) and Java 8 ([#3527](https://github.com/spring-projects/spring-ai/issues/3527)).
- Conference sentiment: Spring I/O 2026 attendees were "generally positive". Hallway talk followed the agent emphasis, and traditional Java topics drew smaller groups. — [foojay](https://foojay.io/today/spring-i-o-2026-field-notes-from-barcelona/)

### Inferences
- Sentiment splits by audience. Spring-insider venues (Spring I/O, spring.io, InfoQ, vendor blogs) are enthusiastic. Independent practitioners mostly raise pragmatic upgrade-path concerns rather than quality complaints. The near-absence of HN discussion suggests Spring AI is seen as enterprise plumbing rather than a frontier-AI topic.

### Gaps
- **Reddit (r/java, r/SpringBoot):** the search tool blocks reddit.com, so no Reddit threads could be retrieved or quoted. This is a known blind spot.
- No Stack Overflow developer-survey 2026 data mentioning Spring AI was found.

## Korean-language community resources

### Takeaway
Korean resources exist but are modest: Inflearn courses, a KSUG Spring AI meetup, and Spring Camp talks. I found no Korean material specific to Spring AI 2.0.x. Treat these as starting points to verify.

### Cited Findings
- Inflearn has a Spring AI tag curation page. — [inflearn.com/tag-curation/skill/spring-ai](https://www.inflearn.com/tag-curation/skill/spring-ai)
- Inflearn course "나도! 스프링으로 인공지능을 할 수 있다(인프1탄)": per a search snippet, rated 4.6 with 34 reviews and 300+ students at ₩49,500. — [Inflearn course](https://www.inflearn.com/course/%EC%8A%A4%ED%94%84%EB%A7%81%EC%9C%BC%EB%A1%9C-%EC%9D%B8%EA%B3%B5%EC%A7%80%EB%8A%A5-%EC%9D%B8%ED%94%841%ED%83%84)
- "Spring AI 실전 가이드: RAG 챗봇 만들기": free, rated 4.9 with 21 reviews and 1,200+ students, per a search snippet with no direct URL captured. — [Inflearn Spring AI tag](https://www.inflearn.com/tag-curation/skill/spring-ai)
- KSUG (Korea Spring User Group) hosted a "Spring AI Meetup" on Spring AI's structure, contributing, and adoption cases. The event date was not captured. — [event-us.kr/ksug/event/114010](https://event-us.kr/ksug/event/114010); [ksug.org](https://www.ksug.org/)
- **[2024]** Spring Camp 2024 included the session "Spring AI : LLM에도 봄이 찾아오다" (황민호). — [Inflearn Spring Camp 2024](https://www.inflearn.com/course/offline/springcamp2024)

### Inferences
- Korean learning material targets 1.x-era APIs. Korean teams adopting 2.0.1 should rely on the official reference docs and upgrade notes rather than older courses.

### Gaps
- I did not check whether the Inflearn courses have been updated for 2.0.x, or the date of the KSUG meetup. No Korean blog reviews of 2.0 GA (2.0 후기) surfaced.
