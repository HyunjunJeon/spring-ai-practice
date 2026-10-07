# Spring AI Agent 발전 구현 계획

상태: 2026-10-06 사용자 구현 승인 후 실행 중. 체크박스는 검증된 구현만 완료 표시한다. 권장 설정과 예산을 채택하고 managed run은 staged 기본, 일반 턴은 기존 즉시 모드를 보존한다. 새 diff 의존성·실제 유료 애플리케이션 호출·커밋·배포는 수행하지 않는다. 실행 근거는 `.omo/ulw-execute/ledger.jsonl`과 `.omo/evidence/agent-evolution/`에 기록한다.

작성 근거: 사용자가 지정한 `anthropic-subscription/claude-opus-5-5`, `thinkingLevel=xhigh` 작업자의 네 단계 원본과 부모의 소스 대조 검토. 각 단계는 실제 `stopReason=stop`, 종료 코드 0으로 수신했다. 원본은 `.omo/drafts/agent-evolution-phase{1,2,3,4}-opus.md`에 보존한다. 아래 계약과 작업표는 원본 사이의 충돌을 정리한 최종안이며 원본보다 우선한다.

## 목표와 범위

1. 같은 실행에서 제공자·모델을 선택하고, 한 턴의 도구 왕복에는 같은 바인딩을 유지한다.
2. 대화와 별도의 실행 기록·예산·승인·복구를 제공한다.
3. 변경을 스테이징하고 diff 승인, 적용, 실제 검증, 영속 체크포인트 복구를 연결한다.
4. 모델·도구·권한·메모리가 분리된 자식 작업을 제한된 병렬도로 실행하고 부모가 증거를 확인해 결과를 합친다.

Java 21, Spring Boot 4.1.1, Spring AI GA 2.0.1과 공식 `ChatModel`/`ChatClient`/`ToolCallingAdvisor`를 유지한다. Spring bean 플러그인, 동적 MCP 콜백, 권한 interceptor, 메모리 요약·예산, TraceEvent, TUI/REPL을 확장한다. 앱 런타임에 senpi/Bun/Node, 외부 워크플로 엔진, 새로운 Maven 모듈을 추가하지 않는다. 위임은 Java 런타임에서 수행한다.

## 현재 상태와 소스 근거

이하 경로는 `mini-agent/` 기준이다.

- `core/` = `agent-core/src/main/java/com/example/miniagent/core/`
- `app/` = `agent-app/src/main/java/com/example/miniagent/app/`
- `memory/` = `plugins/plugin-memory/src/main/java/com/example/miniagent/plugin/memory/`
- `fs/` = `plugins/plugin-fs/src/main/java/com/example/miniagent/plugin/fs/`
- `bash/` = `plugins/plugin-bash/src/main/java/com/example/miniagent/plugin/bash/`
- `permission/` = `plugins/plugin-permission/src/main/java/com/example/miniagent/plugin/permission/`
- `tui/` = `plugins/plugin-tui/src/main/java/com/example/miniagent/plugin/tui/`
- `trace/` = `plugins/plugin-trace/src/main/java/com/example/miniagent/plugin/trace/`
- `deleg/` = 신규 `core/delegation/`
- `codex/` = `plugins/plugin-codex-model/src/main/java/com/example/miniagent/plugin/codex/`
- `claude/` = `plugins/plugin-claude-model/src/main/java/com/example/miniagent/plugin/claude/`

검토한 근거:

- `core/Agent.java:28-101`: 단일 final ChatClient, 동기 run, 세션·cwd·UUID 턴 ID 전달 및 합산 usage. `105-120`: 동적 ToolCallbackProvider 확장.
- `core/AgentAutoConfiguration.java:77-134`: 순서화된 도구·interceptor·prompt contributor·advisor 조립과 단일 builder 의존.
- `core/AgentSession.java:15-43`: mutable 세션 ID. 모델 선택이나 실행 상태 저장은 없다.
- `core/AgentPlugin.java:3-22`: 설명자이며, 기능은 Spring AI 확장점 bean으로 기여한다.
- `core/TraceEvent.java:17-61`: sealed interface와 내부 record들. 외부 패키지에서 임의 구현체를 추가하지 않는다.
- `core/AgentConsole.java:12-19,47-63`: 동기 단일 입력 스트림과 비대화형 표시. `tui/TuiFrontEnd.java:254-312`: 한 busy 작업·pendingQuestion, Future 취소; `335-347`: 모델/세션 표시.
- `codex/CodexModelAutoConfiguration.java:25-112`, `claude/ClaudeModelAutoConfiguration.java:13-60`: 단일 backend 조건, 모델 bean과 로그인 명령. Codex의 기존 모델/effort 변경도 새 선택 경로에 연결해야 한다.
- `fs/FileCheckpoints.java:21-44`: 턴별 변경 그룹, 임시 백업, 같은 프로세스에서만 undo 가능, 이후 외부 편집을 덮어쓸 수 있음.
- `memory/MemoryPluginAutoConfiguration.java`, `JsonlChatMemory.java`, `JsonlChatMemoryRepository.java`: 요약·컨텍스트 예산과 JSONL 문자열 metadata 보존. provenance 없는 예전 메시지의 private 상태는 재생 시 보수적으로 처리한다.
- `agent-app/src/main/resources/application.yml`: 기존 도구 호출 한도, shell timeout, 권한, 메모리, trace. 이를 없는 기능으로 다시 만들지 않는다.

현재 Git은 `main`의 첫 커밋 전 저장소이며 프로젝트가 미추적 상태다. 커밋된 HEAD나 git diff를 필수 조건으로 삼지 않는다. Git이 없는 작업공간도 같은 계약으로 처리한다. 과거 193개 통과와 GLM 실제 응답은 배경 증거이고, 이 계획 수립에서 새 테스트를 실행한 결과가 아니다.

## 구현 착수 게이트

작업 1에서 고정된 2.0.1 jar/소스와 기존 테스트로 `ChatClient.builder(ChatModel)`, 생성 옵션·콜백 범위, `spring.ai.chat.client.enabled`, `spring.ai.model.chat`, OpenAI 공식 클라이언트 생성 API, 내부/외부 advisor 순서와 metadata 복사 API를 확인한다. 메서드 서명을 추측해서 구현하지 않는다. Java LSP 진단 뒤 관련 Maven 검사를 실행한다.

변경 전 관련 테스트를 한 번 실행해 기준을 기록한다. 새 기능은 fake ChatModel 또는 포트 0의 로컬 HTTP 서버로 검증한다. 기존 테스트 중 동작 변경으로 낡은 것만 갱신하고, 기존 실패를 삭제·약화하지 않는다. 실제 계정 호출은 별도 승인 전 하지 않는다.

## 공통 모델·선택 계약

- `ModelRef(provider, model)`, `ModelEntry(ref, wireFamily, unavailableCode)`, `ModelProvider`, `ProviderAuth`, `ModelRegistry`, `AgentChatClientFactory`, `SessionModelStore`를 `core/model/`에 둔다.
- 모델과 client는 제공자 내부 factory가 만들고 registry가 소유한다. 공식 단일 ChatClient 자동구성은 검증된 설정으로 끄며 앱은 기여자별 client를 명시적으로 조립한다.
- 기존 `ChatModelSummarizer`의 단일 ChatModel 주입도 registry/current binding 경로로 이전한다. compaction이 다른 제공자를 호출하거나 다중/없는 ChatModel bean으로 기동이 실패하지 않도록 작업 3·8에서 함께 검증한다.
- OpenAI와 Zai는 같은 구현 타입이어도 별도의 키·URL·옵션·HTTP client를 사용한다. 인증이 없으면 목록에 `AUTH_MISSING`으로 남고 앱 기동을 실패시키지 않는다.
- `ModelBinding(bindingId, ModelRef, optionsFingerprint, wireFamily, client)`은 턴 시작에 만든 일시적 객체다. client template 캐시는 같은 ref/옵션/도구 범위에 한해 사용한다. 각 턴·attempt에는 새 bindingId를 주고 client·키·헤더는 저장하지 않는다.
- `TurnContext(sessionId, turnId, cwd, ModelRef, runId?, attemptId?)`를 요청/advisor/ToolContext에 전달한다. mutable 전역 세션이나 모델을 라운드 중 다시 읽지 않는다. 자동 mid-tool fallback은 없다.
- `SessionModelStore`의 영속 구현은 `core/model/`에서 독립적으로 제공해 memory plugin을 꺼도 선택 저장이 유지된다. 대화 JSONL과 분리된 versioned sidecar를 쓰고 원본 대화는 재작성하지 않는다.
- provenance는 `agent.provider`, `agent.model`로 명시한다. provider는 자신이 소유하는 reasoning/signature key를 선언한다. 다른 ref 또는 legacy unknown의 private metadata는 요청 사본에서 제거하고 공개 텍스트·유효한 tool 쌍을 보존한다. 불완전 tool 쌍은 검증된 resume 경계에서 다룬다.
- 새 모델 선택은 다음 턴부터 적용한다. TUI의 기존 busy 거부를 유지한다. `/codex model`·`effort`도 같은 선택/옵션 경로를 사용해 캐시된 현재 턴 모델을 변경하지 않는다.
- `/login [provider]`, `/logout [provider]`의 인자 생략은 선택 provider를 사용한다. Codex의 `/login device`는 선택 provider가 codex일 때 보존한다. `/codex`, `/claude`의 상태·모델 명령과 기존 AGENT_PROFILE·ZAI 환경변수는 문서화한 변환으로 유지한다.

## 실행 기록·승인·예산 계약

- `core/run/`: RunSpec, RunRecord, RunId, Attempt, RunEvent, RunState, RunStore/FileRunStore, RunExecutor, RunContext. 대화 ID, run ID, attempt ID는 구분한다. run 전용 conversation ID와 원래 부모 conversation ID를 함께 보존한다.
- RunSpec은 ref·cwd·예산·도구/옵션 fingerprint·시각·schemaVersion만 고정한다. credentials/client나 전체 환경변수를 저장하지 않는다.
- 상태: `CREATED -> RUNNING -> WAITING_APPROVAL|INTERRUPTED|COMPLETED|FAILED|CANCELLED`. WAITING_APPROVAL은 재승인 후 RUNNING 또는 INTERRUPTED/CANCELLED, INTERRUPTED는 명시 재개 또는 CANCELLED. terminal 뒤 append는 금지한다. usage·ATTEMPT_ENDED를 먼저 기록하고 terminal 상태 이벤트를 마지막으로 기록한다.
- 이벤트 로그가 권위이고 record는 투영이다. `<agent.home>/runs/<runId>/`의 spec, sequence/CRC 이벤트 로그, writer lock을 사용한다. 전용 writer와 force, temp/atomic move를 적용한다. 프로세스·JVM 당 run writer는 하나다.
- 알려진 schema의 개행 없는 마지막 불완전 행만 안전 복구한다. 개행이 있는 CRC 오류나 중간 손상은 보존하고 CORRUPT로 거부한다. 미래 schema는 opaque 내보내기/조회만 허용한다. 쓰기 잠금이 사라진 활성 run은 PROCESS_LOST/INTERRUPTED로 투영한다.
- `core/approval/ApprovalBroker` 하나를 공용으로 쓴다. requestId, run/attempt, plan/child 선택 필드, toolCallKey, args/diff digest를 묶고 화면에는 FIFO로 한 질문만 표시한다. 늦은·중복·다른 ID 응답은 거부한다.
- 비대화형 미응답은 DEFERRED/WAITING_APPROVAL이다. 사용자 cancel은 승인 요청을 폐기하고 CANCELLED로 간다. 프로세스 소실은 INTERRUPTED다. 인터럽트만 보고 이 셋을 혼동하지 않는다.
- 실행 순서: 역할/경로 허용 검사 -> 한도 검사 -> 기존 권한/승인 -> STARTED fsync -> 도구 실행 -> FINISHED. 분류가 없는 도구는 mutating으로 취급한다.
- STARTED만 있는 효과는 UNKNOWN이다. 검증된 read-only 재실행 외에는 retry/skip의 명시적 지시를 요구한다. 재개는 확인된 결과와 불확실성을 구분해 새 attempt에 전달하며 가짜 성공 결과나 미완성 tool 쌍을 재전송하지 않는다.
- 같은 ModelRef를 다시 바인딩한다. 다른 모델·wireFamily·cwd는 무조건 accept-drift로 우회하지 않는다. 명시된 안전 변환 또는 새 run을 요구한다. 허용된 도구/파일 fingerprint drift의 승인은 기록한다.
- 단일 `RunBudget(wall, tokenCap?, toolCallCap)` 계약을 부모와 자식이 공유한다. 제안 기본값은 활성 wall 30분, tokenCap 미설정, tool 50회다. 자식은 남은 한도 안에서 예약하며 한도를 확대하지 않는다.
- 모델 round와 compaction 요약 호출까지 집계한다. loop 합계와 round usage를 중복 계산하지 않는다. usage unknown은 0과 다르다. cap이 있으면 unknown 이후 새 호출을 멈춘다. 이미 시작한 호출의 overshoot와 불확실성은 별도로 보고하며 정확한 청구액 상한을 보장하지 않는다.
- 승인 대기와 queue 시간을 wall에 포함할지 명시한다. 추천은 active 시간에만 포함하고 전체 deadline은 별도 설정으로 둔다. clock/latch를 주입해 결정적으로 검증한다.
- `/runs`, `/run start|show|resume|cancel`을 추가하되 기존 대화 `/resume`은 보존한다. 별도의 DelegationStore/ApprovalBroker/Budget를 중복 생성하지 않는다.

## 변경·검증·복구 계약

- `core/change/`: ChangeOwner, Fingerprint, Diff, ChangeSet, Approval, Checkpoint, VerificationEvidence와 store/engine.
- owner는 session/run/attempt/turn ID를 고정한다. 일반 턴도 내부 execution identity를 만들며 저장 매체에서 ID 타입을 혼용하지 않는다.
- 상태: PENDING_APPROVAL, APPROVED, REJECTED, SUPERSEDED, STALE, APPLYING, APPLIED, PARTIALLY_APPLIED, RECOVERY_REQUIRED, UNDONE, PARTIALLY_UNDONE. 새 attempt의 이전 미적용 변경은 SUPERSEDED이고 적용 이력·blob은 보존한다.
- `agent.home/changes/<workspaceHash>/`에 manifest/journal/content-addressed backup/staging/evidence를 둔다. workspace 안에 상태 디렉터리를 만들고 다시 복사하지 않는다. atomic move/force와 writer lock을 사용하며 지원하지 않는 파일시스템은 typed error로 멈춘다.
- baseline/proposed fingerprint는 상대 경로, 종류, SHA-256, 크기, mode, link text다. 승인 digest는 정렬된 경로·before/after·diff를 묶는다. byte/mode 또는 baseline 변경 시 승인과 증거는 stale이다.
- 새 코드 변경은 staged view를 대상으로 하고 actual workspace는 승인된 apply만 변경한다. 기존 tool 이름/인자는 유지하며 managed/staged 모드의 기본 전환과 legacy 즉시 모드 보존은 아래 승인 항목을 따른다. 기존 체크포인트 클래스의 계약을 영속 backend로 확장하며 테스트를 임의 삭제하지 않는다.
- git diff/HEAD 대신 파일 baseline을 사용한다. `.git`, `target`, `build`, `node_modules`, 실행 상태, auth 및 `.env*`·key/certificate 파일은 source snapshot에서 제외한다. workspace skills는 필요한 읽기 전용 부분만 복사한다. 제외된 변경은 typed error로 거부한다.
- parent realpath와 symlink 경계를 검증하고 escaping path/link 쓰기는 거부한다. secret 파일을 제외한다는 이유만으로 임의 shell 출력에 secret이 없다고 주장하지 않는다. 프로세스에는 최소 환경을 주며 알려진 인증 값은 전달하지 않는다.
- shell argv 검사만으로 격리를 주장하지 않는다. managed mutating shell은 기존 macOS BashSandbox를 stage/temp 및 명시적 cache 쓰기 범위에 적용한다. 지원·검증된 sandbox가 없으면 mutating shell은 거부하거나 별도 승인된 trusted-only 모드를 요구한다. 모르는 변이 MCP는 managed write 역할에서 거부한다.
- diff renderer의 제안 기본값은 새 외부 의존 없이 bounded JDK 구현이다. 큰/바이너리/계산 한도 초과 입력은 명확한 before/after 증거와 거부 사유를 내고 생략된 내용에 자동 승인하지 않는다. java-diff-utils 추가는 별도 의존성 승인 전 하지 않는다.
- apply/undo/recovery는 agent workspace lease 안에서 전체 baseline을 확인하고 파일별 PREPARED backup을 fsync한 뒤 쓰며 journal로 부분 상태를 추적한다. 다중 파일 atomic transaction이나 비협조적 외부 editor에 대한 atomic compare-and-swap을 주장하지 않는다.
- 알려진 외부 편집은 STALE/CONFLICT로 보존한다. 외부 writer를 배제할 수 없다면 `/apply`는 EXCLUSIVE_OWNERSHIP_REQUIRED로 멈추고 patch를 제공한다. 콘솔 사용자의 exclusive 확인만 가능하며 모델/tool이 대신 확인하지 않는다.
- 취소 시 파일별 원자 단계/기록 경계를 정리한 뒤 PARTIALLY_APPLIED를 보존한다. 재기동은 before/after/다른 내용으로 분류하며 충돌을 자동 덮어쓰지 않는다. undo는 현재 after와 같은 파일만 되돌린다.
- `/changes`, `/apply`, `/verify`, `/undo`는 공용 approval broker와 busy 정책을 사용한다. 비대화형 apply는 PENDING_APPROVAL로 남는다.
- 검증은 정확한 argv/cwd/exit 또는 cancelled/log digest/source tree digest를 기록한다. 같은 source revision·설정에만 유효하다. generated/cache 쓰기는 검증 작업공간에서 허용하고 source 변이는 실패로 구분한다. 실제 workspace를 검증하면 별도 승인을 요구한다. 성공은 모델의 주장 대신 exit/evidence로 판단한다.

## 위임·협업 계약

- `core/delegation/`: ChildSpec, DelegationPlan, ChildResult, DelegationResult, RolePolicy, scope factory, scheduler, aggregator와 delegate command/tool.
- 부모의 delegate tool은 결과 future를 기다리는 동기 호출이고 child들만 제한 병렬 실행한다. 제안은 동시 2, queue 8, 깊이 1, 최대 attempt 2다. parent UI의 한 busy task를 유지한다.
- child는 명시한 ref 또는 부모 TurnContext ref, 별도 안전한 UUID conversation ID, 별도 run/attempt와 tool scope를 쓴다. mutable 선택·부모 전체 이력·credential은 복사하지 않는다.
- Research는 read-only, implement는 staged writes, verify는 source-no-mutation이다. verify는 clone 안의 generated/cache output은 허용한다. 역할/경로 검사로 먼저 거부한 뒤 기존 permission을 적용한다. 허용 범위는 부모의 부분집합이다.
- 동적 MCP는 explicit 이름 allowlist와 시작 snapshot으로 제한하고 새/removed/schema-drift tool은 해당 attempt에서 바꾸지 않는다. 해석 불가 외부 효과는 기본 거부한다.
- 같은 session/run은 한 writer, 부모는 자식별 write ownership을 관리한다. 자식은 actual workspace에 적용하지 못한다. parent-only apply는 단계 3의 same digest/approval/exclusive ownership 계약을 그대로 사용한다.
- 상태는 QUEUED/RUNNING/SUCCEEDED/FAILED/SKIPPED/CANCELLED/TIMED_OUT이다. 구조적인 id/edge/cycle/depth/예약 초과는 시작 전 거부한다. 런타임에 모델 사용 불가는 해당 child만 FAILED, dependent는 SKIPPED다. 큐 포화는 silent dropping 대신 명시한 reject/defer 정책을 따른다.
- 자식 예산 예약·집계는 단계 2 RunBudget을 재사용한다. 부모 한도가 우선하며 사용량 unknown/overshoot를 숨기지 않는다. transient model 오류의 재시도도 UNKNOWN mutating 외부 효과를 자동 재실행하지 않는다.
- 부모 cancel은 대기/실행/승인까지 전파하고 종료·정리 결과를 집계한다. staged 결과는 보존하되 적용하지 않는다. 재시작 시 활성 child는 INTERRUPTED로 기록하며 자동 재개/적용은 없다. 명시한 부모 resume은 같은 안전 복구 계약으로 새 child attempt를 만든다.
- 증거는 모델 응답, 실제 tool result, 읽은 source, 검증 로그를 typed evidence로 구별한다. 모델 서술만으로 코드 변경/테스트 통과를 증명하지 않는다. 부모가 digest·현재 revision·exit를 대조한다.
- 전부 검증 성공은 COMPLETE, 일부는 PARTIAL, 없으면 FAILED, 부모 취소는 CANCELLED다. status와 코드/실패 원인/증거를 함께 반환한다.
- `delegate`, `apply_staged`는 부모 scope에만 노출한다. `/delegate run|status|apply|discard`를 추가하고 busy 동안 제어/승인 경로와 새 작업 시작을 구분한다.
- `TraceEvent.ModelBound`, `RunStateChanged`, `Change...`, `Delegation...`는 sealed interface의 내부 record로 추가하고 모든 trace/TUI dispatcher를 갱신한다. raw 이벤트 callback의 동기 실행을 보존하되 UI redraw는 thread-safe하게 전달한다.

## 순서·의존·크기

- 단계 1: 작업 1-5. registry -> binding -> provider/profile -> provenance -> commands. task별 대략 1-3일이다.
- 단계 2: 작업 6-11. 단계 1의 ref/binding/selection 뒤 store -> executor -> budget/approval -> journal/drift -> commands. task별 대략 1-3일이다.
- 단계 3: 작업 12-17. phase 1 context와 phase 2 실행/승인 계약 뒤 path/diff -> store -> stage -> apply/recovery -> verification -> commands. task별 대략 1-4일이다.
- 단계 4: 작업 18-23. phase 2 budget/store/broker와 phase 3 workspace/evidence 뒤 contracts -> scopes -> scheduler -> lifecycle -> apply bridge -> UI/tool. task별 대략 1-4일이다.
- F1-F4는 1-23 뒤 실행한다. 이는 계획 추정이며 구현 결과나 확정 일정이 아니다. 서로 다른 저장소/계약을 동시에 따로 구현하지 말고 먼저 위 계약을 고정한다.

## 구현 전 사용자 결정

아래는 실행 승인과 함께 확정할 공개 설정/동작 선택이다. 이 문서는 구현 승인을 대신하지 않는다.

1. 신규 설정은 기존 `agent` 접두어에 맞춰 `agent.models|runs|changes|delegation`을 권장한다. 기존 profile·환경변수·명령의 이전 규칙은 유지한다.
2. staged 변경을 managed run 기본으로 활성화하고 일반 턴의 즉시 모드는 명시적 legacy 옵션으로 남기는 안을 권장한다. 변경 전 승인을 모든 일반 턴의 기본으로 할지는 확인이 필요하다.
3. 새 diff 의존성 없이 먼저 구현하는 안을 권장한다. java-diff-utils가 필요하면 버전·라이선스·유지 비용을 확인해 별도 승인한다.
4. active 30분/tool 50/token 미설정, child concurrency 2/queue 8/깊이 1을 기본 제안한다. 유료 호출의 실제 비용 상한·데이터/백업 보존 기간은 사용자가 확정한다.
5. apply의 exclusive workspace 확인과 지원되지 않는 OS의 mutating shell 거부를 기본으로 권장한다. 더 넓은 trusted mode는 명시적으로 선택한다.

## QA 공통 계약

아래 명령은 미래 구현 후 수행하는 계획이다. 지금 실행하지 않았다.

```bash
cd mini-agent
set -o pipefail
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
EV=../.omo/evidence/agent-evolution
mkdir -p "$EV"/phase1 "$EV"/phase2 "$EV"/phase3 "$EV"/phase4
MVN="./mvnw -q -am -Dsurefire.failIfNoSpecifiedTests=false"
q(){ ./mvnw -q -am -Dsurefire.failIfNoSpecifiedTests=false -pl "$1" test -Dtest="$2" 2>&1 | tee "$EV/$3.log"; }
```

성공 경로와 실패를 단언하는 테스트 모두 exit 0이 통과다. 각 task의 로그와 해당 모듈 `target/surefire-reports/TEST-<FQCN>.xml`을 증거로 남긴다. tests는 fake/local HTTP/임시 cwd·home, 주입 Clock, 동작 전에 등록한 event/future/latch와 bounded wait를 사용한다. sleep/polling/문구 고정/실제 계정 의존은 없다. fixture만으로 crash를 다루는 테스트 외에 실제 자식 JVM 종료 뒤 복구하는 통합 검증을 F3에 포함한다.

모든 표기 `core/app/...`는 위 alias의 실제 파일이며 신규 타입은 명시한 package에 만든다. 아래 계획의 테스트 이름도 미래 생성할 machine-behavior 테스트다. 커밋 명령은 포함하지 않으며 별도 요청 전 커밋하지 않는다.

## Todos
- [x] 1. 코어 모델 레지스트리·조립 계약
  - Recommended task executor category: deep-high
  - 의존: 없음
  - 변경 파일: core/model/{ModelRef,ModelEntry,ModelProvider,ProviderAuth,ModelBinding,ModelRegistry,ModelUnavailableException,AgentChatClientFactory,ModelSelection,SessionModelStore,FileSessionModelStore}.java; core/ToolContextKeys.java
  - 근거: core/AgentAutoConfiguration.java:77-134
  - 수용/오류 경계: 같은 ref/옵션은 template만 재사용하고 바인딩 ID는 턴마다 새롭다. absent auth 목록과 typed 오류, 2.0.1 착수 게이트를 검증한다.
  - 성공 QA: q agent-core 'ModelRegistryTest' 1-ok
  - 실패 QA: q agent-core 'ModelRegistryTest#bindUnknownFailsWithUnknownModel' 1-fail
  - 증거: $EV/1-ok.log, $EV/1-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 2. 턴 고정 바인딩·독립 선택 저장
  - Recommended task executor category: deep-high
  - 의존: 1
  - 변경 파일: core/{Agent,AgentSession,AgentAutoConfiguration}.java; core/model/FileSessionModelStore.java; core/TraceEvent.java
  - 근거: core/Agent.java:66-120; core/AgentSession.java:15-43
  - 수용/오류 경계: 도구 왕복 중 선택 변경에도 기존 턴은 A, 다음 턴은 B다. memory off에서도 재시작 선택이 복원되고 대화 파일은 변하지 않는다.
  - 성공 QA: q agent-app 'AgentTurnBindingTest,SessionModelPersistenceTest' 2-ok
  - 실패 QA: q agent-app 'AgentTurnBindingTest#providerErrorMidToolFailsWithoutFallback' 2-fail
  - 증거: $EV/2-ok.log, $EV/2-fail.log, agent-app/target/surefire-reports/TEST-*.xml

- [x] 3. Provider factory·기존 프로필·인증 명령 이전
  - Recommended task executor category: deep-low
  - 의존: 1, 2
  - 변경 파일: app/model/{OpenAiCompatibleModelProvider,OllamaModelProvider,AnthropicApiModelProvider,FakeModelProvider}.java; codex/{CodexModelAutoConfiguration,CodexChatModel,CodexModelPicker}.java; claude/{ClaudeModelAutoConfiguration,ClaudeAuthCommands,ClaudeChatModel}.java; core/model/AuthCommand.java; agent-app/src/main/resources/application*.yml
  - 근거: codex/CodexModelAutoConfiguration.java:25-112; claude/ClaudeModelAutoConfiguration.java:13-60
  - 수용/오류 경계: OpenAI/Zai 경로·키·옵션이 분리되고 키 없는 startup/fake와 이전 profile·login device·codex effort가 동작한다. duplicate command는 기동 실패다.
  - 성공 QA: q agent-app 'OpenAiZaiIsolationTest,ProviderProfileMigrationTest,NoKeyStartupTest' 3-ok
  - 실패 QA: q agent-app 'SlashCommandConflictTest#duplicateNameFailsStartup' 3-fail
  - 증거: $EV/3-ok.log, $EV/3-fail.log, agent-app/target/surefire-reports/TEST-*.xml

- [x] 4. Provenance·provider private 상태 재생 필터
  - Recommended task executor category: deep-high
  - 의존: 1, 2, 3
  - 변경 파일: core/model/ProviderPrivateFieldFilter.java; memory/{JsonlChatMemory,JsonlChatMemoryRepository,MemoryBudgetAdvisor,MemoryPluginAutoConfiguration}.java
  - 근거: memory/MemoryPluginAutoConfiguration.java; 문자열 metadata 저장 계약
  - 수용/오류 경계: 같은 ref의 reasoning/signature는 유지하고 foreign/legacy는 요청 사본에서만 제거한다. 유효 tool 쌍과 원본 저장 bytes는 보존하고 예산은 필터 후 계산한다.
  - 성공 QA: q plugins/plugin-memory 'ProviderPrivateFieldFilterTest' 4-ok
  - 실패 QA: q plugins/plugin-memory 'ProviderPrivateFieldFilterTest#legacyRowDropsSignature' 4-fail
  - 증거: $EV/4-ok.log, $EV/4-fail.log, plugins/plugin-memory/target/surefire-reports/TEST-*.xml

- [x] 5. 모델 제어 명령·TUI/REPL 표시
  - Recommended task executor category: unspecified-high
  - 의존: 2, 3, 4
  - 변경 파일: core/model/{ModelsCommand,ModelCommand}.java; core/{AgentConsole,TraceEvent}.java; app/AgentRepl.java; tui/{TuiConsole,TuiFrontEnd,TraceGraph}.java; trace/ConsoleTraceRenderer.java
  - 근거: core/AgentConsole.java:47-63; tui/TuiFrontEnd.java:254-347
  - 수용/오류 경계: 선택 ref와 실행 snapshot을 구분하고 busy/사용불가 선택을 처리한다. sealed 이벤트/renderer를 확장하며 이전 명령과 fake one-shot을 유지한다.
  - 성공 QA: q agent-app 'ModelCommandTest,ModelDisplayTest' 5-ok
  - 실패 QA: q agent-app 'ModelCommandTest#unavailableSelectionKeepsPrevious' 5-fail
  - 증거: $EV/5-ok.log, $EV/5-fail.log, agent-app/target/surefire-reports/TEST-*.xml

- [x] 6. 실행 스키마·권위 로그·writer lock
  - Recommended task executor category: deep-high
  - 의존: 1, 2
  - 변경 파일: core/run/{RunId,RunBudget,RunSpec,RunState,Attempt,RunEvent,RunUsage,RunRecord,RunProjector,RunStore,RunWriter,FileRunStore,RunStoreException}.java
  - 근거: core/AgentSession.java:15-43; 공통 실행 계약
  - 수용/오류 경계: roundtrip/version/CRC/seq/단일 writer와 complete-row 손상 보존을 검증한다. 깨진 tail만 복구하고 credential 바이트는 저장하지 않는다.
  - 성공 QA: q agent-core 'FileRunStoreTest' 6-ok
  - 실패 QA: q agent-core 'FileRunStoreTest#secondWriterRunLocked+midCorruptionRefused' 6-fail
  - 증거: $EV/6-ok.log, $EV/6-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 7. RunExecutor·전용 context·안전 재개
  - Recommended task executor category: deep-high
  - 의존: 2, 6
  - 변경 파일: core/run/{RunExecutor,RunContext,RunResumeContributor}.java; core/{Agent,ToolContextKeys}.java
  - 근거: core/Agent.java:66-101
  - 수용/오류 경계: 새 attempt/binding으로 같은 ref를 재개하며 mutable 콘솔 세션에 영향받지 않는다. terminal 이후 실행/append와 변환되지 않은 불완전 tool replay를 거부한다.
  - 성공 QA: q agent-core 'RunExecutorTest' 7-ok
  - 실패 QA: q agent-core 'RunExecutorTest#resumeUnavailableModelKeepsState+terminalRunRejectsResume' 7-fail
  - 증거: $EV/7-ok.log, $EV/7-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 8. 라운드·요약·도구 예산과 usage 집계
  - Recommended task executor category: deep-low
  - 의존: 7
  - 변경 파일: core/run/{RunBudgetAdvisor,RunBudgetInterceptor,UsageRecorder}.java; core/AgentAutoConfiguration.java; memory/ChatModelSummarizer.java
  - 근거: core/TraceEvent.java:64-86; 기존 도구/메모리 한도
  - 수용/오류 경계: N+1 tool 시작을 막고 injected clock/deadline, compaction usage, unknown≠0와 overshoot를 구분한다. final loop 합계는 중복 합산하지 않는다.
  - 성공 QA: q agent-core 'RunBudgetTest' 8-ok
  - 실패 QA: q agent-core 'RunBudgetTest#unknownUsageWithTokenCapInterrupts+noToolStartsAfterCap' 8-fail
  - 증거: $EV/8-ok.log, $EV/8-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 9. 공용 승인 broker·질문 ID·취소 의미
  - Recommended task executor category: deep-high
  - 의존: 6, 7
  - 변경 파일: core/approval/{ApprovalBroker,ApprovalRequest,ApprovalDecision,ConsoleApprovalBroker}.java; permission/ConfirmToolInterceptor.java; core/AgentConsole.java; app/AgentRepl.java; tui/{TuiConsole,TuiFrontEnd}.java
  - 근거: core/AgentConsole.java:12-19; tui/TuiFrontEnd.java:254-312
  - 수용/오류 경계: FIFO 질문/late ID 거부, unattended DEFERRED, 사용자 cancel CANCELLED를 구분한다. run과 child 승인 모두 같은 broker를 사용한다.
  - 성공 QA: q agent-core 'ApprovalBrokerTest' 9-ok
  - 실패 QA: q agent-core 'ApprovalBrokerTest#staleAnswerRejected+unattendedDefers' 9-fail
  - 증거: $EV/9-ok.log, $EV/9-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 10. 도구 효과 journal·drift·UNKNOWN 복구
  - Recommended task executor category: deep-high
  - 의존: 7, 8, 9
  - 변경 파일: core/run/{ToolJournalInterceptor,SideEffectClass,DriftGate,WorkspaceFingerprint}.java; core/AgentAutoConfiguration.java
  - 근거: core/ToolInterceptor.java; core/AgentAutoConfiguration.java:77-134
  - 수용/오류 경계: 역할/한도/승인 뒤 STARTED를 force한 후 실행한다. UNKNOWN mutating 재실행·cwd/wire 변경을 거부하고 확인된 결과만 resume에 전달한다.
  - 성공 QA: q agent-core 'SafeResumeTest' 10-ok
  - 실패 QA: q agent-core 'SafeResumeTest#unknownMutatingRefusedWithoutFlag+humanEditBlocksResume' 10-fail
  - 증거: $EV/10-ok.log, $EV/10-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 11. 실행 명령·trace 상태·회귀 연결
  - Recommended task executor category: unspecified-high
  - 의존: 5, 8, 9, 10
  - 변경 파일: core/run/{RunsCommand,RunCommand,RunProperties}.java; core/{AgentAutoConfiguration,AgentInputHandler,TraceEvent}.java; tui/{TuiConsole,TuiFrontEnd}.java; agent-app/src/main/resources/application.yml
  - 근거: core/SlashCommand.java; 기존 session /resume
  - 수용/오류 경계: fake start→interrupt→explicit resume→completed가 실행되고 기존 /resume은 유지된다. API 예외는 structured 실패/exit로 연결한다.
  - 성공 QA: q agent-app 'RunCommandTest,ConsoleApprovalRoutingTest' 11-ok
  - 실패 QA: q agent-app 'RunCommandTest#resumeCompletedRunRejected+sessionResumeUntouched' 11-fail
  - 증거: $EV/11-ok.log, $EV/11-fail.log, agent-app/target/surefire-reports/TEST-*.xml

- [x] 12. 변경 owner·경로·fingerprint·bounded diff
  - Recommended task executor category: deep-high
  - 의존: 2, 6, 9
  - 변경 파일: core/change/{ChangeOwner,EntryKind,Fingerprint,Diff,ChangeStatus,ChangeSet,Approval,Checkpoint,VerificationEvidence,PathPolicy,Fingerprinter,DiffRenderer}.java; core/{TraceEvent,ToolContextKeys}.java
  - 근거: fs/FileCheckpoints.java:21-44; sealed TraceEvent
  - 수용/오류 경계: secret/탈출/link 경계를 거부하고 mode 변경도 digest에 반영한다. 큰 diff/한도 초과를 숨기거나 자동 승인하지 않는다.
  - 성공 QA: q agent-core 'PathPolicyTest,FingerprinterTest,DiffRendererTest' 12-ok
  - 실패 QA: q agent-core 'PathPolicyTest#symlinkParentEscapeRejected' 12-fail
  - 증거: $EV/12-ok.log, $EV/12-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 13. 변경 store·blob·준비/적용 journal
  - Recommended task executor category: deep-high
  - 의존: 12
  - 변경 파일: core/change/{ChangeStore,FileChangeStore,ChangeJournal,BlobStore,AtomicFiles,ChangeProperties}.java; core/AgentAutoConfiguration.java
  - 근거: 임시 FileCheckpoints 계약; 실행 store 계약
  - 수용/오류 경계: 새 인스턴스로 manifest/approval/checkpoint를 복원하고 cut journal을 분류한다. fsync/atomic move 미지원은 typed 오류이며 partial 파일은 자동 덮어쓰지 않는다.
  - 성공 QA: q agent-core 'FileChangeStoreTest' 13-ok
  - 실패 QA: q agent-core 'FileChangeStoreTest#truncatedJournalMarksRecoveryRequired' 13-fail
  - 증거: $EV/13-ok.log, $EV/13-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 14. 관리 작업공간·FS/shell/MCP 이전
  - Recommended task executor category: deep-high
  - 의존: 3, 12, 13
  - 변경 파일: core/change/{StagedWorkspace,ChangeSetRecorder,StagingToolInterceptor,ExternalChangeScanner}.java; fs/{FileTools,FileCheckpoints}.java; bash/{BashTool,BashSandbox}.java; core/AgentAutoConfiguration.java; fs/FsPluginAutoConfiguration.java; bash/BashPluginAutoConfiguration.java
  - 근거: 기존 permission과 allowed roots; 변경 스테이징 계약
  - 수용/오류 경계: managed 턴은 live bytes를 유지한다. 지원 sandbox 없는 mutating shell과 미분류 변이 MCP는 거부한다. 최소 env·exclude·legacy 모드 이전을 검증한다.
  - 성공 QA: q agent-app 'ImmediateToolMigrationTest,ShellScopeTest' 14-ok
  - 실패 QA: q agent-app 'ShellScopeTest#argvOutsideWorkspaceRejected' 14-fail
  - 증거: $EV/14-ok.log, $EV/14-fail.log, agent-app/target/surefire-reports/TEST-*.xml

- [x] 15. 독점 apply·부분 복구·충돌 보존 undo
  - Recommended task executor category: deep-high
  - 의존: 9, 10, 13, 14
  - 변경 파일: core/change/{ApplyEngine,UndoEngine,RecoveryScanner,ConflictReport}.java; fs/FileCheckpoints.java
  - 근거: 외부 수정 overwrite 위험; 공통 변경 계약
  - 수용/오류 경계: digest/승인/독점 상태가 없으면 쓰지 않는다. 중단을 journal로 분류하고 after와 다른 사용자 파일은 undo에서도 보존한다.
  - 성공 QA: q agent-core 'ApplyEngineTest,UndoEngineTest,RecoveryScannerTest' 15-ok
  - 실패 QA: q agent-core 'UndoEngineTest#humanEditAfterApplyIsNotClobbered' 15-fail
  - 증거: $EV/15-ok.log, $EV/15-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 16. 검증 프로세스·source revision 증거
  - Recommended task executor category: deep-low
  - 의존: 8, 13, 14
  - 변경 파일: core/change/VerificationRunner.java; core/change/ChangeProperties.java; bash/BashTool.java의 프로세스 정리 계약
  - 근거: 기존 shell timeout/cancel; VerificationEvidence 계약
  - 수용/오류 경계: argv/cwd/exit/log/source digest를 저장한다. nonzero/cancel/내용 변경 뒤 stale를 구분하고 자식 process 정리를 검증한다. Maven 재귀 대신 Java fixture를 사용한다.
  - 성공 QA: q agent-core 'VerificationRunnerTest' 16-ok
  - 실패 QA: q agent-core 'VerificationRunnerTest#nonZeroExitRecordedAsFailed' 16-fail
  - 증거: $EV/16-ok.log, $EV/16-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 17. 변경 명령·승인·E2E·기존 undo 이전
  - Recommended task executor category: unspecified-high
  - 의존: 5, 9, 15, 16
  - 변경 파일: core/change/command/{ChangesCommand,ApplyCommand,VerifyCommand,UndoCommand}.java; core/AgentConsole.java; tui/{TuiConsole,TuiFrontEnd}.java; fs/FsPluginAutoConfiguration.java
  - 근거: tui/TuiFrontEnd.java:254-312; 단계3 흐름
  - 수용/오류 경계: turn→changes→승인 apply→verify→undo와 reject/busy/recovery 표시를 확인한다. 비대화형은 pending으로 남으며 legacy 즉시 모드의 기존 tests를 보존한다.
  - 성공 QA: q agent-app 'ChangeCommandsE2ETest' 17-ok
  - 실패 QA: q agent-app 'ChangeCommandsE2ETest#applyWhileBusyRejected' 17-fail
  - 증거: $EV/17-ok.log, $EV/17-fail.log, agent-app/target/surefire-reports/TEST-*.xml

- [x] 18. 위임 계약·DAG 검증·역할 축소
  - Recommended task executor category: deep-high
  - 의존: 1, 6, 8, 9, 12
  - 변경 파일: deleg/{ChildSpec,ChildRole,ToolScope,ChildStatus,ChildFailure,ChildResult,DelegationPlan,DelegationResult,DelegationPlanValidator,RolePolicy,DelegationProperties}.java; core/ToolContextKeys.java
  - 근거: core/AgentPlugin.java:3-22; 공통 위임 계약
  - 수용/오류 경계: 구조적 cycle/id/depth/예약 합 초과는 시작 전 거부한다. 설정과 자식은 부모 권한을 넓히지 않는다. 런타임 unavailable은 해당 child 실패다.
  - 성공 QA: q agent-core 'DelegationPlanValidatorTest,RolePolicyTest' 18-ok
  - 실패 QA: q agent-core 'DelegationPlanValidatorTest#cycleRejectsBeforeAnyStart' 18-fail
  - 증거: $EV/18-ok.log, $EV/18-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 19. 자식 snapshot·세션·도구·메모리 분리
  - Recommended task executor category: deep-high
  - 의존: 2, 4, 7, 14, 18
  - 변경 파일: core/{TurnRunner,Agent,AgentSession}.java; deleg/{ChildScopeFactory,ScopedToolInterceptor,McpToolSnapshot}.java; core/model/AgentChatClientFactory.java; memory/MemoryPluginAutoConfiguration.java; plugins/plugin-mcp/src/main/java/com/example/miniagent/plugin/mcp/McpPluginAutoConfiguration.java
  - 근거: core/Agent.java:105-120; 요청 콜백 gate
  - 수용/오류 경계: 역할 검사를 승인보다 먼저 수행한다. child가 parent JSONL/선택에 영향을 주지 않고 MCP 추가/삭제/schema drift를 snapshot 밖으로 사용하지 않는다.
  - 성공 QA: q agent-core 'ChildScopeFactoryTest' 19-ok
  - 메모리 분리 QA: q plugins/plugin-memory 'ChildMemoryIsolationTest' 19-memory
  - 실패 QA: q agent-core 'ChildScopeFactoryTest#mcpToolAddedAfterBindIsDenied' 19-fail
  - 증거: $EV/19-ok.log, $EV/19-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 20. bounded scheduler·예약·의존·retry·집계
  - Recommended task executor category: deep-high
  - 의존: 8, 10, 18, 19
  - 변경 파일: deleg/{DefaultDelegationRuntime,SessionRunLock,DelegationAggregator}.java; core/run/RunBudget.java; core/AgentAutoConfiguration.java
  - 근거: 동기 Agent.run; run budget/journal 계약
  - 수용/오류 경계: concurrency/queue cap, explicit reject/defer와 부분 집계를 검증한다. 예약은 부모 한도 안에 있고 unknown mutating 외부 효과는 자동 retry하지 않는다.
  - 성공 QA: q agent-core 'DelegationRuntimeTest,BudgetLedgerTest' 20-ok
  - 실패 QA: q agent-core 'DelegationRuntimeTest#failedDependencySkipsDependentsPlanPartial' 20-fail
  - 증거: $EV/20-ok.log, $EV/20-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 21. 공용 승인·취소·child run 저장·재시작
  - Recommended task executor category: deep-high
  - 의존: 7, 9, 10, 13, 20
  - 변경 파일: deleg/{DelegationRecovery,DelegationRunProjector}.java; core/run/{RunSpec,RunStore,RunProjector}.java; core/approval/ApprovalBroker.java; permission/ConfirmToolInterceptor.java; tui/TuiConsole.java
  - 근거: 단일 TUI pending question; shared run/approval 계약
  - 수용/오류 경계: parent cancel은 queue/worker/approval까지 전파한다. memory off에서도 child 상태는 남고 restart는 INTERRUPTED이며 자동 resume/apply가 없다.
  - 성공 QA: q agent-core 'DelegationCancelTest,DelegationRecoveryTest' 21-ok
  - 실패 QA: q agent-core 'DelegationCancelTest#parentCancelDeniesPendingApprovalAndCancelsChildren' 21-fail
  - 증거: $EV/21-ok.log, $EV/21-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 22. 자식 스테이징·부모 적용 bridge
  - Recommended task executor category: deep-high
  - 의존: 9, 15, 19, 21
  - 변경 파일: deleg/{StagedWriteInterceptor,StagedApplyService}.java; core/change/{StagedWorkspace,ApplyEngine}.java
  - 근거: 단계3 checkpoint/approval/독점 계약
  - 수용/오류 경계: implement는 stage만 변경한다. research/verify source mutation과 child apply를 거부한다. human drift는 whole child conflict이며 알려진 live 변경을 덮어쓰지 않는다.
  - 성공 QA: q agent-core 'StagedApplyTest' 22-ok
  - 실패 QA: q agent-core 'StagedApplyTest#humanEditAfterStageRejectsWholeChild' 22-fail
  - 증거: $EV/22-ok.log, $EV/22-fail.log, agent-core/target/surefire-reports/TEST-*.xml

- [x] 23. delegate 제어·이벤트·부모 결과 검증
  - Recommended task executor category: unspecified-high
  - 의존: 5, 11, 17, 20, 21, 22
  - 변경 파일: deleg/{DelegateTool,ApplyStagedTool,DelegateCommand}.java; core/{TraceEvent,AgentConsole}.java; tui/{TuiConsole,TuiFrontEnd,TraceGraph}.java; trace/ConsoleTraceRenderer.java
  - 근거: core/SlashCommand.java; sealed trace; 단계4 집계
  - 수용/오류 경계: 동기 delegate 결과와 parent-only apply/approve를 검증한다. 모델 주장은 evidence로 대체하지 않고 busy 제어·승인·새 작업 시작을 구분한다.
  - 성공 QA: q agent-app 'DelegateToolTest,DelegateCommandTest,DelegationBackendIntegrationTest' 23-ok
  - 실패 QA: q agent-app 'DelegateCommandTest#applyWhileBusyReportsSessionBusy' 23-fail
  - 증거: $EV/23-ok.log, $EV/23-fail.log, agent-app/target/surefire-reports/TEST-*.xml

## Final verification wave

- [x] F1. 네 영역 실제 통합·provider/tool protocol
  - Recommended task executor category: unspecified-high
  - 의존: 1-23
  - QA: q agent-app 'AgentEvolutionIntegrationTest,DelegationBackendIntegrationTest,OpenAiZaiIsolationTest,SafeResumeIntegrationTest,RunBudgetIntegrationTest' F1-ok; q agent-app 'DelegationBackendIntegrationTest#deniedVerifyWritesKeepPlanPartialBeforeToolBudget' F1-fail
  - 수용: 두 로컬 provider, memory inside/outside/off, run resume, staged 승인/검증/undo, research→implement→verify→parent apply가 실제 경로에서 동작한다. provider별 auth/path/서명과 작업별 evidence를 확인한다.
  - 증거: $EV/F1-ok.log, F1-fail.log와 app Surefire XML.

- [x] F2. 전체 회귀·진단·정확한 런처
  - Recommended task executor category: deep-low
  - 의존: F1
  - QA: 수정 Java의 LSP 진단 후 ./mvnw clean verify 2>&1 | tee "$EV/F2-verify.log"; q agent-app 'SlashCommandConflictTest#duplicateNameFailsStartup' F2-fail
  - 수용: exit 0, 관련 target XML이 실제 존재하고 tests>0/failure=0/skip=0이다. 전체 검사와 fake/Ollama/API/구독 profile의 키 없는 또는 synthetic auth/local HTTP 기동이 통과한다. 기존 deprecation 경고는 숨기지 않고 기록한다.
  - 런처: mktemp로 만든 임시 home/cwd에서 AGENT_PROFILE=fake AGENT_UI=plain bash ./agent.sh --agent.home=<temp-home> 'hello'; shell exit와 실제 응답을 캡처한다. 유료 모델을 호출하지 않는다.
  - 증거: $EV/F2-verify.log, F2-fail.log, launcher.log, 진단 결과와 전체 XML 집계.

- [x] F3. 실제 프로세스 종료·복구·충돌 보존
  - Recommended task executor category: deep-high
  - 의존: F1
  - QA: q agent-app 'RunCrashRecoveryIntegrationTest,ChangeCrashRecoveryIntegrationTest,DelegationRecoveryIntegrationTest' F3-ok; q agent-core 'UndoEngineTest#humanEditAfterApplyIsNotClobbered' F3-fail
  - 수용: 자식 JVM의 STARTED/PREPARED 기록 완료 신호를 먼저 구독해 수신한 뒤 프로세스를 종료한다. 재기동 writer lock, tail/CRC, unknown effect, partial apply, 승인 stale, child interruption과 수동 resume를 확인한다. fixture뿐 아니라 OS 경계를 검증한다.
  - 증거: $EV/F3-ok.log, F3-fail.log, temp manifest/journal/hash 비교와 recovery XML.

- [x] F4. TUI·REPL·비대화형 승인과 취소
  - Recommended task executor category: visual-engineering
  - 의존: F1-F3
  - QA: q agent-app 'ModelDisplayTest,ConsoleApprovalRoutingTest,DelegationTuiTest,DelegationReplTest,ChangeCommandsE2ETest' F4-ok; q agent-app 'DelegationTuiTest#secondApprovalWaitsForFirstAnswer' F4-fail
  - 수용: PTY에서 실제 TUI를 넓은/좁은 화면과 light/dark 설정으로 캡처하고 모델 snapshot, run/child ID, FIFO 질문, stale 응답·busy·cancel을 눈으로 확인한다. REPL/one-shot 미응답은 자동 승인하지 않고 pending 코드로 끝난다. 화면 검사 기록을 테스트 성공과 구분한다.
  - 증거: $EV/F4-ok.log, F4-fail.log, terminal 캡처와 항목별 QA 판정.

## 완료 판정과 구현 handoff

23개 구현 행과 F1-F4가 각각 근거·범위·수용·성공/실패 QA를 갖추고 의존 순서를 따른다. 실제 구현은 사용자 선택/승인 후 시작한다. 현재 문서는 계획이며 future QA 명령은 실행하지 않았다. 원본 네 Opus 초안과 부모 검토 메모가 provenance다. 새로운 API/플랫폼 지원이 게이트를 통과하지 않으면 구현자가 조용히 대체하지 말고 계획을 수정해 해당 결정을 확인한다.

