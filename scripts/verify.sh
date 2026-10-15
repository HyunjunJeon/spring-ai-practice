#!/usr/bin/env bash
# 통합 검증 명령. 코드를 고친 뒤 사람과 코딩 에이전트가 같은 것을 돌린다.
#   bash ./scripts/verify.sh            전체 reactor: 컴파일 + 단위/통합 테스트 + ArchUnit 규칙(ArchitectureTest)
#   bash ./scripts/verify.sh -pl agent-app -am -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false
#                                       추가 인자는 그대로 mvnw 에 넘긴다
# agent.sh 의 자동 빌드(-DskipTests)는 검증이 아니다. 테스트 통과의 근거는 이 스크립트의 종료 코드다.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# agent.sh 와 같은 규칙: JAVA_HOME 이 비어 있으면 Homebrew openjdk@21 을 쓴다.
# macOS 의 /usr/libexec/java_home 은 Homebrew JDK 를 찾지 못하므로 그 결과에 기대지 않는다.
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21 ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

if ! version="$("$JAVA" -version 2>&1)"; then
  echo "verify: java 를 실행할 수 없습니다 ($JAVA): ${version%%$'\n'*}" >&2
  echo "verify: JDK 21 을 설치하거나 JAVA_HOME 을 지정하세요 (README '사전 준비')." >&2
  exit 2
fi
if [[ "$version" != *'version "21.'* ]]; then
  echo "verify: JDK 21 이 필요합니다. 찾은 것: ${version%%$'\n'*}" >&2
  exit 2
fi
echo "verify: JAVA_HOME=${JAVA_HOME:-<PATH 의 java>}"

# 실제 provider 를 부르는 테스트는 환경 변수로만 켠다. 꺼져 있으면 건너뛰었다는 사실을 남긴다.
if [[ "${AGENT_FORGE_LIVE_CODEX:-}" == "true" ]]; then
  echo "verify: live 테스트 켜짐 (AGENT_FORGE_LIVE_CODEX=true, PrimeLiveCodexTest)"
else
  echo "verify: live 테스트 건너뜀 (PrimeLiveCodexTest 는 AGENT_FORGE_LIVE_CODEX=true 일 때만 실행)"
fi

cd "$ROOT"
if ./mvnw -B -ntp test "$@"; then
  echo "verify: PASS"
else
  status=$?
  echo "verify: FAIL (mvnw 종료 코드 $status). 실패 내용: */target/surefire-reports/*.txt" >&2
  exit "$status"
fi
