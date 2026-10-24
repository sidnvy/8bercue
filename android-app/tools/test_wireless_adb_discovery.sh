#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set Android SDK with platform android-36}"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
"$JAVAC" -cp "$ANDROID_HOME/platforms/android-36/android.jar" -d "$out" \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbDiscovery.java \
    tools/OnDeviceAdbDiscoveryOfflineTest.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbPairingFrame.java \
    tools/OnDeviceAdbPairingFrameOfflineTest.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbPairingExchange.java \
    tools/OnDeviceAdbPairingExchangeOfflineTest.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbPairingTransport.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbWirePacket.java \
    tools/OnDeviceAdbWirePacketOfflineTest.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbRestrictedClient.java \
    tools/OnDeviceAdbRestrictedClientOfflineTest.java
"$JAVA" -cp "$out:$ANDROID_HOME/platforms/android-36/android.jar" \
    dev.sid.fiveberinspector.OnDeviceAdbDiscoveryOfflineTest
"$JAVA" -cp "$out:$ANDROID_HOME/platforms/android-36/android.jar" \
    dev.sid.fiveberinspector.OnDeviceAdbPairingFrameOfflineTest
"$JAVA" -cp "$out:$ANDROID_HOME/platforms/android-36/android.jar" \
    dev.sid.fiveberinspector.OnDeviceAdbPairingExchangeOfflineTest
"$JAVA" -cp "$out:$ANDROID_HOME/platforms/android-36/android.jar" \
    dev.sid.fiveberinspector.OnDeviceAdbWirePacketOfflineTest
"$JAVA" -cp "$out:$ANDROID_HOME/platforms/android-36/android.jar" \
    dev.sid.fiveberinspector.OnDeviceAdbRestrictedClientOfflineTest
python3 - <<'PY'
from pathlib import Path
source=Path('app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbDiscovery.java').read_text()
activity=Path('app/src/main/java/dev/sid/fiveberinspector/MainActivity.java').read_text()
for required in ('NsdManager','discoverServices(', 'resolveService(', 'isOwnAddress(',
                 'stopServiceDiscovery(', 'if (!running.get()) stopRegisteredDiscovery()'):
    assert required in source, f'Missing discovery safety requirement: {required}'
for forbidden in ('for (int port', '65535; port', 'setprop ', 'adb tcpip', 'adb_wifi_enabled',
                  'WRITE_SECURE_SETTINGS', 'Runtime.getRuntime().exec'):
    assert forbidden not in source, f'Discovery must not scan or mutate adbd: {forbidden}'
assert 'showWirelessDiscoveryPreview()' in activity or 'this::showWirelessDiscoveryPreview' in activity
assert 'pairing and TLS login' not in activity.lower()  # no false success claims in Java code
restricted=Path('app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbRestrictedClient.java').read_text()
for required in ('verifiedPairedIdentity()', 'ADB TLS required', 'fixedEntrypoint(', 'A_STLS', 'MAX_PACKETS'):
    assert required in restricted, f'Missing TLS-only restricted session contract: {required}'
for forbidden in ('Runtime.getRuntime().exec', 'ProcessBuilder(', 'adb tcpip', 'setprop '):
    assert forbidden not in restricted, f'Forbidden generic ADB execution: {forbidden}'
print('PASS on-device DNS-SD lifecycle, TLS-only state, and no-adbd-mutation source guards')
PY
