# Spring AI 2.0.1, Boot 4 위에 에이전트 토대를 놓다

> **기준일** 2026-10-01 · **날짜 표기** "Maven Central 업로드일 / GitHub 릴리스일" (두 출처는 보통 하루 차이)
>
> **버전 태그**
> - `[1.1.x]`: 1.1 라인에서 이미 도입됨 (2.0의 새 기능 아님)
> - `[2.0.0]`: 2.0.0 GA(M1~RC2 포함)에서 도입·변경
> - `[2.0.1]`: 2.0.1 패치에서 도입·변경
> - `[2.0.x 문서]`: 2.0.1 문서에 있으나 도입 시점 미확인
> - `[2.1.0-M1]`: 마일스톤 전용, GA 아님 (기능 설명은 8장에만)
> - `[community]`: spring-ai-community 또는 서드파티, Spring AI 코어 아님
>
> **목차** 바로 시작하기 · 핵심 결론 · 1 릴리스 현황 · 2 플랫폼과 지원 · 3 핵심 API · 4 MCP·에이전트·제공자 · 5 마이그레이션 · 6 생태계·경쟁·이슈 · 7 출처 충돌 모음 · 8 로드맵(마일스톤) · 결론

---

## 바로 시작하기: 빈 디렉터리에서 오늘 새 프로젝트를 만든다면

### 필수 좌표

| 항목 | 권장 값 | 근거 |
|---|---|---|
| BOM | `org.springframework.ai` : `spring-ai-bom` : **`2.0.1`** (`<type>pom</type>`, `<scope>import</scope>`) | spring.io가 2.0.1을 `GENERAL_AVAILABILITY`, `current: true`로 표시 ([api.spring.io releases](https://api.spring.io/projects/spring-ai/releases)) |
| 저장소 | **Maven Central만.** 추가 저장소 불필요 (SNAPSHOT 쓸 때만 `https://repo.spring.io/snapshot` 필요) | ([Getting Started 2.0.1](https://docs.spring.io/spring-ai/reference/2.0.1/getting-started.html)) |
| Spring Boot | **4.1.x** (지원 범위 4.0.x·4.1.x, 2.0.1은 4.1.1로 빌드됨) | ([Getting Started 2.0.1](https://docs.spring.io/spring-ai/reference/2.0.1/getting-started.html), [pom.xml@v2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/pom.xml)) |
| Java | **17 이상** (2.0.1 jar 매니페스트 `Java-Version: 17`, 클래스 파일 major 61) | ([spring-ai-model-2.0.1.jar](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-model/2.0.1/spring-ai-model-2.0.1.jar)) |
| 스타터 예시 | `org.springframework.ai` : `spring-ai-starter-model-openai` (버전 생략, BOM이 관리) | ([starters@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1/starters)) |

**Boot 4.0이 아니라 4.1.x를 권하는 이유:** Boot 4.0.x의 OSS 지원은 2026-12-31에 끝난다. Spring AI 2.0.x의 OSS 지원은 2027-07-31까지인데, 이는 Boot 4.1.x 종료일과 정확히 같다 ([Spring Boot generations](https://api.spring.io/projects/spring-boot/generations), [Spring AI generations](https://api.spring.io/projects/spring-ai/generations)).

```xml
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

<dependencies>
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
  </dependency>
</dependencies>
```

```groovy
implementation platform("org.springframework.ai:spring-ai-bom:2.0.1")
implementation "org.springframework.ai:spring-ai-starter-model-openai"
```

다른 제공자도 같은 이름 규칙을 따른다: `spring-ai-starter-model-anthropic`, `-google-genai`, `-bedrock-converse`, `-mistral-ai`, `-deepseek`, `-ollama` ([starters@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1/starters)).

### 경고: Maven Central이 마일스톤 2.1.0-M1을 "release"로 표시한다

- **함정:** `spring-ai-bom`의 `maven-metadata.xml`에는 `<latest>2.1.0-M1</latest>`, `<release>2.1.0-M1</release>`가 들어 있다 ([Maven Central metadata](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/maven-metadata.xml)). Maven의 `<release>`는 "가장 최근의 non-SNAPSHOT 업로드"라는 뜻일 뿐 GA를 뜻하지 않는다.
- **누가 걸리나:** `versions:use-latest-releases`, Gradle `latest.release`, 느슨하게 설정한 Dependabot·Renovate가 마일스톤을 끌어올 수 있다. 2.1.0-M1은 Spring Boot 4.2.0-M2(역시 마일스톤)로 빌드되었다 ([pom.xml@v2.1.0-M1](https://github.com/spring-projects/spring-ai/blob/v2.1.0-M1/pom.xml)).
- **대응:** 버전을 `2.0.1`로 명시 고정한다.
- **문서도 낡았다:** 2.0.1 Getting Started 페이지의 BOM 예제는 아직 `2.0.0`을 보여준다 ([Getting Started 2.0.1](https://docs.spring.io/spring-ai/reference/2.0.1/getting-started.html)).
- **Initializr 확인:** start.spring.io에 "Update Spring AI to 2.1.0-M1" PR(#2292)이 있으나 병합 여부는 확인되지 않았다 ([start.spring.io PR #2292](https://github.com/spring-io/start.spring.io/pull/2292)). 생성된 `pom.xml`의 Spring AI 버전을 꼭 확인한다.

### 새 프로젝트가 처음 부딪히는 기본 동작 5가지

| 기본 동작 | 버전 | 대응 |
|---|---|---|
| `ChatModel.call()`/`stream()`은 툴을 실행하지 않고 툴 호출 요청만 돌려준다 | `[2.0.0]` | `ChatClient`를 쓴다 (툴 루프는 자동 등록되는 `ToolCallingAdvisor`가 맡는다) |
| 메모리 어드바이저는 호출마다 conversation ID가 필요하고, 없으면 `IllegalArgumentException` | `[2.0.0]` | `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "session-1"))` |
| 기본 temperature 0.7이 사라져 제공자 기본값이 적용된다 | `[2.0.0]` | 필요하면 명시한다 |
| 기본 모델은 OpenAI `gpt-5-mini` `[2.0.0]`, Anthropic `claude-haiku-4-5` `[2.0.x 문서]`이고, Anthropic `maxTokens` 기본값은 4096이다 `[2.0.0]` | `[2.0.0]` / `[2.0.x 문서]` | 비용을 생각해 모델과 토큰을 명시한다 |
| 툴 호출 한도(툴당 40회, 턴당 총 150회)가 있고, 요청에 붙이지 않은 툴은 실행하지 않는다 | `[2.0.1]` | 긴 에이전트 루프는 한도를 조정한다 |

근거: ([Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html), [OpenAI 문서](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html), [Anthropic 문서](https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html), [Tools 문서](https://docs.spring.io/spring-ai/reference/api/tools.html))

```java
@RestController
class ChatController {
    private final ChatClient chatClient;

    ChatController(ChatClient.Builder builder) {   // 자동 구성된 Builder를 주입
        this.chatClient = builder.build();
    }

    @GetMapping("/ask")
    String ask(@RequestParam String q) {
        return chatClient.prompt().user(q).call().content();
    }
}
```

---

## 핵심 결론

2026-10-01 기준 Spring AI의 최신 GA는 **2.0.1**(2026-08-20 / 08-21)이다. OSS 지원을 받는 라인은 2.0.x 하나뿐이며 지원은 **2027-07-31**까지다. 1.0.x와 1.1.x의 무료 지원은 **2026-06-30에 끝났다** ([api.spring.io generations](https://api.spring.io/projects/spring-ai/generations)). 2.0은 기능 추가보다 **플랫폼과 아키텍처를 재편**한 릴리스다. 기반을 Spring Boot 4.0/4.1, Framework 7, Jackson 3, JSpecify로 옮겼고, 각 `ChatModel` 안에 숨어 있던 툴 실행 루프를 `ChatClient`가 자동 등록하는 `ToolCallingAdvisor`로 끌어올렸다. 옵션은 불변·빌더 전용이 되었고, OpenAI와 Anthropic은 공식 벤더 SDK 위에 다시 지어졌으며, 코어 채팅 제공자는 7개로 줄었다 ([2.0.0 GA 블로그](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)). 2.0.1은 이름만 패치다. 툴 호출 한도, 툴 해석 폴백 비활성화, OpenAI strict 기본값 false 같은 동작 변경에 CVE 7건 수정까지 들어 있어서, 2.0.0에서 올리더라도 회귀 테스트가 필요하다 ([2.0.1 블로그](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)). 약점도 분명하다. 스트리밍과 툴 호출이 겹치는 경로에 미해결 이슈가 몰려 있다. MCP는 SDK 2.0.0(스펙 2025-11-25)에 고정되어 있어 최신 스펙 2026-07-28과 SDK 2.0.1의 수정이 빠져 있다. GPT-5.4 이후 OpenAI에서 추론과 툴 호출을 함께 쓰려면 Responses API가 필요한데, 이 API는 2.1.0-M1 마일스톤에만 있다. 다음 버전 2.1은 Boot 4.2 기반의 M1이 2026-09-24 / 09-25에 나왔다. 공식 GA 일정은 없지만 Boot 정렬 패턴으로 보면 **2026년 11월 말이 유력하다(추정)**. 공식 에이전트 프로젝트는 2026년 11월 실험 프로젝트로 먼저 나오고, 2027년 중반 Spring AI 3.0에 합쳐질 계획이다 ([2.1.0-M1 블로그](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/)).

---

## 1. 릴리스 현황: 현재 GA는 2.0.1, 2.1.0-M1은 아직 마일스톤

### 라인별 상태

| 라인 | 최신 버전 (날짜) | 상태 |
|---|---|---|
| 1.0.x | 1.0.9 (2026-06-12) | GA, OSS 지원 종료 |
| 1.1.x | 1.1.8 (2026-06-12) | GA, OSS 지원 종료 |
| **2.0.x** | **2.0.1 (2026-08-20 / 08-21)** | **GA, current** |
| 2.1.x | 2.1.0-M1 (2026-09-24 / 09-25) | 마일스톤 (`PRERELEASE`) |
| 개발 빌드 | 2.0.2-SNAPSHOT, 2.1.0-SNAPSHOT, 1.1.9-SNAPSHOT | SNAPSHOT |

근거: ([api.spring.io releases](https://api.spring.io/projects/spring-ai/releases), [GitHub v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1), [GitHub v2.1.0-M1](https://github.com/spring-projects/spring-ai/releases/tag/v2.1.0-M1), [snapshot metadata](https://repo.spring.io/snapshot/org/springframework/ai/spring-ai-bom/maven-metadata.xml)). 문서 사이트의 버전 선택기도 "Stable 2.0.1 1.1.8 1.0.9 · Preview 2.1.0-M1"로 표시한다 ([Reference 2.0.1](https://docs.spring.io/spring-ai/reference/2.0.1/index.html)).

### 타임라인

| 시점 | 릴리스 |
|---|---|
| 2025-05-19 / 05-20 | 1.0.0 GA |
| 2025-11-12 | 1.1.0 GA |
| 2025-12-11 ~ 2026-05-27 | 2.0.0-M1 ~ M8 (M7을 "마지막 마일스톤"이라 했는데 M8이 추가됨) |
| 2026-06-05 / 06-06, 06-09 | 2.0.0-RC1, RC2 |
| **2026-06-12** | **2.0.0 GA**, 같은 날 1.1.8과 1.0.9 (1.x의 마지막 OSS 릴리스) |
| **2026-08-20 / 08-21** | **2.0.1 GA (current)** |
| 2026-09-24 / 09-25 | 2.1.0-M1 |

근거: ([GitHub releases API](https://api.github.com/repos/spring-projects/spring-ai/releases?per_page=60), [Maven Central 디렉터리](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/), [milestones API](https://api.github.com/repos/spring-projects/spring-ai/milestones?state=all))

### 해석

**2.0.1은 "첫 유지보수 릴리스"지만 기능 릴리스에 가깝다.** 80개가 넘는 이슈·PR과 CVE 7건을 처리했다 ([2.0.1 블로그](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)). 릴리스 노트의 "New Features"에는 툴 호출 한도(#6726), `OpenAiAudioSpeechModel` 오디오 스트리밍(#6733), Google 이미지 생성(#6408), Google GenAI `ToolChoice`(#6531)가 올라 있다 ([GitHub v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1)).

**Spring AI는 Spring Boot 릴리스에 맞춰 움직인다.** 2.0.0 GA는 Boot 4.1.0 직후(06-11 → 06-12)에, 2.0.1은 Boot 4.1.1과 같은 날에, 2.1.0-M1은 Boot 4.2.0-M2와 같은 날에 나왔다 ([Spring Boot milestones API](https://api.github.com/repos/spring-projects/spring-boot/milestones?state=all)). 다음 패치 2.0.2는 GitHub 마일스톤에서 33개를 닫고 17개가 남은 상태이며 기한은 없다 ([milestone 2.0.2](https://github.com/spring-projects/spring-ai/milestone/54)). 패턴대로라면 다음 Boot 4.1.x 패치와 함께 나올 가능성이 높지만, 확인된 사실은 아니다.

**1.1.9-SNAPSHOT이 아직 스냅샷 저장소에 있다.** 1.1.x OSS 지원이 끝났으므로 상용 전용 릴리스를 준비하는 것일 수 있다. 다만 이를 확인해 주는 출처는 없다 ([snapshot metadata](https://repo.spring.io/snapshot/org/springframework/ai/spring-ai-bom/maven-metadata.xml)).

---

## 2. 플랫폼과 지원: Boot 4.1 + Java 17, OSS 지원은 2.0.x만 2027-07-31까지

### 호환성 매트릭스

| Spring AI 라인 | 지원 Boot | 빌드 기준 Boot | Framework | Java 최소 | Kotlin(빌드) | MCP Java SDK |
|---|---|---|---|---|---|---|
| 1.0.x (1.0.9) | 3.4.x, 3.5.x | 3.5.15 | 6.2.x (추정) | 17 | 1.9.25 | 0.18.3 |
| 1.1.x (1.1.8) | 3.5.x | 3.5.15 | 6.2.x (추정) | 17 | 1.9.25 | 0.18.3 |
| **2.0.x (2.0.1)** | **4.0.x, 4.1.x** | 4.1.1 (2.0.0은 4.1.0) | **7.0** (Boot 4.1.1 기준 7.0.9) | **17** | 2.3.20 | 2.0.0 |
| 2.1.x (2.1.0-M1) | 4.2 | 4.2.0-M2 | 7.1.0-M2 | 17 | 2.3.20 | 2.0.0 |

근거: ([api.spring.io generations](https://api.spring.io/projects/spring-ai/generations), [pom.xml@v2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/pom.xml), [pom.xml@v1.1.8](https://github.com/spring-projects/spring-ai/blob/v1.1.8/pom.xml), [pom.xml@v2.1.0-M1](https://github.com/spring-projects/spring-ai/blob/v2.1.0-M1/pom.xml), [spring-boot-dependencies-4.1.1](https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom))

### 지원 정책

| 라인 | OSS 지원 종료 | 상용 지원 종료 | 비고 |
|---|---|---|---|
| 1.0.x | **2026-06-30 (종료)** | 2027-06-30 | |
| 1.1.x | **2026-06-30 (종료)** | **2032-06-30** | `extended` 지원, Boot 3.5.x 연장 지원과 같은 날짜 |
| **2.0.x** | **2027-07-31** | **2028-07-31** | Boot 4.1.x와 같은 날짜 |
| 2.1.x | 미게시 | 미게시 | 지원 표에 아직 없음 |

근거: ([api.spring.io generations](https://api.spring.io/projects/spring-ai/generations), [Spring Boot generations](https://api.spring.io/projects/spring-boot/generations))

**지원 기간은 Boot를 그대로 따른다.** 2.0.x의 종료일은 Boot 4.1.x와 같고, 1.1.x의 2032년은 Boot 3.5.x의 연장 지원과 같다. 여기에는 실무적인 귀결이 하나 있다. Boot 3.5의 OSS 지원도 2026-06-30에 끝났기 때문에, **OSS 지원을 받는 "Boot 3.x + Spring AI" 조합은 이제 없다.** `default`와 `extended`의 차이를 설명하는 Spring AI 공식 페이지는 찾지 못했다.

### 플랫폼 요점 `[2.0.0]`

- **Java 17은 바이너리를 쓰는 데 필요한 최소 버전이다.** 2.0.0-M1 노트는 "Java 17 is still the baseline for consuming the Spring AI 2.x binaries"라고 하고, Java 21은 소스에서 빌드할 때만 요구한다 ([GitHub v2.0.0-M1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M1)). Java 21이나 25로 테스트했다는 공식 목록은 없다.
- **Jackson 3와 Jackson 2가 함께 쓰인다.** `spring-ai-commons`는 `tools.jackson.core:jackson-databind` 3.1.5에 의존한다 ([pom](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-commons/2.0.1/spring-ai-commons-2.0.1.pom)). 반면 `openai-java-core` 4.49.0은 Jackson 2.18.9를 끌고 오고 ([pom](https://repo1.maven.org/maven2/com/openai/openai-java-core/4.49.0/openai-java-core-4.49.0.pom)), `OpenAiChatModel`은 Jackson 2만 쓴다(#6392) ([PR #6392](https://github.com/spring-projects/spring-ai/pull/6392)). 공식 Upgrade Notes에는 Boot 4나 Jackson 3를 다루는 별도 절이 없다.
- **JSpecify는 Kotlin 코드에 영향을 준다.** K2 컴파일러에서 `Generation.getResult()`와 `Message.getText()`가 nullable로 바뀌어 `!!`나 `?:`가 필요해진다. 우회책은 `-Xjspecify-annotations=ignore`다 ([Embabel 마이그레이션 위키](https://github.com/embabel/embabel-agent/wiki/Spring-Boot-4---Spring-AI-2.0-Migration)) `[community]`.
- **GraalVM 네이티브 이미지:** 2.0.0에서는 MCP 어노테이션을 쓰면 기동에 실패했고, 2.0.1에서 고쳐졌다(#6427) `[2.0.1]` ([Issue #6427](https://github.com/spring-projects/spring-ai/issues/6427)). 네이티브 지원 범위를 공식적으로 밝힌 문서는 없다.
- **로깅:** SLF4J 대신 `org.apache.commons.logging.LogFactory`를 쓴다(RC1 #6280) ([GitHub v2.0.0-RC1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC1)).

---

## 3. 핵심 API: 툴 루프가 ChatModel을 떠나 어드바이저 체인으로 들어갔다

2.0에서 무게중심은 `ChatModel`에서 **`ChatClient`와 어드바이저 체인**으로 옮겨 갔다. 툴 실행, 메모리 위치, 검증 재시도, 툴 탐색을 이제 모두 체인이 맡고, 이들은 `order` 값으로 조합된다. GA 블로그도 `ChatClient`를 "primary user-facing API"로, `ChatModel`을 하위 컴포넌트로 규정한다 ([2.0.0 GA 블로그](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)).

### 3.1 ChatClient와 옵션: 불변·빌더 전용 `[2.0.0]`

| 변경 | 내용 |
|---|---|
| `.options()` / `.defaultOptions()` | 완성된 객체 대신 `ChatOptions.Builder`를 받는다. 예: `.options(AnthropicChatOptions.builder().maxTokens(100).temperature(0.7))`. 완성된 `ChatOptions`를 넘기면 컴파일되지 않는다 |
| `ChatModel.call(Prompt)` | 여전히 완성된 옵션을 받고, 병합하지 않는다 |
| 불변성 | `ChatOptions#copy()`와 `*Options#fromOptions()`, setter가 제거되었다. `options.mutate()...build()`를 쓴다 |
| 이름 변경 | `getDefaultOptions()`는 deprecated되고 `getOptions()`로 대체, `N()`은 `n()`으로 |
| 속성 키 | 임베딩·이미지·오디오·모더레이션·OCR의 `.options` 세그먼트가 제거되었다. 예: `spring.ai.openai.embedding.options.model` → `spring.ai.openai.embedding.model` (deprecated 별칭 제공) |

근거: ([Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html), [ChatClient 문서](https://docs.spring.io/spring-ai/reference/api/chatclient.html))

- `[2.0.0]` `.entity(...)`에 `EntityParamSpec`이 생겼다: `.entity(ActorFilms.class, spec -> spec.useProviderStructuredOutput().validateSchema())` ([ChatClient 문서](https://docs.spring.io/spring-ai/reference/api/chatclient.html))
- `[2.0.x 문서]` 메시지 메타데이터(`.user(u -> u.text("...").metadata("messageId","msg-123"))`)와 `.responseEntity(Class)`, `.toolContext(Map)`가 있다. 1.1에도 있었는지는 확인되지 않았다.
- `[2.0.x 문서]` 미디어는 `UserMessage`에만 붙는다. 시스템·어시스턴트 메시지는 텍스트만 가능하다 ([Multimodality 문서](https://docs.spring.io/spring-ai/reference/api/multimodality.html)).
- `[2.0.1]` `Media.Builder.data(Object)`가 제거되었다. `data(byte[])`, `data(String)`, `data(URI)`, `data(URL)`, `data(Resource)`를 쓴다 ([2.0.1 블로그](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)).

### 3.2 툴 호출: 2.0에서 가장 크게 바뀐 영역

| 기능 | 버전 | 핵심 |
|---|---|---|
| `ToolCallingAdvisor` 자동 등록 | `[2.0.0]` | `ChatClient`에 정확히 하나 등록되며 order는 `HIGHEST_PRECEDENCE + 300`. 끄려면 `AdvisorParams.toolCallingAdvisorAutoRegister(false)` 또는 `spring.ai.chat.client.tool-calling.enabled=false` |
| `ChatModel` 내부 루프 제거 | `[2.0.0]` | `ChatModel`을 직접 쓰면 `ToolCallingManager.executeToolCalls(...)` 루프를 직접 짠다 |
| 제거된 API | `[2.0.0]` | `SpringBeanToolCallbackResolver`, `toolNames()`, `internalToolExecutionEnabled`, `FunctionCallback` 계열, `ChatClient.ToolSpec`, `streamToolCallResponses` |
| `ToolSearchToolCallingAdvisor` | `[2.0.0]` (RC1 #5909) | 점진적 툴 노출. 스타터는 `spring-ai-starter-tool-search-advisor`, 인덱스는 `regex`(기본)·`lucene`·`vector`. 문서는 토큰 34~64% 절감을 주장한다 |
| 툴 호출 한도 | `[2.0.1]` | 툴당 40회, 전체 150회(현재 턴 기준). 초과하면 `ToolCallLimitExceededException`, 동작은 `ToolCallLimitBehavior.THROW` 또는 `RETURN_ERROR_RESPONSE` |
| 툴 해석 폴백 기본 off | `[2.0.1]` | 요청에 붙인 툴만 실행된다. 되돌리려면 `spring.ai.tools.resolution.fallback.enabled=true` |
| 루프 전체 `Usage` 누적 | `[2.0.1]` | 이전에는 마지막 호출의 토큰만 집계했다 |
| OpenAI 툴 strict 기본 false | `[2.0.1]` | 선택 파라미터가 있는 툴이 strict 모드에서 400으로 거부되던 문제를 해결(#6755). 되돌리려면 `OpenAiChatOptions.builder().strict(true)` |
| `AugmentedToolCallbackProvider` | `[2.0.x 문서]` | 모델에는 `innerThought` 같은 확장 스키마를 보여주고, 툴에는 원래 인자만 넘긴다 |

근거: ([Tools 문서](https://docs.spring.io/spring-ai/reference/api/tools.html), [Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html), [Tool Search 문서](https://docs.spring.io/spring-ai/reference/api/tools/tool-search-tool.html), [GitHub v2.0.0-RC1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC1), [GitHub v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1))

**충돌: 툴 한도 속성 이름.** Tools 문서는 `spring.ai.tools.limits.max-calls-per-tool-default`, `.max-calls-per-tool.<name>`, `.excluded-tools`, `.max-total-tool-calls`, `.on-limit-exceeded`를 나열한다 ([Tools 문서](https://docs.spring.io/spring-ai/reference/api/tools.html)). Upgrade Notes는 `spring.ai.tools.limits.max-calls-per-tool=40`으로 적는다 ([Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)). 자동 구성 소스를 확인하기 전까지는 `ToolCallingManager.builder().maxCallsPerTool("search", 10).maxTotalToolCalls(150)` 같은 빌더 API가 더 안전하다.

같은 2.0.1에서 "광고하지 않은 툴을 `DefaultToolCallingManager`가 실행하던" 문제(CVE-2026-59318)도 고쳐졌다 ([2.0.1 블로그](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)). 폴백을 다시 켤 때는 문서 경고처럼 "리졸버가 노출하는 툴을 직접 통제할 때만" 켠다 ([Tools 문서](https://docs.spring.io/spring-ai/reference/api/tools.html)).

### 3.3 어드바이저: order 값이 동작을 결정한다

| 어드바이저 | 기본 order | 의미 |
|---|---|---|
| 메모리 어드바이저 (`MessageChatMemoryAdvisor` 등) | `HIGHEST_PRECEDENCE + 200` (1.x에서는 +1000) `[2.0.0]` | 툴 루프 **바깥**. 최종 사용자·어시스턴트 메시지만 본다 |
| `ToolCallingAdvisor` | `+300` `[2.0.0]` | 툴 루프를 소유한다 |
| order가 +300보다 큰 커스텀 어드바이저 | – | 루프 **안쪽**. 툴 반복을 매번 본다 (감사 로그, 가드레일 등) |

근거: ([Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html), [Composable tool calling 블로그](https://spring.io/blog/2026/06/15/spring-ai-composable-tool-calling/))

- `[2.0.0]` 마커 인터페이스 `ToolAdvisor`(툴 루프 소유자는 하나만 허용)와 `MemoryAdvisor`가 추가되었다. `ToolCallingAdvisor`에는 서브클래스용 훅 4쌍(`doInitializeLoop`, `doBeforeCall`, `doAfterCall`, `doFinalizeLoop` 및 각 Stream 버전)이 생겼다 ([Composable tool calling 블로그](https://spring.io/blog/2026/06/15/spring-ai-composable-tool-calling/)).
- `[2.0.0]` `PromptChatMemoryAdvisor`가 제거되었다. `MessageChatMemoryAdvisor`를 쓴다.
- `[1.1.x]` 재귀 어드바이저는 1.1에서 이미 나왔다 ([1.1 GA 블로그](https://spring.io/blog/2025/11/12/spring-ai-1-1-GA-released/)). 내장 구현은 `ToolCallingAdvisor`와 `StructuredOutputValidationAdvisor` 둘이다 ([Recursive Advisors 문서](https://docs.spring.io/spring-ai/reference/api/advisors-recursive.html)).
- **업그레이드 함정:** 1.x에서 메모리 어드바이저를 +1000에 두었다면 2.0에서는 루프 안쪽으로 들어가 동작이 조용히 바뀐다.
- **충돌:** 문서마다 설명이 다르다. 명시적으로 `ToolCallingAdvisor`를 한 번 더 등록하면 Tools 문서는 "명시적 오류"라고 하고, Upgrade Notes는 "중복 등록"이라고 한다. 어느 쪽이든 명시 등록은 제거한다 ([Tools 문서](https://docs.spring.io/spring-ai/reference/api/tools.html), [Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)).

### 3.4 구조화 출력: 네이티브 강제와 스키마 검증 `[2.0.0]`

권장 패턴(블로킹 호출): `.entity(X.class, spec -> spec.useProviderStructuredOutput().validateSchema())`

- **`useProviderStructuredOutput()`**: 제공자의 네이티브 JSON 스키마를 쓴다. 지원 여부는 옵션 클래스가 `StructuredOutputChatOptions`를 구현하는지로 판단하고, 미지원이면 **조용히** 프롬프트 지시 방식으로 돌아간다 ([Native 문서](https://docs.spring.io/spring-ai/reference/api/structured-output/native.html)).
- **`validateSchema()`**: `StructuredOutputValidationAdvisor`가 JSON Schema `DRAFT_2020_12`로 검증하고, 실패하면 오류를 프롬프트에 붙여 기본 3회까지 재시도한다. **스트리밍에서는 지원되지 않는다** ([Validation 문서](https://docs.spring.io/spring-ai/reference/api/structured-output/validation.html)).
- **커스텀 컨버터**는 `getJsonSchema()`를 구현해야 한다. 구현하지 않으면 두 스위치 모두 아무 일도 하지 않는다 ([Converters 문서](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html)).
- **`BeanOutputConverter`는 이제 `JsonSchemaGenerator`에 위임한다.** Kotlin 선택 속성과 `@JsonProperty(required=false)`는 `required`에서 빠지고, `postProcessSchema()`는 제거되어 `generateSchema()` 오버라이드로 바뀌었다 ([Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html)).

| 제공자 | 네이티브 구조화 출력 (Native 문서 기준) |
|---|---|
| OpenAI | Full (최상위 배열 불가, record로 감싼다) |
| Anthropic | Full (아래 충돌 참조) |
| Google GenAI | Full |
| Mistral AI | Full |
| Ollama | Partial / 불안정 |
| Bedrock Converse | Native 페이지에는 없지만 Bedrock 문서에 `outputSchema`가 있다 ([Bedrock Converse 문서](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/bedrock-converse.adoc)) |

**충돌: Anthropic 네이티브 구조화 출력.** 채팅 비교표는 Anthropic을 "Built-in JSON ✗"로 표시한다 ([Chat comparison](https://docs.spring.io/spring-ai/reference/api/chat/comparison.html)). 반면 Native 문서는 "Full"이라 하고, Anthropic 문서에는 `AnthropicChatOptions.builder().outputSchema(jsonSchema)`가 있다 ([Anthropic 문서](https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html)). 비교표가 낡았을 가능성이 높다.

### 3.5 채팅 메모리: conversation ID 필수, 툴 메시지 저장은 저장소마다 다르다

| 저장소 | 스타터 | 툴 메시지 저장 |
|---|---|---|
| `InMemoryChatMemoryRepository` | 기본 자동 구성 | 가능 (메모리 어드바이저를 루프 안쪽에 둘 때) |
| `JdbcChatMemoryRepository` | `spring-ai-starter-model-chat-memory-repository-jdbc` | 걸러냄. **`sequence_id` 컬럼 추가 필요** `[2.0.0]` |
| `CassandraChatMemoryRepository` | `...-repository-cassandra` | 걸러냄 |
| `MongoChatMemoryRepository` | `...-repository-mongodb` | 걸러냄. 반환 순서가 오래된 순으로 수정됨 `[2.0.0]` |
| `Neo4jChatMemoryRepository` | `...-repository-neo4j` | 가능 |
| `RedisChatMemoryRepository` | `...-repository-redis` `[2.0.0]` (M1에서 추가) | 가능. 2.0.1에서 모듈명과 접두사가 바뀜 `[2.0.1]` |
| Cosmos DB | 외부 (Azure Cosmos DB 팀) | – |

근거: ([Chat Memory 문서](https://docs.spring.io/spring-ai/reference/api/chat-memory.html), [Tools 문서](https://docs.spring.io/spring-ai/reference/api/tools.html), [Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html))

- `[2.0.x 문서]` `MessageWindowChatMemory`는 기본 20개 메시지를 유지하고, 턴 경계 단위로 잘라낸다. `SystemMessage`는 항상 보존된다.
- `[1.1.x]` MongoDB, Oracle JDBC, Cosmos DB 메모리는 1.1에서 추가되었다 ([1.1 GA 블로그](https://spring.io/blog/2025/11/12/spring-ai-1-1-GA-released/)).
- **충돌 1:** Chat Memory 페이지는 아직 중간 툴 메시지가 "현재 저장되지 않는다"며 Spring AI Session을 안내한다. Tools 문서와 블로그는 InMemory·Redis·Neo4j가 루프 안쪽(예: `HIGHEST_PRECEDENCE + 400`)에서 툴 메시지를 저장한다고 쓴다.
- **충돌 2:** order 설정 메서드가 Tools 문서에는 `.order(...)`, Upgrade Notes에는 `.advisorOrder(...)`로 되어 있다.

### 3.6 RAG·ETL·벡터 스토어: 1.1과 2.0 사이에서 가장 안정적인 영역

- **그대로 이어진 것:** `RetrievalAugmentationAdvisor`와 모듈형 RAG(`QueryTransformer`, `DocumentRetriever`, `QueryAugmenter` 등), `QuestionAnswerAdvisor`, ETL(`DocumentReader → DocumentTransformer → DocumentWriter`), 필터 DSL ([RAG 문서](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html), [Vector DBs 문서](https://docs.spring.io/spring-ai/reference/api/vectordbs.html)).
- `[2.0.0]` `spring-ai-advisors-vector-store`가 `spring-ai-vector-store-advisor`로 이름이 바뀌었고, HANA DB와 Infinispan 스토어는 제거되었다(M6). 커스텀 필터 컨버터는 `doSingleValue(...)`를 구현해야 한다 ([Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html), [GitHub v2.0.0-M6](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M6)).
- 2.0.1 BOM에는 있고 1.1.8 BOM에는 없는 아티팩트(도입 시점 미확인): `spring-ai-s3-vector-store`, `spring-ai-bedrock-knowledgebase-store`, `spring-ai-redis-semantic-cache` ([BOM 2.0.1](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/2.0.1/spring-ai-bom-2.0.1.pom))
- `[2.0.1]` `PagePdfDocumentReader`가 페이지 범위를 받는다. PDF 리더의 무한 재귀 CVE를 수정했고, Milvus·OpenSearch·PgVector 버그를 고쳤다 ([2.0.1 블로그](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)).
- **아직 없는 것:** 반응 수 1위 이슈인 `TokenTextSplitter` 오버랩(#2123, 52 reactions)이 아직 열려 있다 ([Issue #2123](https://github.com/spring-projects/spring-ai/issues/2123)). 내장 리랭커도 없다.

### 3.7 모델 유형과 관측성

| 항목 | 버전 | 내용 |
|---|---|---|
| OpenAI 공식 SDK | `[2.0.0]` | Chat·Embedding·Image·Audio·Moderation 모두 `openai-java` 기반. `spring.ai.openai.*`는 유지되고, `extraBody`는 `additionalBodyProperties`로 매핑 |
| Anthropic 공식 SDK | `[2.0.0]` | `anthropic-java` 기반. `AnthropicApi` 제거, 캐시 클래스는 `org.springframework.ai.anthropic`으로 이동 |
| 통합 캐시 토큰 | `[2.0.0]` | `Usage.getCacheReadInputTokens()` / `getCacheWriteInputTokens()` (Anthropic·Bedrock은 둘 다, OpenAI·Google은 읽기만) |
| 추론(reasoning) 내용 | `[1.1.x]` 도입, 2.0.1에서도 제공자별 | Anthropic thinking은 메타데이터(`signature`, `data`, `thinking`), OpenAI 호환 서버는 `reasoningContent`. 이식 가능한 `ReasoningPart`는 2.1.0-M1에서 나온다 |
| 프롬프트 캐싱 | `[1.1.x]` 도입 | Anthropic 전략 5종과 TTL 5분/1시간, Bedrock Converse. `[2.0.0]` OpenAI `promptCacheKey` |
| 오디오 | `[2.0.1]` | `OpenAiAudioSpeechModel`이 실제 청크 단위로 스트리밍하고 `instructions` 옵션이 생김. `TranscriptionModel`이 `StreamingTranscriptionModel`을 확장 |
| 이미지·모더레이션 | `[2.0.1]` | Google GenAI `ImageModel` 추가. Mistral 모더레이션 모델 `mistral-moderation-2603`과 `jailbreaking` 카테고리 |
| 툴 span | `[2.0.0]` | span 이름 `tool_call <name>` → `execute_tool <name>`, `gen_ai.operation.name=execute_tool`, `spring.ai.tool.type`·`spring.ai.tool.call.id` 추가 |

근거: ([Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html), [Anthropic 문서](https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html), [OpenAI 문서](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html), [Observability 문서](https://docs.spring.io/spring-ai/reference/observability/index.html), [2.0.1 블로그](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/))

**관측성:** OpenTelemetry GenAI 시맨틱 컨벤션에 "맞춰져" 있지만 완전히 준수하지는 않는다. `gen_ai.system=spring_ai` 같은 Spring 전용 값을 쓴다. 프롬프트와 응답 내용 수집은 모두 opt-in이다. 스트리밍에서는 HTTP span이 `gen_ai.client.operation` 아래에 붙지 않는다 ([Observability 문서](https://docs.spring.io/spring-ai/reference/observability/index.html)). **`tool_call` span 이름에 걸어 둔 대시보드와 알림은 2.0으로 올리면 깨진다.**

### 3.8 새것처럼 보이지만 1.1에서 이미 나온 기능 `[1.1.x]`

| 기능 | 1.1에서의 형태 |
|---|---|
| MCP 어노테이션 (`@McpTool`, `@McpResource`, `@McpPrompt`) | 외부 `mcp-annotations`로 제공. 2.0에서 코어로 이동 |
| 재귀 어드바이저 | "self-improving agents", LLM-as-a-Judge |
| 프롬프트 캐싱 | Anthropic, Bedrock Converse |
| 추론 지원 | Ollama, ZhipuAI, Anthropic `ReasoningContent`, OpenAI |
| Google GenAI SDK 채팅·임베딩, `TextToSpeechModel`/ElevenLabs, `VectorStoreRetriever` | 1.1 GA에 포함 |

근거: ([1.1 GA 블로그](https://spring.io/blog/2025/11/12/spring-ai-1-1-GA-released/))

---

## 4. MCP·에이전트·제공자: 스펙은 한 판 뒤, 에이전트는 커뮤니티, 제공자는 7개로

### 4.1 MCP: 기본은 Streamable HTTP, SDK는 2.0.0에 고정

| 항목 | 2.0.1 GA |
|---|---|
| MCP Java SDK | **2.0.0** 고정. 최신 SDK는 2.0.1(2026-08-19)인데 반영되지 않았다 |
| MCP 스펙 | **2025-11-25**. 최신 2026-07-28은 미지원 |
| 클라이언트 스타터 | `spring-ai-starter-mcp-client`(JDK HttpClient), `spring-ai-starter-mcp-client-webflux`. **WebMVC 전용 클라이언트 스타터는 없다** |
| 서버 스타터 | `spring-ai-starter-mcp-server`(STDIO), `spring-ai-starter-mcp-server-webmvc`, `spring-ai-starter-mcp-server-webflux` |
| 서버 프로토콜 | `spring.ai.mcp.server.protocol` = `SSE` / **`STREAMABLE`(기본)** / `STATELESS` |
| 어노테이션 | 서버 `@McpTool`·`@McpResource`·`@McpPrompt`·`@McpComplete`, 클라이언트 `@McpLogging`·`@McpSampling`·`@McpElicitation` 등. 패키지 `org.springframework.ai.mcp.annotation` |
| 보안 | 코어는 의도적으로 인증을 넣지 않았다. 기본 `POST /mcp`는 모든 요청을 받는다 |

근거: ([starters@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1/starters), [MCP client 문서](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-client-boot-starter-docs.adoc), [MCP server 문서](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-server-boot-starter-docs.adoc), [java-sdk v2.0.0](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.0))

**결정에 영향을 주는 리스크 (SDK 고정):**

- **무슨 일이 있었나:** 2026-07-30부터 OpenAI의 호스티드 MCP 클라이언트(Responses API에서 사용)는 `tools/list`보다 먼저 `server/discover`를 보낸다. SDK 2.0.0의 stateless 트랜스포트는 여기에 HTTP 500으로 답했고, OpenAI 쪽은 424 `external_connector_error`로 실패했다 ([java-sdk #1072](https://github.com/modelcontextprotocol/java-sdk/issues/1072)).
- **어디서 고쳐졌나:** SDK 2.0.1에 "Fix error behavior on unregistered handlers in stateless server handler"가 들어갔다. 같은 릴리스에 HTTP·STDIO 읽기 상한(bounded reads) 보강도 있다 ([java-sdk v2.0.1](https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.1)).
- **영향(추정):** Spring AI 2.0.1은 SDK 2.0.0을 관리 버전으로 쓴다. 따라서 OpenAI 커넥터에 노출한 Spring AI stateless MCP 서버는 **영향을 받을 가능성이 높다.** 연구자의 추정이며 직접 테스트하지는 않았다.
- **선택지:** `mcp-bom`을 2.0.1로 직접 올리거나(자기 책임), 다음 Spring AI 패치를 기다린다.

**버전별 변경**

| 버전 | 변경 |
|---|---|
| `[2.0.0]` | 기본 서버 프로토콜이 SSE에서 **STREAMABLE**로 바뀌었다(M7 #5969). SSE 트랜스포트는 SDK 수준에서 deprecated ([McpServerProperties@v2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-common/src/main/java/org/springframework/ai/mcp/server/common/autoconfigure/properties/McpServerProperties.java), [@v1.1.8](https://github.com/spring-projects/spring-ai/blob/v1.1.8/auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-common/src/main/java/org/springframework/ai/mcp/server/common/autoconfigure/properties/McpServerProperties.java)) |
| `[2.0.0]` | 어노테이션과 Spring 트랜스포트(`mcp-spring-webflux`/`webmvc`, groupId `org.springframework.ai`)가 코어로 이동했다. 서버 툴 입력 검증이 기본으로 켜졌다. `McpClientCustomizer<B>`로 통합되었다. `expose-mcp-client-tools`가 추가되었다(M5) |
| `[2.0.1]` | `@McpTool` 예외 처리를 `@Tool`과 맞췄다(RuntimeException은 오류 `CallToolResult`로 반환, checked·`Error`·`McpError`는 그대로 실패). `toolCallExceptionClass`는 deprecated(2.1.0에서 제거 예정). 서버 세션 `maxSessions`/`sessionIdleTimeout`(#6760, 속성명 미확인). GraalVM 수정. HTTP 클라이언트 customizer 빈 적용(#6716) |

근거: ([upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc), [GitHub v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1))

**충돌: 기본 프로토콜.** 2.0.1 MCP overview 표는 "`protocol=SSE` 또는 빈 값이면 SSE"라고 쓴다 ([mcp-overview@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-overview.adoc)). 그러나 코드 기본값은 `ServerProtocol.STREAMABLE`이고 GA 블로그도 "Streamable HTTP is now the default"라고 한다. **코드가 맞고 표가 낡았다.** SSE만 쓰는 클라이언트가 있다면 서버에 `spring.ai.mcp.server.protocol=SSE`를 명시한다.

**보안 `[community]`:** OAuth2와 API 키 인증은 `spring-ai-community/mcp-security` 0.1.x(최신 v0.1.14, 2026-08-06)가 제공한다. Spring AI 2.0.x 전용이고(1.1.x는 0.0.6), OAuth2와 API 키는 **WebMVC에서만** 동작한다. 공식 문서 페이지도 "(WIP)"이며 "not officially endorsed yet"이라고 밝힌다 ([mcp-security README](https://github.com/spring-ai-community/mcp-security), [MCP Security 문서](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-security.adoc)).

**오래 열려 있는 MCP 이슈:** 서버가 재시작되면 클라이언트가 재연결하지 않는다(#2740, 댓글 30개) ([Issue #2740](https://github.com/spring-projects/spring-ai/issues/2740)). 두 번째 툴 호출에서 역직렬화 오류가 난다(#2818) ([Issue #2818](https://github.com/spring-projects/spring-ai/issues/2818)).

### 4.2 에이전트: 코어는 빌딩 블록, 프레임워크는 커뮤니티

**코어(2.0.1 GA)가 제공하는 것:**

- "Building Effective Agents" 5가지 패턴 가이드: Chain, Parallelization, Routing, Orchestrator-Workers, Evaluator-Optimizer ([effective-agents 문서](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/effective-agents.adoc))
- `ToolCallingAdvisor`의 루프 훅, `ToolSearchToolCallingAdvisor`, `StructuredOutputValidationAdvisor` `[2.0.0]`
- 툴 호출 한도. 2.0.1 블로그 표현으로는 "An agentic loop that never terminates is an expensive failure mode." `[2.0.1]`
- 코어 저장소 트리에는 `agents` 모듈도, A2A 모듈도 없다 ([spring-ai@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1))

**커뮤니티·서드파티 `[community]`:**

| 프로젝트 | 최신 (날짜) | 무엇 | 주의 |
|---|---|---|---|
| `spring-ai-agent-utils` | v0.12.0 (2026-08-30) | Claude Code 스타일 툴(File/Shell/Grep/WebFetch 등), Agent Skills, 서브에이전트 | GA 블로그가 소개 ([README](https://github.com/spring-ai-community/spring-ai-agent-utils)) |
| `spring-ai-session` | v0.10.0 (2026-09-28) | event-sourced 대화 메모리, 툴 호출 포함 | 2.1에서 `ChatMemory`를 대체할 계획 ([releases](https://github.com/spring-ai-community/spring-ai-session/releases)) |
| `spring-ai-a2a` | v0.3.0 (2026-06-07) | A2A **서버** 측 지원 | **2.0.0-RC1** 기준 빌드, GA 호환은 미검증 ([README](https://github.com/spring-ai-community/spring-ai-a2a)) |
| Embabel | 1.5.2 (2026-09-16) | GOAP 기반 에이전트 프레임워크, Spring AI 위에서 동작 | Boot 4.1.1, `spring-ai-openai` 2.0.1을 따라감 ([releases](https://github.com/embabel/embabel-agent/releases), [pom](https://raw.githubusercontent.com/embabel/embabel-agent/main/pom.xml)) |
| `agent-client` | v0.31.0 (2026-09-17) | CLI 에이전트용 포터블 클라이언트 | 2026-08-06 커뮤니티 org에서 `markpollack` 개인 계정으로 이동 ([releases](https://github.com/markpollack/agent-client/releases)) |

Embabel README는 두 프로젝트의 관계를 "Spring AI exists at the level of the Servlet API, while Embabel is more like Spring MVC"라고 설명한다 ([Embabel README](https://github.com/embabel/embabel-agent)). 공식 에이전트 지원은 **발표만 되었고 아직 출시되지 않았다**. 일정은 8장에 정리했다.

### 4.3 모델 제공자: 코어 7개로 축소하고 공식 SDK로 재구축 `[2.0.0]`

| 등급 | 제공자 | 구현 |
|---|---|---|
| 1급 채팅 모듈 | **OpenAI, Anthropic, Google GenAI, Bedrock Converse** | 공식 SDK: `openai-java` 4.49.0, `anthropic-java` 2.52.0, `google-genai`, AWS SDK |
| 1급 채팅 모듈 | **Mistral AI, DeepSeek, Ollama** | Spring 자체 HTTP 클라이언트 |
| 그 밖의 모델 모듈 | ElevenLabs(TTS), Stability(이미지), PostgresML·ONNX Transformers(임베딩), Vertex AI(임베딩만), Bedrock Titan/Cohere(임베딩), Google GenAI 임베딩·이미지 `[2.0.1]` | – |
| 문서화된 프록시 | Azure OpenAI/Microsoft Foundry → OpenAI 모듈. Groq·NVIDIA·Perplexity·Docker Model Runner → OpenAI 모듈. MiniMax → Anthropic 모듈(`https://api.minimax.io/anthropic`) | 전용 모듈·스타터 없음 |
| 벤더·커뮤니티 유지 `[community]` | OCI GenAI(Oracle), Azure Cosmos DB(Microsoft), watsonx.ai, Replicate, Qianfan, Moonshot | 코어 밖 |
| 제거, 대체 경로 문서 없음 | Hugging Face, ZhiPu AI | – |

근거: ([models@v2.0.1](https://github.com/spring-projects/spring-ai/tree/v2.0.1/models), [models@v1.1.8](https://github.com/spring-projects/spring-ai/tree/v1.1.8/models), [2.0.0 GA 블로그](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/), [upgrade-notes@2.0.1](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc), [spring-ai-community](https://github.com/spring-ai-community))

**제공자별 고유 기능 (2.0.1)**

| 제공자 | 주요 기능 |
|---|---|
| Anthropic | 확장·적응형 thinking과 `Display` 제어, citations, 캐싱(5분/1시간), `outputSchema`와 effort, Skills, 웹 검색 툴, service tier, `inferenceGeo` |
| Google GenAI | thinking level·budget, thought signatures, cached content, Google Search와 커스텀 툴 병행, `ToolChoice` `[2.0.1]` |
| Bedrock Converse | 프롬프트 캐싱, 추론과 툴 병행, `outputSchema`, 이미지·비디오·문서 멀티모달 |
| OpenAI (Chat Completions) | 호환 서버의 reasoning content, `promptCacheKey`, 오디오 입출력, strict 기본 false `[2.0.1]` |
| DeepSeek / Mistral / Ollama | prefix completion과 thinking / OCR과 모더레이션 / thinking과 모델 자동 pull |

근거: ([Anthropic 문서](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/anthropic-chat.adoc), [Google GenAI 문서](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/google-genai-chat.adoc), [Bedrock Converse 문서](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/bedrock-converse.adoc), [OpenAI 문서](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/openai-chat.adoc))

**GA의 기능 공백:** 2.1.0-M1 문서는 "Starting with GPT-5.4, Chat Completions does not support tool calling with a reasoning effort other than `none`. Responses does."라고 적는다 ([openai-responses-chat@v2.1.0-M1](https://github.com/spring-projects/spring-ai/blob/v2.1.0-M1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/openai-responses-chat.adoc)). 2.0.1 GA의 OpenAI 모듈은 Chat Completions만 지원하므로, **최신 OpenAI 모델에서 추론과 툴 호출을 함께 쓰는 에이전트는 GA만으로는 만들 수 없다.** 이 부분은 연구자의 추론이다.

**모호점: Mistral "deprecated".** 2.0.1 블로그 요약은 "Mistral AI chat models deprecated"라고 쓴다 ([2.0.1 블로그](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)). 그런데 Upgrade Notes에 나오는 것은 `ChatModel` enum 상수 4개 제거(`MAGISTRAL_MEDIUM`, `MAGISTRAL_SMALL`, `DEVSTRAL`, `OPEN_MISTRAL_NEMO`)와 3개 재지정뿐이다 ([Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-1)). 모듈 자체를 폐기한다는 뜻인지 모델 상수를 정리했다는 뜻인지는 노트만으로 판정할 수 없다. `spring-ai-mistral-ai` 모듈과 스타터는 2.0.1과 2.1.0-M1에 모두 남아 있다.

---

## 5. 마이그레이션: 1.1→2.0은 Boot 4 이전 작업이고, 2.0.0→2.0.1도 그냥 교체할 수 없다

**권장: 1.x에서 올라온다면 2.0.0을 건너뛰고 바로 2.0.1로 간다.** 2.0.1에는 GraalVM과 MCP 수정, OpenAI strict 모드 400 수정, MCP HTTP customizer 빈 수정이 들어 있다 ([GitHub v2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1)).

### 5.1 2.0.0 → 2.0.1: 패치 안에 들어온 breaking change `[2.0.1]`

| 변경 | 유형 | 대응 |
|---|---|---|
| 툴 해석 폴백 기본 off | **런타임, 조용히 바뀜** | 툴을 `.tools(...)`나 `.defaultTools(...)`로 명시 첨부. 또는 `spring.ai.tools.resolution.fallback.enabled=true` |
| OpenAI 툴 strict 기본 `false` | **런타임, 조용히 바뀜** | `OpenAiChatOptions.builder().strict(true)` |
| 툴 호출 한도 40/150 | 런타임, 예외 발생 | `ToolCallingManager.builder()`로 조정 |
| `ToolCallingAdvisor` Usage 누적 | 테스트 | 정확한 토큰 수를 단언하는 테스트 수정 |
| `Media.Builder.data(Object)` 제거 | **컴파일** | 타입별 오버로드 사용 |
| Mistral enum 상수 4개 제거 (`MAGISTRAL_MEDIUM`, `MAGISTRAL_SMALL`, `DEVSTRAL`, `OPEN_MISTRAL_NEMO`) | **컴파일** | 참조 코드 수정. 남은 상수도 대상 모델이 바뀜: `MISTRAL_SMALL` = `mistral-small-2603`, `MISTRAL_MEDIUM` = `mistral-medium-3-5`, `MISTRAL_LARGE`는 `mistral-medium-3-5`의 별칭 |
| Redis 메모리 autoconfig 이름 변경 | 설정 | `spring-ai-autoconfigure-model-chat-memory-repository-redis`, `spring.ai.chat.memory.repository.redis.*` (예전 모듈은 deprecated 브리지로 남음) |
| `@McpTool` 예외 의미 변경 | 런타임 | checked 예외가 `UndeclaredThrowableException`으로 실패 처리되는지 확인 |
| `TranscriptionModel`이 `StreamingTranscriptionModel` 확장 | API | 기본 `stream()`은 `Flux.error(UnsupportedOperationException)` |
| OpenAI 요청별 타임아웃이 60초로 떨어지는 회귀(#6915) | **회귀, 미해결** | 옵션이 붙은 Prompt에서 타임아웃 확인 ([Issue #6915](https://github.com/spring-projects/spring-ai/issues/6915)) |

근거: ([Upgrade Notes #2.0.1](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-1), [PR #6771](https://github.com/spring-projects/spring-ai/pull/6771))

### 5.2 1.1.x → 2.0.0: 컴파일 오류보다 조용한 동작 변화가 더 위험하다 `[2.0.0]`

| 컴파일 오류가 나는 변경 | 컴파일은 되지만 동작이 바뀌는 변경 |
|---|---|
| `ChatClient.options(완성된 객체)` → 빌더 | `ChatModel.call()`이 툴을 실행하지 않음 |
| 옵션 setter·`copy()`·`fromOptions()` 제거 | 기본 temperature 0.7 삭제 |
| `FunctionCallback` 계열 → `ToolCallback`, `FunctionToolCallback`, `ToolCallingChatOptions`, `defaultTools` | Anthropic `maxTokens` 500 → 4096 (비용) |
| 메모리 `.conversationId()` 빌더와 `DEFAULT_CONVERSATION_ID` 제거 | 메모리 order +1000 → +200 |
| `AnthropicApi` 제거, `ModelOptionsUtils` JSON 메서드 → `JsonHelper`·`JacksonUtils` | MCP 서버 기본 프로토콜 SSE → Streamable HTTP, 툴 입력 검증 기본 on |
| MCP 패키지 이동(`org.springaicommunity.mcp.*` → `org.springframework.ai.mcp.annotation.*`), `McpClientCustomizer<B>` | 툴 span 이름 `execute_tool`로 변경 (대시보드 깨짐) |

**DB 작업도 필요하다.** JDBC 메모리 테이블에 `sequence_id BIGINT` 컬럼과 인덱스를 추가해야 하고, 백필 SQL이 제공된다. MongoDB 메모리는 오래된 순으로 반환되므로 역순 처리 우회 코드를 지워야 한다 ([Upgrade Notes #2.0.0](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0)).

공식 "Upgrading to 2.0.0" 절은 M1~RC2의 변경을 한 절로 합쳐 놓았다(#6333). 그래서 각 변경이 어느 마일스톤에서 들어왔는지는 릴리스 노트에서만 확인할 수 있다 ([GitHub v2.0.0](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0)).

### 5.3 아티팩트: 제거·이관·이름 변경

| 구분 | 아티팩트 |
|---|---|
| 제거 | `spring-ai-azure-openai`, `spring-ai-openai-sdk`, `spring-ai-minimax`, `spring-ai-zhipuai`, `spring-ai-huggingface` |
| 제거 | `spring-ai-vertex-ai-gemini`, `spring-ai-hanadb-store`, `spring-ai-spring-cloud-bindings` |
| 외부로 이관 | `spring-ai-oci-genai` → [oracle/spring-cloud-oracle](https://github.com/oracle/spring-cloud-oracle/tree/main/spring-ai-oracle). Cosmos DB 스토어·메모리 → [Azure Cosmos DB 팀](https://azurecosmosdb.github.io/spring-ai/docs/index.html) |
| 이름 변경 | `spring-ai-advisors-vector-store` → `spring-ai-vector-store-advisor`. `[2.0.1]` Redis 메모리 autoconfig |
| 코어로 편입 | `spring-ai-mcp-annotations`, `org.springframework.ai:mcp-spring-webflux`·`mcp-spring-webmvc` |
| 설명 없이 BOM에서 빠짐 | `spring-ai-model-chat-memory`, `spring-ai-starter-mcp-server-common` (명시 선언하면 버전 해석 실패) |

BOM 아티팩트 수는 1.1.8의 179개에서 2.0.1의 169개로 줄었다. 2.0.1과 2.1.0-M1의 목록은 같다 ([BOM 1.1.8](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/1.1.8/spring-ai-bom-1.1.8.pom), [BOM 2.0.1](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/2.0.1/spring-ai-bom-2.0.1.pom), [BOM 2.1.0-M1](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/2.1.0-M1/spring-ai-bom-2.1.0-M1.pom)).

### 5.4 설정 속성

- **삭제:** `spring.ai.<provider>.chat.internal-tool-execution-enabled`, `...tool-calling.stream-tool-call-responses`, `...tool-search-advisor.stream-tool-call-responses` `[2.0.0]`
- **이름 변경:** `spring.ai.ollama.chat.think-option` → `spring.ai.ollama.chat.think`. `.options` 세그먼트 평탄화(deprecated 별칭 제공) `[2.0.0]`
- **신규:** `spring.ai.chat.client.tool-calling.enabled`, `spring.ai.chat.client.tool-search-advisor.enabled`·`.tool-index-type` `[2.0.0]`. `spring.ai.tools.resolution.fallback.enabled` `[2.0.1]`
- **2.0.1에서 바뀐 접두사:** `spring.ai.chat.memory.redis.*` → `spring.ai.chat.memory.repository.redis.*`

Anthropic 속성 리팩터링(M6)과 Azure OpenAI → OpenAI 속성 매핑은 공식 대응표가 없다 ([Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html), [GitHub v2.0.0-M6](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M6)).

### 5.5 자동화 도구와 권장 순서

| 도구 | 범위 | 한계 |
|---|---|---|
| 공식 `migrate-to-2-0-0-M3.yaml` (`org.springframework.ai.migration.MigrateToSpringAI200M3`) | MCP 어노테이션, MCP 트랜스포트, MCP 클라이언트 customizer만 | Maven 전용. BOM이 관리하는 `io.modelcontextprotocol.sdk:mcp-spring-*`가 있으면 pom 파싱이 실패해 레시피가 아예 돌지 않는다(perl 사전 패치 안내) ([yaml](https://github.com/spring-projects/spring-ai/blob/2.0.x/src/rewrite/migrate-to-2-0-0-M3.yaml)) |
| Arconia `io.arconia.rewrite.spring.ai2.UpgradeSpringAi_2_0` `[community]` | 의존성 정리, 패키지 이동, `.options` 평탄화, `think-option`, `getDefaultOptions()` → `getOptions()` 등 | Boot 4·Jackson 3는 다루지 않는다. 옵션 빌더, FunctionCallback, 2.0.1 변경을 다루는지는 미확인 ([Arconia 2.0](https://docs.arconia.io/arconia-migrations/latest/spring/spring-ai/2-0/)) |
| OpenRewrite `UpgradeSpringFramework_7_0` | 플랫폼 단계 | Spring AI 전용 아님 ([OpenRewrite](https://docs.openrewrite.org/recipes/java/spring/framework/upgradespringframework_7_0)) |

**권장 순서 (1.1.x 사용자):**

1. Boot 4, Framework 7, Jackson 3 마이그레이션을 먼저 한다.
2. Arconia `UpgradeSpringAi_2_0`을 실행한다 (`arconia update spring-ai --to-version 2.0`).
3. MCP를 쓴다면 공식 M3 레시피로 남은 부분을 보완한다.
4. 레시피가 다루지 않는 부분은 손으로 고친다: `ChatClient.options(builder)`, conversation ID, 툴 루프, JDBC `sequence_id` DDL, Anthropic 생성자, 5.1의 2.0.1 변경.
5. 통합 테스트로 조용한 동작 변화(5.2 오른쪽 열)를 확인한다.

**Kotlin 코드베이스는 추가 비용이 든다.** JSpecify와 K2의 null 처리, Jackson 3 Kotlin 모듈 변경이 겹친다. Embabel 위키에는 Jackson 3 함정이 정리되어 있다. 패키지가 `tools.jackson.databind.*`로 바뀌었지만 어노테이션은 `com.fasterxml.jackson.annotation.*`에 그대로 있다. 매퍼가 불변이 되었다. `Jackson2ObjectMapperBuilder`가 사라졌다 ([Embabel 위키](https://github.com/embabel/embabel-agent/wiki/Spring-Boot-4---Spring-AI-2.0-Migration)) `[community]`.

### 5.6 다음 패치 예고 (미출시) `[2.0.2-SNAPSHOT]`

Neo4j 채팅 메모리의 인덱스가 `session_id_index`와 `message_idx_index`로 바뀐다. 예전 인덱스 두 개는 `DROP INDEX ... IF EXISTS`로 지우라고 안내한다. 이 내용은 아직 릴리스되지 않은 업그레이드 노트 소스에만 있다 ([UN source 2.0.x](https://github.com/spring-projects/spring-ai/blob/2.0.x/spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc)).

---

## 6. 생태계·경쟁·이슈: Boot 4 전용이 강점이자 약점, 스트리밍+툴이 가장 불안정

### 6.1 경쟁 구도: 선택은 플랫폼이 가른다

| 프로젝트 | 최신 (날짜) | GitHub Stars | 위치 |
|---|---|---|---|
| **Spring AI** | 2.0.1 (2026-08-20 / 08-21) | 9,503 (기여자 568명) | Boot 4 네이티브 통합 계층 |
| LangChain4j | 1.20.2 (2026-09-28) | 13,184 (기여자 499명) | 프레임워크 중립, Boot 3.5와 Boot 4 스타터 모두 제공 |
| Spring AI Alibaba | 2.0.0-M1.1 (2026-06-25) | 10,952 | Spring AI 위의 그래프·에이전트 계층 (아래 6.2) |
| Embabel | 1.5.2 (2026-09-16) | 4,480 | Spring AI 위의 GOAP 에이전트 프레임워크 |
| Koog (JetBrains) | 1.3.0 (2026-09-24) | 4,596 | Kotlin 에이전트 프레임워크 |
| Google ADK Java | v1.10.1 (2026-09-18) | 1,741 | Google 생태계 에이전트 |
| Semantic Kernel Java | java-1.4.4-RC1 (2025-05-06) | 276 | 2025-05 이후 릴리스 없음 |

근거: ([Spring AI](https://github.com/spring-projects/spring-ai), [LangChain4j releases](https://github.com/langchain4j/langchain4j/releases), [Alibaba](https://github.com/alibaba/spring-ai-alibaba), [Embabel releases](https://github.com/embabel/embabel-agent/releases), [Koog](https://github.com/JetBrains/koog), [ADK Java](https://github.com/google/adk-java), [SK Java releases](https://github.com/microsoft/semantic-kernel-java/releases))

| 상황 | 추천 |
|---|---|
| 이미 Boot 4를 쓰고 있고 자동 구성, 관측성, MCP 서버 어노테이션이 필요하다 | **Spring AI 2.0.x** |
| Boot 3.x에 묶여 있거나 Quarkus·plain Java를 쓰거나, 제공자 폭이 필요하다 | **LangChain4j**. `langchain4j-spring`이 Boot 3.5.13과 4.0.5 양쪽으로 빌드된다 ([pom](https://raw.githubusercontent.com/langchain4j/langchain4j-spring/main/pom.xml)) |
| Spring 위에서 목표 지향 에이전트를 만든다 | **Embabel** (Spring AI를 대체하지 않고 그 위에 올린다) |
| 워크플로형 멀티 에이전트를 바로 쓰고 싶다 | LangChain4j `langchain4j-agentic` (supervisor, 루프·병렬 워크플로, HITL suspend/resume) ([dev.to](https://dev.to/jamilxt/spring-ai-vs-langchain4j-in-2026-the-choice-got-harder-here-is-how-to-decide-429n)) |

LangChain4j는 Spring AI가 앞서던 영역을 따라잡고 있다. 1.20.0(2026-09-04)에서 실험적 reactive AI Services와 opt-in Jackson 3를 추가했다 ([LangChain4j 1.20.0](https://github.com/langchain4j/langchain4j/releases/tag/1.20.0)). dev.to 비교 글은 "If you're on Spring Boot 4 already, Spring AI 2.0 is a clean fit"이라고 정리하면서, 2026년 운영 환경 대부분은 아직 Boot 3.x라고 지적한다 ([dev.to](https://dev.to/jamilxt/spring-ai-vs-langchain4j-in-2026-the-choice-got-harder-here-is-how-to-decide-429n)). 코어 제공자를 7개로 줄인 결과 제공자 폭에서는 LangChain4j와의 격차가 벌어졌다. Spring AI는 코어의 깊이와 벤더가 유지하는 외부 모듈 쪽에 걸고 있다.

### 6.2 생태계: 에이전트 기능은 코어 밖에서 자란다

- **spring-ai-community org (30개 저장소):** `awesome-spring-ai`(스타 856), `spring-ai-agent-utils`(스타 647), `spring-ai-playground`, `mcp-security`, `spring-ai-agentcore`(Bedrock AgentCore), `spring-ai-session`, `spring-ai-a2a`, `spring-ai-watsonx-ai` 등 ([spring-ai-community](https://github.com/spring-ai-community))
- **Spring AI Alibaba는 뒤처져 있다.** 정식 릴리스 라인(1.1.2.x)은 Spring AI 1.1.2와 Boot 3.5.8 기반이라 둘 다 OSS 지원이 끝났다. 2.x 쪽 릴리스는 Spring AI **2.0.0-M1** 기반 마일스톤뿐이고, 2.0.0 GA를 쓰는 `2.x` 브랜치는 아직 릴리스되지 않았다 ([releases](https://github.com/alibaba/spring-ai-alibaba/releases), [2.x pom](https://raw.githubusercontent.com/alibaba/spring-ai-alibaba/2.x/pom.xml), [Maven metadata](https://repo1.maven.org/maven2/com/alibaba/cloud/ai/spring-ai-alibaba-bom/maven-metadata.xml))
- **거버넌스 신호:** 2026-08-06 `agent-client`, `agent-bench`, `claude-agent-sdk-java` 등이 "No longer maintained"로 표시되고 Mark Pollack 개인 계정으로 옮겨 갔다. 공개된 이유는 없다 ([spring-ai-community](https://github.com/spring-ai-community))
- **한국어 자료:** Inflearn 강의, KSUG 밋업 등이 있지만 2.0.x를 다룬 자료는 찾지 못했다. 2.0.1을 도입한다면 공식 레퍼런스와 Upgrade Notes를 기준으로 삼는다 ([Inflearn Spring AI](https://www.inflearn.com/tag-curation/skill/spring-ai), [KSUG](https://event-us.kr/ksug/event/114010))

### 6.3 채택 신호: 기관의 추진력은 강하고, 현장 커뮤니티 반응은 약하다

| 신호 | 값 |
|---|---|
| 기여자 / Stars / Forks | 568 / 9,503 / 2,936 ([repo](https://github.com/spring-projects/spring-ai)) |
| Spring I/O 2026 (바르셀로나, 4월) | 메인 트랙 세션의 약 1/3이 Spring AI, MCP, 에이전트 ([foojay](https://foojay.io/today/spring-i-o-2026-field-notes-from-barcelona/)) |
| Stack Overflow `spring-ai` 태그 | 전체 99건, 2026년 6건 ([SO](https://stackoverflow.com/questions/tagged/spring-ai)) |
| 검증 가능한 기업 사례 | 찾지 못함 |
| Maven 다운로드 추이 | 공개되지 않음 |

Stars가 LangChain4j보다 적은 것은 Spring 사용자가 GitHub보다 start.spring.io와 BOM으로 들어오기 때문일 수 있다. 기여자 수(568 대 499)가 건강도를 더 잘 보여 주는 지표다. 이는 연구자의 해석이다.

### 6.4 알려진 이슈 (2.0.x GA)

| 영역 | 이슈 | 상태 |
|---|---|---|
| **스트리밍 + 툴 호출** | `streamToolCallResponses` 제거로 툴 호출 매니저가 깨짐 [#6614](https://github.com/spring-projects/spring-ai/issues/6614). `OpenAiChatModel$ChunkMerger` "No value present" [#6762](https://github.com/spring-projects/spring-ai/issues/6762). 2.0.1 스트리밍에서 라운드별 Usage 손실 [#6996](https://github.com/spring-projects/spring-ai/issues/6996) | 미해결 |
| **옵션·타임아웃** | 2.0.1부터 옵션이 붙은 Prompt의 OpenAI 요청별 타임아웃이 60초로 떨어짐 [#6915](https://github.com/spring-projects/spring-ai/issues/6915). `ChatClient.mutate()`가 yaml 기본값을 덮어쓰지 못함 [#6620](https://github.com/spring-projects/spring-ai/issues/6620) | 미해결 |
| **추론 스트리밍** | 증분 API 없이 누적 텍스트만 제공 [#6833](https://github.com/spring-projects/spring-ai/issues/6833). Bedrock 스트림이 `reasoningContent`를 누락 [#6845](https://github.com/spring-projects/spring-ai/issues/6845) | 미해결 |
| **툴·MCP 스키마** | 선택·nullable 필드에서 만족 불가능한 output schema 생성 [#6950](https://github.com/spring-projects/spring-ai/issues/6950). `$defs`와 `$ref` 위치 불일치 [#6633](https://github.com/spring-projects/spring-ai/issues/6633) | 미해결 |
| **트리아지 적체** | 열린 이슈 968건 중 **745건**이 `status: waiting-for-triage` ([검색](https://github.com/spring-projects/spring-ai/issues?q=is%3Aissue+is%3Aopen+label%3A%22status%3A+waiting-for-triage%22)) | – |

**보안 `[2.0.1]`:** CVE 7건을 수정했다. PDF 아웃라인 재귀(CVE-2026-47851), ONNX 캐시 디렉터리 예측 가능(-47852), MCP 세션 할당 DoS(-59279), `ResourceCacheService` 경로 탐색(-59294), 시맨틱 캐시 테넌트 간 우회(-59308), `RedisChatMemoryRepository` RediSearch 태그 인젝션(-59319), 광고하지 않은 툴 실행(-59318) ([2.0.1 블로그](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/)). 2026-06-12 이후 1.x OSS 릴리스는 없다. 각 CVE가 1.x에도 해당하는지는 확인하지 못했다.

**실무 판단:** 스트리밍 에이전트에서 토큰 회계가 정확해야 한다면, 쓰려는 제공자에서 직접 검증하거나 2.0.2 이후를 기다린다. 패치 버전을 올릴 때마다 고정·통합 테스트를 거친다. SLA가 필요하면 상용 지원을 검토한다.

---

## 7. 출처 충돌 모음: 문서 표 여러 개가 코드보다 늦다

### 7.1 API·설정 관련

| 주제 | 출처 A | 출처 B | 판단 |
|---|---|---|---|
| 툴 한도 속성 이름 `[2.0.1]` | [Tools 문서](https://docs.spring.io/spring-ai/reference/api/tools.html): `max-calls-per-tool-default`, `.max-calls-per-tool.<name>`, `excluded-tools`, `max-total-tool-calls`, `on-limit-exceeded` | [Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html): `max-calls-per-tool=40` | **미해결.** 빌더 API를 권장 |
| Anthropic 네이티브 구조화 출력 | [비교표](https://docs.spring.io/spring-ai/reference/api/chat/comparison.html): "Built-in JSON ✗" | [Native 문서](https://docs.spring.io/spring-ai/reference/api/structured-output/native.html) "Full", [Anthropic 문서](https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html) `outputSchema()` | 비교표가 낡았을 가능성 높음 |
| MCP 서버 기본 프로토콜 | [overview 표](https://github.com/spring-projects/spring-ai/blob/v2.0.1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/mcp/mcp-overview.adoc): 빈 값이면 SSE | [코드](https://github.com/spring-projects/spring-ai/blob/v2.0.1/auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-common/src/main/java/org/springframework/ai/mcp/server/common/autoconfigure/properties/McpServerProperties.java): `STREAMABLE` | **코드가 맞음** |
| `ToolCallingAdvisor` 명시 등록 | Tools 문서: 명시적 오류 | Upgrade Notes: 중복 등록 | 미해결. 명시 등록 제거 |
| 메모리에 툴 메시지 저장 | Chat Memory 문서: "현재 저장되지 않음" | Tools 문서·블로그: InMemory·Redis·Neo4j는 루프 안쪽에서 저장 | 미해결 |
| 메모리 order 메서드 | Tools 문서 `.order(...)` | Upgrade Notes `.advisorOrder(...)` | 미해결 |
| 모델 빌더 옵션 메서드 | 공식 UN: `AnthropicChatModel.builder()...defaultOptions(...)` | [Embabel 위키](https://github.com/embabel/embabel-agent/wiki/Spring-Boot-4---Spring-AI-2.0-Migration)(M8): `.defaultOptions` → `.options` | 미해결. IDE에서 확인 |
| MCP 입력 검증 속성 | 요약본에만 있는 `spring.ai.tools.validation.enabled` | UN 원문은 빌더 `validateToolInputs(false)`만 언급 | 속성은 미검증 |
| MCP 세션 속성명 | `maxSessions`/`sessionIdleTimeout`(#6760) | v2.0.1 properties 클래스에 없음 | 속성명 미확인 |

### 7.2 버전·날짜·문서 관련

| 주제 | 출처 A | 출처 B | 판단 |
|---|---|---|---|
| `Media.Builder.data(Object)` 제거 시점 | UN 추출본: 2.0.0 본문 | [2.0.1 블로그](https://spring.io/blog/2026/08/21/spring-ai-2-0-1-available-now/), UN "Upgrading to 2.0.1" | 2.0.1 |
| `TranscriptionModel` 스트리밍 계약 | UN 2.0.0 본문 | UN "Upgrading to 2.0.1" | 2.0.1 (확정은 아님) |
| ToolSearch 도입 시점 | tool-search 문서 요약: "new in 2.0.1" | GA 블로그, [RC1 노트](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-RC1) #5909 | 2.0.0 |
| Embeddings 문서의 제공자 목록 | [Embeddings 문서](https://docs.spring.io/spring-ai/reference/api/embeddings.html): Azure OpenAI, OCI GenAI 포함 | UN: Azure OpenAI 제거, OCI는 Oracle로 이관 | 문서 목록이 낡음 |
| DeepSeek 분류 | 비교표: "OpenAI-proxy" | 전용 `spring-ai-deepseek` 모듈·스타터 존재 | 두 경로 모두 동작 |
| Getting Started | [2.0.1 페이지](https://docs.spring.io/spring-ai/reference/2.0.1/getting-started.html) BOM 예제 `2.0.0`. [2.1 페이지](https://docs.spring.io/spring-ai/reference/2.1/getting-started.html)는 여전히 "2.0.x supports Boot 4.0.x and 4.1.x" | Maven Central, spring.io API | 버전은 API와 Central 기준 |
| UN의 temperature 예시 | `spring.ai.azure.openai.chat.temperature=0.7` | 같은 문서가 Azure OpenAI 모듈 제거를 명시 | 예시가 낡음 |
| Java 최소 버전 | [besthub.dev](https://www.besthub.dev/articles/spring-ai-2-0-rc-released-migration-guide-new-features-and-langchain4j-comparison-f10aff30a6bf): 마이그레이션 비용으로 "Java 21" | [2.0.1 jar](https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-model/2.0.1/spring-ai-model-2.0.1.jar): 17. 21은 소스 빌드용 | **17** |
| 2.0.0 GA 날짜 | [byteiota](https://byteiota.com/spring-ai-2-ga-java-production-stack/) "May 28"(실제로는 M8 시점). [VSM](https://visualstudiomagazine.com/articles/2026/06/29/spring-ai-2-0-goes-ga-giving-java-developers-a-more-mature-ai-app-stack.aspx) 2026-06-29 보도. spring.io `initialReleaseDate` 2026-06-30(월말 근사값) | GitHub와 Maven Central: 2026-06-12 | **2026-06-12** |
| 코어 제공자 수 | dev.to 검색 요약: "about eight" | [GA 블로그](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/): "seven" | 7 |
| Mistral "deprecated" | 2.0.1 블로그 요약 | UN: enum 상수 정리만 기재 | **판정 불가** (4.3 참조) |
| MCP 스펙 링크 | 일부 2.0.1 문서가 2025-06-18 스펙 페이지를 링크 | GA 블로그·SDK: 2025-11-25 | 링크가 낡음. 지원 스펙은 2025-11-25 |

---

## 8. 로드맵 (마일스톤·계획 단계이며 GA 기능이 아님): 2.1은 MessagePart와 Responses API, 3.0은 에이전트

> 이 장의 내용은 모두 `[2.1.0-M1]` 또는 발표된 계획이다. 운영 환경 기준(2.0.1)과 섞지 않는다. 2.1.0-M1은 Boot 4.2.0-M2를 요구하므로, Boot 4.0/4.1 운영 앱은 2.0.x에 머문다.

### 8.1 2.1.0-M1에 들어간 것 (2026-09-24 / 09-25)

| 기능 | 내용 |
|---|---|
| `MessagePart` (#6997) | `AssistantMessage`·`UserMessage`·`ToolResponseMessage`가 순서 있는 파트 목록을 가진다: `TextPart`, `ReasoningPart`, `ToolCallPart`, `ToolResultPart`, `MediaPart`, `UnknownPart`. `getReasoning()`으로 **제공자 중립적인 추론** 접근 |
| OpenAI Responses API (#7006) | `OpenAiResponsesChatModel`, 전환은 `spring.ai.openai.chat.api=responses` 한 줄. `HostedTool`로 웹 검색·파일 검색·코드 인터프리터·원격 MCP·이미지 생성 같은 서버 실행 툴을 쓴다. 모델은 stateless |
| `VectorStore.upsert` (#7002) | 미리 계산한 `float[]` 임베딩을 그대로 저장(`add()`는 항상 다시 임베딩). pgvector, Redis, Elasticsearch, Qdrant |
| MCP | `McpStatelessSyncServerCustomizer`/`McpStatelessAsyncServerCustomizer`. SDK는 **2.0.0 그대로** |
| 플랫폼 | Boot 4.2(4.2.0-M2로 빌드), Anthropic SDK 2.64.0 |

근거: ([GitHub v2.1.0-M1](https://github.com/spring-projects/spring-ai/releases/tag/v2.1.0-M1), [2.1.0-M1 블로그](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/), [openai-responses-chat@v2.1.0-M1](https://github.com/spring-projects/spring-ai/blob/v2.1.0-M1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/openai-responses-chat.adoc))

### 8.2 2.1에서 이미 예고된 breaking change

- **메시지:** `getMedia()`와 `getToolCalls()`가 수정 불가 목록을 반환한다. `equals`/`hashCode`에 파트가 포함된다. protected 필드 `textContent`·`media`·`responses`는 한 릴리스 동안만 deprecated로 유지된다. 스트리밍은 `StreamingParts`로 바뀐다 ([Upgrade Notes 2.1](https://docs.spring.io/spring-ai/reference/2.1/upgrade-notes.html#upgrading-to-2-1-0)).
- **Anthropic:** 비스트리밍 응답이 단일 `Generation`이 되고 추론은 `getOutput().getReasoning()`으로 읽는다. `AnthropicAssistantMessage`와 `anthropicThinkingContents`는 제거된다. **이전 버전이 저장한 채팅 메모리를 재생하면 thinking 블록이 사라진다.**
- **Bedrock / PgVector:** `BedrockChatOptions.requestParameters`가 `Map<String,String>`에서 `Map<String,Object>`로 바뀐다. 저장된 `distance` 메타데이터를 덮어쓰지 않는다.
- **제거 예정:** `toolCallExceptionClass`와 `AbstractMcpToolProvider.doGetToolCallException()` ([Upgrade Notes #2.0.1](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-1)).
- **메모리 방향:** "spring-ai-session ... is planned to replace `ChatMemory` in Spring AI 2.1." ([Upgrade Notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#upgrading-to-2-0-0))

### 8.3 이후 계획과 날짜

| 항목 | 내용 | 확실성 |
|---|---|---|
| 2.1.0-RC1 | "Work on 2.1.0-RC1 has started. Message parts are the foundation for most of it." 모든 `ChatModel`을 `MessagePart` 기반으로 리팩터링(Anthropic 완료 #7067, Google GenAI 진행 중 #7075) | 공식 발표, 기한 없음 ([milestone 2.1.0-RC1](https://github.com/spring-projects/spring-ai/milestone/56)) |
| 세션 관리의 코어 편입 | Spring AI Session의 핵심을 코어로 가져온다. 대화 이력 압축(compaction)·회상(recall), `SessionRepository`가 `MessagePart`를 영속화 | 공식 발표 |
| MCP 2026-07-28 스펙 | SDK와 Spring AI 양쪽에서 "in progress". SEP에는 stateless 기본화, Roots·Sampling·Logging deprecated, Tasks 확장이 있다 → `@McpSampling`, `@McpLogging`, `root-change-notification`이 바뀔 가능성이 있다 | 진행 중 / 영향 범위는 추정 ([java-sdk issues](https://github.com/modelcontextprotocol/java-sdk/issues?q=2026-07-28)) |
| RC1 후보 이슈 | `TokenTextSplitter` chunkOverlap(#4054), PgVector distance type(#6864), Azure 커스텀 자격증명(#6937), deprecated Redis 메모리 모듈 제거(#7017) | 마일스톤에 배정됨 |
| **공식 에이전트 프로젝트** | "We plan to release the new agentic support in November 2026 as a separate project under Spring Projects Experimental" | 공식 계획 (2026년 11월) |
| **Spring AI 3.0** | "...with the plan to merge it into Spring AI 3.0 mid next year" | 공식 계획 (2027년 중반) |
| **2.1.0 GA 날짜** | 공식 날짜는 없다. 2.0.0·2.0.1·2.1.0-M1이 모두 대응하는 Boot 버전과 하루 이내에 나왔고, Boot 4.2.x 첫 릴리스가 2026-11-30(월말 근사)으로 잡혀 있다 → **2026년 11월 말이 유력** | **추정** ([Spring Boot generations](https://api.spring.io/projects/spring-boot/generations)) |
| 2.1.x 지원 기간 | 아직 지원 표에 없다. 패턴대로라면 Boot 4.2.x와 같은 날짜(OSS 2027-12-31, 상용 2028-12-31) | **추정** |

근거(인용): ([2.1.0-M1 블로그](https://spring.io/blog/2026/09/25/spring-ai-2-1-0-M1-available-now/))

**충돌: 다음 단계가 M2인가 RC1인가.** Responses 문서는 메시지 파트를 영속화하는 메모리 구현이 "expected in 2.1.0-M2"라고 쓴다 ([openai-responses-chat@v2.1.0-M1](https://github.com/spring-projects/spring-ai/blob/v2.1.0-M1/spring-ai-docs/src/main/antora/modules/ROOT/pages/api/chat/openai-responses-chat.adoc)). 그러나 GitHub에는 "2.1.0-RC1" 마일스톤만 있고 "2.1.0-M2"는 없다 ([milestones API](https://api.github.com/repos/spring-projects/spring-ai/milestones?state=open)). 2.0 때도 "마지막" M7 다음에 M8이 추가된 적이 있으므로, M2가 끼어들 가능성은 열려 있다.

---

## 결론

2.0에서 가장 크게 바뀐 것은 기능 목록이 아니라 **설계를 결정하는 위치**다. 툴 루프, 메모리, 검증 재시도, 툴 탐색이 모두 어드바이저 체인 위에서 `order` 값으로 조합된다. 그래서 "메모리를 +200에 둘지 +400에 둘지"가 이제 데이터 보존과 토큰 비용을 가르는 아키텍처 결정이 되었다. 이 구조는 2.1의 `MessagePart`와 코어 세션 관리, 그리고 3.0의 공식 에이전트 프로젝트로 이어지는 토대다. Spring AI가 "통합 라이브러리"에서 "에이전트 런타임"으로 가는 경로는 이미 그려져 있지만, 2026-10-01 현재 그 위층(A2A, 세션, 에이전트 유틸리티, MCP 보안)은 각자 릴리스 주기를 가진 커뮤니티 프로젝트에 흩어져 있다. 그러니 "Spring AI로 에이전트를 만든다"는 평가는 코어 하나가 아니라 이 프로젝트 묶음 전체를 기준으로 해야 한다.

실무적으로 GA는 두 군데에서 최신 기능보다 한 박자 늦다. MCP 스펙(2025-11-25 대 2026-07-28, SDK 2.0.0 고정)과 OpenAI Responses API다. OpenAI 추론과 툴 호출을 함께 쓰거나 최신 MCP 기능이 필요한 팀은 2.1 GA(2026년 11월 말 추정)까지 "안정성이냐 기능이냐"를 골라야 한다. 새 프로젝트라면 지금 **2.0.1과 Boot 4.1.x로 시작하는 것이 맞다.** 다만 2.1 업그레이드 비용을 줄이려면 두 가지를 지킨다. `ChatModel`이 아니라 `ChatClient`를 기준으로 코드를 짜고, Anthropic thinking처럼 제공자 메타데이터에서 추론을 직접 꺼내는 코드는 한곳에 격리한다. 2.1의 `ReasoningPart`가 바로 그 지점을 대체하기 때문이다. 또 2.0.1이 보여 주었듯 이 프로젝트의 패치 릴리스는 동작을 바꾼다. 버전 고정, 통합 테스트, Maven Central의 `<release>` 표시를 믿지 않는 습관이 기본값이 되어야 한다.
