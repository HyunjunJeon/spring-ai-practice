# Opus 5.5 xhigh: 단계 1 원본 초안

상태: 네 단계 중 첫 단계만 수신. 최종 전체 계획과 부모 검토는 아직 미완료입니다. 모델: anthropic-subscription/claude-opus-5-5; effort: xhigh; 원본 호출 종료 코드: 0.

Phase 1 계획 작성 요청으로 이해했습니다. 검증된 사실만으로 계약과 작업 5개를 정리했고, 아래 계획 전달로 마칩니다.

# Phase 1: 런타임 모델 레지스트리와 턴 고정 바인딩

## 결정
- 새 모듈은 만들지 않습니다. `agent-core`에 `core/model` 패키지를 추가하고, 각 provider는 `ModelProvider` bean으로 기여합니다.
- 공식 자동구성의 다중 모델 모호성은 `spring.ai.chat.client.enabled=false`와 `spring.ai.model.chat=none`으로 막습니다. `ChatModel`은 bean으로 등록하지 않고 provider 내부 인스턴스로만 둡니다.
- `ModelRef`마다 독립된 `ChatClient`를 만들어 캐시합니다. 인증·옵션·HTTP 클라이언트는 공유하지 않고, 정렬된 advisor·prompt contributor·interceptor 래핑 callback만 공유합니다.
- 바인딩은 턴 시작 시 한 번만 하고, 모든 tool round가 같은 client 호출 안에서 끝납니다. 자동 fallback은 없습니다. 키가 없어도 기동은 실패하지 않으며 해당 항목이 `AUTH_MISSING`이 됩니다.
- 검증 게이트(2.0.1 jar 확인): `ChatClient.builder(ChatModel)`, `OpenAiChatModel` 생성 API, 위 두 속성명, `AssistantMessage` metadata 재구성, `SlashCommand` 시그니처, `agent.sh` 프로필 전달 방식.

## 공유 계약
```java
record ModelRef(String provider, String model) {} // "provider/model"
enum UnavailableCode { UNKNOWN_MODEL, AUTH_MISSING, PROVIDER_DISABLED }
record ModelEntry(ModelRef ref, String wireFamily, UnavailableCode unavailable) {} // null=가용
interface ProviderAuth { boolean authenticated(); void login(AgentConsole c); void logout(); }
interface ModelProvider { String id(); List<ModelEntry> models(); ChatModel chatModel(ModelRef r); Optional<ProviderAuth> auth(); }
record ModelBinding(UUID bindingId, ModelRef ref, String wireFamily, ChatClient client) {}
class ModelRegistry { List<ModelEntry> list(); ModelBinding bind(ModelRef r); } // ModelUnavailableException.code()
record ModelSelection(ModelRef ref, Instant at, String source) {} // default|command|restored
interface SessionModelStore { Optional<ModelSelection> load(String sessionId); void save(String sessionId, ModelSelection s); }
record TurnContext(String sessionId, String turnId, Path cwd, ModelRef model) {}
record ModelBoundEvent(String turnId, ModelRef ref, UUID bindingId) implements TraceEvent {}
```
- 키: assistant metadata `miniagent.provider`·`miniagent.model`, `ToolContextKeys.MODEL_REF`.
- Phase 2는 run에 `ModelRef`와 `bindingId`를 저장하고, 재개할 때 같은 ref로만 `bind`합니다.
- Phase 3은 `turnId`로 diff·테스트·rollback을 묶습니다.
- Phase 4는 명시한 `ModelRef`와 별도 sessionId로 독립 `bind`합니다.

## 프로필·세션 이전
- 기본 YAML에 `miniagent.models.providers.<id>.{base-url,api-key-env,models}`를 선언합니다.
- 기존 프로필의 backend 설정값은 해당 provider 블록으로 옮기고, 프로필에는 `miniagent.models.default`만 남깁니다. `fake` 프로필의 기본값은 `fake/echo`입니다.
- OpenAI와 Zai는 같은 `OpenAiChatModel`을 쓰지만 API 객체·키·URL·옵션은 따로 둡니다.
- Claude/Codex 플러그인에서 선택 provider 조건과 자체 `/login`을 제거하고, 대신 `ProviderAuth`를 기여하게 합니다.
- 인증 명령은 코어의 `/login <provider>`와 `/logout <provider>`만 남깁니다. 같은 이름의 명령이 둘 이상 등록되면 기동이 실패합니다.
- 복원한 선택 모델을 쓸 수 없어도 다른 모델로 바꾸지 않습니다. 사용 불가 상태를 표시하고, 턴은 오류 코드와 함께 실패시킵니다.
- provenance가 없는 legacy 행은 unknown으로 봅니다.
- 현재 ref와 다르거나 unknown인 행은 재생할 때 `reasoningContent`, Claude signed content, 암호화 reasoning을 제거합니다. 텍스트와 tool 쌍은 유지하고 JSONL 파일은 재작성하지 않습니다.

## 작업
경로 기준은 `mini-agent/`이고, `core/`는 `agent-core/src/main/java/com/example/miniagent/core/`를 뜻합니다.

QA 공통: `cd mini-agent && MVN="./mvnw -q -am -Dsurefire.failIfNoSpecifiedTests=false"`. ✓는 성공 경로, ✗는 실패를 단언하는 테스트입니다.

테스트는 스크립트한 fake `ChatModel` 또는 포트 0 JDK `HttpServer`에 기록된 요청을 호출이 끝난 뒤 단언합니다. sleep을 쓰지 않고 문구도 고정하지 않으며, 코드·ref·헤더만 비교합니다. 커밋은 하지 않습니다.

- [ ] 1. 코어 레지스트리·계약
  - 경로: `core/model/`(위 타입, `ModelUnavailableException`, `InMemorySessionModelStore`, `AgentChatClientFactory`), `core/ToolContextKeys.java`
  - 의존: 없음 / 실행: deep / 근거: `AgentAutoConfiguration.java:77-134`, `Agent.java:28-66`
  - 수용: 같은 ref는 같은 client를 받고, 다른 ref는 다른 인스턴스를 받습니다. 미인증 항목도 목록에 남습니다.
  - QA: ✓ `$MVN -pl agent-core test -Dtest=ModelRegistryTest` ✗ `$MVN -pl agent-core test -Dtest='ModelRegistryTest#bindUnknownFailsWithUnknownModel'`
- [ ] 2. 턴 고정 바인딩·선택 저장
  - 경로: `core/Agent.java`, `core/AgentSession.java`, `core/AgentAutoConfiguration.java`, `plugins/plugin-memory/.../JsonlSessionModelStore.java`, `.../MemoryPluginAutoConfiguration.java`
  - 내용: `run`이 선택 모델을 읽어 `bind`를 한 번 하고, `TurnContext`를 ToolContext와 advisor context에 넣습니다. 동적 provider 확장(105-120)은 lazy로 두고 fallback 비활성 상태를 유지합니다.
  - 의존: 1 / 실행: deep / 근거: `Agent.java:66-120`, `AgentSession.java:15-43`
  - 수용: tool 실행 중 선택을 A에서 B로 바꿔도 그 턴의 모든 요청은 A로 가고, 다음 턴부터 B로 갑니다. 재시작 후에도 선택이 복원됩니다.
  - QA: ✓ `$MVN -pl plugins/plugin-memory test -Dtest='AgentTurnBindingTest,JsonlSessionModelStoreTest'` ✗ `$MVN -pl agent-core test -Dtest='AgentTurnBindingTest#providerErrorMidToolFailsWithoutFallback'`
- [ ] 3. Provider 어댑터·프로필·인증 명령
  - 경로: `agent-app/src/main/java/com/example/miniagent/app/model/{OpenAiCompatibleModelProvider,FakeModelProvider}.java`, `agent-app/src/main/resources/application*.yml`, `core/model/AuthCommand.java`, Claude/Codex AutoConfiguration(`rg -l ConditionalOn plugins`로 확정)
  - 의존: 1 / 실행: unspecified-high / 근거: 단일 backend 프로필, OpenAI·Zai 공식 구현, `/login` 중복 위험
  - 수용: tool round까지 포함해 OpenAI와 Zai 요청의 Authorization·경로가 각자의 설정과 일치합니다. 키 없이도 컨텍스트가 기동되고, `ChatClient.Builder` bean은 없습니다.
  - QA: ✓ `$MVN -pl agent-app test -Dtest='OpenAiZaiIsolationTest,NoKeyStartupTest'` ✗ `$MVN -pl agent-app test -Dtest='SlashCommandConflictTest#duplicateNameFailsStartup'`
- [ ] 4. Provenance·reasoning 위생
  - 경로: `core/model/ProviderPrivateFieldFilter.java`, `plugins/plugin-memory/.../JsonlChatMemory.java`(쓰기 시 provenance 태깅), 메모리 advisor(예산 계산 전에 필터 적용)
  - 의존: 1, 2 / 실행: deep / 근거: JSONL metadata 보존, `MemoryPluginAutoConfiguration.java`의 순서·예산
  - 수용: 같은 ref면 private 필드를 보존하고, 다른 ref나 legacy 행이면 제거합니다. 저장 파일은 바이트 단위로 바뀌지 않습니다.
  - QA: ✓ `$MVN -pl plugins/plugin-memory test -Dtest=ProviderPrivateFieldFilterTest` ✗ `$MVN -pl plugins/plugin-memory test -Dtest='ProviderPrivateFieldFilterTest#legacyRowDropsSignature'`
- [ ] 5. `/models`·`/model`·선택 모델 표시
  - 경로: `core/model/{ModelsCommand,ModelCommand}.java`, `core/AgentConsole.java`와 그 구현체
  - 내용:
    - `/models`는 항목, 사용 불가 코드, 현재 선택을 보여줍니다.
    - `/model <ref>`는 검증한 뒤 저장합니다. 실패하면 선택은 바뀌지 않고, 진행 중인 턴에는 다음 턴부터 적용됩니다.
    - 상태줄에는 선택한 ref를 보여주고, 턴 실행 중에는 `ModelBoundEvent`의 ref를 보여줍니다.
  - 의존: 2, 3 / 실행: unspecified-high / 근거: `SlashCommand.java`, `AgentConsole.java`, `TraceEvent.java`
  - 수용: 키가 없는 fake 프로필에서 E2E 응답이 나옵니다.
  - QA: ✓ `$MVN -pl agent-app test -Dtest=ModelCommandTest && env -i PATH="$PATH" HOME="$HOME" JAVA_HOME="$JAVA_HOME" SPRING_PROFILES_ACTIVE=fake bash ./agent.sh "hello"` ✗ `$MVN -pl agent-app test -Dtest='ModelCommandTest#unavailableSelectionKeepsPrevious'`

