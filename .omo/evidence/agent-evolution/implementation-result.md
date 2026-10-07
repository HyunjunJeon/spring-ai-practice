# Spring AI 에이전트 구현 결과

승인된 네 영역의 23개 구현 항목은 Java 애플리케이션과 실제 CLI에 연결되었다.
최종 감사가 발견한 부모 예산 누락 B1을 수정했다. 실제 Spring 후속 호출·
두 번째 위임·unknown·초과량·동일 부모/자식 재개와 전체 회귀가 통과했다.
최신 실제 런처도 부모 426토큰·도구 2회의 영속 합산을 확인했다.
변경 범위 재감사는 APPROVE, high confidence, blocker 없음으로 완료되었다.
최초 거절 보고서는 보존하고 수정 검증과 승인 보고서를 별도로 남겼다.

## 구현한 동작

- 모델 레지스트리와 제공자 팩토리, 다음 턴 모델 선택, 현재 턴의 고정
  바인딩을 분리했다. 모델 출처를 표시하고 다른 제공자의 private metadata를
  재생하지 않는다. 기존 프로필과 Java 소유 OAuth를 유지한다.
- RunStore의 권위 이벤트 로그, writer 잠금, 예산 계측, 공용 승인,
  도구 효과 journal, drift 검사와 명시 재개를 연결했다. 완료되지 않은
  변경 효과를 UNKNOWN으로 구분하고 임의 성공으로 복구하지 않는다.
- 관리 실행은 원본 대신 스테이징에 쓴다. 경로 정책, diff 승인, 독점 적용,
  실제 검증 프로세스와 증거, 체크포인트/부분 복구를 연결했다.
  적용 뒤의 사용자 변경은 되돌리기로 덮어쓰지 않는다.
- 자식 모델·세션·도구·권한·메모리를 분리하고 제한된 병렬도/큐/깊이와
  예약 예산을 적용한다. 자식은 원본을 직접 적용하지 못한다.
  부모가 실제 역할별 증거를 확인한 뒤 결과를 합친다.

Java 21, Spring Boot 4.1.1, Spring AI 2.0.1의 공식 ChatModel, ChatClient,
ToolCallingAdvisor를 사용한다. 앱에 Bun/Node/senpi 런타임이나 새로운 Maven
모듈을 추가하지 않았다. terminal 캡처용 Bun/xterm은 `.omo/evidence`의 QA
도구일 뿐이다.

## 사용

```bash
cd mini-agent
AGENT_PROFILE=fake AGENT_UI=plain bash ./agent.sh "hello"
AGENT_PROFILE=fake bash ./agent.sh
```

화면에서 `/help`로 실행·변경·위임 명령을 확인한다.
`/model select fake/fake-scripted`는 다음 턴을 선택하며, 실행 중인 턴의
바인딩을 바꾸지 않는다. 일반 턴의 기존 파일 도구 동작은 유지하고,
스테이징 보호는 관리 실행에서 적용한다.

## 검증과 증거

- 최종 전체 회귀: `.omo/evidence/agent-evolution/B1-final-exact-parent-verify.log`
  (exit 0, 보존된 90개 XML의 700개 검사, 실패·오류·skip 0).
  실행 로그의 모듈 합계는 712개다. nested MemoryBudget의 실행 24개와
  보존 XML 집계 12개 차이이며, 감사는 실제 사례와 assertion도 확인했다.
- 공식 OpenAI/ZAI HTTP 클라이언트와 실제 도구·스테이징·검증·부모 적용·
  되돌리기 통합: `F1-integrated-local-providers.log`.
- OS 강제 종료/새 JVM 재시작의 10개 검사: run 3, change 6, child 1.
  계약과 경계: `.omo/drafts/crash-recovery-qa-contract.md`.
- 실제 TUI/REPL/one-shot, FIFO/UUID/취소, 수정한 화면 회귀:
  `F4-results.md`와 PNG·런처 로그.
- 공유 부모 예산 수정과 실제 호출·재개 증거: `B1-results.md`,
  `.omo/drafts/shared-parent-budget-fix-contract.md`.
- 구현 항목별 기록: `.omo/plans/spring-ai-agent-evolution.md`,
  `.omo/ulw-execute/ledger.jsonl`.
- 최종 승인: `.omo/evidence/agent-evolution-gate-review-B1.md`.
  기존 전체 계획 감사와 B1 변경 범위 감사를 함께 읽는다.

## 남은 환경 한계

실제 유료 제공자 호출은 하지 않았다. light terminal에는 배경뿐 아니라
ANSI 팔레트도 light 설정이어야 한다. 앱이 터미널 팔레트를 자동 선택하지
않으며, 기존 dark dim 정보의 대비는 터미널 설정에 따른다.
기존 Spring AI/JDK/Mockito 경고는 숨기지 않았다. QA HTML/MJS의 LSP는
TypeScript/Biome 미설치로 실행하지 못했지만 실제 Bun/browser 구동은 확인했다.
Git 커밋·배포·외부 게시를 하지 않았다.
