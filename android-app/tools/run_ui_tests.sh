#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src=app/src/main/java/dev/sid/fiveberinspector
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$out" "$src"/{ConnectionCommand,ConnectionFeedback,ProfileActionPolicy,ProfileDetailSession,RecoveryUiPolicy,UiStatusPolicy,ProfileUiIdentity,IntentDigests,OperationTokens,SettingsNavigation}.java tools/UiFlowOfflineTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$out" dev.sid.fiveberinspector.UiFlowOfflineTest
python3 tools/test_settings_navigation.py
