#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src=app/src/main/java/dev/sid/fiveberinspector
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$out" "$src"/{ConnectionCommand,RecoveryProcessGuard,CardTransactionLock,QuarantineReview,OperationJournal}.java tools/ReadOnlyRecoveryOfflineTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$out" dev.sid.fiveberinspector.ReadOnlyRecoveryOfflineTest
python3 tools/test_recovery_review_entry.py