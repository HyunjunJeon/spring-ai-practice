# 학습 순서

단계마다 **해 보기 → 관찰 → 읽을 코드** 순서로 진행합니다. 1~5단계는 API 키 없이 할 수 있습니다.

| 단계 | 주제 | 시간 | 키 필요 |
|---|---|---|---|
| 1 | 모델이 실제로 받는 것 | 10분 | X |
| 2 | 어드바이저 체인과 도구 루프 | 20분 | X |
| 3 | 메모리를 루프 바깥/안쪽에 두기 | 15분 | X |
| 4 | 도구를 만드는 두 방식 | 15분 | X |
| 5 | 도구 실행 가로채기 | 10분 | X |
| 6 | 진짜 provider로 바꾸기 (Codex 구독 또는 API 키) | 20분 | O |
| 7 | MCP 서버 붙이기 | 10분 | X (npx 필요) |
| 8 | 플러그인 직접 만들기 | 1~3시간 | 선택 |

---

## 1. 모델이 실제로 받는 것

**해 보기**
```text
./agent.sh
안녕
/system
/tools -v
```

**관찰**
- 가짜 모델은 받은 메시지 목록과 도구 이름을 그대로 출력합니다.
- `/system`은 기본 프롬프트에 플러그인 섹션(Environment, Skills)이 합쳐진 결과입니다.
- `/tools -v`는 모델에게 가는 JSON 스키마입니다. `@ToolParam(description=...)`이 스키마의 `description`이 됩니다.

**읽을 코드**
- `ScriptedChatModel.java`: `ChatModel` 인터페이스는 `call(Prompt)` 하나만 구현하면 됩니다. `getOptions()` 주석을 꼭 읽으세요.
- `AgentAutoConfiguration.agent(...)`: 플러그인 빈을 모아 `ChatClient`를 만드는 곳입니다.

## 2. 어드바이저 체인과 도구 루프

**해 보기**
```text
/advisors
/trace detail
!tool list_dir {"path":"plugins"}
```

**관찰**
```text
┌ 턴 시작 — 입력 메시지 2개
│ [1] → 모델       메시지 2개 · 도구 6개
│ [1] ← 도구 요청    list_dir {"path":"plugins"}   12ms
│      ⚙ list_dir 실행  147자 · 3ms
│ [2] → 모델       메시지 4개 (도구 호출·결과 +2) · 도구 6개
│ [2] ← 최종 답변    179자   5ms
└ 턴 완료 — 모델 2회 · 도구 1회 · 25ms
```
- `┌ 턴 시작`/`└ 턴 완료`는 1번, `[1]`·`[2]` 라운드는 2번 찍힙니다. TurnTraceAdvisor는 루프 바깥, RoundTraceAdvisor는 안쪽에 있기 때문입니다.
- 라운드 2에서 메시지가 2개 늘어납니다(assistant의 tool call + tool 결과). 대화를 이어가면 라운드 1에 `(메모리 +N)`이 붙습니다.
- 평소에는 `/trace summary`(턴당 한 줄), 끄려면 `/trace off`.
- 위 출력은 REPL(`AGENT_UI=repl ./agent.sh`) 기준입니다. 기본 TUI에서는 같은 이벤트가 오른쪽 사이드바에 사용자 입력 → AI 출력 → Tool → AI 답변 그래프(노드마다 토큰·시간, 위에는 모델·effort)로 그려집니다. 둘 다 trace 플러그인이 발행한 같은 `TraceEvent`를 그린 것입니다(`ConsoleTraceRenderer` vs `TraceGraph`).

**읽을 코드**
- `RoundTraceAdvisor.java`와 `TurnTraceAdvisor.java`는 order만 다르고 구조는 같습니다.
- Spring AI 소스 `ToolCallingAdvisor.adviseCall`: `do { callAdvisorChain.copy(this).nextCall(...) ... } while (isToolCall)`. `copy(this)`는 "나보다 뒤에 있는 어드바이저만 다시 실행"한다는 뜻입니다.

**더 해 보기:** `AdvisorParams.toolCallingAdvisorAutoRegister(false)`로 자동 루프를 끄고 `ToolCallingManager`로 직접 루프를 돌려 보세요. 공식 문서의 "User-controlled tool execution" 예제가 출발점입니다. 어드바이저가 대신 해 주던 일이 무엇인지 드러납니다.

## 3. 메모리를 루프 바깥/안쪽에 두기

**해 보기**
```bash
./agent.sh
```
```text
!tool list_dir {"path":"."}
/history
/exit
```
```bash
./agent.sh --agent.plugins.memory.inside-loop=true
```
```text
!tool list_dir {"path":"."}
/history
```

**관찰**
- 바깥(기본): `USER`, `ASSISTANT` 2개만 기억합니다.
- 안쪽: `USER`, `ASSISTANT`(tool call), `TOOL`, `ASSISTANT` 4개를 기억합니다.
- 세션 파일은 `~/.mini-agent/sessions/<세션ID>.jsonl`입니다. `/sessions`, `/resume`, `/new`로 오갑니다.
- 대화 요약을 보려면 `--agent.plugins.memory.max-messages=4`로 실행해 네 번 넘게 대화한 뒤 `/history`를 보세요. 맨 앞에 `Summary of the earlier conversation`으로 시작하는 USER 메시지가 붙고, 세션 파일에는 `"type":"summary"` 줄이 남습니다. 가짜 모델은 요약 대신 "받은 요청"을 그대로 돌려주므로, 요약 내용은 진짜 모델로 확인하세요.

**왜?** 메모리 어드바이저가 HIGHEST+300 안쪽에 있으면 라운드마다 실행되기 때문입니다. 이때 `DefaultChatClient`는 "아래쪽에 MemoryAdvisor가 있다"는 것을 감지해 `ToolCallingAdvisor`의 내부 히스토리를 끄고 메모리에 맡깁니다.

**읽을 코드**
- `MemoryPluginAutoConfiguration.chatMemoryAdvisor`
- `JsonlChatMemoryRepository`, `JsonlChatMemory`: 저장소 SPI는 메서드 4개입니다. 이 프로젝트는 세션 파일에 전체 기록을 덧붙이고, 모델에게 보낼 최근 부분은 `JsonlChatMemory.window`가 정합니다. Spring AI 기본 구현 `MessageWindowChatMemory`는 창(window)만 다시 저장하므로 창 밖의 기록이 파일에서도 지워집니다. 창 밖으로 밀려난 앞부분은 `ChatModelSummarizer`가 요약해 문맥 앞에 붙입니다.
- 테스트 `PluginVariationsTest.MemoryInsideTheLoop`

## 4. 도구를 만드는 두 방식

**비교할 파일**
- `FileTools.java`: `@Tool` 메서드 → `MethodToolCallbackProvider`. 메서드 파라미터가 스키마가 됩니다. `ToolContext` 파라미터는 스키마에서 빠집니다.
- `BashTool.java`: `FunctionToolCallback.builder(name, (input, toolContext) -> …)`. 입력 레코드가 스키마가 되고, 이름과 설명을 런타임에 정할 수 있습니다.

**해 보기**
```text
!tool read_file {"path":"없는파일.txt"}
```
도구가 던진 `IllegalArgumentException` 메시지가 도구 결과로 모델에게 갑니다. 진짜 모델이면 이 오류를 보고 다시 시도합니다. RuntimeException이 아닌 예외(checked)는 루프 밖으로 던져집니다(`DefaultToolExecutionExceptionProcessor`).

## 5. 도구 실행 가로채기

**해 보기**
```text
!tool bash {"command":"echo hi"}
n
!tool bash {"command":"echo hi"}
a
!tool bash {"command":"echo again"}
```

**관찰:** 거부해도 예외가 나지 않습니다. 거부 메시지가 도구 결과로 모델에게 가므로, 모델은 다른 방법을 찾거나 사용자에게 묻습니다. bash의 `a`(항상)는 같은 명령에만 적용되므로 마지막 `echo again`은 다시 묻습니다. 권한은 허용 목록 방식이라 `read_file`, `list_dir`, `load_skill` 외의 도구는 모두 실행 전에 묻습니다.

**더 해 보기:** `!tool write_file {"path":"a.txt","content":"one"}`로 파일을 만든 뒤 `/undo`를 입력하면 그 턴의 변경이 되돌려지고 `a.txt`가 지워집니다(`FileCheckpoints`). macOS라면 `--agent.plugins.bash.sandbox=workspace-write`로 실행해 `!tool bash {"command":"touch ~/x"}`를 보내 보세요. 셸 안에서 `Operation not permitted`로 막힙니다(`BashSandbox`). 둘 다 도구를 바꾸지 않고 도구 안쪽이나 실행 방식만 감싼 예입니다.

**읽을 코드**
- `ToolInterceptor.java`, `InterceptingToolCallback.java`: Spring AI에는 도구 훅이 따로 없습니다. 대신 `ToolCallback.call(input, toolContext)` 하나만 감싸면 어떤 출처의 도구든 가로챌 수 있습니다.
- `ConfirmToolInterceptor`(HIGHEST)와 `ToolTraceInterceptor`(LOWEST)의 순서도 확인하세요.

## 6. 진짜 provider로 바꾸기

### 6-a. ChatGPT 구독 (Codex OAuth)

```bash
AGENT_PROFILE=codex ./agent.sh
```
```text
/codex
list_dir 도구로 plugins 디렉터리를 확인하고 플러그인 개수만 알려줘
```

**관찰:** `/trace detail`로 보면 `[1] ← 도구 요청`, `[2] ← 최종 답변`의 흐름이 가짜 모델 때와 똑같습니다. provider가 바뀌어도 루프는 `ToolCallingAdvisor`가 돌기 때문입니다.

**읽을 코드 (provider를 직접 만드는 법)**
- `CodexResponses.java`: Spring AI 메시지 ↔ Responses API 변환표. `ToolCall.id`에 넣은 `call_id`가 `ToolResponse.id`로 돌아오는 왕복이 핵심입니다.
- `CodexResponses.OUTPUT_ITEMS`: 추론 모델의 reasoning item을 `AssistantMessage` 메타데이터에 보관했다가 다음 라운드에 그대로 다시 보냅니다. `ToolCallingAdvisor`가 메시지 객체를 그대로 히스토리에 넣기 때문에 가능한 방법입니다.
- `CodexChatModel.sessionId`: 코어가 넣은 ToolContext는 `ChatOptions` 안에 실려 오므로 모델 구현에서도 읽을 수 있습니다. 여기서는 프롬프트 캐시 키로 씁니다.
- `CodexAuth`: 토큰 출처 두 개와 "Codex CLI 토큰은 갱신하지 않는다" 규칙. 테스트는 `CodexAuthTest`.

### 6-b. API 키

```bash
cp .env.example .env     # 키 입력
AGENT_PROFILE=anthropic ./agent.sh
```
```text
이 프로젝트에서 ToolCallingAdvisor 가 어떻게 쓰이는지 code-tour 스킬로 설명해줘
```

**관찰**
- 모델이 `load_skill` → `read_file`을 스스로 호출합니다. `/trace detail`로 라운드 수를 확인하세요.
- provider 교체는 코드 변경 없이 `spring.ai.model.chat` 값 하나로 됩니다. 각 starter의 자동 구성이 `@ConditionalOnProperty(spring.ai.model.chat = 자기이름)`이기 때문입니다.
- `application.yml`에서 `spring.ai.model.embedding: none` 등을 지우면 OpenAI 프로필이 embedding/image 모델까지 만들려고 합니다. starter 하나가 모델 여러 개를 자동 구성한다는 점을 확인할 수 있습니다.

## 7. MCP 서버 붙이기

```bash
AGENT_PROFILE=mcp ./agent.sh
```
```text
/mcp
!tool get_sum {"a":2,"b":40}
```

**관찰:** `plugin-mcp`에는 도구 코드가 없습니다. MCP starter가 `SyncMcpToolCallbackProvider` 빈을 만들면 코어가 다른 도구와 똑같이 모읍니다. 권한과 트레이스 인터셉터도 그대로 적용되므로, MCP 도구도 기본으로 실행 전에 묻습니다. 묻지 않고 실행하려면 `agent.plugins.permission.auto-allow-tools`에 도구 이름이나 `접두어*`를 추가하세요.

**주의 (2.0.1):** Spring AI 2.0.1은 MCP Java SDK 2.0.0에 고정되어 있습니다. stateless MCP **서버**를 만들어 OpenAI 호스티드 MCP에 연결하면 HTTP 500 문제가 보고되어 있습니다. 클라이언트로만 쓰는 이 프로젝트는 해당하지 않습니다.

## 8. 플러그인 직접 만들기

쉬운 것부터 정렬했습니다.

1. **plugin-todo** (30분): `@Tool`로 `todo_add`, `todo_list`, `todo_done`을 만듭니다. 세션별 상태는 `ToolContext`의 `agent.sessionId`로 나눕니다. pi 예제 `todo.ts`와 같은 기능입니다.
2. **`/plan <목표>`**: 이미 붙어 있습니다. 그 턴에만 `submit_plan` 도구를 붙이고, 모델이 그 도구를 호출해야 계획이 됩니다. 답변 본문의 JSON 은 쓰지 않습니다. 가짜 모델은 도구를 스스로 고르지 않으므로 실패 메시지를 보고, 진짜 모델로 같은 명령을 다시 실행해 보세요.
3. **요약 바꿔 보기** (1~2시간): 대화 요약은 `plugin-memory`의 `JsonlChatMemory`와 `ChatModelSummarizer`에 들어 있습니다. 요약 지침(`ChatModelSummarizer.INSTRUCTIONS`)을 바꾸거나, 요약만 더 싸고 빠른 모델로 부르도록 바꿔 보세요. 같은 일을 루프 바깥 어드바이저로 다시 만들어 비교해 보는 것도 좋습니다. 그때 order는 메모리(HIGHEST+200)보다 커야 메모리가 붙인 메시지를 볼 수 있습니다.
4. **도구 검색**: 스타터는 이미 의존성에 있습니다. `spring.ai.chat.client.tool-search-advisor.enabled=true`로 기본 `ToolCallingAdvisor`를 `ToolSearchToolCallingAdvisor`로 바꿉니다. MCP 프로필과 함께 쓰면 도구 중 필요한 것만 모델에게 보이는지 확인할 수 있습니다. `/advisors`의 도구 루프 이름이 바뀌는지도 보세요(`ToolAdvisor`는 클라이언트당 하나만 허용됩니다).
5. **스트리밍**: 대화 입력은 `Agent.stream`입니다. 트레이스 어드바이저와 메모리 예산도 `StreamAdvisor`를 구현합니다. 관리 실행(`/run`, 자식 위임)은 `Agent.run`의 `call()`에 남아 있습니다. 2.0.x는 스트리밍과 도구 호출이 겹치는 경로가 가장 불안정하므로, 같은 입력을 `run`과 `stream`으로 보내 최종 답을 비교해 보세요.

**완성 기준:** `./mvnw test`가 통과하고, 그 플러그인을 켰다 껐다 하는 테스트를 `PluginVariationsTest`에 하나 추가합니다.
