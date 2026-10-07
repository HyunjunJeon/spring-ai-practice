# Opus 5.5 xhigh: 단계 4 원본 초안

상태: 단계 3 수신과 전체 부모 검토가 미완료입니다. 모델 claude-opus-5-5 / xhigh / 종료 코드 0.

Phase 4 계획 요청으로 이해했습니다. 계약, 불변식, 작업 18-23, F1-F4를 정리했습니다.

# Phase 4: 독립 작업 위임·협업

## 결정
- 새 모듈은 만들지 않습니다. `core/delegation`(이하 `deleg/`) 패키지만 추가합니다. 런타임은 Java `java.util.concurrent`만 쓰고 senpi/Bun/Node에는 의존하지 않습니다.
- 위임은 부모 턴 안에서 호출하는 동기 `delegate` 도구입니다. 부모 `Agent.run`은 결과가 나올 때까지 블록합니다. 그래서 TUI의 단일 busy task와 REPL 동작은 그대로이고, 병렬로 도는 것은 child뿐입니다. 위임 깊이는 1로 제한합니다.
- 구현 게이트(2.0.1 jar와 현재 소스에서 확인):
  - `ToolCallingAdvisor` 순서
  - 요청 단위 tool 지정이 기본값을 대체하는지 병합하는지
  - `ChatMemory.CONVERSATION_ID`
  - `Usage` 토큰 접근자
  - MCP 도구 변경 API
  - `ToolInterceptor`, 권한 질문, TUI pending question 시그니처
  - 셸 도구 유무

## 기본값 소유자
- **모델(`DelegationRuntime`)**: 부모 `TurnContext.model`을 씁니다. 세션의 현재 선택은 쓰지 않습니다. 명시한 ref를 쓸 수 없으면 그 child만 `FAILED(UNAVAILABLE_MODEL)`로 끝납니다. 원인 `UnavailableCode`는 trace에 남기고, fallback은 없습니다.
- **세션(`DelegationRuntime`)**: 계획을 만들 때 `TurnContext`에서 `parentSessionId~planId~childId~a<n>`을 캡처합니다. 따라서 세션 ID가 나중에 바뀌어도 영향이 없습니다.
- **역할(`RolePolicy`)**:
  - research: `READ_ONLY`, 읽기·검색만
  - implement: `STAGED`, 읽기와 staged 쓰기
  - verify: `NO_MUTATION`, 읽기와 허용된 명령
  - 동적 MCP 도구는 모든 역할에서 기본 제외하고, `roles.<role>.mcp-tools`에 적힌 이름만 허용합니다.
  - 설정으로는 권한을 줄이기만 할 수 있습니다. 넓히는 설정이 있으면 기동이 실패합니다.
- **한도(`DelegationProperties`, `miniagent.delegation.*`)**:
  - `max-concurrent=2`, `queue-capacity=8`, `max-attempts=2`
  - 부모 턴 한도: `deadline=10m`, `tokens=200000`, `tool-calls=200`
  - staged 루트: `<cwd>/.miniagent/delegation/<planId>/<childId>/`
  - child 예산을 지정하지 않으면 부모의 남은 예산을 균등 분할합니다.
- **적용(`StagedApplyService`)**: 부모 세션만 적용할 수 있습니다. Git이 unborn·미추적 상태이므로 비교 기준은 git diff가 아니라 sha256입니다.

## 공유 계약
```java
enum ChildRole { RESEARCH, IMPLEMENT, VERIFY }
enum WriteMode { READ_ONLY, STAGED, NO_MUTATION }
record Budget(Duration deadline, long tokens, int toolCalls) {}
record ChildSpec(String childId, ChildRole role, String task, Optional<ModelRef> model,
    Set<String> dependsOn, Optional<Budget> budget, int maxAttempts) {}
record DelegationPlan(String planId, TurnContext parent, List<ChildSpec> children) {}
record ToolScope(Set<String> toolNames, WriteMode mode, String mcpFingerprint) {}
enum ChildStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, SKIPPED, CANCELLED, TIMED_OUT }
enum ChildFailure { INVALID_PLAN, UNAVAILABLE_MODEL, BUDGET_EXHAUSTED, QUEUE_FULL, SESSION_BUSY,
    DEPENDENCY_FAILED, PERMISSION_DENIED, MUTATION_DETECTED, NO_EVIDENCE, CRASHED, RECOVERED }
record EvidenceRef(String turnId, String toolCallId, String outputSha256) {}
record StagedChange(String relPath, String baseSha256, String stagedSha256) {}
record ChildResult(String childId, ChildStatus status, Optional<ChildFailure> failure, int attempts,
    ModelRef model, UUID bindingId, List<EvidenceRef> evidence, List<StagedChange> staged, String summary) {}
enum PlanStatus { COMPLETE, PARTIAL, FAILED, CANCELLED }
enum ApplyResult { APPLIED, CONFLICT, NOT_FOUND }
record DelegationResult(String planId, String parentTurnId, PlanStatus status, List<ChildResult> children) {}
interface DelegationRuntime { DelegationHandle submit(DelegationPlan p); } // InvalidPlanException
interface DelegationHandle { CompletableFuture<DelegationResult> result(); void cancel(); }
final class SessionRunLock { AutoCloseable acquire(String sessionId); } // SessionBusyException
final class BudgetLedger { Optional<Reservation> reserve(String parentTurnId, Budget b); }
record ApprovalRequest(UUID requestId, String planId, String childId, String toolCallId, String tool, String argsSha256) {}
interface ApprovalBroker { CompletableFuture<Boolean> ask(ApprovalRequest r); }
record DelegationEvent(String planId, String childId, ChildStatus status, int attempt) implements TraceEvent {}
interface DelegationStore { void save(DelegationResult r); List<DelegationResult> incomplete(); }
```
- child trace에는 `ToolContextKeys.DELEGATION`=`planId/childId`를 붙입니다.
- 저장하는 값은 ref 문자열, bindingId, sha256, MCP fingerprint뿐입니다. 키, `ChatClient`, `ChatModel`은 저장하지 않습니다.

## 실패 불변식
1. 계획 검증(중복 id, 없는 의존 대상, 순환, 깊이>1, 사용 불가 ref, 예산 합 초과)에 실패하면 child는 하나도 시작하지 않고 예약도 하지 않습니다.
2. child 권한은 부모 권한의 부분집합입니다. 기존 interceptor 체인 다음에 `ScopedToolInterceptor`가 범위 밖 호출을 `PERMISSION_DENIED`로 거부합니다.
3. child는 시작할 때 `ModelRegistry.bind`를 한 번 호출하고, 모든 round에서 같은 binding을 씁니다.
4. 예산:
   - 예약 합계는 부모의 남은 예산을 넘을 수 없고, 쓰지 않은 예약은 child가 끝날 때 반환합니다.
   - 예약 실패는 `SKIPPED(BUDGET_EXHAUSTED)`, 큐 포화는 `SKIPPED(QUEUE_FULL)`입니다.
   - 실행 중 토큰이나 tool 한도를 넘으면 `FAILED(BUDGET_EXHAUSTED)`입니다.
   - 기한을 넘기면 `cancel(true)` 후 `TIMED_OUT`입니다.
   - 어느 경우든 부모 턴은 계속됩니다.
5. sessionId마다 실행은 하나만 허용합니다. 두 번째 실행 요청은 `SESSION_BUSY`입니다.
6. 선행 작업이 SUCCEEDED가 아니면 후행 작업은 `SKIPPED(DEPENDENCY_FAILED)`가 됩니다. 형제 작업은 계속 진행합니다.
7. 재시도는 provider 일시 오류(I/O, 429, 5xx)에만 합니다. 같은 ref와 새 attempt 세션으로 다시 시도하고, 이전 attempt의 staged 결과는 버립니다.
8. 증거와 변경 검사:
   - SUCCEEDED가 되려면 runtime이 기록한 `EvidenceRef`가 하나 이상 있어야 합니다. 모델의 서술은 증거로 치지 않으며, 증거가 없으면 `NO_EVIDENCE`입니다.
   - verify 실행 전후로 작업공간 sha256(`target/`, `.miniagent/` 제외)이 다르면 `MUTATION_DETECTED`입니다.
9. 집계 규칙: 모두 성공하면 COMPLETE, 일부만 성공하면 PARTIAL, 하나도 성공하지 못하면 FAILED, 부모가 취소하면 CANCELLED입니다.
10. 취소 전파:
    - 부모 `Future.cancel(true)` → 대기 중인 `delegate` 인터럽트 → `handle.cancel()` → 각 child `cancel(true)` 순서로 전파합니다.
    - 해당 child의 승인 요청은 자동 거부하고, 인터럽트 플래그는 복원합니다.
    - staged 결과는 보존하지만 적용하지 않습니다.
11. child 예외는 `CRASHED`로 격리합니다. 부모에서 예외가 나면 finally에서 child를 cancel합니다.
12. 승인 질문:
    - 한 번에 하나만 띄우고, `planId/childId`를 표시하며 requestId로 응답을 짝지어 처리합니다.
    - 늦게 오거나 중복된 응답은 무시합니다.
    - child 기한이 지나면 거부합니다.
13. apply는 부모 세션 락 안에서 실행하고, Phase 3 turnId로 묶습니다. 현재 sha256이 base와 다른 파일이 하나라도 있으면 그 child 전체를 `CONFLICT`로 거부하고 아무것도 쓰지 않습니다. 그래서 이후 사람이 한 편집을 덮어쓰지 않습니다.
14. 재시작 시 미완료 계획은 `CANCELLED(RECOVERED)`가 됩니다. staged 결과는 보존하고, 자동 재개나 자동 적용은 하지 않습니다.

## 이전
- `Agent.run` 본문을 `TurnRunner.run(TurnContext, ToolScope, String)`으로 옮깁니다. `Agent.run`은 전체 범위로 이 메서드를 호출하기만 합니다. `ToolCallingAdvisor` 순서, bean 플러그인 기여, memory·trace는 바뀌지 않습니다.
- child 메모리의 conversation id는 child sessionId입니다. child는 부모 이력 없이 과제만 받고, 부모 JSONL은 바이트 단위로 바뀌지 않습니다.
- 권한 질문은 `ApprovalBroker`를 거치게 바꾸지만, 부모만 실행할 때의 동작은 같습니다.
- 게이트 확인 결과 요청 단위 tool 지정이 병합 방식이면, child는 캐시를 거치지 않는 `AgentChatClientFactory.create(binding, scope)`를 씁니다.

## 의존
- 외부: Phase 1 행 1·2·5, Phase 2 run 기록(같은 ref·bindingId 형식), Phase 3 turnId 묶음
- 내부: 19←18, 20←19, 21←20, 22←20·Phase 3, 23←21·22, F1-F4←23

## 작업
**QA 공통**
- 준비: `cd mini-agent && MVN="./mvnw -q -am -Dsurefire.failIfNoSpecifiedTests=false" EV=../qa-evidence/phase4 && mkdir -p $EV`
- 로그: ✓ 결과는 `> $EV/<행>-ok.log 2>&1`, ✗ 결과는 `> $EV/<행>-ng.log 2>&1`로 남깁니다. 통과 조건은 exit 0입니다.
- ✗는 실패를 단언하는 테스트입니다.
- 착수 전에 `./mvnw -q test > $EV/baseline.log 2>&1`로 새 기준 테스트 수를 기록합니다. 과거의 193개는 기준으로 쓰지 않습니다.
- 비동기 테스트:
  - 실행 전에 `DelegationEvent`와 future를 먼저 구독합니다.
  - fake `ChatModel`의 latch로 단계를 관측합니다.
  - 대기는 `get(5, SECONDS)` 상한을 둡니다.
  - sleep과 문구 고정 없이 상태·코드·sha256만 비교합니다.
- 커밋은 하지 않습니다.

- [ ] 18. 계약·계획 검증·역할 정책
  - 경로: `deleg/{위 타입,DelegationPlanValidator,RolePolicy,DelegationProperties}.java`, `core/ToolContextKeys.java`
  - 실행: deep-high / 근거: `AgentPlugin.java`, `ToolInterceptor.java`, `AgentAutoConfiguration.java:77-134`
  - 수용: 불변식 1을 지키고, 역할 기본값이 적용되며, 권한을 넓히는 설정은 기동 실패입니다.
  - QA:
    - ✓ `$MVN -pl agent-core test -Dtest='DelegationPlanValidatorTest,RolePolicyTest'`
    - ✗ `$MVN -pl agent-core test -Dtest='DelegationPlanValidatorTest#cycleRejectsBeforeAnyStart'`
    - 증거: `agent-core/target/surefire-reports/TEST-*DelegationPlanValidatorTest.xml`
- [ ] 19. child 범위: 세션·바인딩·도구·메모리·MCP
  - 경로: `core/{TurnRunner,Agent,AgentSession}.java`, `deleg/{ChildScopeFactory,ScopedToolInterceptor,McpToolSnapshot}.java`, `core/model/AgentChatClientFactory.java`
  - 실행: deep-high / 근거: `Agent.java:28-120`, `AgentSession.java:15-43`, MCP 플러그인(`rg -l Mcp plugins`로 확정)
  - 수용:
    - 불변식 2·3을 지킵니다.
    - 실행 중 부모 sessionId가 바뀌어도 child에 영향이 없습니다.
    - 바인딩 후에 추가된 MCP 도구는 보이지 않고, 호출하면 거부됩니다.
    - 부모 JSONL은 바뀌지 않습니다.
    - 기존 부모 테스트가 수정 없이 통과합니다.
  - QA:
    - ✓ `$MVN -pl agent-core,plugins/plugin-memory test -Dtest='ChildScopeFactoryTest,ChildMemoryIsolationTest'`
    - ✗ `$MVN -pl agent-core test -Dtest='ChildScopeFactoryTest#mcpToolAddedAfterBindIsDenied'`
    - 증거: `agent-core/target/surefire-reports/TEST-*ChildScopeFactoryTest.xml`
- [ ] 20. 실행기·세션 락·예산·의존·재시도·집계
  - 경로: `deleg/{DefaultDelegationRuntime,SessionRunLock,BudgetLedger,DelegationAggregator}.java`, `core/AgentAutoConfiguration.java`(`ThreadPoolExecutor`+`ArrayBlockingQueue`, 종료 시 `shutdownNow`)
  - 실행: deep-high / 근거: 동기 `Agent.run`(`Agent.java:66-120`), `Usage` 게이트
  - 수용: 불변식 4-9를 지키고, 동시 실행 수가 `max-concurrent`를 넘지 않습니다(latch로 관측).
  - QA:
    - ✓ `$MVN -pl agent-core test -Dtest='DelegationRuntimeTest,BudgetLedgerTest'`
    - ✗ `$MVN -pl agent-core test -Dtest='DelegationRuntimeTest#failedDependencySkipsDependentsPlanPartial'`
    - 증거: `agent-core/target/surefire-reports/TEST-*DelegationRuntimeTest.xml`
- [ ] 21. 승인 상관·취소·crash 전파·복구 저장
  - 경로: `deleg/{ConsoleApprovalBroker,InMemoryDelegationStore,DelegationRecovery}.java`, 권한 interceptor(`rg -l ToolInterceptor agent-core plugins`로 확정), `plugins/plugin-memory/.../{JsonlDelegationStore,MemoryPluginAutoConfiguration}.java`
  - 실행: deep-high / 근거: `AgentConsole.java`, TUI의 busy task·pending question·`Future.cancel(true)`
  - 수용: 불변식 10-12·14를 지키고, 저장 레코드 필드에 키나 client가 없습니다.
  - QA:
    - ✓ `$MVN -pl agent-core,plugins/plugin-memory test -Dtest='ApprovalBrokerTest,DelegationCancelTest,JsonlDelegationStoreTest'`
    - ✗ `$MVN -pl agent-core test -Dtest='DelegationCancelTest#parentCancelDeniesPendingApprovalAndCancelsChildren'`
    - 증거: `agent-core/target/surefire-reports/TEST-*DelegationCancelTest.xml`
- [ ] 22. staged 쓰기·부모 전용 apply
  - 경로: `deleg/{StagedWorkspace,StagedWriteInterceptor,StagedApplyService}.java`, Phase 3 turn 변경 기록부
  - 실행: deep-high / 근거: 검증된 undo 위험(턴 단위로 묶이지만 임시이고, 이후 편집을 덮어쓸 수 있음)
  - 수용:
    - implement의 쓰기는 staged 루트에만 가고, 실제 파일의 sha256은 바뀌지 않습니다.
    - 불변식 13을 지킵니다.
    - 적용된 변경은 부모 turnId의 undo 대상입니다.
    - research·verify가 쓰기를 시도하면 `PERMISSION_DENIED`입니다.
  - QA:
    - ✓ `$MVN -pl agent-core test -Dtest=StagedApplyTest`
    - ✗ `$MVN -pl agent-core test -Dtest='StagedApplyTest#humanEditAfterStageRejectsWholeChild'`
    - 증거: `agent-core/target/surefire-reports/TEST-*StagedApplyTest.xml`
- [ ] 23. `delegate` 도구·명령·TUI/REPL 표시
  - 경로: `deleg/{DelegateTool,ApplyStagedTool,DelegateCommand}.java`, `core/AgentConsole.java`와 그 구현체
  - 내용:
    - `delegate(plan)`은 동기 호출이며 `DelegationResult` JSON을 반환합니다.
    - `apply_staged(planId, childId)`는 부모 범위에서만 쓰고 권한 승인을 거칩니다.
    - 명령: `/delegate run <role> <task>`, `/delegate status [planId]`, `/delegate apply|discard <planId> <childId>`
    - 진행 표시는 `DelegationEvent`로, 승인 라벨은 `planId/childId`로 합니다.
  - 실행: unspecified-high / 근거: `SlashCommand.java`, `TraceEvent.java`, Phase 1 행 5
  - 수용:
    - child 범위에는 두 도구가 없습니다.
    - 부모 세션이 실행 중일 때 들어온 명령은 `SESSION_BUSY`입니다.
    - busy task는 계속 하나입니다.
  - QA:
    - ✓ `$MVN -pl agent-app test -Dtest='DelegateToolTest,DelegateCommandTest'`
    - ✗ `$MVN -pl agent-app test -Dtest='DelegateCommandTest#applyWhileBusyReportsSessionBusy'`
    - 증거: `agent-app/target/surefire-reports/TEST-*DelegateCommandTest.xml`

## 최종 검증
- [ ] F1 통합 (unspecified-high)
  - 내용: fake 모델이 research→implement→verify 계획을 내고, 부모가 apply합니다.
  - ✓ `$MVN -pl agent-app test -Dtest=DelegationIntegrationTest`
  - ✗ `$MVN -pl agent-app test -Dtest='DelegationIntegrationTest#verifyWriteAttemptDeniedPlanPartial'`
  - 증거: `$EV/F1-ok.log`, `$EV/F1-ng.log`
- [ ] F2 회귀 (deep-low)
  - ✓ `./mvnw -q test > $EV/F2-ok.log 2>&1`
  - surefire XML 합계를 `$EV/F2-count.txt`에 기록합니다. 테스트 수는 baseline 이상이고 실패는 0이어야 합니다.
  - ✗ `$MVN -pl agent-app test -Dtest='SlashCommandConflictTest#duplicateNameFailsStartup'`(새 명령 포함)
  - 증거: `$EV/F2-ng.log`
- [ ] F3 복구 (deep-low)
  - ✓ `$MVN -pl plugins/plugin-memory test -Dtest=DelegationRecoveryTest`
  - ✗ `$MVN -pl plugins/plugin-memory test -Dtest='DelegationRecoveryTest#recoveredPlanNeverAutoApplies'`
  - 증거: `$EV/F3-ok.log`, `$EV/F3-ng.log`
- [ ] F4 UI (quick)
  - ✓ `$MVN -pl agent-app test -Dtest='DelegationTuiTest,DelegationReplTest' && env -i PATH="$PATH" HOME="$HOME" JAVA_HOME="$JAVA_HOME" SPRING_PROFILES_ACTIVE=fake bash ./agent.sh "hello" > $EV/F4-launch.log 2>&1`
  - ✗ `$MVN -pl agent-app test -Dtest='DelegationTuiTest#secondApprovalWaitsForFirstAnswer'`
  - 증거: `$EV/F4-ok.log`, `$EV/F4-ng.log`, `$EV/F4-launch.log`

