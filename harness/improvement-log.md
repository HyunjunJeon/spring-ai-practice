# Harness Improvement Log

코딩 에이전트(Claude Code, Codex 등)가 이 저장소에서 일하다 생긴 실패와, 그 실패를 만든 환경을 고친 기록이다.
앱 기능인 `/lesson`(agent-forge 가 실행 중에 쓰는 교훈)과는 별개다.

## 규칙
- 한 번 생긴 일은 `status: candidate` 로만 남긴다. 근거(`evidence`) 없이 규칙으로 올리지 않는다.
- `evidence` 에는 실제 오류 문구, 실패한 테스트 이름, `파일:줄` 을 그대로 적는다. 바꿔 말하지 않는다.
- 한 번에 하나만 바꾼다. 바꾼 뒤 `bash ./scripts/verify.sh` 가 통과해야 `applied` 로 올린다.
- 자연어 규칙보다 실행되는 검사를 먼저 고른다. `preferred_enforcement` 는 아래 표에서 고른다.
- 낡은 항목은 지우지 않고 `retired` 로 바꾸고, 해당 규칙·문서도 함께 걷어낸다.

## status (이 프로젝트의 정의)
- `candidate`: 기록만 했다. 아직 harness 를 바꾸지 않았다.
- `applied`: harness 를 바꿨고 verify 가 통과했다. `applied_in` 에 바꾼 파일을 적는다.
- `rejected`: 검토 결과 바꾸지 않기로 했다. 이유를 `notes` 에 적는다.
- `retired`: 한때 applied 였지만 더는 맞지 않아 걷어냈다.

## 어디에 반영하나 (`preferred_enforcement`)
| 발견한 문제 | 반영 위치 | 이 저장소에서 |
|---|---|---|
| 특정 버그 | Regression Test | 해당 모듈 `src/test` |
| 반복되는 코드 실수 | Lint / ArchUnit / Static Rule | `agent-app/.../ArchitectureTest.java` |
| 프로젝트 전체 원칙 | AGENTS.md | 루트·모듈 `AGENTS.md` |
| 상세 설계 지식 | docs/architecture | `README.md`, 모듈 `AGENTS.md` |
| 반복 작업 절차 | Skill | `.claude/skills/` (코딩 에이전트), `.mini-agent/skills/` (agent-forge) |
| 특정 분야 판단 | Subagent | delegation runtime |
| 반드시 돌아야 하는 검사 | Hook | `ToolInterceptor`, Claude Code hooks |
| 에이전트가 하기 어려운 반복 작업 | Script / Tool | `scripts/` |
| 과거 작업에서 얻은 경험 | Curated Memory | `/lesson` |

## 기록

```yaml
id: 2026-10-15-001
symptom: >
  검토 답변이 ArchitectureTest 실행 명령으로
  `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw ...` 를 안내했고, 이 맥에서 실패했다.
root_cause: >
  JDK 21 은 Homebrew openjdk@21 이라 macOS java_home 에 등록되어 있지 않다. 경로는 agent.sh:13 과
  README '사전 준비' 에 이미 있었지만, AGENTS.md NOTES 는 "set JAVA_HOME" 만 말하고 값을 주지 않아
  에이전트가 일반적인 macOS 명령을 지어냈다.
evidence: >
  `/usr/libexec/java_home -v 21` 출력: "The operation couldn’t be completed. Unable to locate a Java Runtime."
  agent.sh:13-15 는 JAVA_HOME 이 비면 /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home 을 쓴다.
fix: Homebrew 경로를 직접 지정해 ArchitectureTest 7/7 통과를 확인했다.
recurrence_risk: high
proposed_harness_change: 검증을 한 명령으로 묶고, 그 명령이 JDK 를 스스로 찾고 버전을 확인하게 한다.
preferred_enforcement: Script / Tool
applied_in: scripts/verify.sh, AGENTS.md (COMMANDS, NOTES)
status: applied
notes: >
  verify.sh 는 JAVA_HOME=/nonexistent 와 JDK 27 에서 모두 종료 코드 2 와 원인 문구를 낸다.
```

```yaml
id: 2026-10-15-002
symptom: >
  AGENTS.md CONVENTIONS 의 advisor 순서표(turn trace +100, memory +200, tool loop +300,
  round trace +400)에 LessonAdvisor 가 없다.
root_cause: >
  순서표는 자연어뿐이고 검사가 없다. lesson 기능이 들어올 때 표를 고칠 계기가 없었다.
  순서값은 설정(spring.ai.chat.client.tool-calling.advisor-order)에 따라 런타임에 계산되어 ArchUnit 으로는 볼 수 없다.
evidence: >
  agent-core/.../lesson/LessonAdvisor.java:88 getOrder() = HIGHEST_PRECEDENCE + 150.
  AGENTS.md:72 순서표에 lesson 항목 없음.
fix: AGENTS.md 순서표에 lesson +150 을 넣었다. memory +200, tool loop +300, round trace +400 은 실제와 같았다.
recurrence_risk: medium
proposed_harness_change: >
  Spring 컨텍스트를 띄워 실제 advisor 빈과 도구 루프 순서를 읽고 순서표와 맞는지 단언하는 테스트.
preferred_enforcement: Regression Test
applied_in: agent-app/src/test/java/com/example/miniagent/app/AdvisorPlacementTest.java, AGENTS.md (CONVENTIONS)
status: applied
notes: >
  테스트는 숫자를 자체로 들고 있지 않고 AGENTS.md 의 그 줄을 읽어 비교한다. 코드만 바꾸거나 문서만 바꾸면 깨진다.
  red check 두 번: (1) 고치기 전 AGENTS.md 로 실행 → "actual size 5, expected size 4" 로 실패.
  (2) LessonAdvisor 를 +250 으로 바꿔 실행 → "lesson"=250 이 기대값과 달라 실패. 둘 다 되돌린 뒤 통과.
  기본 설정(agent.plugins.memory.inside-loop=false)만 본다. inside-loop=true 이면 memory 는 tool loop +50 이다.
```
