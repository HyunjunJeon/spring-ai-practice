# Opus 5.5 xhigh: 단계 2 원본 초안

상태: 최종 전체 계획의 부모 검토는 미완료입니다. 모델 claude-opus-5-5 / xhigh / 종료 코드 0.

Phase 2 계획 요청으로 이해하고, Phase 1 계약과 검증된 사실만 바탕으로 정리했습니다. 계약·불변식·이전·의존성과 작업 6-11을 아래에 담았습니다.

# Phase 2: 지속 실행 기록·한도·재개

## 결정
- 새 모듈이나 외부 의존성은 추가하지 않습니다. `agent-core`에 `core/run`·`core/approval` 패키지만 추가하고 JDK NIO와 기존 Jackson만 씁니다.
- 이벤트 로그만 권위 있는 기록이고, `RunRecord`는 로그에서 계산한 투영입니다.
- `RunId`는 채팅 세션과 분리합니다. run은 전용 세션 `run-<runId>`를 쓰므로 콘솔의 가변 세션 ID나 기존 `/resume`의 영향을 받지 않습니다.
- 재개할 때는 저장된 `ModelRef`로 `ModelRegistry.bind`를 다시 호출해 새 `bindingId`와 새 Attempt를 만듭니다.
  - fallback은 없습니다.
  - `ChatClient`, 키, 헤더는 저장하지 않습니다.
- exactly-once는 보장하지 않습니다.
- 재개와 취소는 undo를 호출하지 않습니다. 기존 undo는 임시 기능이라 이후 사람이 수정한 내용을 덮어쓸 수 있기 때문입니다. 롤백은 Phase 3에서 `turnId` 기준으로 다룹니다.

## 구현 게이트
아래 항목은 2.0.1 jar와 소스로 확인하기 전까지 확정하지 않습니다.
- `ToolCallingAdvisor` 안쪽 advisor가 tool round마다 호출되는지
- `ChatResponse`의 usage API와 값이 없을 때의 표현
- Boot 4.1.1이 쓰는 Jackson 2/3 패키지와 `java.time` 직렬화
- `ToolInterceptor` 훅의 시그니처와 실행 순서
- 권한 프롬프트 위치: `rg -l "implements ToolInterceptor" agent-core plugins`로 확인
- `AgentConsole` 구현체와 TUI 질문 슬롯
- 기존 `/resume` 클래스
- macOS에서 디렉터리 fsync 가능 여부

## 계약
```java
record RunId(String value) {} // "r-yyyyMMddHHmmss-8hex"
record Budget(Duration wall, Long maxTokens, Integer maxToolCalls) {} // null=무제한
record RunSpec(int v, RunId id, String sessionId, String prompt, ModelRef model, String wireFamily,
    String cwd, Budget budget, Map<String,String> fingerprints, Instant createdAt) {}
enum RunState { CREATED, RUNNING, WAITING_APPROVAL, INTERRUPTED, COMPLETED, FAILED, CANCELLED }
record Attempt(int n, String turnId, UUID bindingId, Instant start, Instant end, String endReason) {}
record RunEvent(int v, long seq, RunId run, int attempt, Instant at, String type, Map<String,Object> data) {}
record Usage(long in, long out, boolean unknown, long overshoot) {}
record RunRecord(RunSpec spec, RunState state, String reason, List<Attempt> attempts,
    Usage usage, int toolCalls, Duration active, long lastSeq, UUID pendingApproval) {}
interface RunStore { RunWriter create(RunSpec s); RunWriter open(RunId id); RunRecord read(RunId id); List<RunRecord> list(); }
record ApprovalRequest(UUID approvalId, RunId run, String toolCallKey, String tool, String argsSha256) {} // run null=일반 턴
enum ApprovalDecision { ALLOW, DENY, DEFERRED }
interface ApprovalBroker { ApprovalDecision request(ApprovalRequest r) throws InterruptedException; boolean answer(UUID id, boolean allow); }
record RunStateChangedEvent(RunId run, RunState from, RunState to, String reason) implements TraceEvent {}
```
- 이벤트 type: `RUN_CREATED`, `ATTEMPT_STARTED`, `MODEL_CALL`, `USAGE`, `TOOL_CALL_STARTED`, `TOOL_CALL_FINISHED`, `APPROVAL_REQUESTED`, `APPROVAL_RESOLVED`, `DRIFT_DETECTED`, `TAIL_REPAIRED`, `STATE_CHANGED`, `ATTEMPT_ENDED`
- 키: `ToolContextKeys.RUN_ID`, `toolCallKey=attempt:seq`
- fingerprints:
  - provider 설정 해시: 키를 빼고 계산하며, `api-key-env`는 이름만 넣습니다.
  - 도구 이름과 스키마의 sha256
  - advisor 체인
  - 도구가 건드린 파일의 sha256

## 저장·잠금
- 파일 구성: `<dir>/<runId>/spec.json`, `events.jsonl`, `writer.lock`, `owner.json`
  - `spec.json`은 temp 파일에 쓰고 fsync한 뒤 `ATOMIC_MOVE`로 옮기며, 이후 바꾸지 않습니다.
  - `owner.json`은 진단용입니다.
- 각 행은 `crc32c<TAB>json\n` 형식이고, append할 때마다 `force(true)`를 호출합니다.
- 쓰기는 전용 writer 스레드에서만 합니다. 그래야 `Future.cancel(true)`의 인터럽트가 채널을 닫지 않습니다(`ClosedByInterruptException` 방지).
- 파일을 열 때의 처리
  - 마지막 행에 개행이 없거나 CRC가 맞지 않으면 마지막 유효 지점까지 잘라내고 `TAIL_REPAIRED`를 남깁니다.
  - 중간 행이 손상됐으면 `CORRUPT`로 쓰기를 거부하고 파일은 그대로 둡니다.
  - 알 수 없는 `v`는 `SCHEMA_UNSUPPORTED`로 처리하고 읽기만 허용합니다.
- writer는 `FileChannel.tryLock`으로 하나만 허용합니다.
  - 잠금에 실패하거나 같은 JVM에서 중복으로 열면, 기다리지 않고 바로 `RUN_LOCKED`를 반환합니다.
  - 프로세스가 죽으면 OS가 잠금을 해제합니다.
  - 잠금이 없는 RUNNING은 목록에서 INTERRUPTED(`PROCESS_LOST`)로 보여 주고, 다음 writer가 이 전이를 기록합니다.

## 생명주기·한도
- 상태 전이
  - CREATED→RUNNING
  - RUNNING→WAITING_APPROVAL|INTERRUPTED|COMPLETED|FAILED|CANCELLED
  - WAITING_APPROVAL→RUNNING|INTERRUPTED|CANCELLED
  - INTERRUPTED→RUNNING|CANCELLED
  - 종료 상태에 도달하면 이후 append는 거부합니다.
- INTERRUPTED 사유: `USER_INTERRUPT`, `PROCESS_LOST`, `BUDGET_TIME`, `BUDGET_TOKENS`, `BUDGET_TOOLS`, `USAGE_UNKNOWN`
- 재개가 거부돼도(모델 `UnavailableCode`, 드리프트 등) 상태는 바뀌지 않고 거부 코드만 반환합니다. 이전 attempt의 도구 결과는 `RunResumeContributor`가 구조화해서 넣어 줍니다.
- wall 한도는 attempt별 활성 시간을 누적해서 셉니다.
- 모델 호출과 도구 실행은 시작하기 전에 한도를 검사합니다.
  - 한도를 넘은 뒤에는 새로운 요청을 시작하지 않습니다.
  - 이미 진행 중이던 응답이 넘긴 양은 `overshoot`로 기록합니다.
- usage가 오지 않으면 `unknown=true`로 기록합니다. 토큰 상한이 설정돼 있으면 다음 호출 전에 INTERRUPTED(`USAGE_UNKNOWN`)로 멈춥니다.
- 상한은 재개 플래그로만 올릴 수 있고, 올린 내용은 이벤트로 기록합니다.

## 승인·부수효과
- 처리 순서: 한도 검사 → 승인 → `TOOL_CALL_STARTED` fsync → 실행 → `TOOL_CALL_FINISHED`
- broker는 approvalId로 요청과 응답을 짝지어, 입력 콘솔 하나를 REPL과 TUI가 함께 쓰게 합니다.
  - 화면에는 질문을 하나만 띄우고 나머지는 FIFO로 대기시킵니다.
  - ID가 맞지 않거나 이미 처리된 응답은 거부합니다.
  - 비대화형이거나 인터럽트되면 결과는 `DEFERRED`이고 상태는 WAITING_APPROVAL이 됩니다. 재개할 때 같은 ID로 다시 묻습니다.
  - 자동 승인은 없습니다.
- `STARTED`만 있고 `FINISHED`가 없는 호출은 UNKNOWN으로 봅니다.
  - 읽기 전용 도구는 다시 실행합니다.
  - 그 외 도구는 `--retry-unknown` 또는 `--skip-unknown` 없이는 `UNKNOWN_SIDE_EFFECT`로 거부합니다.
- 하드 드리프트는 `--accept-drift` 없이는 거부합니다.
  - wireFamily, cwd, 스키마 변경
  - 사용했던 도구의 제거나 스키마 변경
  - 건드린 파일의 해시 변경
- 동적 MCP로 도구가 추가된 경우는 기록만 하고 진행합니다.

## 실패 불변식
1. seq는 끊기지 않으며, 중간 손상이 있으면 쓰기를 거부합니다.
2. 부수효과는 `TOOL_CALL_STARTED` fsync가 끝난 뒤에만 일어납니다.
3. run 하나당 writer는 하나입니다.
4. 종료 상태 이후에는 아무것도 append하지 않습니다.
5. 재개는 같은 `ModelRef`로만 하며 fallback은 없습니다.
6. 키, client, 헤더는 저장하지 않습니다.
7. 한도를 넘은 뒤에는 새 요청을 시작하지 않습니다.
8. 응답은 ID가 일치할 때만 처리합니다.
9. UNKNOWN 호출은 명시적으로 지시하지 않으면 다시 실행하지 않습니다.
10. 채팅 세션 ID나 `/resume`과 독립적으로 동작합니다.

## 소유·기본값
`RunProperties`는 agent-core가 소유하며, 아래 기본값은 바꿀 수 있습니다.
- `miniagent.runs.dir=${user.home}/.mini-agent/runs`
- `budget.wall=30m`
- `budget.tokens` 미설정
- `budget.tool-calls=50`
- 분류가 안 된 도구는 변경 도구로 취급합니다.
- 모델은 run 시작 시점의 `ModelSelection`을 씁니다.
- TUI가 다른 작업으로 바쁠 때 `/run start`는 대기열에 넣지 않고 `BUSY`를 반환합니다.

## 이전·의존
- 기존 데이터가 없으므로 세션 JSONL은 그대로 둡니다. `application.yml`에는 `miniagent.runs.*`만 추가합니다.
- 권한 interceptor가 콘솔에서 직접 입력을 받던 부분은 broker를 거치도록 바꿉니다.
- 선행 조건: Phase 1의 작업 1·2·3·5
- 이 결과를 쓰는 곳: Phase 3은 `turnId`와 도구 이벤트를, Phase 4는 `RunStore`를 사용합니다.

## 작업
- 공통 준비: Phase 1의 `$MVN`을 그대로 쓰고, 먼저 `set -o pipefail; mkdir -p .qa/phase2`를 실행합니다.
- ✓와 ✗ 모두 종료 코드 0이 기대값입니다. ✗는 실패 경로를 단언하는 테스트입니다.
- 비동기 테스트
  - 행동하기 전에 리스너나 `CompletableFuture`를 먼저 등록하고, `get(5, SECONDS)`로 기다립니다.
  - 시간은 주입한 `Clock`으로 제어합니다.
  - sleep이나 실제 provider 호출은 쓰지 않습니다.
  - 출력 문구는 고정하지 않고 상태, 코드, ID만 비교합니다.
- 커밋하지 않습니다. 기존 193개 테스트 수는 과거 기록일 뿐이며, 이번에 다시 확인한 결과가 아닙니다.

- [ ] 6. 스키마·저장소 — deep-high
  - 파일: `core/run/{RunId,Budget,RunSpec,RunState,Attempt,RunEvent,Usage,RunRecord,RunProjector,RunStore,RunWriter,FileRunStore,RunStoreException}.java`
  - 근거: Phase 1 `ModelRef`, 위 저장 절 / 의존: P1-1
  - 수용 기준
    - 직렬화했다가 다시 읽으면 동일합니다.
    - 꼬리 복구 뒤에도 seq가 연속입니다.
    - 저장된 바이트에 테스트 키가 없습니다.
  - QA
    - ✓ `$MVN -pl agent-core test -Dtest=FileRunStoreTest | tee .qa/phase2/6-ok.log`
    - ✗ `$MVN -pl agent-core test -Dtest='FileRunStoreTest#secondWriterRunLocked+midCorruptionRefused' | tee .qa/phase2/6-fail.log`
  - 증거: `agent-core/target/surefire-reports/TEST-com.example.miniagent.core.run.FileRunStoreTest.xml`
- [ ] 7. 실행기·재개 — deep-high
  - 파일: `core/run/{RunExecutor,RunContext,RunResumeContributor}.java`, `core/Agent.java`(`TurnContext`를 명시적으로 받는 오버로드), `core/ToolContextKeys.java`
  - 근거: `Agent.java:28-120`, `AgentSession.java:15-43` / 의존: 6, P1-2
  - 수용 기준
    - 재개하면 ref는 같고 `bindingId`는 다르며 attempt가 1 증가합니다.
    - 실행 중 콘솔 세션을 바꿔도 run에 영향이 없습니다.
  - QA
    - ✓ `$MVN -pl agent-core test -Dtest=RunExecutorTest | tee .qa/phase2/7-ok.log`
    - ✗ `$MVN -pl agent-core test -Dtest='RunExecutorTest#resumeUnavailableModelKeepsState+terminalRunRejectsResume' | tee .qa/phase2/7-fail.log`
  - 증거: `agent-core/target/surefire-reports/TEST-com.example.miniagent.core.run.RunExecutorTest.xml`
- [ ] 8. 한도·usage — deep-low
  - 파일: `core/run/{RunBudgetAdvisor,RunBudgetInterceptor,UsageRecorder}.java`, `core/AgentAutoConfiguration.java`
  - 근거: `AgentAutoConfiguration.java:77-134`, `ToolInterceptor.java` / 의존: 7
  - 수용 기준
    - 상한 N이면 N+1번째 도구는 실행되지 않습니다.
    - wall 한도를 넘으면 `BUDGET_TIME`이 됩니다.
    - overshoot가 기록됩니다.
  - QA
    - ✓ `$MVN -pl agent-core test -Dtest=RunBudgetTest | tee .qa/phase2/8-ok.log`
    - ✗ `$MVN -pl agent-core test -Dtest='RunBudgetTest#unknownUsageWithTokenCapInterrupts+noToolStartsAfterCap' | tee .qa/phase2/8-fail.log`
  - 증거: `agent-core/target/surefire-reports/TEST-com.example.miniagent.core.run.RunBudgetTest.xml`
- [ ] 9. ApprovalBroker — deep-high
  - 파일: `core/approval/{ApprovalBroker,ApprovalRequest,ApprovalDecision,ConsoleApprovalBroker}.java`, 권한 interceptor(게이트의 `rg`로 확정), REPL·TUI의 `AgentConsole` 구현체
  - 근거: `AgentConsole.java`, 단일 busy 작업과 질문 슬롯 / 의존: 6, 7
  - 수용 기준
    - 동시에 두 요청이 와도 하나씩 순서대로 표시됩니다.
    - 인터럽트되면 WAITING_APPROVAL이 되고, 재개 때 같은 ID로 다시 묻습니다.
  - QA
    - ✓ `$MVN -pl agent-app test -Dtest='ApprovalBrokerTest,ConsoleApprovalRoutingTest' | tee .qa/phase2/9-ok.log`
    - ✗ `$MVN -pl agent-app test -Dtest='ApprovalBrokerTest#staleAnswerRejected+unattendedDefers' | tee .qa/phase2/9-fail.log`
  - 증거: `agent-core/target/surefire-reports/TEST-com.example.miniagent.core.approval.ApprovalBrokerTest.xml`, `agent-app/target/surefire-reports/TEST-com.example.miniagent.app.ConsoleApprovalRoutingTest.xml`
- [ ] 10. 부수효과·드리프트 — deep-high
  - 파일: `core/run/{ToolJournalInterceptor,SideEffectClass,DriftGate,WorkspaceFingerprint}.java`
  - 근거: `ToolInterceptor.java`, `AgentAutoConfiguration.java:105-120`, undo 관련 확인 사실 / 의존: 7, 9
  - 수용 기준
    - 도구 본문이 실행되는 시점에 이미 `STARTED`가 디스크에 있습니다.
    - `--accept-drift`를 썼다는 사실이 이벤트로 남습니다.
  - QA
    - ✓ `$MVN -pl agent-core test -Dtest=SafeResumeTest | tee .qa/phase2/10-ok.log`
    - ✗ `$MVN -pl agent-core test -Dtest='SafeResumeTest#unknownMutatingRefusedWithoutFlag+humanEditBlocksResume' | tee .qa/phase2/10-fail.log`
  - 증거: `agent-core/target/surefire-reports/TEST-com.example.miniagent.core.run.SafeResumeTest.xml`
- [ ] 11. `/runs`·`/run` 명령 — unspecified-high
  - 파일: `core/run/{RunsCommand,RunCommand,RunProperties}.java`, `core/AgentAutoConfiguration.java`, `agent-app/src/main/resources/application.yml`, 상태줄을 그리는 `AgentConsole` 구현체
  - `/run` 하위 명령: `start|show|resume|cancel`
  - 근거: `SlashCommand.java`, P1-5 / 의존: 8, 9, 10
  - 수용 기준
    - fake 프로필 SpringBootTest에서 시작 → 인터럽트 → 재개 → COMPLETED까지 진행됩니다.
    - 기존 `/resume`은 그대로입니다.
  - QA
    - ✓ `$MVN -pl agent-app test -Dtest='RunCommandTest,SlashCommandConflictTest' | tee .qa/phase2/11-ok.log && ./mvnw -q test | tee .qa/phase2/11-full.log`
    - ✗ `$MVN -pl agent-app test -Dtest='RunCommandTest#resumeCompletedRunRejected+sessionResumeUntouched' | tee .qa/phase2/11-fail.log`
  - 증거: `agent-app/target/surefire-reports/TEST-com.example.miniagent.app.RunCommandTest.xml`, 전체 실행 시 모든 모듈의 `target/surefire-reports/`

