#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src=app/src/main/java/dev/sid/fiveberinspector
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$out" "$src"/{ConnectionCommand,RecoveryProcessGuard,CardTransactionLock,QuarantineReview,OperationJournal,DownloadDiagnostics,DownloadOutcomePolicy}.java tools/ReadOnlyRecoveryOfflineTest.java tools/CardScopedJournalOfflineTest.java tools/DownloadRecoveryOfflineTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$out" dev.sid.fiveberinspector.ReadOnlyRecoveryOfflineTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$out" dev.sid.fiveberinspector.CardScopedJournalOfflineTest
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$out" dev.sid.fiveberinspector.DownloadRecoveryOfflineTest
python3 tools/test_recovery_review_entry.py
python3 tools/test_download_recovery_entry.py
python3 tools/test_recovery_observation.py
python3 tools/test_profile_observation_entry.py
