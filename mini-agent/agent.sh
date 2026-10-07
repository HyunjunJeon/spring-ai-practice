#!/usr/bin/env bash
# 사용법:
#   ./agent.sh                          대화형 (기본 provider = fake, API 키 불필요)
#   ./agent.sh "README 요약해줘"          한 번 실행하고 종료
#   AGENT_PROFILE=claude ./agent.sh      Claude 구독 (앱의 /login, Java OAuth)
#   AGENT_PROFILE=zai ./agent.sh         Z.ai API 키 + 공식 OpenAI 호환 모델
#   AGENT_PROFILE=codex ./agent.sh       provider 전환 (codex | claude | zai | anthropic | openai | ollama), 쉼표로 mcp 추가 가능
#   AGENT_UI=repl ./agent.sh             전체 화면 TUI 대신 줄 단위 REPL
# 에이전트의 작업 디렉터리는 이 스크립트를 실행한 위치다.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21 ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

JAR="$ROOT/agent-app/target/mini-agent.jar"

# jar 가 없거나, jar 보다 새로 바뀐 소스(src/ 아래 파일, pom.xml)가 하나라도 있으면 다시 빌드한다.
# (.mini-agent/skills 같은 런타임 파일은 빌드와 무관하므로 보지 않는다)
needs_build() {
  [[ ! -f "$JAR" ]] && return 0
  [[ -n "$(find "$ROOT" -path "$ROOT/*/target" -prune -o \
      \( -path '*/src/*' -o -name pom.xml \) -type f -newer "$JAR" -print -quit)" ]]
}

if needs_build; then
  echo "소스가 바뀌어 다시 빌드합니다 (테스트 생략)..." >&2
  (cd "$ROOT" && ./mvnw -q install -DskipTests)
fi

# .env (KEY=VALUE) 가 있으면 Spring 속성으로 읽는다. 예: ANTHROPIC_API_KEY=sk-...
exec "$JAVA" -jar "$JAR" \
  --spring.config.import="optional:file:$ROOT/.env[.properties]" \
  --spring.profiles.active="${AGENT_PROFILE:-default}" \
  --agent.ui="${AGENT_UI:-tui}" \
  "$@"
