# Opus 5.5 xhigh: 단계 3 원본 초안

상태: 최종 통합 계획 부모 검토가 미완료입니다. 모델 claude-opus-5-5 / xhigh / 종료 코드 0.

Phase 3 계획 요청으로 이해했습니다. 계약, 불변식, 작업 12-17을 정리한 아래 계획을 전달하는 것으로 마칩니다.

# Phase 3: 변경 승인·검증·영속 복구

## 결정
- `agent-core`에 `core/change` 패키지만 추가합니다. Git은 쓰지 않으므로 unborn 저장소와 Git이 없는 환경에서 똑같이 동작합니다. 새로 추가하는 런타임 의존은 `java-diff-utils` 하나이고, Node·Bun·senpi 의존은 없습니다.
- 파일과 shell 효과는 run 스테이징 ws에만 쌓입니다. 실제 workspace는 diff를 승인한 뒤 `/apply`로만 바뀝니다.
- 승인은 콘솔의 사람만 할 수 있고 tool과 MCP는 승인할 수 없습니다. 비대화형으로 `bash ./agent.sh`를 실행하면 변경은 `PENDING_APPROVAL` 상태로 남습니다.
- 상태 경로 설정은 `miniagent.changes.state-dir`이고, 기본값은 `${user.home}/.miniagent/changes/<workspace-realpath-sha256>`입니다.
- 구현 게이트(구현 전에 확인할 항목):
  - 2.0.1 `ToolCallingAdvisor`·`ToolContext` 접근 방식
  - `ToolInterceptor`·`SlashCommand`·`AgentConsole` 시그니처
  - 즉시 쓰기 tool, shell tool, 임시 undo, TUI 클래스의 위치(`rg -l '@Tool|[Uu]ndo|cancel\(true\)' agent-core agent-app plugins`)
  - `java-diff-utils` 버전
  - `ATOMIC_MOVE`·POSIX 지원 여부
- 과거의 193개 테스트 결과는 쓰지 않습니다. 착수 전에 `./mvnw test`를 새로 돌려 `mini-agent/target/qa-phase3/baseline.log`로 남깁니다.

## 공유 계약
```java
record ChangeOwner(String sessionId, String runId, String attemptId, String turnId) {}
enum EntryKind { ABSENT, REGULAR, SYMLINK }
record Fingerprint(String relPath, EntryKind kind, String sha256, long size, String posixMode, String linkTarget) {}
record Diff(String relPath, Fingerprint baseline, Fingerprint proposed, String unifiedPatch, boolean binary) {}
enum ChangeStatus { PENDING_APPROVAL, APPROVED, REJECTED, SUPERSEDED, STALE, APPLYING, APPLIED,
  PARTIALLY_APPLIED, RECOVERY_REQUIRED, UNDONE, PARTIALLY_UNDONE }
record ChangeSet(UUID id, ChangeOwner owner, ModelRef model, UUID bindingId, List<Diff> diffs, String digest, ChangeStatus status) {}
record Approval(UUID changeSetId, String digest, String approver, boolean approved, Instant at) {}
record Checkpoint(UUID changeSetId, List<Fingerprint> before, List<Fingerprint> after, Map<String,String> backupBlobByPath) {}
record VerificationEvidence(UUID id, ChangeOwner owner, UUID changeSetId, List<String> argv, Path cwd, String treeDigest,
  Integer exitCode, String outcome, Path log, String logSha256, long durationMs) {} // PASSED|FAILED|CANCELLED
```
- Owner 기본값:
  - `sessionId`와 `turnId`는 턴이 시작될 때 Phase 1 `TurnContext`에서 복사합니다. 이후 session ID가 바뀌어도 영향을 받지 않습니다.
  - Phase 2 run이 없으면 `runId=turnId`, `attemptId="1"`을 씁니다.
  - ChangeSet은 턴마다 하나입니다. 새 attempt가 시작되면 이전 attempt의 미적용 ChangeSet은 `SUPERSEDED`가 됩니다.
- `digest`는 경로 순서대로 정렬한 baseline, proposed, patch의 sha256입니다.
- 저장하는 것은 `ModelRef`, `bindingId`, fingerprint뿐입니다. 키, `ChatClient`, env 값은 저장하지 않습니다.
- 함께 추가하는 것: `ToolContextKeys.CHANGE_SET_ID`, 그리고 changeSetId와 turnId를 담는 TraceEvent `ChangeStagedEvent`·`ApprovalRecordedEvent`·`ApplyProgressEvent`·`VerificationFinishedEvent`·`UndoFinishedEvent`.

## 저장·스테이징
- 디렉터리 배치: `<state>/sets/<id>/{manifest.json,journal.jsonl}`, `blobs/<sha256>`, `runs/<runId>/<attemptId>/ws/`, `evidence/<id>.log`, `lock`.
- manifest와 blob은 같은 디렉터리의 temp 파일에 쓰고, fsync → `ATOMIC_MOVE` → 디렉터리 fsync 순서로 확정합니다. journal은 append한 뒤 fsync합니다. apply, undo, 복구는 `lock` 파일 잠금을 잡은 상태에서만 실행합니다.
- 제외 대상:
  - 경로 `{.git,target,build,node_modules,.idea,.miniagent}/`
  - 파일 `{.env*,*.pem,*.key,*.p12,*.jks,id_*}`
  - `miniagent.changes.exclude`에 지정한 항목
- 제외 대상은 스테이징, diff, 백업, 로그 어디에도 넣지 않습니다. 쓰기를 시도하면 `EXCLUDED_PATH`로 거부합니다.
- 경로는 realpath를 기준으로 판단합니다. `..`이나 절대경로로 workspace를 벗어나는 경로, 부모 경로에 링크가 낀 경로는 거부합니다. 링크는 따라가지 않고 링크 텍스트를 fingerprint합니다. 링크를 만들거나 바꾸려 하면 `SYMLINK_UNSUPPORTED`입니다. mode만 바뀌어도 Diff로 기록합니다.
- attempt가 시작되면 포함 대상 파일을 ws로 복사하고 baseline을 기록합니다. 읽기 tool은 스테이징 뷰를 읽습니다.
- shell tool:
  - 기존 interceptor의 권한 확인을 먼저 거칩니다.
  - cwd를 ws로 고정하고, argv에 ws 밖 경로가 있으면 거부합니다.
  - 종료 후 baseline과 비교해 Diff를 만듭니다.
  - OS 샌드박스는 아닙니다.
- 동적 MCP tool은 스테이징하지 않습니다. 대신 턴이 끝날 때 실제 workspace를 스캔해 baseline과 다른 파일을 `EXTERNAL_CHANGE`로 보고하고, 그 파일과 겹치는 ChangeSet을 `STALE`로 바꿉니다.

## 실패 불변식
1. 현재 digest와 일치하는 승인 `Approval`이 없으면 실제 workspace에 쓰지 않습니다.
2. 적용, undo, 복구 모두 현재 fingerprint가 기대값과 다른 파일은 덮어쓰지 않습니다. 적용 전에 baseline이 하나라도 다르면 ChangeSet 전체가 `STALE`이 되고 승인도 무효가 됩니다.
3. 파일을 쓰기 전에 백업 blob과 `PREPARED` journal이 먼저 디스크에 있어야 합니다. 파일 하나의 원자성은 rename이, 여러 파일 사이의 일관성은 journal이 보장합니다.
4. `Future.cancel(true)`가 오면 진행 중이던 파일의 rename과 journal 기록까지 마친 뒤 멈추고, 상태는 `PARTIALLY_APPLIED`가 됩니다.
5. 기동할 때 `COMMITTED` 기록이 없는 journal은 `RECOVERY_REQUIRED`로 봅니다. 경로마다 현재 내용이 proposed와 같으면 적용됨, baseline과 같으면 미적용, 둘 다 아니면 `CONFLICT`로 분류합니다. 이 경로와 겹치는 새 apply는 막습니다.
6. 검증 증거는 `treeDigest`가 현재 트리와 같을 때만 유효합니다.
7. 명령이 실패하면 journal에 기록된 부분 상태만 남기고 오류 코드를 보고합니다.

## 명령 흐름
- `/changes [id]`: id가 없으면 목록을, 있으면 diff, 상태, 승인, 증거 유효성, 충돌을 보여줍니다.
- `/apply <id>`: diff를 보여주고 pending question으로 y/n을 묻습니다. 답을 `Approval`로 저장하고, baseline을 다시 확인한 뒤 적용합니다. 부분 적용 상태나 복구 상태는 남은 경로가 baseline과 같을 때만 이어서 진행합니다.
- `/verify [id]`: `miniagent.changes.verify.argv`(기본값 `["./mvnw","-q","test"]`)를 실행합니다. 적용 전에는 ws에서, 적용 후에는 실제 workspace에서 실행합니다. 취소하면 프로세스를 destroy하고 `CANCELLED`, exit null로 기록합니다.
- `/undo [id]`: id가 없으면 세션의 최근 `APPLIED` 변경이 대상입니다. 현재 내용이 after와 같은 파일만 백업으로 되돌리고, 원래 `ABSENT`였던 파일은 삭제합니다. 나머지는 `CONFLICT`로 남기고 상태는 `PARTIALLY_UNDONE`이 됩니다.
- TUI가 다른 작업 중이면 `BUSY`로 거부하고 큐에 넣지 않습니다. REPL과 TUI는 같은 `AgentConsole` 입력을 씁니다.

## 이전·의존
- 즉시 쓰기 tool은 이름과 인자를 그대로 두고 백엔드만 `StagedWorkspace`로 바꿉니다. 메모리에만 있던 턴 undo와 그 명령은 삭제합니다. 옮길 데이터는 없습니다.
- 변경 tool과 명령은 bean으로 기여합니다. `ToolCallingAdvisor` 순서, interceptor, memory, trace, TUI/REPL은 그대로 유지합니다.
- Phase 1 작업에 의존합니다: 1·2(`TurnContext`, `ToolContextKeys`), 3(`fake/echo`), 5(`AgentConsole`).

## 작업
QA 공통:
```bash
set -o pipefail; cd mini-agent; E=target/qa-phase3; mkdir -p $E
q(){ ./mvnw -q -am -Dsurefire.failIfNoSpecifiedTests=false -pl "$1" test -Dtest="$2" 2>&1 | tee "$E/$3.log"; }
```
- 증거 경로는 `$E/<번호>-{ok,fail}.log`와 `<모듈>/target/surefire-reports/TEST-<FQCN>.xml`입니다. 테스트 패키지는 `com.example.miniagent.{core,app}.change`입니다.
- ✗ 항목은 실패를 단언하는 테스트입니다.
- 비동기 테스트는 trace 구독과 latch를 먼저 등록한 뒤 동작을 시작하고, `await(5, SECONDS)`로 기다립니다. sleep이나 문구 고정 없이 코드, 상태, 바이트만 비교합니다.
- 크래시는 단계별로 자른 journal fixture로, 중단은 쓰기 훅 latch로 재현합니다.

- [ ] 12. 계약·경로 정책·fingerprint
  - 파일: `core/change/{ChangeOwner,EntryKind,Fingerprint,Diff,ChangeStatus,ChangeSet,Approval,Checkpoint,VerificationEvidence,PathPolicy,Fingerprinter,ChangeEvents}.java`, `core/ToolContextKeys.java`
  - 의존: Phase 1 작업 1·2 / 실행: deep-low / 근거: `AgentSession.java:15-43`(가변 sessionId), `TraceEvent.java`
  - 수용: 제외 경로, 탈출 경로, 링크가 낀 부모 경로를 거부합니다. mode나 링크 텍스트가 바뀌면 digest가 바뀝니다. owner 기본값이 위 규칙과 같습니다.
  - QA: ✓ `q agent-core 'PathPolicyTest,FingerprinterTest' 12-ok` ✗ `q agent-core 'PathPolicyTest#symlinkParentEscapeRejected' 12-fail`
- [ ] 13. 영속 저장소·journal
  - 파일: `core/change/{ChangeStore,FileChangeStore,ChangeJournal,BlobStore,AtomicFiles,ChangeProperties}.java`, `core/AgentAutoConfiguration.java`
  - 의존: 12 / 실행: deep-high / 근거: `AgentAutoConfiguration.java:77-134`, 현재 undo가 임시 저장이라는 검증 결과
  - 수용: 새 인스턴스가 ChangeSet, Approval, Checkpoint를 복원합니다. manifest에 키나 env 값이 없습니다. 잘린 journal은 `RECOVERY_REQUIRED`가 됩니다.
  - QA: ✓ `q agent-core FileChangeStoreTest 13-ok` ✗ `q agent-core 'FileChangeStoreTest#truncatedJournalMarksRecoveryRequired' 13-fail`
- [ ] 14. 스테이징·tool 이전·shell 범위
  - 파일: `core/change/{StagedWorkspace,ChangeSetRecorder,StagingToolInterceptor,ExternalChangeScanner}.java`, 게이트로 확정한 즉시 쓰기·shell tool 클래스, 임시 undo 저장소와 명령(삭제)
  - 의존: 12·13 / 실행: deep-high / 근거: `ToolInterceptor.java`, `Agent.java`(동기 `run`), `AgentAutoConfiguration.java:77-134`
  - 수용: 스크립트된 fake `ChatModel`로 턴을 돌린 뒤 실제 workspace 바이트가 그대로이고 `PENDING_APPROVAL` 변경이 1개 생깁니다. 권한 interceptor가 먼저 차단합니다. 새 attempt를 시작하면 이전 변경이 `SUPERSEDED`가 됩니다.
  - QA: ✓ `q agent-app 'ImmediateToolMigrationTest,ShellScopeTest' 14-ok` ✗ `q agent-app 'ShellScopeTest#argvOutsideWorkspaceRejected' 14-fail`
- [ ] 15. 적용·복구·undo 엔진
  - 파일: `core/change/{ApplyEngine,UndoEngine,RecoveryScanner,ConflictReport}.java`
  - 의존: 13·14 / 실행: deep-high / 근거: TUI의 `Future.cancel(true)`, 현재 임시 undo가 나중의 사람 편집을 덮어쓸 수 있다는 검증 결과
  - 수용:
    - 승인이 없거나 digest가 다르면 아무것도 쓰지 않습니다.
    - baseline이 바뀌면 `STALE`이 됩니다.
    - 3개 파일 중 2번째에서 중단하면 `PARTIALLY_APPLIED`가 되고, 재기동 시 경로 분류가 정확합니다.
    - 적용 후 사람이 고친 파일은 undo 뒤에도 바이트가 그대로입니다.
  - QA: ✓ `q agent-core 'ApplyEngineTest,UndoEngineTest,RecoveryScannerTest' 15-ok` ✗ `q agent-core 'UndoEngineTest#humanEditAfterApplyIsNotClobbered' 15-fail`
- [ ] 16. 검증 실행·증거
  - 파일: `core/change/VerificationRunner.java`, `core/change/ChangeProperties.java`(`verify.argv`)
  - 의존: 12·14 / 실행: unspecified-high / 근거: 게이트로 확정한 shell tool의 프로세스 처리, `TraceEvent.java`
  - 수용:
    - argv, cwd realpath, exit, 로그 sha256, `treeDigest`를 기록합니다.
    - exit가 0이 아니면 `FAILED` 증거를, 취소하면 `CANCELLED`를 남깁니다. 파일이 바뀐 뒤의 증거는 stale입니다.
    - 테스트의 argv는 `java.home`의 `java`로 `FakeVerifyMain <exit>`를 실행하므로 Maven을 재귀 호출하지 않습니다.
  - QA: ✓ `q agent-core VerificationRunnerTest 16-ok` ✗ `q agent-core 'VerificationRunnerTest#nonZeroExitRecordedAsFailed' 16-fail`
- [ ] 17. 명령·콘솔 연동
  - 파일: `core/change/command/{ChangesCommand,ApplyCommand,VerifyCommand,UndoCommand}.java`, `core/AgentConsole.java`와 구현체, 게이트로 확정한 TUI busy/pending 클래스
  - 의존: 15·16 / 실행: unspecified-high / 근거: `SlashCommand.java`, `AgentConsole.java`, TUI의 단일 busy 작업과 pending question
  - 수용:
    - n으로 답하면 `REJECTED`이고 아무것도 쓰지 않습니다.
    - busy 상태에서는 `BUSY`로 거부합니다.
    - 기동 시 `RECOVERY_REQUIRED`를 표시합니다.
    - fake 프로필 컨텍스트에서 verify argv를 `FakeVerifyMain`으로 두고 턴 → `/changes` → `/apply` y → `/verify` → `/undo` 흐름이 통과합니다.
  - QA: ✓ `q agent-app ChangeCommandsE2ETest 17-ok` ✗ `q agent-app 'ChangeCommandsE2ETest#applyWhileBusyRejected' 17-fail`

