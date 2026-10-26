#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src=app/src/main/java/dev/sid/fiveberinspector
mapfile -t stubs < <(find tools/compatibility-stubs -name '*.java' -print)
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$out" "${stubs[@]}" \
 "$src"/{OperationTokens,CardChoicePlan,CompatibilityReport,HidlManifestReaders,HidlSecureElementTransport,NotificationJournal,NotificationTransaction,NotificationMetadataInventory,PortableNotificationHalBridge,DownloadWorkerSupervisor,OwnerOperationScope,IntentDigests,ActivationCode,DownloadSessionPolicy,NotificationCodec,NotificationSessionPolicy,TelephonyNotificationPolicy,TelephonyLifecyclePolicy,TelephonyChannelCodec,TelephonySecureElementTransport,SecureElementTransport,SecureElementFailure,AidlSecureElementTransport,OmapiSecureElementTransport,ReaderDescriptor,ManagementAvailability,FiveberReaderSelector,CardReadApduPolicy,ProfileLifecycleGuard,ProtectedProfilePolicy}.java \
 app/src/release/java/dev/sid/fiveberinspector/ReaderAdmission.java tools/CompatibilityOfflineTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$out" dev.sid.fiveberinspector.CompatibilityOfflineTest
