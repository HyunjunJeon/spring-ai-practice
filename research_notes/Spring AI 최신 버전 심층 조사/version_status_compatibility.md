# Spring AI: release status, version timeline and compatibility matrix (as of 2026-10-01)

Research date: 2026-10-01. All version and date facts below come from live primary sources fetched on that date: Maven Central, the GitHub REST API, spring.io's public project-metadata API, docs.spring.io and spring.io/blog. Date sources often differ by a day or more, so each date names its source. Dates given as "MC" are Maven Central upload timestamps from the directory listing. "GH" dates are GitHub Release `published_at` values (UTC). spring.io `initialReleaseDate` values are rounded to the end of the month and are therefore approximate.

## 1. What is the latest GA version as of 2026-10-01? What patch versions exist on that line? Are newer pre-releases in progress?

### Takeaway
**Spring AI 2.0.1 is the latest GA (General Availability) release.** It was uploaded to Maven Central on 2026-08-20 and published on GitHub on 2026-08-21. The 2.0.x line has two GA releases, 2.0.0 (2026-06-12) and 2.0.1. One version is newer but is not GA: **2.1.0-M1**, a milestone published 2026-09-24/25. Development builds also exist: 2.0.2-SNAPSHOT and 2.1.0-SNAPSHOT, plus 1.1.9-SNAPSHOT. Caution: Maven Central's `<latest>` and `<release>` fields both show 2.1.0-M1. That value is a milestone, not the current GA.

### Cited Findings
- Maven Central `spring-ai-bom` metadata shows `<latest>2.1.0-M1</latest>` and `<release>2.1.0-M1</release>`, with `lastUpdated` 20260924091844. The full version list ends `... 2.0.0-RC1, 2.0.0-RC2, 2.0.0, 2.0.1, 2.1.0-M1` — [Maven Central maven-metadata.xml](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml)
- spring.io's release API marks **2.0.1** as `"status": "GENERAL_AVAILABILITY", "current": true`. It lists 2.1.0-M1 as `"status": "PRERELEASE"` (repository `spring-milestones`) and 2.0.2-SNAPSHOT as `"status": "SNAPSHOT"`. The other GA entries are 1.1.8 and 1.0.9, both with `current: false` — [api.spring.io/projects/spring-ai/releases](https://api.spring.io/projects/spring-ai/releases)
- spring.io's generations API lists `latestPatch.oss` as 2.0.1 for 2.0.x, 1.1.8 for 1.1.x and 1.0.9 for 1.0.x. No 2.1.x generation is listed yet — [api.spring.io/projects/spring-ai/generations](https://api.spring.io/projects/spring-ai/generations)
- **Spring AI 2.0.1 (GA)**: GitHub release published 2026-08-21T10:23:13Z, `prerelease: false` — [GitHub release v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1). Maven Central directory dated 2026-08-20 12:21 — [Maven Central spring-ai-bom/](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/). GitHub milestone "2.0.1" closed 2026-08-20 with 139 issues closed and 1 left open — [GitHub milestones API](https://api.github.com/repos/spring-projects/spring-ai/milestones?state=all)
- spring.io's 2.0.1 blog post calls it "the first maintenance release on top of the 2.0.0 GA foundations". Source: search-result summary of the post, which was not fetched in full — [Spring AI 2.0.1 Available Now (2026-08-21)](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)
- The 2.0.1 release notes include new features as well as fixes: configurable tool-call limits (#6726), audio streaming in `OpenAiAudioSpeechModel` (#6733), Google image generation (#6408), ToolChoice for Google GenAI (#6531), an item for GraalVM with MCP annotations (#6427, listed under "New Features"), and a rename of `spring-ai-autoconfigure-model-chat-memory-redis` (#6416) — [GitHub release v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1)
- **Spring AI 2.0.0 (GA)**: GitHub release 2026-06-12T15:14:59Z; Maven Central 2026-06-12 10:56 — [GitHub release v2.0.0](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0); [Maven Central](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/). Blog dated 2026-06-12, author Christian Tzolov, "now available from Maven Central" — [Spring AI 2.0.0 GA Available Now](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- **Spring AI 2.1.0-M1 (milestone, pre-release)**: GitHub release 2026-09-25T07:28:53Z with `prerelease: true`; Maven Central 2026-09-24 09:12; GitHub milestone closed 2026-09-24 — [GitHub release v2.1.0-M1](https://github.com/spring-projects/spring-ai/releases/tag/v2.1.0-M1); [Maven Central](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/). The blog is dated 2026-09-25 and says the release "is now available from Maven Central" — [Spring AI 2.1.0-M1 Available Now](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/)
- **Snapshots**: the Spring snapshot repo metadata lists `1.1.8-SNAPSHOT, 1.1.9-SNAPSHOT, 2.0.0-SNAPSHOT, 2.0.1-SNAPSHOT, 2.0.2-SNAPSHOT, 2.1.0-SNAPSHOT`, lastUpdated 20260930141934 — [repo.spring.io snapshot metadata](https://repo.spring.io/snapshot/org/springframework/ai/spring-ai-bom/maven-metadata.xml). The Central Portal snapshot repo lists `2.1.0-SNAPSHOT` — [central.sonatype.com snapshots metadata](https://central.sonatype.com/repository/maven-snapshots/org/springframework/ai/spring-ai-bom/maven-metadata.xml)
- The docs version selector on 2026-10-01 shows "Stable 2.0.1 1.1.8 1.0.9 · Preview 2.1.0-M1 · Snapshot 2.1.0-SNAPSHOT 1.1.9-SNAPSHOT" — [Spring AI reference 2.0.1 index](https://docs.spring.io/spring-ai/reference/2.0.1/index.html)
- The unversioned docs URL serves the current GA. `https://docs.spring.io/spring-ai/reference/index.html` returns `<meta name="version" content="2.0.1">` and `<meta name="latest-version" content="true">`, so it is **not** a snapshot — [Spring AI reference (bare URL)](https://docs.spring.io/spring-ai/reference/index.html)
- The next patch on the GA line is tracked by the open GitHub milestone "2.0.2": no due date, 17 open and 33 closed issues — [GitHub milestones API](https://api.github.com/repos/spring-projects/spring-ai/milestones?state=open)

### Inferences
- A project that wants "the latest stable Spring AI" today should use BOM **2.0.1**. Tools that pick the highest version in Maven Central's `<release>` field, such as `versions:use-latest-releases` or the Gradle `latest.release` selector, may choose **2.1.0-M1** instead. Maven Central now hosts milestone builds, so its `<release>` field is not a reliable GA signal for Spring AI.
- 2.0.2 is in progress (33 issues already closed). Past releases have followed Spring Boot patch dates (see Q5). If that continues, 2.0.2 will likely ship alongside the next Boot 4.1.x patch. This is not confirmed.
- The 1.1.9-SNAPSHOT build is still listed even though 1.1.x OSS support ended on 2026-06-30 (see Q4). It may be work toward a commercial-only 1.1.x release. No source confirms this.

### Gaps
- I did not fetch the full 2.0.1 blog post, only its search snippet.
- The server-rendered HTML of [spring.io/projects/spring-ai](https://spring.io/projects/spring-ai) mentions "Spring AI 2.0.1". The Support table dates (2026-06-30, 2027-07-31, 2032-06-30, etc.) are not in that HTML because the table renders client-side. The support dates were therefore taken from the public api.spring.io project API. That API is very likely the page's data source, but I could not confirm this visually.

## 2. Full release timeline (0.8 → 1.0 → 1.1 → 2.0 → 2.1)

### Takeaway
Release history at a glance:
- **1.0.0 GA:** 2025-05-19/20.
- **1.0.x:** nine patches, ending with 1.0.9 on 2026-06-12.
- **1.1.0 GA:** 2025-11-12.
- **1.1.x:** eight patches, ending with 1.1.8 on 2026-06-12.
- **2.0 pre-releases:** eight milestones (M1 2025-12-11 → M8 2026-05-27) and two RCs (2026-06-05 and 2026-06-09).
- **2.0.0 GA:** 2026-06-12. **2.0.1:** 2026-08-20/21.
- **2.1.0-M1:** 2026-09-24/25.

On 2026-06-12, 1.0.9 and 1.1.8 shipped on the same day as 2.0.0 GA. They appear to be the last OSS releases of their lines.

### Cited Findings
Timeline table. MC = Maven Central directory timestamp ([listing](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/)). GH = GitHub Release `published_at` ([releases API](https://api.github.com/repos/spring-projects/spring-ai/releases?per_page=60)). Milestone = GitHub milestone `closed_at` ([milestones API](https://api.github.com/repos/spring-projects/spring-ai/milestones?state=all)).

| Version | Type | MC date | GH release date | Notes |
|---|---|---|---|---|
| 0.8.0 | pre-1.0 | not in bom MC metadata | n/a | milestone closed 2024-02-28 |
| 0.8.1 | pre-1.0 | not in bom MC metadata | n/a | milestone closed 2024-03-14 |
| 1.0.0-M1 | Milestone | not in bom MC metadata | n/a | milestone closed 2024-05-28 |
| 1.0.0-M2 | Milestone | not in bom MC metadata | n/a | milestone closed 2024-09-04 |
| 1.0.0-M3 | Milestone | not in bom MC metadata | n/a | milestone closed 2024-10-08 |
| 1.0.0-M4 | Milestone | not in bom MC metadata | n/a | milestone closed 2024-11-20 |
| 1.0.0-M5 | Milestone | 2025-01-29 | n/a | first version in MC metadata; milestone closed 2024-12-23. The MC upload is about 5 weeks after release, so it looks backfilled and the MC date is **not** the release date |
| 1.0.0-M6 | Milestone | 2025-02-14 | n/a | milestone closed 2025-02-18 |
| 1.0.0-M7 | Milestone | 2025-04-10 | n/a | milestone closed 2025-04-15 |
| 1.0.0-M8 | Milestone | 2025-04-30 | n/a | milestone closed 2025-05-01 |
| 1.0.0-RC1 | RC | 2025-05-13 | n/a | milestone closed 2025-05-14; [RC1 blog 2025-05-13](https://spring.io/blog/2025/05/13/spring-ai-1-0-0-RC1-released/) |
| **1.0.0** | **GA** | **2025-05-19** | n/a (tag `v1.0.0` exists, no GitHub Release) | milestone due/closed **2025-05-20** |
| 1.0.1 | GA patch | 2025-07-29 | 2025-08-08 | |
| 1.0.2 | GA patch | 2025-09-12 | 2025-09-17 | |
| 1.0.3 | GA patch | 2025-10-01 | 2025-10-01 | |
| 1.1.0-M1 | Milestone | 2025-09-08 | 2025-09-09 | |
| 1.1.0-M2 | Milestone | 2025-09-22 | 2025-09-22 | |
| 1.1.0-M3 | Milestone | 2025-10-03 | 2025-10-15 | |
| 1.1.0-M4 | Milestone | 2025-11-01 | 2025-11-03 | |
| 1.1.0-RC1 | RC | 2025-11-08 | 2025-11-08 | milestone note: "APIs frozen, bug fixes only" |
| **1.1.0** | **GA** | **2025-11-12** | **2025-11-12** | |
| 1.1.1 | GA patch | 2025-12-04 | 2025-12-05 | |
| 1.1.2 | GA patch | 2025-12-08 | 2025-12-09 | |
| 2.0.0-M1 | Milestone | 2025-12-11 | 2025-12-11 | milestone note: "Initial Spring Boot 4.0 migration and compatibility" |
| 2.0.0-M2 | Milestone | 2026-01-23 | 2026-01-23 | [blog 2026-01-23](https://spring.io/blog/2026/01/23/spring-ai-2-0-0-M2-available-now/) |
| 2.0.0-M3 + 1.1.3 + 1.0.4 | M + patches | 2026-03-16 | 2026-03-17 | [blog 2026-03-17](https://spring.io/blog/2026/03/17/spring-ai-2-0-0-M3-and-1-1-3-and-1-0-4-available/) |
| 2.0.0-M4 + 1.1.4 + 1.0.5 | M + patches | 2026-03-26 | 2026-03-26 | [blog 2026-03-26](https://spring.io/blog/2026/03/26/spring-ai-2-0-0-M4-and-1-1-4-and-1-0-5-available/) |
| 2.0.0-M5 + 1.1.5 + 1.0.6 | M + patches | 2026-04-27 | 2026-04-27 | |
| 2.0.0-M6 + 1.1.6 + 1.0.7 | M + patches | 2026-05-08 | 2026-05-08 | [blog 2026-05-08](https://spring.io/blog/2026/05/08/spring-ai-1-0-7-1-1-6-2-0-0-M6-available-now/) |
| 2.0.0-M7 + 1.1.7 + 1.0.8 | M + patches | 2026-05-22 | 2026-05-22 | milestone note: "Last milestone for Spring AI 2.0"; [blog dated 2026-05-23](https://spring.io/blog/2026/05/23/spring-ai-1-0-8-1-1-7-2-0-0-M7-available-now/) |
| 2.0.0-M8 | Milestone | 2026-05-27 | 2026-05-27 | an extra milestone after the "last" one (M7) |
| 2.0.0-RC1 | RC | 2026-06-05 | 2026-06-06 | milestone note: "APIs frozen, bug fixes and documentation only" |
| 2.0.0-RC2 | RC | 2026-06-09 | 2026-06-09 | |
| **2.0.0** | **GA** | **2026-06-12** | **2026-06-12** | [GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/) |
| 1.1.8 | GA patch (last OSS for 1.1.x) | 2026-06-12 | 2026-06-12 | |
| 1.0.9 | GA patch (last OSS for 1.0.x) | 2026-06-12 | 2026-06-12 | |
| **2.0.1** | **GA patch (current)** | **2026-08-20** | **2026-08-21** | [blog 2026-08-21](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/) |
| 2.1.0-M1 | Milestone | 2026-09-24 | 2026-09-25 | [blog 2026-09-25](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/) |

- GitHub Releases has no entries for 1.0.0 GA or earlier versions. Page 2 of the releases API is empty, and the oldest GitHub Release is v1.0.1. The tag `refs/tags/v1.0.0` does exist — [GitHub releases API page 2](https://api.github.com/repos/spring-projects/spring-ai/releases?per_page=60&page=2); [v1.0.0 tag ref](https://api.github.com/repos/spring-projects/spring-ai/git/refs/tags/v1.0.0)
- 1.0.0 GA date: Maven Central upload 2025-05-19 10:01; GitHub milestone "1.0.0" due 2025-05-20 and closed 2025-05-20T14:20Z — [Maven Central](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/); [milestones API](https://api.github.com/repos/spring-projects/spring-ai/milestones?state=all). A secondary write-up of the RC1 release says "GA Coming May 20, 2025" — [Medium (secondary)](https://codefarm0.medium.com/spring-ai-1-0-0-rc1-released-ga-coming-may-20-2025-2c13eac7cd96)
- spring.io's approximate `initialReleaseDate` values: 1.0.x "2025-05-31", 1.1.x "2025-11-30", 2.0.x "2026-06-30". These differ from the actual release dates (05-20, 11-12, 06-12) — [api.spring.io generations](https://api.spring.io/projects/spring-ai/generations)
- Secondary press coverage of 2.0 GA: Visual Studio Magazine, 2026-06-29, "Spring AI 2.0 Goes GA" — [Visual Studio Magazine](https://visualstudiomagazine.com/articles/2026/06/29/spring-ai-2-0-goes-ga-giving-java-developers-a-more-mature-ai-app-stack.aspx)

### Inferences
- Release cadence: about 6 months from 1.0 GA to 1.1 GA (May → Nov 2025) and 7 months from 1.1 GA to 2.0 GA (Nov 2025 → Jun 2026). The 2.0 cycle was long: 8 milestones and 2 RCs over 6 months.
- From March to June 2026, every 2.0 milestone shipped together with 1.1.x and 1.0.x patches (a "triple release" pattern). That pattern ended at 2.0.0 GA. No 1.x OSS release appears after 2026-06-12.

### Gaps
- I did not verify the spring.io blog URLs for 1.0 GA and 1.1 GA by reading their content. The site returns HTTP 200 but renders client-side, so the claims above rest on the Maven Central and GitHub milestone dates instead.
- 1.0.0-M1 through M4 and 0.8.x do not appear in the `spring-ai-bom` Maven Central metadata. Only that one artifact was checked; they were distributed via repo.spring.io/milestone. Their dates are GitHub milestone closure dates, not artifact publication dates.

## 3. Platform requirements of the latest GA (2.0.x / 2.0.1)

### Takeaway
Spring AI **2.0.x supports Spring Boot 4.0.x and 4.1.x**. 2.0.0 was built against Boot 4.1.0 and 2.0.1 against Boot 4.1.1. It targets **Spring Framework 7.0** and requires **Java 17** as the minimum: the pom sets `java.version` 17 and the compiler JDK range `[17.0.19,)`. It moves to **Jackson 3**, uses JSpecify null-safety annotations and ships MCP Java SDK 2.0.0. Spring AI's own Kotlin code is compiled with Kotlin 2.3.20. The 1.x lines target Boot 3.4/3.5, Java 17 and Kotlin 1.9.25. 2.1.0-M1 moves the baseline to Boot 4.2, still with Java 17.

### Cited Findings
- The getting-started guide (2.0.1) says: "Spring AI 2.0.x supports Spring Boot 4.0.x and 4.1.x." — [Getting Started 2.0.1](https://docs.spring.io/spring-ai/reference/2.0.1/getting-started.html)
- spring.io's generations data agrees: 2.0.x links to `spring-boot: ["4.0.x","4.1.x"]`, 1.1.x to `["3.5.x"]` and 1.0.x to `["3.4.x","3.5.x"]` — [api.spring.io generations](https://api.spring.io/projects/spring-ai/generations)
- The 2.0.0 GA blog says it is "designed to be used with Spring Boot 4.0 / 4.1" and Spring Framework 7.0, includes an "upgrade from Jackson 2 to Jackson 3", is "fully annotated with JSpecify annotations", and "ships with MCP Java SDK 2.0.0, compliant with the latest 2025-11-25 MCP specification" — [Spring AI 2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- 2.0.1 build pom: `<java.version>17</java.version>`, `<compiler.jdk.version>[17.0.19,)</compiler.jdk.version>`, `<kotlin.version>2.3.20</kotlin.version>`, `<kotlin.compiler.jvmTarget>${java.version}</kotlin.compiler.jvmTarget>`, `<spring-boot.version>4.1.1</spring-boot.version>`, `<mcp.sdk.version>2.0.0</mcp.sdk.version>`, `anthropic-sdk.version 2.52.0`, `openai-sdk.version 4.49.0` — [pom.xml @ v2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/pom.xml)
- 2.0.0 build pom: java.version 17, spring-boot.version **4.1.0**, kotlin 2.3.20, MCP SDK 2.0.0 — [pom.xml @ v2.0.0](https://github.com/spring-projects/spring-ai/blob/v2.0.0/pom.xml). The 2.0.0 release notes list "Upgrade to Spring Boot 4.1.0 (#6329)" and "Upgrade to MCP SDK 2.0.0 (#6385)" — [GitHub release v2.0.0](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0)
- 2.1.0-M1 build pom: java.version 17, spring-boot.version **4.2.0-M2**, kotlin 2.3.20, MCP SDK 2.0.0 — [pom.xml @ v2.1.0-M1](https://github.com/spring-projects/spring-ai/blob/v2.1.0-M1/pom.xml). Its blog says the release "moves the baseline to Spring Boot 4.2 (this milestone is built against 4.2.0-M2)" — [2.1.0-M1 blog](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/)
- 1.1.8 and 1.0.9 build poms: java.version 17, spring-boot.version **3.5.15**, kotlin **1.9.25**, MCP SDK **0.18.3**. 1.1.8 also sets mcp-annotations 0.9.0 — [pom.xml @ v1.1.8](https://github.com/spring-projects/spring-ai/blob/v1.1.8/pom.xml); [pom.xml @ v1.0.9](https://github.com/spring-projects/spring-ai/blob/v1.0.9/pom.xml)
- What Spring Boot 4.1.1 manages (the Boot version 2.0.1 is built on): `spring-framework.version 7.0.9`, `jackson-bom.version 3.1.5`, `jackson-2-bom.version 2.21.5`, `jakarta-servlet.version 6.1.0`, `kotlin.version 2.3.21`, `hibernate.version 7.4.5.Final` — [spring-boot-dependencies-4.1.1.pom](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom)
- What Spring Boot 4.0.0 manages (the low end of 2.0.x's supported range): Spring Framework 7.0.1, Jackson 3.0.2 (plus Jackson 2 2.20.1), Jakarta Servlet 6.1.0, Kotlin 2.2.21 — [spring-boot-dependencies-4.0.0.pom](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.0.0/spring-boot-dependencies-4.0.0.pom)
- Jackson nuance: the 2.0.0 release notes include "Use only Jackson 2 in `OpenAiChatModel` (#6392)". Not every module uses Jackson 3 exclusively, and Boot 4.x still manages a Jackson 2 BOM — [GitHub release v2.0.0](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0)
- GraalVM native: issue #6427, "GraalVM for mcp annotations doesn't work in Spring AI 2.0.0", is listed as resolved in the 2.0.1 release notes, under the "New Features" heading. GraalVM is evidently a supported use case, and MCP annotations were broken in native mode in 2.0.0 — [GitHub release v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1)
- Kotlin behavior: the upgrade notes state that `BeanOutputConverter` now delegates to `JsonSchemaGenerator`. As a result, "Kotlin properties that are optional in their primary constructor (nullable or declared with a default value) are no longer included in the JSON Schema required array" — [Upgrade notes (2.1 docs)](https://docs.spring.io/spring-ai/reference/2.1/upgrade-notes.html)
- Spring Boot 4.2.0-M2, the build baseline for 2.1.0-M1, is on Maven Central. It manages `spring-framework.version 7.1.0-M2` and `jackson-bom.version 3.1.6` — [spring-boot-dependencies-4.2.0-M2.pom](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.2.0-M2/spring-boot-dependencies-4.2.0-M2.pom)
- The 2.1 docs' getting-started page still says "Spring AI 2.0.x supports Spring Boot 4.0.x and 4.1.x". It has not yet been updated for 2.1 or Boot 4.2 — [Getting Started 2.1](https://docs.spring.io/spring-ai/reference/2.1/getting-started.html)

Compatibility matrix (sources as cited above):

| Spring AI line | Latest version (2026-10-01) | Supported Spring Boot | Built against Boot | Spring Framework (via Boot) | Java min | Kotlin (build) | MCP Java SDK |
|---|---|---|---|---|---|---|---|
| 1.0.x | 1.0.9 (GA) | 3.4.x, 3.5.x | 3.5.15 (1.0.9) | 6.2.x (inferred from Boot 3.4/3.5; not verified) | 17 | 1.9.25 | 0.18.3 |
| 1.1.x | 1.1.8 (GA) | 3.5.x | 3.5.15 (1.1.8) | 6.2.x (inferred; not verified) | 17 | 1.9.25 | 0.18.3 |
| **2.0.x** | **2.0.1 (GA, current)** | **4.0.x, 4.1.x** | 4.1.0 (2.0.0) / 4.1.1 (2.0.1) | **7.0** (7.0.9 via Boot 4.1.1) | **17** | 2.3.20 | 2.0.0 |
| 2.1.x | 2.1.0-M1 (milestone) | Boot 4.2 ("baseline") | 4.2.0-M2 | 7.1 (7.1.0-M2 via Boot 4.2.0-M2) | 17 | 2.3.20 | 2.0.0 |

### Inferences
- "Java 17 baseline" here means the minimum. The pom compiles with `release 17` and requires JDK ≥ 17.0.19 for building. Java 21 and 25 are expected to work as with Spring Boot 4, but I found no Spring AI document listing tested JDKs.
- Jakarta EE level: Boot 4.x manages Jakarta Servlet 6.1.0, which belongs to Jakarta EE 11. Spring AI 2.0.x therefore runs on a Jakarta EE 11-level stack through Boot 4. This is inferred from Boot's managed versions. No Spring AI document states it.
- Spring Boot's "supported range" (4.0.x and 4.1.x) is broader than its "build baseline" (4.1.x). Users on Boot 4.0.x can use 2.0.1, but CI is built against 4.1.x.

### Gaps
- I found no explicit Spring AI statement on Java versions beyond the pom, such as "Java 17+ required" or a tested-JDK list.
- No Spring AI document states the Jakarta EE level. The Jakarta EE 11 point above is an inference from Boot 4's managed Servlet 6.1.
- I found no official GraalVM native-image support statement or compatibility list for Spring AI 2.0.x beyond the #6427 fix.
- I did not verify the Spring Framework version for the 1.x lines from Boot 3.4/3.5 poms; the table entry is inferred.

## 4. Support policy: OSS and commercial end dates; which lines are still maintained

### Takeaway
As of 2026-10-01, **only Spring AI 2.0.x still has open-source support**, until 2027-07-31; commercial support continues to 2028-07-31. **OSS support for both 1.0.x and 1.1.x ended on 2026-06-30.** 1.0.x keeps commercial support until 2027-06-30. 1.1.x is an "extended" support line with commercial support until **2032-06-30**, matching Spring Boot 3.5.x. 2.1.x does not appear in the support table yet.

### Cited Findings
- spring.io generations data, quoted verbatim — [api.spring.io/projects/spring-ai/generations](https://api.spring.io/projects/spring-ai/generations)

| Line | initialReleaseDate (approx.) | support | ossSupportEndDate | commercialSupportEndDate | Linked Boot | latestPatch.oss |
|---|---|---|---|---|---|---|
| 1.0.x | 2025-05-31 | default | **2026-06-30** (ended) | 2027-06-30 | 3.4.x, 3.5.x | 1.0.9 |
| 1.1.x | 2025-11-30 | **extended** | **2026-06-30** (ended) | **2032-06-30** | 3.5.x | 1.1.8 |
| 2.0.x | 2026-06-30 | default | **2027-07-31** | **2028-07-31** | 4.0.x, 4.1.x | 2.0.1 |

- The GitHub milestone "1.1.x" was closed on 2026-07-23 with 0 open and 0 closed issues, consistent with the end of OSS development on 1.1.x. The "2.0.x" and "2.1.x" bucket milestones remain open — [milestones API](https://api.github.com/repos/spring-projects/spring-ai/milestones?state=all)
- For comparison, Spring Boot's own support dates are: Boot 3.5.x OSS to 2026-06-30 and commercial to 2032-06-30; Boot 4.0.x OSS to 2026-12-31 and commercial to 2027-12-31; Boot 4.1.x OSS to 2027-07-31 and commercial to 2028-07-31; Boot 4.2.x has initialReleaseDate 2026-11-30 (approx.), OSS to 2027-12-31 and commercial to 2028-12-31 — [api.spring.io/projects/spring-boot/generations](https://api.spring.io/projects/spring-boot/generations)
- The last OSS releases were 1.0.9 and 1.1.8, both on 2026-06-12. 1.1.9-SNAPSHOT still appears in the Spring snapshot repo and the docs selector — [GitHub releases API](https://api.github.com/repos/spring-projects/spring-ai/releases?per_page=60); [repo.spring.io snapshot metadata](https://repo.spring.io/snapshot/org/springframework/ai/spring-ai-bom/maven-metadata.xml)

### Inferences
- Spring AI's support windows mirror Spring Boot's. 2.0.x's OSS and commercial end dates are identical to Boot 4.1.x's (2027-07-31 and 2028-07-31). 1.1.x's 2032-06-30 date mirrors Boot 3.5.x's extended support. The "extended" label on 1.1.x most likely exists because 1.1.x is the Spring AI line paired with Boot 3.5, the last Boot 3 line, which has long-term commercial support. This is inferred, not stated.
- Boot 4.0.x OSS ends 2026-12-31, but Spring AI 2.0.x OSS runs to 2027-07-31. Users of Spring AI 2.0.x on Boot 4.0 should move to Boot 4.1 before 2027 to stay on a fully OSS-supported stack.
- 2.1.x will probably appear in the support table at GA, likely with dates aligned to Boot 4.2.x (OSS to 2027-12-31, commercial to 2028-12-31). This is speculation based on the pattern above.

### Gaps
- I did not find an explicit explanation of "default" versus "extended" support on a Spring AI page. The meaning above is inferred from the Spring Boot parallel.
- I found no public statement on whether commercial-only 1.1.x or 1.0.x releases (for example 1.1.9) are planned or already published.

## 5. Roadmap: what is planned for the next version, and when

### Takeaway
The next minor release is **Spring AI 2.1**. M1 shipped 2026-09-24/25, and **work on 2.1.0-RC1 has started**; its GitHub milestone has no due date. The announced 2.1 themes are: MessagePart content across all providers, session and conversation-history management in core, MCP spec 2026-07-28 support, the OpenAI Responses API, and `VectorStore.upsert`. A separate **Spring AI Agents** experimental project is planned for **November 2026**, to be merged into **Spring AI 3.0 "mid next year"** (mid-2027). No official 2.1.0 GA date has been published.

### Cited Findings
- The 2.1.0-M1 blog says: "Work on 2.1.0-RC1 has started. Message parts are the foundation for most of it." — [2.1.0-M1 blog](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/)
- Message parts across providers: "We will refactor the existing ChatModel implementations to produce and consume MessagePart content natively, while preserving backward compatibility for code that uses the current accessors." — [2.1.0-M1 blog](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/)
- Session management in core: "We are bringing the key parts of Spring AI Session into Spring AI itself. Along with advanced conversation history management, compaction and recall, its SessionRepository implementations will persist the full MessagePart structure." — [2.1.0-M1 blog](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/)
- MCP: "Work on support for the MCP 2026-07-28 specification is in progress, both in the MCP Java SDK and in the Spring AI MCP abstractions and annotations built on top of it." — [2.1.0-M1 blog](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/)
- Agents and 3.0: "We plan to release the new agentic support in November 2026 as a separate project under Spring Projects Experimental, with the plan to merge it into Spring AI 3.0 mid next year." — [2.1.0-M1 blog](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/)
- 2.1.0-M1 delivered features: OpenAI Responses API via `OpenAiResponsesChatModel` (#7006), `VectorStore.upsert` for pre-computed embeddings (#7002), `MessagePart` in `AbstractMessage` (#6997), and an upgrade to Spring Boot 4.2 (#7003) — [GitHub release v2.1.0-M1](https://github.com/spring-projects/spring-ai/releases/tag/v2.1.0-M1). The blog adds that upsert supports pgvector, Redis, Elasticsearch and Qdrant, and that the Anthropic Java SDK moves to 2.64.0 — [2.1.0-M1 blog](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/)
- 2.1 upgrade notes ("Upgrading to 2.1.0"): `AssistantMessage` and `UserMessage` now hold an ordered list of `MessagePart` entries (`TextPart`, `ReasoningPart`, `ToolCallPart`, `ToolResultPart`, `MediaPart`, `UnknownPart`), with a new `getReasoning()`. The `textContent`, `media` and `responses` protected fields are deprecated "for one release". A new streaming contract (`StreamingParts`) is introduced — [Upgrade notes 2.1](https://docs.spring.io/spring-ai/reference/2.1/upgrade-notes.html)
- GitHub milestone "2.1.0-RC1": no due date, 8 open and 7 closed issues. Items include "Google GenAI to use message parts" (#7075, open), "Migrate Anthropic chat model to message parts" (#7067, closed), "Upgrade Tika to 4.0.0" (#7068, closed), "Remove deprecated Redis chat memory autoconfig module" (#7017, open), "Support custom Azure credentials" (#6937, open), "configurable PgVectorStore distance type" (#6864, open), "chunkOverlap support to TokenTextSplitter" (#4054, open) — [milestones API](https://api.github.com/repos/spring-projects/spring-ai/milestones?state=open); [GitHub milestone 2.1.0-RC1](https://github.com/spring-projects/spring-ai/milestone/56)
- Other open milestones: ["2.0.2"](https://github.com/spring-projects/spring-ai/milestone/54) (no due date, 17 open / 33 closed), plus bucket milestones "2.0.x" (3 open), "2.1.x" (7 open) and "backlog" (48 open) — [milestones API](https://api.github.com/repos/spring-projects/spring-ai/milestones?state=open)
- The 2.0.0 GA blog said only that 2.0 "opens the path to upcoming ambitious new features we will share in the upcoming months". It gave no dates — [2.0.0 GA blog](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- Spring Boot timing that 2.1 tracks: Boot 4.2.0-M1 milestone closed 2026-08-21 and 4.2.0-M2 closed 2026-09-25 — [Spring Boot milestones API](https://api.github.com/repos/spring-projects/spring-boot/milestones?state=all). Boot 4.2.x has `initialReleaseDate` "2026-11-30" (approx., month-end) — [api.spring.io spring-boot generations](https://api.spring.io/projects/spring-boot/generations)
- Release-alignment evidence: Spring AI 2.0.0 GA (2026-06-12) followed Boot 4.1.0, whose milestone closed 2026-06-11. Spring AI 2.0.1 (2026-08-20) came out the same day as Boot 4.1.1 (milestone due 2026-08-20). Spring AI 2.1.0-M1 (2026-09-24) matched Boot 4.2.0-M2 (due 2026-09-24) — [Spring Boot milestones API](https://api.github.com/repos/spring-projects/spring-boot/milestones?state=all); [Spring AI milestones API](https://api.github.com/repos/spring-projects/spring-ai/milestones?state=all)

### Inferences
- **2.1.0 GA most likely lands in late November 2026**, shortly after Spring Boot 4.2.0 GA (spring.io approximates Boot 4.2.x's initial release as November 2026). Spring AI has released within about a day of the matching Boot version for 2.0.0, 2.0.1 and 2.1.0-M1. This is an inference; no Spring AI source gives a 2.1 GA date.
- Spring Boot release trains usually include an RC before GA. Spring AI already has a "2.1.0-RC1" milestone and no "2.1.0-M2" milestone, so the path may be M1 → RC1 → GA. Additional milestones remain possible, as the extra 2.0.0-M8 after the "last" M7 showed.
- **Spring AI 3.0** is signposted for about mid-2027, when the experimental Agents project is merged in. Its Boot pairing is unknown; Boot 4.3 or 5.0 would be speculation.

### Gaps
- No official 2.1.0-RC1 or 2.1.0 GA date was found. Neither GitHub milestone has a due date.
- No conference-talk roadmap (SpringOne or Devoxx 2026) was retrieved within this research budget.
- I did not find the name or GitHub location of the planned "Spring AI Agents" experimental project.

## 6. Maven/Gradle coordinates and repository requirements

### Takeaway
To use the current GA, import `org.springframework.ai:spring-ai-bom:2.0.1` (type `pom`, scope `import`) from **Maven Central**. No extra repository is needed. Maven Central hosts every milestone and RC from 1.0.0-M5 on, including 2.1.0-M1. The M5 upload date suggests that publishing milestones to Central at release time began in early 2025 (around 1.0.0-M6). A Spring repository is needed only for SNAPSHOTs: `https://repo.spring.io/snapshot` or Central Portal snapshots (`https://central.sonatype.com/repository/maven-snapshots/`).

### Cited Findings
- Docs (2.0.1): "Releases - Use Maven Central. Spring AI artifacts are available in Maven Central. No additional repository configuration is required." — [Getting Started 2.0.1](https://docs.spring.io/spring-ai/reference/2.0.1/getting-started.html)
- Docs (2.0.1), snapshots: "To use the latest development versions (e.g. 2.0.0-SNAPSHOT), you need to add the following snapshot repositories". The listed repositories are `spring-snapshots` → `https://repo.spring.io/snapshot` and `central-portal-snapshots` → `https://central.sonatype.com/repository/maven-snapshots/`. The Gradle snippet also adds `https://repo.spring.io/milestone` — [Getting Started 2.0.1](https://docs.spring.io/spring-ai/reference/2.0.1/getting-started.html)
- BOM usage from the docs: `<dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-bom</artifactId><version>…</version><type>pom</type><scope>import</scope></dependency>` for Maven, and `implementation platform("org.springframework.ai:spring-ai-bom:…")` for Gradle. The BOM "just contains dependency management and no plugin declarations or direct references to Spring or Spring Boot". Note that the 2.0.1 docs snippet still shows `2.0.0` as the version — [Getting Started 2.0.1](https://docs.spring.io/spring-ai/reference/2.0.1/getting-started.html)
- Maven Central hosts every version from 1.0.0-M5 through 2.1.0-M1, including all milestones and RCs since then — [Maven Central maven-metadata.xml](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml)
- The 2.1.0-M1 blog says it "is now available from Maven Central" — [2.1.0-M1 blog](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/). spring.io's release API still associates 2.1.0-M1 with the `spring-milestones` repository — [api.spring.io releases](https://api.spring.io/projects/spring-ai/releases). In practice the artifact resolves from Maven Central.
- Reference documentation URLs from spring.io's release API: 2.0.1 → `https://docs.spring.io/spring-ai/reference/2.0.1/index.html`; 2.1.0-M1 → `.../reference/2.1/index.html`; 2.0.2-SNAPSHOT → `.../reference/2.0-SNAPSHOT/index.html`; 1.1.8 → `.../reference/1.1/index.html`; 1.0.9 → `.../reference/1.0/index.html` — [api.spring.io releases](https://api.spring.io/projects/spring-ai/releases)
- start.spring.io (Spring Initializr) is the recommended starting point: "Head on over to start.spring.io and select the AI Models and Vector Stores…" — [Getting Started 2.0.1](https://docs.spring.io/spring-ai/reference/2.0.1/getting-started.html). A start.spring.io PR titled "Update Spring AI to 2.1.0-M1" (#2292) exists. Source: search result only; I did not check its merge status — [start.spring.io PR #2292](https://github.com/spring-io/start.spring.io/pull/2292)

Recommended coordinates as of 2026-10-01:

```xml
<!-- GA (recommended) -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>2.0.1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<!-- Parent / Boot: Spring Boot 4.0.x or 4.1.x (4.1.1 is what 2.0.1 is built against) -->
```
```groovy
implementation platform("org.springframework.ai:spring-ai-bom:2.0.1")
```

### Inferences
- In the 1.0.0-M1 to M4 era (2024), milestones required repo.spring.io/milestone. Today, trying 2.1.0-M1 needs no extra repository. It resolves from Maven Central, though it requires Spring Boot 4.2.0-M2, which is itself a milestone. Boot 4.2.0-M2 is also on Maven Central ([pom](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.2.0-M2/spring-boot-dependencies-4.2.0-M2.pom)), so a 2.1.0-M1 + Boot 4.2.0-M2 trial needs no Spring repository. Only `*-SNAPSHOT` versions (2.0.2-SNAPSHOT, 2.1.0-SNAPSHOT) need the snapshot repositories.
- The docs carry stale version strings: the BOM snippet shows 2.0.0, the snapshot example shows 2.0.0-SNAPSHOT, and the 2.1 getting-started page still says "2.0.x supports Boot 4.0.x and 4.1.x". Downstream writers should take version numbers from Maven Central or spring.io's API, not from the docs' code snippets.

### Gaps
- I did not check whether start.spring.io's default Spring AI version for Boot 4.1.x is 2.0.1. I did not query start.spring.io's metadata.
