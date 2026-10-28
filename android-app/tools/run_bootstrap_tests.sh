#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src=app/src/main/java/dev/sid/fiveberinspector
find tools/bootstrap-stubs -name '*.java' -print0 | xargs -0 "${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$out"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -cp "$out" -d "$out" "$src"/{OperationTokens,ConnectionFeedback,CompanionBinderProvider}.java tools/BootstrapProviderOfflineTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$out" dev.sid.fiveberinspector.BootstrapProviderOfflineTest
