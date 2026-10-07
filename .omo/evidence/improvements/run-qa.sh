#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mkdir -p .omo/evidence/improvements .omo/qa/runtime/lib
if [[ ! -f .omo/qa/runtime/lib/spring-ai-model-2.0.1.jar ]]; then
  unzip -j -q -o mini-agent/agent-app/target/mini-agent.jar 'BOOT-INF/lib/*' -d .omo/qa/runtime/lib
fi
CP="mini-agent/agent-core/target/classes:mini-agent/plugins/plugin-fs/target/classes:mini-agent/plugins/plugin-bash/target/classes:mini-agent/plugins/plugin-memory/target/classes:.omo/qa/runtime/lib/*"
"$JAVA_HOME/bin/java" --source 21 --class-path "$CP" .omo/evidence/improvements/ImprovementQa.source "$1" 2>&1 | tee ".omo/evidence/improvements/$1.log"
