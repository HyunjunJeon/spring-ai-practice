# 부모 검토 메모

상태: 단계 1만 수신했으며, 전체 계획 검토는 아직 완료되지 않았다.

## 단계 1의 통합 전 수정 사항

- `TraceEvent.java:17`은 sealed interface다. 이름 없는 모듈에서 `core/model/ModelBoundEvent`를 별도 패키지의 구현체로 추가하면 안 된다. `TraceEvent` 안의 nested record로 모델 바인딩 이벤트를 추가하고 기존 렌더러의 exhaustive dispatch를 함께 확인한다.
- `CodexModelAutoConfiguration.java:25`와 `ClaudeModelAutoConfiguration.java:13`의 단일 backend 조건을 모델 기여자 조건으로 바꿀 실제 경로를 최종 작업에 명시한다. `...`나 실행 시 파일을 찾으라는 지시는 최종 계획에 남기지 않는다.
- Codex의 기존 `/codex model`·`effort`는 모델 객체를 변경한다. 이 명령도 새 세션 선택/다음 턴 옵션 경로로 연결해야 고정 바인딩과 충돌하지 않는다. 기존 `/login device`·인자 없는 `/login` 동작의 이전 규칙을 명시한다.
- 신규 공개 설정 접두어는 기존 `AgentProperties`의 `agent`와 일치시키는 `agent.models`를 권장한다. 원본 초안의 `miniagent.models`는 최종 계획의 승인 전 추천값으로 정규화한다.
- `ModelBinding.client`와 credential은 영속화하지 않는다. run에는 ref·옵션/권한 fingerprint와 일시적 binding ID를 진단용으로만 남기고 재개 시 새 바인딩을 만든다.
- executor `deep`은 현재 사용 가능한 category가 아니다. 계약/불변식 변경은 `deep-high`, 관찰 가능한 구현 작업은 `deep-low` 등 실제 category로 정규화한다.
- `AgentTurnBindingTest`의 위치와 Maven `-pl` 대상을 일치시킨다. 플러그인 모듈에서 app 통합 테스트를 실행하는 초안 명령은 수정한다.
- 메모리를 끈 경우 선택 저장의 지속 여부를 명시한다. `SessionModelStore`를 메모리 플러그인에만 묶으면 해당 기능의 요구 범위가 줄어든다.
- 현재 `AgentConsole`은 동기 단일 입력 스트림을 전제로 하고, `TuiFrontEnd.java:254-312`는 단일 busy 작업·단일 pendingQuestion을 처리한다. 작업/자식 동시 실행은 승인 broker와 UI 조정을 전제로 해야 한다.
- 현재 `TraceEvent.Tokens.from(null)`은 ZERO를 반환한다. 실행 예산에서 사용량 미상과 실제 0을 구분하는 별도 known/unknown 상태가 필요하다.

이 메모는 제품 변경이나 테스트 통과 증거가 아니다. 최종 계획에 수정 내용을 반영하고 네 영역 전체를 검토한 뒤 별도로 완료 판정한다.

## 단계 2의 통합 전 수정 사항

- approval 대기 중의 사용자 취소와 비대화형 미응답/프로세스 소실을 구분한다. 사용자 cancel은 CANCELLED로 가며 요청을 폐기한다. 인터럽트라는 사실만으로 WAITING_APPROVAL로 되돌리지 않는다.
- 알려진 schema의 개행 없는 불완전 마지막 행만 안전 복구 대상으로 삼는다. 완성된 마지막 행의 CRC 오류는 정상 데이터 손상일 수 있으므로 자동 삭제하지 않고 CORRUPT로 보존한다. 미래 schema는 opaque 조회/내보내기만 허용한다.
- ATTEMPT_ENDED와 최종 usage를 기록한 뒤 terminal STATE_CHANGED를 마지막으로 기록한다. terminal 뒤 append 금지와 실행기 정리 순서가 충돌하지 않도록 명시한다.
- 예산은 모델 라운드와 도구뿐 아니라 메모리 요약의 직접 모델 호출까지 포함한다. 합산 usage의 중복 집계를 피하고 usage 미상은 0으로 간주하지 않는다.
- cwd/wireFamily 불일치는 단순 accept-drift로 tool protocol을 재해석하지 않는다. 허용된 fingerprint drift만 승인 이벤트로 기록하고, 불일치 모델·작업공간은 새 run 또는 명시된 안전 변환 경로를 요구한다.
- 실패한/UNKNOWN tool을 skip할 때 가짜 완료 결과를 만들지 않는다. 새 attempt의 resume contributor는 확인된 효과와 불확실성을 구분하며, 미완성 tool 쌍을 원본 provider에 그대로 재전송하지 않는다.
- agent-core의 ApprovalBrokerTest와 agent-app의 ConsoleApprovalRoutingTest를 서로 다른 Maven -pl 대상에서 실행한다.
- RunSpec의 session ID는 전용 run session을 고정하되 부모 대화 ID는 별도 provenance로 보존한다. 자식 별도 session과 단일-writer 규칙을 단계 4와 일치시킨다.
