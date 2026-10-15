# agent-forge

이 저장소의 루트가 Maven 프로젝트 루트입니다. 하위 앱 폴더로 이동하지 않고 여기서
`./mvnw test`와 `bash ./agent.sh`를 실행합니다. 실행 JAR은 `agent-app/target/agent-forge.jar`입니다.

기존 로그인과 세션을 이어 쓰도록 `~/.mini-agent`, 작업 공간의 `.mini-agent/skills`,
Java 패키지 `com.example.miniagent`와 기존 `agent.*` 설정은 유지합니다.

Spring AI **2.0.1** 위에 만든 최소 에이전트 원형입니다.   
[pi coding-agent](https://github.com/earendil-works/pi/tree/main/packages/coding-agent)처럼 코어는 작게 두고, 기능 대부분을 플러그인으로 붙입니다.

목적은 학습입니다. 플러그인마다 Spring AI 확장점을 하나씩 직접 써 보도록 나눴습니다.

- Spring Boot 4.1.1, Java 21 (Spring AI 2.0.x 최소 요구는 Java 17)
- Maven 멀티모듈, 플러그인은 Spring Boot auto-configuration으로 붙음

---

## 프로젝트 지침·검색·대화 분기

시작할 때 가장 가까운 Git 루트부터 현재 작업 디렉터리까지 `CLAUDE.md`, `AGENTS.md`를
읽어 시스템 프롬프트에 합칩니다. Git 저장소 밖에서는 현재 디렉터리만 읽습니다.
하위 디렉터리의 지침이 상위 지침보다 우선하며, 같은 위치에서는 `AGENTS.md`가 우선합니다.
`/context`로 실제 로딩 경로를 확인합니다. 시작 시점의 스냅샷이므로 수정 후에는 재시작합니다.
작업 디렉터리 아래의 모든 지침을 재귀 로딩하지는 않습니다.
심볼릭 링크는 거부하며 파일당 64KiB, 합계 256KiB가 상한입니다.
`--agent.project-instructions.enabled=false`로 끌 수 있습니다. 지침 텍스트가 도구 권한을 넓히지는 않습니다.

읽기 도구 `search_content`는 대소문자를 구분하는 리터럴 내용 검색,
`glob_paths`는 경로 검색입니다. 가짜 모델로도 실제 실행을 확인할 수 있습니다.

```text
!tool search_content {"path":"agent-core/src","query":"TurnContext","offset":0,"limit":20}
!tool glob_paths {"path":".","pattern":"**/*.java","offset":0,"limit":20}
```

결과는 상대 경로 순이며 내용 검색에는 줄 번호가 붙습니다. `offset`은 0부터,
`limit`은 1~100입니다. `nextOffset`으로 다음 페이지를 요청합니다.
스캔은 10,000개 항목·깊이 16·출력 본문 16,000자로 제한됩니다.
내용 검색은 파일당 최대 1MiB와 총 16MiB를 읽으며 긴 줄·바이너리·잘못된 UTF-8을
건너뜁니다. `.git`, 빌드·의존성·앱 상태 디렉터리와 알려진 인증 파일은 고정 목록으로
제외합니다. `.gitignore`를 해석하지 않으며, 심볼릭 링크를 따라가지 않습니다.
부분 스캔 여부와 건너뛴 파일·줄 수는 결과 필드로 확인합니다.
이 두 도구도 기존 cwd·추가 허용 루트·stage·자식 역할 정책을 그대로 따릅니다.

```text
/fork          # 마지막 완료 답변까지 복사하고 새 분기로 전환
/fork 1        # 첫 번째 완료 답변까지 복사
/tree          # 세션별 부모 ID와 분기 위치 조회
/tree <id>     # 해당 세션으로 전환
```

분기는 전체 기록의 완료 경계에서 만들며 미완성 도구 호출은 복사하지 않습니다.
적용 가능한 요약과 모델 선택 값은 보존하고, 이후 기록은 독립적으로 추가합니다.
대화 분기는 실행 저널·승인·stage·실행 중 바인딩을 복사하거나 작업 파일을 되돌리지 않습니다.
기존 `/sessions`, `/resume`, `/new`, `/history`도 사용할 수 있습니다.
`/forget`으로 분기의 기억을 지워도 부모·분기 위치 정보는 유지합니다.

## 대형 도구 결과 아티팩트

8,192자를 넘는 도구 결과는 원문 그대로 `agent.home/artifacts`에 보관하고 모델에는
`artifactId`, SHA-256, UTF-8 바이트 수와 짧은 미리보기만 전달합니다.
`read_artifact`로 부분 조회하고 `search_artifact`로 대소문자를 구분하는 리터럴 검색을 합니다.
오프셋은 0부터 시작하는 유니코드 코드 포인트 기준입니다.
읽기는 기본 1,024·최대 2,048 코드 포인트, 검색은 기본 10·최대 20개 일치입니다.
`truncated`가 참이면 `nextOffset`으로 이어 읽거나 검색합니다.

원문은 변경하지 않으며 파일 경로 대신 불투명 UUID만 받습니다.
권한은 실행기가 전달한 작업 디렉터리와 세션에 묶입니다.
다른 세션·자식·분기가 아티팩트 ID만 복사해 조회할 수는 없습니다.
대화 분기에 복사된 과거 참조도 원래 세션의 접근 권한을 상속하지 않습니다.
한 결과는 최대 4MiB, 한 작업 디렉터리·세션은 합계 64MiB와 128건으로 제한됩니다.
한도 초과는 명시적으로 거부하고 결과를 몰래 잘라 보관하지 않습니다.
조회 도구의 작은 결과는 다시 아티팩트로 바꾸지 않습니다.

설정은 `agent.artifacts.enabled`, `threshold-chars`, `preview-chars`,
`max-artifact-bytes`, `max-scope-bytes`, `max-artifacts`입니다.
저장은 비공개 POSIX 권한·디렉터리 force·원자적 rename을 요구합니다.
중단된 `.pending-*` 게시가 있으면 새 게시를 막으며 자동으로 지우거나 재시도하지 않습니다.

## 스트리밍과 작업 중 입력

Codex·Claude의 일반 대화는 실제 SSE 텍스트 delta를 표시합니다.
완성되지 않은 도구 JSON은 실행하지 않고 완료 응답에서만 도구 호출과 사용량을 전달합니다.
취소·응답 용량·전송 기한 제한은 유지합니다. 관리 실행과 자식 실행은 기존 동기 호출 경로를 사용합니다.

```text
/steer 추가 지시       # 다음 모델·도구 왕복 경계에 주입
/follow-up 다음 요청   # 현재 에이전트 턴이 끝난 뒤 실행
```

일반 대화 중 TUI에 보낸 보통 입력도 follow-up으로 대기합니다. 각 큐 안에서는 FIFO,
steering이 follow-up보다 우선합니다. 마지막 모델 응답 뒤 도착한 steering은 다음 턴으로 넘깁니다.
대기 텍스트가 `/`로 시작해도 권한 있는 콘솔 명령으로 재해석하지 않습니다.
한 입력 묶음은 이미 소비한 항목까지 합쳐 32건·UTF-8 64KiB로 제한됩니다.
취소·실패 시 남은 입력을 버리며, 세션이 달라진 입력 대상은 거부합니다.
실행 중 모델·세션·적용 명령은 거부하고 기존 실행 제어 명령은 유지합니다.
관리 명령 실행 중에는 이 대화 큐를 사용할 수 없습니다.
줄 단위 REPL은 동기 입력을 유지하므로 작업 중 입력 수신에는 TUI 또는 RPC를 사용합니다.

## JSONL 출력과 RPC

한 번 실행하는 JSONL 출력과 지속적인 stdin/stdout RPC를 제공합니다. 기본 fake 제공자로
아래 명령을 실행할 수 있으며, 프로토콜 stdout과 진단 로그 stderr를 분리합니다.

```bash
AGENT_UI=json bash ./agent.sh "안녕"
printf '%s\n' '{"v":1,"id":"p1","method":"prompt","text":"안녕"}' |
  AGENT_UI=rpc bash ./agent.sh
```

RPC는 UTF-8 JSON 객체 하나를 한 줄로 받습니다. 모든 요청에 버전 `v:1`,
연결 안에서 중복되지 않는 `id`, `method`가 필요합니다.

```json
{"v":1,"id":"p1","method":"prompt","text":"코드를 조사해줘"}
{"v":1,"id":"s1","method":"steer","targetId":"p1","text":"테스트부터 확인해줘"}
{"v":1,"id":"f1","method":"follow_up","targetId":"p1","text":"결과를 요약해줘"}
{"v":1,"id":"c1","method":"cancel","targetId":"p1"}
{"v":1,"id":"m1","method":"command","text":"/models"}
{"v":1,"id":"end","method":"shutdown"}
```

예시의 줄을 한꺼번에 보내지 말고 `ready`, `response`, `event`, `completion`을 확인하며
보냅니다. 큐 요청은 해당 실행의 `ModelBound` 이벤트 뒤에 보내야 합니다.
이전 실행 ID나 종료된 큐 세대는 거부합니다. 실행 중 새 prompt와 세션·모델 변경 명령은 거부합니다.
취소 요청 후에도 실제 worker 정리가 끝날 때까지 새 실행을 시작하지 않습니다.

모든 프레임에 `v`, `type`, `requestId`, `executionId`, `sessionId`, `turnId`가 있습니다.
아직 결정되지 않은 값은 null입니다. `delta.text`는 텍스트 조각,
`completion.status`는 `completed`, `failed`, `cancelled` 중 하나입니다.
큐 요청의 응답은 자신의 `requestId`와 부모 `executionId`를 가지며,
이어 실행되는 턴은 부모 실행 상관관계를 유지하면서 새 `turnId`를 발행합니다.

`command.text`는 `/model`, `/models`, `/sessions`, `/new`, `/resume`, `/tree`, `/fork`,
`/history`, `/forget`, `/runs`, `/run`, `/changes`, `/context`, `/goal`, `/lesson`,
`/worker`, `/message`로 제한됩니다.
승인 이벤트의 `approvalId`에는 `approval` 메서드와 `APPROVED`, `REJECTED`,
`DEFERRED`, `CANCELLED` 결정을 보냅니다. 일반 확인 질문은 `questionId`에
`question` 메서드와 `text`로 답합니다. 두 ID는 일회용이며 기존 승인 브로커가 결정을 검증합니다.
`/run approve|reject|defer|cancel`은 이 명령 경로로 보내지 않습니다.

입력은 줄당 65,536바이트, 텍스트 필드당 32,768바이트, 연결당 요청 ID 4,096개,
동시 질문 64개까지입니다. 잘못된 요청은 `error.code`로 반환합니다.
EOF는 진행 중인 작업을 마저 기다리고, 답할 수 없는 승인은 보류하며 확인 질문을 종료합니다.
명시적 취소·shutdown은 대기 입력과 질문을 정리합니다.

## 지속 목표와 호스트 검증

`/goal`은 여러 에이전트 턴을 하나의 관리 실행과 누적 예산 아래에서 진행합니다.
모델의 완료 문장이 아니라 사용자가 지정한 검증 명령의 실제 결과로 성공을 판정합니다.

```text
/goal start {"goal":"answer.txt를 READY 한 줄로 고쳐라. 실제 소스에는 적용하지 마라.","gates":[{"argv":["/usr/bin/grep","-q","^READY$","answer.txt"],"timeoutMillis":30000,"maxOutputBytes":4096}],"maxSteps":6}
/goal submit {"goal":"같은 형식으로 목표만 저장","gates":[{"argv":["/usr/bin/true"],"timeoutMillis":30000,"maxOutputBytes":4096}]}
/goal list
/goal show <goal-id>
/goal resume <goal-id>
/goal cancel <goal-id>
```

`submit`은 대기 목표를 저장하고 실행하지 않습니다. `start`는 저장 후 현재 프로세스에서
실행합니다. 선택된 모델과 옵션, 원래 대화·작업 경로, 고정 gate argv와 예산을 보관합니다.
`maxSteps` 기본값은 6이며 최대 32입니다. gate는 1~8개, 각 명령은 최대 64개 인자,
출력은 최대 64KiB입니다. `wallMillis`, `tokenCap`, `toolCallCap`을 생략하면 `agent.runs`를 따릅니다.
모델이 진단 출력에 새 검증 명령을 제안해도 고정 gate를 바꾸지 않습니다.

실제 변경은 관리 stage에서 이루어지므로 기존의 정확한 UUID 승인 절차를 거칩니다.
비대화형 실행에서 답하지 않은 승인은 자동 통과하지 않고 대기합니다.
검증은 비공개 복사본에서 실행되며 proposal 소유자·revision·실행 설정에 결합됩니다.
변경 없는 작업도 별도의 읽기 전용 검증 대상으로 확인하며 빈 변경 제안을 만들지 않습니다.
같은 실패 revision·설정은 무의미하게 다시 검사하지 않습니다.

목표 상태와 run 상태는 별개입니다. 예산 소진·승인 대기·취소·검증 실패는 성공이 아닙니다.
성공은 검증된 임시 내용 또는 읽기 전용 확인을 뜻하며 `/apply`를 대신하지 않습니다.
재개는 같은 run ID와 누적 사용량을 유지합니다. 확인된 이전 stage는 새 소유권으로 복원하되
승인과 검증 결과를 새 제안에 전이하지 않습니다. 불확실한 도구 효과·경로 변경·손상된
체크포인트는 기존 명시적 해결 절차 없이 재실행하지 않습니다.
`/goal resume`에는 기존 `/run resume`의 명시적 해결·드리프트 옵션도 사용할 수 있습니다.

관리 stage를 사용하려면 `agent.home`은 작업 디렉터리 밖의 절대 경로여야 합니다.
`--agent.goals.enabled=false`로 목표 기능을 끌 수 있습니다.

## 근거가 있는 교훈과 명시적 승인

`/lesson`은 검증된 목표에서 교훈 후보를 만들고 운영자가 승인한 내용만 관련 요청에 주입합니다.
`refine`은 현재 선택한 모델과 옵션을 사용하며 해당 요청의 도구 실행을 차단합니다.
모델 출력은 엄격한 JSON으로 읽고 후보를 저장하기 직전에 목표·run·검증 증거를 다시 확인합니다.
실패·미검증·오래된 목표와 다른 대화의 목표는 근거로 사용할 수 없습니다.

```text
/lesson refine <verified-goal-id>
/lesson list
/lesson show <lesson-id>
/lesson accept {"id":"...","expectedVersion":1}
/lesson promote {"id":"...","expectedVersion":2,"scope":"PROJECT"}
/lesson history <lesson-id>
/lesson rollback {"id":"...","expectedVersion":3,"toVersion":2}
/lesson lookup <검색어>
```

직접 후보를 쓰려면 `/lesson propose`에 `goalId,title,content,tags` JSON을 전달합니다.
`/lesson update`는 `id,expectedVersion,goalId,title,content,tags`를 받습니다.
후보·기본 승인의 범위는 `SESSION`입니다. `PROJECT`, `GLOBAL` 승격은 명시적 명령만 허용합니다.
후보는 주입하지 않으며 오래된 `expectedVersion`으로 덮어쓸 수 없습니다.
롤백은 이전 승인 내용으로 새 버전을 추가하며 현재 범위를 몰래 넓히지 않습니다.
과거 승인 교훈은 원본 소스가 바뀐 뒤에도 역사적 근거와 함께 유지됩니다.

저장은 `${agent.home}/lessons`의 비공개 원자적 스냅샷입니다.
최대 128개 교훈·각 32개 버전·본문 4,000자이며 요청마다 관련 상위 5개를 합계 4,096자 안에서 선택합니다.
현재 캡처된 작업 경로·대화로 범위를 결정하고 기본 system 메시지를 보존합니다.
주입은 지침·승인·도구 권한을 대체하지 않는 참고 자료이며 기존 문맥 예산도 적용됩니다.
AGENTS.md를 자동 수정하지 않습니다. `--agent.lessons.enabled=false`로 끌 수 있습니다.

## 터미널과 독립된 목표 worker

worker는 별도 Java 프로세스에서 저장된 목표를 실행합니다.
현재 기동 설정은 공식 Codex CLI OAuth 경로를 지원하며 다른 모델 선택·인증 방식은
조용히 바꾸지 않고 기동을 거부합니다. 예를 들어 검증과 같은 설정으로 시작하려면:

```bash
AGENT_PROFILE=codex AGENT_UI=repl bash ./agent.sh \
  --agent.models.default=codex/gpt-6.1-sol \
  --agent.codex.model=gpt-6.1-sol \
  --agent.codex.reasoning-effort=high --agent.codex.auth=codex-cli
```

```text
/worker start
/worker status
/goal submit {"goal":"도구 없이 응답하라. 완료는 호스트가 확인한다.","gates":[{"argv":["/usr/bin/true"],"timeoutMillis":30000,"maxOutputBytes":4096}]}
/worker wakeup
/worker resume <goal-id>
/worker stop
```

`start`는 같은 작업 경로·상태 홈의 기존 worker가 있으면 재사용합니다.
부모 터미널 종료는 worker를 끄지 않습니다. 다시 같은 설정으로 실행해 `status`와
`/goal show`로 관찰할 수 있습니다. stdin은 분리되며 일반 출력·오류 스트림은 보관하지 않습니다.
토큰이나 API 키를 프로세스 인자·메타데이터로 전달하지 않습니다.

비공개 `${agent.home}/workers`에 상태와 제어 요청을 저장합니다.
단일 소유권은 PID가 아니라 안정된 파일 잠금이며 제어는 정확한 instance UUID에 결합됩니다.
WatchService 이벤트 후 권위 있는 GoalStore를 읽고 현재 작업 경로의 목표만 처리합니다.
`stop`은 현재 목표를 정리한 뒤 종료하며 새 목표를 받지 않습니다.
명령 도착만으로 실행 성공·승인을 뜻하지 않습니다.

재시작은 대기 목표를 발견하고 기존 run ID를 유지합니다.
승인 대기와 불확실한 모델·도구 효과는 자동 재시도하지 않습니다.
`resume`에는 `/run resume`의 명시적 해결 옵션을 전달할 수 있습니다.
worker의 비대화형 승인은 자동 허용되지 않고 기존 승인 절차를 따릅니다.
원래 모델·옵션·누적 예산과 stage·검증·적용 경계는 GoalService가 유지합니다.
현재 프로세스 분리는 POSIX 파일 권한과 `/usr/bin/nohup`을 요구합니다.

## 모델 선택과 관리 실행

`/models`로 제공자별 모델과 인증 상태를 확인하고 `/model <provider/model>`로
다음 턴의 모델을 선택합니다. 이미 시작한 도구 왕복과 자식 실행의 모델 바인딩은
바뀌지 않습니다. `/login [provider]`, `/logout [provider]`는 선택한 제공자의
인증을 사용합니다. 기존 `AGENT_PROFILE`과 `/codex`, `/claude` 명령도 유지합니다.

일반 입력은 기존 즉시 모드입니다. 변경을 먼저 검토하려면 관리 실행을 시작합니다.

```text
/run start !tool write_file {"path":"example.txt","content":"proposal"}
/runs
/run show <run-id>
/changes
/apply <change-set-id>
/verify <change-set-id> ["/usr/bin/grep","-q","proposal","example.txt"]
/undo <change-set-id>
```

관리 실행은 실제 소스 대신 소유자별 stage를 변경합니다. 적용에는 정확한 diff
승인과 콘솔의 `exclusive <change-set-id> <digest>` 확인이 필요합니다. 비대화형
미응답은 pending이며 자동 승인하지 않습니다. 사람이 바꾼 소스는 충돌로 보존합니다.
검증은 필터링된 읽기 전용 소스 clone에서 실제 프로세스를 실행하고 argv, exit,
log, 소스·설정 digest를 저장합니다. 지원되는 sandbox가 없으면 변이 shell과
검증 프로세스를 거부합니다.

기본 관리 예산은 활성 시간 30분, 도구 50회, token cap 미설정입니다. 승인·queue
대기는 활성 시간에서 제외합니다. 알 수 없는 사용량과 이미 발생한 초과 사용량은
0으로 바꾸지 않습니다. `/run resume`은 명시 재개이며 UNKNOWN 효과의 retry/skip과
drift 승인은 `/run` 도움말의 정확한 식별자·digest를 사용해야 합니다.

## 독립 자식 위임

모델의 `delegate` 도구와 콘솔의 `/delegate start <JSON>`은 같은 Java 실행기를
사용합니다. 다음은 fake 제공자로 실제 파일을 읽는 독립 자식의 예입니다.

```text
/delegate start {"planId":"read","children":[{"childId":"research","goal":"!tool read_file {\"path\":\"README.md\"}","role":"RESEARCH","tools":["read_file"],"wallSeconds":60,"toolCallCap":5}]}
/delegate list
/delegate show read
/delegate cancel read
```

자식은 `RESEARCH`, `IMPLEMENT`, `VERIFY` 중 하나이며 `dependsOn`으로 앞선 자식을
지정합니다. `model`은 선택 사항인 `provider/model`입니다. IMPLEMENT의 기본 쓰기
범위는 현재 workspace이며, 명시한 `writeRoots`는 부모 범위를 넓힐 수 없습니다.
VERIFY는 `verify_staged`로 실제 IMPLEMENT 의존성의 stage를 자신의 clone에서
검증합니다. 부모 소스 변경, apply와 추가 위임은 자식에게 허용하지 않습니다.
기본 동시 실행은 2개, queue는 8개, 깊이는 1입니다.

모델의 완료 문장은 검증 증거가 아닙니다. 부모는 실제 읽기·소스, stage 제안과
실제 검증 프로세스의 소유자·digest·현재 상태를 다시 확인해야 `COMPLETE`를 냅니다.
부모의 적용은 `/delegate apply <plan> <implement-child> <proposal UUID>`이며 동일한
공용 승인과 exclusive 확인을 사용합니다.

보류된 질문은 `/delegate approve|reject|defer <plan> <request UUID>`로 해소하고,
`/delegate resume <plan> <child>`로 따로 재개합니다. 프로세스 재시작 뒤에는
`/delegate recover <child-run> [run resume options] -- <정확한 단일 자식 plan JSON>`을
사용합니다. 원래 대화·부모 run·목표·모델·역할·범위가 맞아야 같은 자식을 재개할 수
있으며, 저장된 exit 0이나 문장만으로 이전 검증을 복원하지 않습니다.

## 범위가 제한된 자식 메시징

`send_message`와 `read_messages`는 실제 관리 부모·계획·자식에 결합됩니다.
sender는 캡처된 실행에서 호스트가 정하며 모델 인자로 받지 않습니다.
관리 부모 안에서 `delegate`로 자식을 완료한 뒤 다음 도구 형식을 사용할 수 있습니다.

```json
{"plan":"plan-id","child":"child-id","offset":0}
```

위 입력으로 `read_messages`의 현재 version을 얻고, `send_message`에는
`plan,child,id,version,body`를 전달합니다. `id`는 재시도에도 같은 값을 사용합니다.
동일 ID에 다른 본문을 보내거나 오래된 version을 사용하면 거부합니다.
다른 부모·계획·자식의 메시지를 읽거나 전송할 수 없습니다.

후속 실행은 완료된 run을 다시 열지 않습니다. 자식 대화·선택 모델·옵션·고정 도구 범위를
유지하면서 새 run을 만들고, 이전 자식 사용량과 후속 사용량을 같은 부모 저널에 누적합니다.
부모 중단·재개 이후에도 총량을 유지하며 완료된 부모를 메시지로 부활시키지 않습니다.
현재 RESEARCH·VERIFY 후속 실행을 지원합니다. IMPLEMENT의 미적용 stage를 잃는
새 실행은 명시적으로 거부하며, 기존 제안을 자동 적용하거나 권한을 전이하지 않습니다.
자식이 보내는 응답은 부모용 큐에 보관하며 부모 모델을 자동 호출하지 않습니다.

현재 활성 관리 작업을 제어하는 호스트는 `/message read <plan> <child> [offset]`,
`/message send <plan> <child> <id> <version> <body>`도 사용할 수 있습니다.
일반 RPC의 busy 명령 제한은 유지됩니다. 모델 전송은 활성 부모의 도구 루프에서 수행합니다.
종료·재시작 후 운영자는 원래 대화를 `/resume`한 뒤 다음 읽기 전용 명령을 사용합니다.

```text
/message archive <parent-run-id> <plan-id> <child-id> [offset]
```

archive는 원래 대화와 작업 경로를 실제 부모 run에서 확인하며 새 실행을 만들지 않습니다.
영수증은 `${agent.home}/messages`에 보관합니다. 최대 128개 thread·각 128개 항목,
본문 4,096자·한 페이지 16개이며 응답 preview의 잘림은 `responseTruncated`로 표시합니다.
`DELIVERED`는 모델 실행 종료를 뜻하며 변경 적용·검증 성공을 뜻하지 않습니다.
유실된 RUNNING claim은 UNCERTAIN으로 표시하고 자동 실행하지 않습니다.
최소 한 번 제출과 ID별 중복 방지 계약이지 외부 효과의 exactly-once 보장은 아닙니다.
schema 1은 응답 잘림 여부가 불명확하므로 자동 이전하지 않고 거부합니다.
`--agent.messaging.enabled=false`로 끌 수 있습니다.

## 사전 준비

JDK 21과 Maven은 Homebrew로 설치되어 있습니다(`brew install openjdk@21 maven`, 되돌리려면 `brew uninstall openjdk@21 maven`). `openjdk@21`은 PATH에 자동으로 잡히지 않습니다. `bash ./agent.sh`는 알아서 찾지만 `./mvnw`를 직접 쓰려면 아래 한 줄이 필요합니다.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home   # ~/.zshrc 에 추가 권장
```

## 1분 안에 실행하기

```bash
bash ./agent.sh
```

처음 실행하면 빌드부터 합니다(약 1분). 이후에도 `src/` 아래 파일이나 `pom.xml`이 jar보다 새로우면 실행 전에 자동으로 다시 빌드합니다(테스트는 생략).

전체 화면 TUI는 대화를 전체 폭으로 보여 줍니다. `Ctrl+T`로 Trace를 열면
100열 이상에서는 오른쪽 상세 패널, 그보다 좁으면 대화 대신 Trace를 보여 줍니다.
다시 `Ctrl+T`로 대화로 돌아옵니다. 아래는 기존 Codex 실행의 Trace 표시 예시입니다.
Trace를 닫아도 현재 턴의 최근 도구 3개와 실제 대기·결과·오류 상태를 대화 하단에 요약합니다.
인자와 전체 결과는 이 요약에 넣지 않으며, `Ctrl+T`로 상세 내용을 확인합니다.

```text
╭ Trace · 2턴 ─────────────────────────╮
│ #1 ✓ 도구 0회 · 2.8초                │   ← 지난 턴은 두 줄 요약
│    in 507 · cache 0 · out 14         │
│ ──────────────────────────────────── │
│ #2 gpt-6-luna · low                  │   ← 마지막 턴: 모델 · reasoning effort
│ ╭───────────────────────────────────╮│
│ │ 사용자 입력                       ││
│ │ list_dir 도구로 plugins 폴더를 보 ││
│ │ 고 플러그인 개수만 한 줄로 답해   ││
│ ╰─────────────────┬─────────────────╯│
│ ╭─────────────────▼─────────────────╮│
│ │ AI 출력                     1.8초 ││   ← 도구를 요청한 모델 응답
│ │ 도구 호출 1개                     ││
│ │ in 550 · cache 0 · out 18         ││
│ ╰─────────────────┬─────────────────╯│
│ ╭─────────────────▼─────────────────╮│
│ │ ⚙ list_dir                   12ms ││   ← Tool 입/출력
│ │ 입력 {"path":"plugins"}           ││
│ │ 출력 160자                        ││
│ ╰─────────────────┬─────────────────╯│
│ ╭─────────────────▼─────────────────╮│
│ │ AI 답변 ✓                   2.7초 ││   ← 최종 답변
│ │ 13자                              ││
│ │ in 631 · cache 0 · out 13         ││
│ ╰───────────────────────────────────╯│
│ 도구 1회 · ✓ 답변 · 4.5초            │   ← 이 턴 합계
│ in 1,181 · cache 0 · out 31          │
│ ──────────────────────────────────── │
│ 세션 2턴 · 도구 1회                  │   ← 세션 합계
│ in 1,688 · cache 0 · out 45          │
╰──────────────────────────────────────╯
```

- **노드는 네 종류:** 사용자 입력 → AI 출력(도구 요청) → Tool 입/출력 → AI 답변. 도구를 여러 번 오가면 AI 출력과 Tool 노드가 그만큼 이어집니다.
- **토큰:** AI 노드마다 그 모델 호출 한 번의 토큰이, 턴과 세션 합계에는 누적값이 나옵니다. `cache`는 `in` 중 프롬프트 캐시에 맞은 양입니다(따로 더하지 않음). OpenAI 계열은 프롬프트가 1,024토큰 이상이어야 캐시가 걸립니다.
- **상태:** 진행 중인 노드는 "기다리는 중…", 권한이 거부되어 실행되지 않은 도구는 "실행 안 됨 (거부)", 실행 중 예외가 난 도구는 "오류 …", 취소된 도구는 "취소됨", 오류로 끊긴 턴은 "⚠ 중단"으로 표시됩니다. REPL 요약 줄에서도 `도구 read_file (오류)`처럼 구분됩니다.
- **접기:** 공간이 모자라면 지난 턴 요약부터 `⋮ 이전 n턴`으로, 그래도 모자라면 그래프 앞쪽 노드를 `⋮ 앞 노드 n개`로 접습니다.
- **reasoning effort:** provider가 응답 메타데이터에 넣어 줄 때만 보입니다(지금은 codex 플러그인).

입력창에 아래를 차례로 입력해 보세요(기본 provider인 가짜 모델 기준).

```text
안녕                                  ← 가짜 모델이 "자기가 받은 요청"을 그대로 보여줌
!tool list_dir {"path":"."}           ← 사이드바에 사용자 입력 → AI 출력 → ⚙ list_dir → AI 답변 그래프
!tool bash {"command":"ls"}           ← 권한 플러그인이 묻고, 입력창이 "답변" 모드로 바뀜 (y/n/a)
/advisors                             ← 어드바이저 체인과 도구 루프 위치
/help                                 ← 전체 명령
```


| 키               | 동작                              |
| --------------- | ------------------------------- |
| Enter           | 전송 (권한 질문 중이면 답변)               |
| Ctrl+N          | 입력창 안에서 줄바꿈                       |
| ↑ ↓             | 입력창의 시각적 줄 이동. 첫 줄 위/마지막 줄 아래에서 입력 이력 탐색 |
| Alt+↑ / Alt+↓   | 입력 이력 탐색. 다시 끝으로 내려오면 작성 중이던 초안 복원 |
| Home / End      | 현재 줄 시작/끝. Ctrl과 함께 누르면 전체 입력 시작/끝 |
| PgUp/PgDn, 휠    | 대화 스크롤                          |
| Tab             | `/명령` 첫 단어 완성·후보 순환. 완성 대상이 없으면 Trace 전환 |
| Ctrl+T          | 입력 내용과 무관하게 Trace 전환              |
| Esc             | 입력 지우기. 입력창이 비어 있고 작업 중이면 작업 취소 |
| Ctrl+C, `/exit` | 종료                              |


- 마우스 휠을 쓰려고 마우스 입력을 받기 때문에, 화면의 글자를 드래그로 선택하려면 iTerm2에서 Option 키를 누른 채 드래그하세요.
- 여러 줄 붙여넣기는 줄바꿈을 보존하며 자동 전송하지 않습니다. 입력창은 화면 높이의 1/3,
  최대 8행까지 늘어나고 긴 입력에서는 커서 위치로 스크롤합니다. 한글 표시 폭과 IME용 커서를 유지합니다.
- 단말이 modifier를 전달하면 Alt+Enter·Shift+Enter도 줄바꿈입니다. 전달하지 않는 단말에서는
  `Ctrl+N`을 사용하세요. 승인 답변은 기존의 정확한 질문·승인 UUID에 계속 결합됩니다.
- 30열 × 10행보다 작은 화면에는 크기 안내를 표시하며 초안을 버리지 않습니다.
- TUI가 떠 있는 동안 로그는 화면 대신 `${agent.home}/logs/tui.log`에 쌓입니다.
- 줄 단위 화면을 원하면 `AGENT_UI=repl bash ./agent.sh`. 입력을 파이프로 넣으면(터미널이 아니면) 자동으로 REPL로 동작합니다. REPL에서는 트레이스가 대화 사이에 한 줄로 나오고 `/trace detail`로 라운드별 트리를 볼 수 있습니다.

한 번만 실행하고 끝내려면 `bash ./agent.sh "질문"` 처럼 인자를 주면 됩니다(pi의 print 모드). 이 모드에서는 확인할 사람이 없으므로 확인 없이 허용된 도구(`read_file`, `list_dir`, `load_skill`, `search_content`, `glob_paths`) 외에는 모두 거부됩니다. 허용하려면 `--agent.plugins.permission.allow-non-interactive=true`를 덧붙이세요. 실행이 실패하면(오류, 알 수 없는 명령) 종료 코드 1로 끝나므로 스크립트에서 `$?`로 확인할 수 있습니다.

## 진짜 모델로 바꾸기

1. `cp .env.example .env`
2. `.env`에 키를 채움
3. 프로필을 골라 실행


| 실행                                            | 필요한 것                                   | 기본 모델                          |
| --------------------------------------------- | --------------------------------------- | ------------------------------ |
| `bash ./agent.sh`                             | 없음                                      | fake (가짜)                      |
| `AGENT_PROFILE=codex bash ./agent.sh`         | ChatGPT 구독 + Codex CLI 로그인 또는 `/login`  | `gpt-6-luna` (reasoning xhigh) |
| `AGENT_PROFILE=claude bash ./agent.sh`        | Claude 구독 + 앱의 `/login` (Java OAuth)    | `claude-sonnet-5-5`            |
| `AGENT_PROFILE=anthropic bash ./agent.sh`     | `ANTHROPIC_API_KEY`                     | `claude-sonnet-5-5`            |
| `AGENT_PROFILE=openai bash ./agent.sh`        | `OPENAI_API_KEY`                        | `gpt-5-mini`                   |
| `AGENT_PROFILE=zai bash ./agent.sh`           | `ZAI_API_KEY`                           | `glm-5.3`                      |
| `AGENT_PROFILE=ollama bash ./agent.sh`        | `ollama serve` + `ollama pull qwen3:8b` | `qwen3:8b`                     |
| `AGENT_PROFILE=anthropic,mcp bash ./agent.sh` | 위 + Node.js(npx)                        | MCP 데모 서버 도구 13개 추가            |


모델 이름은 `.env`의 `CODEX_MODEL`, `CLAUDE_MODEL`, `ZAI_MODEL`, `ANTHROPIC_MODEL`, `OPENAI_MODEL`, `OLLAMA_MODEL`로 바꿀 수 있습니다.

### Z.ai (공식 OpenAI 호환 모델 재사용)

`.env`에 `ZAI_API_KEY`를 넣고 실행한다. OpenAI 키와는 별도다.

```bash
AGENT_PROFILE=zai bash ./agent.sh "안녕"
```

Z.ai 는 OpenAI Chat Completions 호환 API 를 제공하므로 기존 Spring AI `OpenAiChatModel`을 재사용한다.
추가 모델 플러그인이나 OAuth 구현은 없다. 따라서 배너의 내부 모델 종류는 `openai`로 표시된다.
기본 모델은 `glm-5.3`, 일반 API 기본 URL 은 `https://api.z.ai/api/paas/v4`다.

GLM Coding Plan 을 사용하는 경우 키의 상품에 맞춰 URL 을 바꾼다.

```bash
ZAI_BASE_URL=https://api.z.ai/api/coding/paas/v4 AGENT_PROFILE=zai bash ./agent.sh "안녕"
```

모델은 `ZAI_MODEL`, 추론 강도는 `ZAI_REASONING_EFFORT`로 지정한다(기본 `low`).
GLM-5.3 은 추론을 비활성화할 수 없어 `thinking.type=enabled`를 사용한다.
Spring AI 2.0.1 공식 OpenAI 모델이 `reasoning_content`를 읽고 도구 후속 요청에 재전송한다.
JSONL 메모리도 모델의 문자열 메타데이터를 보존하므로 루프 안쪽 메모리 설정에서도 추론 상태가 유지된다.

Spring AI 의 [공식 다중 모델 구성](https://docs.spring.io/spring-ai/reference/api/chatclient.html#_working_with_multiple_chat_models)은
여러 `ChatModel`과 모델별 `ChatClient`를 명시적으로 구성하는 방식이다.
현재 agent-forge 는 프로필로 한 모델을 선택한다. 이와 동시 다중 모델 호출·실행 중 제공자 전환은 별개다.
Z.ai 에는 [OpenAI 호환 API](https://docs.z.ai/guides/develop/openai/python) 구성을 적용한다.

### Claude 구독으로 쓰기 (Java OAuth)

```bash
AGENT_PROFILE=claude bash ./agent.sh
# REPL 에서 /login
# 출력된 URL 을 브라우저에서 열고 Claude Pro/Max 계정으로 로그인한다.
# 완료 페이지의 code#state 를 앱에 붙여 넣는다.
# 이후 질문을 입력하거나 아래처럼 한 번 실행한다.
AGENT_PROFILE=claude bash ./agent.sh "안녕"
```

`claude` 프로필은 `anthropic` API 키 프로필과 별개다. JDK 21 만으로 실행하며,
senpi·Bun·Claude Code 설치나 외부 토큰 파일을 사용하지 않는다. 모델은 `CLAUDE_MODEL`로 바꾼다.
senpi 구현은 OAuth 요청 필드와 구독 Messages 프로토콜의 참고 자료로만 사용했다.

`ClaudeOAuth`가 PKCE 로그인·상태 검증·토큰 응답 해석을, `ClaudeAuth`가 비공개 저장과 만료 전
갱신을 담당한다. 토큰은 `${agent.home}/auth/claude.json`에 생성 시점부터 `0600`인 임시 파일로
쓴 뒤 원자적으로 교체한다. 기본 `agent.home`은 `~/.mini-agent`다.
같은 저장소를 사용하는 갱신은 JVM 내 동기화와 파일 잠금으로 조정한다.
`/logout`은 이 앱의 Claude 토큰만 삭제하며, `/claude`는 모델과 토큰 저장 경로를 보여준다.

`ClaudeChatModel → ClaudeMessagesClient → Anthropic Messages API`가 전부 Java 에서 실행된다.
구독 Bearer 헤더, CLI 식별 시스템 블록, 도구 이름 변환과 SSE 파싱도 Java 가 처리한다.
도구 JSON 은 블록 종료와 전체 응답 완료를 확인한 뒤에만 Spring AI 에 넘긴다.
파일·셸·MCP 도구 실행, 권한 확인, 메모리와 Trace 는 기존 `ToolCallingAdvisor`에 남긴다.
API 키로 자동 전환하지 않는다.

OAuth 요청 기한은 `agent.claude.oauth-timeout`(기본 30초), 모델 요청의 본문 완료 기한은
`agent.claude.timeout`(기본 120초)다. 시간 초과·Esc 취소 시 HTTP 요청을 취소한다.
`max-response-bytes` 기본값은 8MB다. 텍스트 delta는 실시간으로 표시하고,
도구 호출·사용량·추론 재전송 상태는 전체 SSE 검증이 끝난 뒤 전달한다.
구독 프로토콜의 CLI 버전 식별자는 `CLAUDE_CLI_VERSION`으로 지정한다(기본 `2.1.289`).
서버가 버전 식별자를 거부하면 지원되는 버전으로 명시적으로 갱신한다.

```bash
# OAuth·Messages 테스트는 Java 로컬 HTTP 서버를 사용한다. 실제 계정은 필요 없다.
./mvnw test
```

API 키 프로필에서 키를 안 넣으면 앱은 뜨지만 첫 질문에서 `오류: UnauthorizedException: 401 ... x-api-key header is required`가 납니다. 이 경우 `.env`를 확인하세요.

## ChatGPT 구독으로 쓰기 (Codex OAuth)

API 키 대신 ChatGPT 플랜(Plus/Pro 등)으로 모델을 씁니다. pi의 `/login`과 같은 방식입니다.

```bash
AGENT_PROFILE=codex bash ./agent.sh
```

**토큰은 두 곳 중 하나에서 가져옵니다** (`agent.codex.auth=auto`)


| 순서  | 출처                                     | 만드는 법                                     | 갱신                |
| --- | -------------------------------------- | ----------------------------------------- | ----------------- |
| 1   | `~/.mini-agent/auth/openai-codex.json` | REPL에서 `/login` (브라우저) 또는 `/login device` | 만료 5분 전 자동 갱신     |
| 2   | `~/.codex/auth.json` (Codex CLI)       | `codex login`                             | **하지 않음** (읽기 전용) |


Codex CLI 토큰을 갱신하지 않는 이유: OpenAI의 refresh token은 쓸 때마다 새것으로 바뀝니다. agent-forge가 Codex CLI의 refresh token으로 갱신하면 Codex CLI에 저장된 토큰이 무효가 되어 Codex CLI가 로그아웃됩니다. Codex CLI 토큰이 만료되면 `codex`를 한 번 실행하거나(Codex CLI가 갱신) `/login`으로 자체 토큰을 받으세요.

**명령**


| 명령                                   | 하는 일                                                                     |
| ------------------------------------ | ------------------------------------------------------------------------ |
| `/codex`                             | 모델, reasoning effort, 두 토큰의 상태와 만료까지 남은 시간                               |
| `/codex models`                      | 쓸 수 있는 모델에 번호를 붙여 보여줌. `*`가 현재 모델 (Codex CLI의 `models_cache.json`을 읽음)   |
| `/codex model`                       | 번호 목록에서 골라 바꾸기. `/codex model 4`나 `/codex model gpt-6.1-sol`처럼 바로 지정해도 됨 |
| `/codex effort`                      | 현재 모델이 지원하는 reasoning effort를 보여주고 고르기. `/codex effort low`처럼 바로 지정해도 됨  |
| `/login`, `/login device`, `/logout` | 자체 토큰 받기/지우기 (Codex CLI 로그인은 건드리지 않음)                                    |
| `/codex refresh`                     | 자체 토큰을 지금 바로 갱신 (만료 5분 전이면 자동으로도 갱신됨)                                    |


`/login`은 기본 브라우저를 자동으로 엽니다. 원격 터미널처럼 브라우저를 열 수 없으면 `--agent.codex.open-browser=false`로 URL만 출력하거나 `/login device`를 쓰세요.

목록에 없는 모델은 고를 수 없습니다(백엔드가 400으로 거부함). 모델을 바꿀 때 현재 effort를 새 모델이 지원하지 않으면(예: `ultra` → `gpt-6-luna`) 그 모델의 기본 effort로 자동으로 맞춥니다. 시작할 때 쓸 값은 `.env`의 `CODEX_MODEL`, `CODEX_REASONING_EFFORT`로 정합니다. `xhigh`는 답이 느리고 플랜 사용량을 더 쓰므로, 가볍게 쓸 때는 `/codex effort low`로 낮추세요.

**알아둘 점**

- 호출 대상은 `https://chatgpt.com/backend-api/codex/responses`입니다. 공개 문서가 없는 ChatGPT 내부 API라 바뀔 수 있습니다.
- 사용량은 API 크레딧이 아니라 ChatGPT 플랜 한도에서 차감됩니다. 한도에 걸리면 `Codex 429 (사용 한도)`와 초기화까지 남은 시간이 표시됩니다.
- 요청 헤더의 `originator`는 `mini-agent`입니다. 다른 클라이언트 이름을 쓰지 않습니다.
- 응답이 `agent.codex.idle-timeout`(기본 5분) 동안 한 줄도 오지 않으면 호출을 끊습니다. 추론이 아주 긴 모델에서 끊기면 이 값을 늘리세요. TUI에서는 Esc로 바로 취소할 수 있습니다. 2026-10-02에 `gpt-6-luna`(xhigh)로 재 보니 9초짜리 추론 중에도 3초 넘게 이벤트가 오지 않는 구간이 있었습니다. 추론이 길수록 이런 구간도 길어질 수 있어 기본값을 넉넉하게 둡니다.

**Spring AI 관점에서:** 2.0.1 GA의 OpenAI 모듈은 Chat Completions API만 지원하고(Responses API는 2.1.0-M1부터), ChatGPT 백엔드는 Responses API만 받습니다. 그래서 `plugin-codex-model`은 `ChatModel`을 직접 구현합니다. 구조는 가짜 모델과 같고, 도구 루프는 여전히 `ToolCallingAdvisor`가 돕니다. 변환 규칙은 `CodexResponses.java` 맨 위 표에 있습니다.

---

## 구조

```text
.
├── agent-core/                 원형. Agent 1개 + 플러그인 계약(SPI) + 기본 명령
├── plugins/
│   ├── plugin-fake-model/      ChatModel 직접 구현 (provider 플러그인)
│   ├── plugin-codex-model/     ChatGPT 구독(Codex OAuth) + Responses API      (ChatModel 직접 구현)
│   ├── plugin-claude-model/    Claude 구독 OAuth + Messages API           (Java ChatModel 직접 구현)
│   ├── plugin-fs/              read_file · write_file · edit_file · list_dir   (@Tool)
│   ├── plugin-bash/            bash                                          (FunctionToolCallback)
│   ├── plugin-permission/      허용 목록 밖 도구 실행 전 확인                  (ToolInterceptor)
│   ├── plugin-memory/          JSONL 파일 세션 + /sessions /resume           (ChatMemory + Advisor)
│   ├── plugin-trace/           턴 / 라운드 / 도구 실행 트레이스               (루프 바깥·안쪽 Advisor)
│   ├── plugin-skills/          SKILL.md 스킬 + load_skill                     (SystemPromptContributor + 도구)
│   ├── plugin-mcp/             MCP 서버 도구 연결                             (spring-ai-starter-mcp-client)
│   └── plugin-tui/             전체 화면 TUI + Trace 그래프 사이드바           (InteractiveFrontEnd + TraceEvent, TamboUI 0.5)
└── agent-app/                  REPL + provider 프로필. 어떤 플러그인을 쓸지는 이 pom 이 정함
```

코어(`agent-core`)에는 도구가 하나도 없습니다. 플러그인을 모두 빼면 대화만 하는 에이전트가 됩니다.

## pi와 Spring AI 대응표


| pi                                  | 이 프로젝트                                   | Spring AI 타입                                                     |
| ----------------------------------- | ---------------------------------------- | ---------------------------------------------------------------- |
| 에이전트 루프                             | 코어에 없음. ChatClient가 자동 등록하는 어드바이저가 돔     | `ToolCallingAdvisor` (2.0에서 생김)                                  |
| `registerTool`                      | 도구 빈 등록                                  | `ToolCallbackProvider`, `ToolCallback`, `@Tool`                  |
| `before_agent_start`, `context` 이벤트 | 루프 **바깥** 어드바이저 (order &lt; HIGHEST+300) | `CallAdvisor` / `BaseAdvisor`                                    |
| 모델 호출마다 실행되는 훅                      | 루프 **안쪽** 어드바이저 (order &gt; HIGHEST+300) | `CallAdvisor`                                                    |
| `tool_call`, `tool_result` 이벤트      | `ToolInterceptor` (코어 SPI)               | `ToolCallback` 데코레이터                                             |
| 세션 (JSONL)                          | `plugin-memory`                          | `ChatMemory`, `ChatMemoryRepository`, `MessageChatMemoryAdvisor` |
| context files, skill 목록             | `SystemPromptContributor` (코어 SPI)       | `ChatClient.Builder.defaultSystem`                               |
| `registerProvider`                  | provider starter + 프로필                   | `ChatModel`, `spring.ai.model.chat`                              |
| `registerMcpServer`                 | `application-mcp.yml`                    | MCP client starter → `SyncMcpToolCallbackProvider`               |
| `registerCommand`                   | `SlashCommand` (코어 SPI)                  | 없음 (이 프로젝트의 REPL 개념)                                             |
| extension 로딩                        | Maven 의존성 + `AutoConfiguration.imports`  | Spring Boot auto-configuration                                   |


## 요청 한 번이 흐르는 길

`/advisors`가 보여주는 체인입니다. 요청은 위에서 아래로, 응답은 아래에서 위로 흐릅니다.

```text
Agent.run("…")  →  ChatClient.prompt().user(…).call()
│
├─ HIGHEST+100  TurnTraceAdvisor          루프 바깥: 사용자 입력당 1번
├─ HIGHEST+200  MessageChatMemoryAdvisor  루프 바깥: 지난 대화를 붙이고, 끝나면 user/answer 저장
├─ HIGHEST+300  ToolCallingAdvisor        ┐ Spring AI가 자동 등록. 여기서 루프가 돈다:
│                                          │   do { 아래 체인 호출 → 응답에 tool call 있으면
│                                          │        ToolCallingManager 가 도구 실행 → 결과 붙여서 다시 }
├─ HIGHEST+400  RoundTraceAdvisor          │ 루프 안쪽: 모델 호출(라운드)마다 실행
└─ LOWEST       ChatModelCallAdvisor       ┘ 실제 ChatModel.call()
                     │
                     └─ 도구 실행: ConfirmToolInterceptor → ToolTraceInterceptor → 실제 도구
```

어드바이저를 HIGHEST+300보다 바깥에 둘지 안쪽에 둘지가 2.0에서 가장 중요한 설계 결정입니다. 메모리를 안쪽으로 옮기는 실험은 `docs/learning-path.md` 3단계에 있습니다.

## 플러그인 만드는 법

1. 모듈을 만듭니다. `plugins/plugin-xxx/pom.xml`에서 `agent-core`를 의존합니다. 기존 플러그인 pom을 복사해도 됩니다.
2. 자동 구성 클래스를 만들고 확장점 타입의 빈을 등록합니다.
   ```java
    @AutoConfiguration
    @ConditionalOnBooleanProperty(name = "agent.plugins.xxx.enabled", matchIfMissing = true)
    public class XxxPluginAutoConfiguration {
        @Bean ToolCallbackProvider xxxTools() { return MethodToolCallbackProvider.builder().toolObjects(new XxxTools()).build(); }
        @Bean AgentPlugin xxxPlugin() { return AgentPlugin.of("xxx", "설명"); }
    }
   ```
3. `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 클래스 이름을 한 줄로 적습니다.
4. 루트 `pom.xml`의 `<modules>`, `<dependencyManagement>`와 `agent-app/pom.xml`의 의존성에 추가합니다.

새 도구는 기본으로 실행 전 확인 대상입니다. 읽기만 하는 도구라면 `agent.plugins.permission.auto-allow-tools`에 이름을 추가하세요.

확장점은 일곱 가지입니다. 아무 조합이나 등록하면 코어가 `ObjectProvider`로 모읍니다.


| 확장점         | 등록할 빈 타입                                  | 예시                      |
| ----------- | ----------------------------------------- | ----------------------- |
| 도구          | `ToolCallbackProvider` 또는 `ToolCallback`  | fs, bash, skills        |
| 요청/응답 가로채기  | `Advisor`                                 | trace, memory           |
| 도구 실행 가로채기  | `ToolInterceptor`                         | permission, trace       |
| 시스템 프롬프트 섹션 | `SystemPromptContributor`                 | skills, 코어의 Environment |
| 명령          | `SlashCommand`                            | memory, skills, trace   |
| 루프 관찰       | `TraceEvent` 발행 / `@EventListener` 구독     | trace(발행), tui(구독)      |
| 화면          | `InteractiveFrontEnd` + `AgentConsole` 상속 | tui                     |


trace 플러그인은 루프를 관찰해 `TraceEvent`를 Spring 이벤트로 발행만 하고, 그리는 일은 하지 않습니다. REPL에서는 trace 플러그인의 콘솔 렌더러가 텍스트로, TUI에서는 tui 플러그인이 사이드바 그래프로 같은 이벤트를 그립니다. 두 플러그인은 서로를 모르고 코어의 이벤트 타입만 공유합니다.

## 2.0.1에서 직접 부딪힌 동작

이 프로젝트를 만들면서 2.0.1 소스로 확인한 내용입니다. 코드 주석에도 같은 설명이 있습니다.

1. **요청에 붙인 도구만 실행됩니다.** resolution fallback이 기본으로 꺼져 있습니다. 그래서 코어는 플러그인 도구를 전부 `defaultToolCallbacks(...)`로 붙입니다. (`AgentAutoConfiguration`)
2. **`ChatModel.getOptions()`가 `ToolCallingChatOptions`여야 도구 루프가 돕니다.** 평범한 `ChatOptions`면 도구가 붙지 않고 `ToolCallingAdvisor`도 그냥 통과합니다. (`ScriptedChatModel.getOptions`)
3. **`ChatModel.call()`은 도구를 실행하지 않습니다.** 루프는 `ChatClient`가 자동 등록하는 `ToolCallingAdvisor`가 돕니다. `ToolAdvisor`가 두 개 있으면 `IllegalStateException`이 납니다.
4. **메모리 어드바이저는 `ChatMemory.CONVERSATION_ID`가 없으면 예외를 던집니다.** 코어가 세션 ID를 매 요청에 넣습니다. (`Agent.run`)
5. **ToolProvider는 요청마다 다시 펼쳐집니다.** 그래서 MCP처럼 도구 목록이 바뀌는 provider는 미리 펼치지 말고 감싸기만 해야 합니다. (`InterceptingToolCallbackProvider`)
6. **도구가 문자열을 반환해도 모델에게는 JSON 문자열로 갑니다.** 기본 `DefaultToolCallResultConverter`가 결과를 직렬화하므로 `"줄1\n줄2"`처럼 따옴표와 이스케이프가 붙습니다. 트레이스에서 확인할 수 있습니다.
7. **도구 호출 한도가 있습니다.** 한 턴 기준으로 도구 하나당 40회, 전체 150회입니다. 속성은 `spring.ai.tools.limits.max-calls-per-tool-default`(전체 기본값)와 `max-calls-per-tool.<도구명>`(도구별 맵)입니다. 문서에 두 이름이 섞여 있는데 둘 다 실제로 존재합니다.

## 안전장치

에이전트는 실제 파일과 프로세스를 다룹니다. 실패하거나 중간에 끊겨도 파일이 반쯤 덮이거나 프로세스가 남지 않도록 다음을 지킵니다.


| 영역        | 동작                                                                                                                                                                                  | 설정 (기본값)                                                                                                                                       |
| --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 파일 경계     | 파일 도구는 작업 디렉터리와 `~/.mini-agent/skills` 안에서만 동작합니다. 심볼릭 링크는 실제 위치로 판단합니다. 로그인 토큰이 있는 `~/.mini-agent/auth`는 열지 않습니다.                                                                  | `agent.plugins.fs.allowed-roots` (쉼표로 구분한 절대 경로 추가)                                                                                            |
| 파일 쓰기     | 기존 파일은 같은 폴더의 임시 파일에 쓴 뒤 원자적으로 바꿉니다. CRLF 줄바꿈, 마지막 줄바꿈, 실행 권한이 그대로 남습니다.                                                                                                            |                                                                                                                                                |
| 되돌리기      | `write_file`·`edit_file`은 쓰기 전에 원본을 보관합니다. `/undo`는 직전 턴의 변경을 되돌리고 그 턴에 만든 파일은 지웁니다. 사본은 실행이 끝나면 지우므로 같은 실행 안에서만 됩니다. 대화 기록은 되돌리지 않습니다.                                             | `agent.plugins.fs.checkpoints` (true)                                                                                                          |
| 읽기 상한     | 상한보다 큰 파일은 읽지 않고 `bash`의 `head`, `sed -n`을 안내합니다. `read_file`은 요청한 줄만 메모리에 올립니다.                                                                                                    | `agent.plugins.fs.max-read-bytes` (10MB)                                                                                                       |
| 권한 | 허용 목록의 도구만 묻지 않고, 새 플러그인과 MCP 도구를 포함한 나머지는 실행 전에 묻습니다. `bash`의 `a`(항상)는 같은 명령에만 적용됩니다. | `agent.plugins.permission.auto-allow-tools` (`read_file,list_dir,load_skill,search_content,glob_paths`), `agent.plugins.permission.remember-exact-input` (`bash`) |
| bash      | 시간 초과·취소·출력 바이트 초과 시 도달 가능한 자식을 깊이 순서로 종료하고 셸을 정리합니다. stdout/stderr를 합친 원시 바이트를 고정 버퍼로 세고 앞부분만 메모리에 보관합니다. 디스크 출력 파일은 만들지 않습니다.                                                     | `agent.plugins.bash.timeout` (30s), `max-timeout` (300s), `max-output-chars` (20000), `max-output-bytes` (1048576); 모두 `agent.plugins.bash` 아래 |
| bash 샌드박스 | `workspace-write`로 켜면 macOS `sandbox-exec`로 bash를 감싸 작업 디렉터리·임시 폴더·지정한 경로 밖 쓰기를 OS 수준에서 막습니다(`Operation not permitted`). 읽기와 네트워크는 막지 않습니다. macOS가 아니면 시작할 때 실패합니다.                 | `agent.plugins.bash.sandbox` (`off`), `agent.plugins.bash.sandbox-writable-roots` (쉼표로 구분한 절대 경로)                                              |
| 실패 알림     | 한 번 실행 모드가 실패하면 종료 코드 1로 끝납니다.                                                                                                                                                      |                                                                                                                                                |
| 응답 대기     | Codex 응답이 멈추면 끊습니다. TUI에서는 입력창이 비어 있을 때 Esc로 작업을 취소합니다.                                                                                                                             | `agent.codex.idle-timeout` (5m)                                                                                                                |
| 세션 기록     | 전체 기록은 덧붙이기만 합니다. 모델 요청은 매 도구 라운드마다 system·요약·기록·현재 USER/도구 그룹을 모두 세어 제한합니다. 오래된 그룹부터 생략하고 필요하면 요약도 생략합니다. 필수 system/현재 그룹이 한도를 넘으면 모델 호출 전에 오류로 거부합니다. 쓰다 끊긴 마지막 줄은 다음 저장 때 잘라냅니다. | `agent.plugins.memory.max-messages` (100), `agent.plugins.memory.max-context-chars` (200000)                                                   |
| 대화 요약     | 오래된 앞부분을 별도 모델 요청으로 요약하고 `"type":"summary"` 줄을 덧붙입니다. 요청 전체와 저장할 요약 각각에 상한이 있습니다. 실패하면 이전 요약과 최근 부분으로 계속하되 취소/인터럽트는 전파합니다. 요약 호출은 트레이스와 토큰 합계에 잡히지 않고 한 줄 알림으로만 보입니다.               | `agent.plugins.memory.compaction` (true), `max-summary-request-chars` (32000), `max-summary-chars` (8000); 모두 `agent.plugins.memory` 아래        |


메모리 예산은 토큰 수가 아니라 UTF-16 길이의 근사치입니다. 메시지 본문, assistant 도구 이름·인자, 도구 응답 데이터를 세며 도구 스키마와 전송 형식의 부가 비용은 제외합니다. 요약 머리말과 생략 표시도 셉니다. 요약 요청 상한은 system 지침, 이전 요약, 기록, 모든 머리말과 생략 표시를 포함합니다. 기록은 상한 안에서 순차적으로 만들고 개별 값도 2000자까지 줄입니다. 모델의 요약 응답은 공백을 제거하고 빈 결과를 거부하며 저장 전에 `max-summary-chars`로 줄입니다. 기존 파일의 긴 요약도 사용할 때 줄이지만 원본 파일은 다시 쓰지 않습니다. 서로게이트 쌍은 중간에서 자르지 않습니다. 이 제한은 provider의 응답 메모리 할당이나 생성 토큰 수를 제한하지 않습니다.

USER부터 다음 USER 전까지의 assistant/도구 메시지가 한 그룹입니다. 저장된 기록에서는 가장 최근 그룹을 먼저 확보하고, 남은 예산에 요약 한 메시지가 들어가면 넣은 뒤 연속된 과거 그룹으로 넓힙니다. 들어가지 않는 그룹에서 멈추므로 더 오래된 턴으로 대체하지 않습니다. 최신 저장 그룹 자체가 너무 크거나 도구 ID가 잘못되면 통째로 생략합니다. 요약의 `covers`가 그룹 경계가 아니면 알림과 함께 무시합니다. 실제 요청에서는 현재 USER와 이어지는 모든 도구 호출·결과를 보존합니다. 현재 그룹은 자르거나 생략하지 않고 초과/잘못된 도구 ID를 오류로 거부합니다. 메모리의 루프 바깥/안쪽 배치 모두 플러그인의 라운드별 검사로 보호하며 코어 도구 루프는 그대로 사용합니다.

네 예산 설정은 양수여야 하고, `max-summary-request-chars`는 고정 지침·머리말·생략 표시와 작은 본문까지 담는 최소값 이상이어야 합니다(미달 시 속성 이름과 최소값으로 시작 오류). `max-context-chars`가 1처럼 작아도 유효하지만 기록은 비고 필수 메시지를 담을 수 없는 실행은 거부될 수 있습니다. `/history`는 저장된 기억의 창이며 system/현재 입력까지 더한 실제 모델 요청과 같지는 않습니다.

bash의 기본/최대 시간은 int로 표현 가능한 양의 정수 초이며 기본값은 최대값 이하여야 합니다. `timeoutSeconds`가 0 이하이거나 최대값보다 크면 실행 전에 거부하며 값을 잘라서 실행하지 않습니다. 두 출력 한도도 양수여야 합니다. 문자 한도는 UTF-16 접두부 길이로, 서로게이트 쌍은 중간에서 자르지 않습니다. 잘못된 UTF-8은 대체 문자가 될 수 있습니다. 바이트 한도와 정확히 같은 출력은 허용하고, 초과하면 `output limit exceeded` 상태와 유한한 잘림 표시를 반환합니다. 하나의 단조 시계 마감 시간이 프로세스 종료와 출력 EOF까지 포함합니다. 시간 초과·인터럽트·바이트 초과·읽기 실패 때 스트림 닫기와 출력 스레드 합류를 포함한 정리는 최대 5초이며 실패를 결과나 예외에 표시합니다. 인터럽트 상태는 보존합니다.

한계도 있습니다. 샌드박스는 기본으로 꺼져 있습니다. 켜면 `./mvnw`처럼 `~/.m2` 같은 홈 캐시에 쓰는 명령이 실패하므로, 그런 경로를 `sandbox-writable-roots`에 넣어야 합니다. 켜도 막는 것은 쓰기뿐이라 읽기와 네트워크는 열려 있고, macOS에서만 동작합니다. 이미 분리되거나 다른 부모로 넘어간 프로세스(`nohup`, `setsid` 등)는 도달 가능한 자식 스냅샷에 없으면 정리하지 못합니다. 프로세스 그룹 격리는 하지 않습니다. `/undo`는 bash로 바꾼 파일을 되돌리지 못합니다. 감독 없이 실행한다면 작업 폴더만 마운트한 컨테이너 안에서 실행하세요.

## 테스트

```bash
./mvnw test      # JAVA_HOME 필요 (사전 준비 참고)
```

기존 단위·회귀 테스트는 네트워크 없이 동작하며 API 키나 로그인이 필요 없습니다.
실제 구독 모델을 호출하는 검증은 별도로 명시적으로 켭니다.

```bash
AGENT_FORGE_LIVE_CODEX=true ./mvnw -pl agent-app -am test \
  -Dtest=PrimeLiveCodexTest -Dsurefire.failIfNoSpecifiedTests=false
```

이 통합 테스트는 Codex CLI OAuth를 읽기 전용으로 사용하고
`codex/gpt-6.1-sol`, reasoning `high`, 실제 ChatGPT 구독 endpoint를 고정합니다.
다른 모델·API 키·가짜 모델로 대체하지 않습니다.
실제 모델 응답의 model·reasoning 메타데이터와 사용량, 도구 결과 및 저장 상태를 함께 검사합니다.
임시 작업 폴더와 그 밖의 상태 홈을 사용하며 stage를 실제 프로젝트에 적용하지 않습니다.
로그인이 필요하고 구독 한도를 사용합니다. gate 프로세스 검증은 지원되는 OS 샌드박스를 요구합니다.
현재 작업의 수락 근거는 이 실호출 검증이며, 아래의 오프라인 테스트와 구분합니다.

- `agent-app/src/test`: 도구 루프, 권한, 메모리 배치, provider 격리, 종료 코드와 실호출 통합
- `agent-core/src/test`: 아티팩트, 목표, 실행 저널·예산·승인, stage·검증·복구, 자식 범위와 증거
- `plugin-codex-model/src/test`: Responses 변환, 401/429, 모델·effort, 읽기 전용 CLI 토큰과 전송 제한
- `plugin-tui/src/test`: 트레이스 그래프, 한글 너비, 작업 취소·터미널 복원과 이벤트 라우팅
- `plugin-fs/src/test`: 파일 형식·권한, 경로 경계·심볼릭 링크, 검색, 크기 제한과 `/undo`
- `plugin-bash/src/test`: 프로세스 정리, 출력 제한과 macOS 샌드박스
- `plugin-permission/src/test`: 허용 목록과 정확한 입력에 묶인 승인
- `plugin-memory/src/test`: 기록·분기·문맥 한도·요약과 자식 기억 격리
- `plugin-trace/src/test`: 도구 오류와 취소 이벤트

## 다음 단계

[docs/learning-path.md](docs/learning-path.md)에 8단계 학습 순서와 직접 만들어 볼 플러그인 과제가 있습니다.
