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
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbTlsContext.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbIdentity.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbNativeCrypto.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbWirePacket.java \
    tools/OnDeviceAdbWirePacketOfflineTest.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbRestrictedClient.java \
    tools/OnDeviceAdbRestrictedClientOfflineTest.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbPublicKey.java \
    tools/OnDeviceAdbPublicKeyOfflineTest.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbCodeBuffer.java \
    tools/OnDeviceAdbCodeBufferOfflineTest.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbTlsExporter.java
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
"$JAVA" -cp "$out:$ANDROID_HOME/platforms/android-36/android.jar" \
    dev.sid.fiveberinspector.OnDeviceAdbPublicKeyOfflineTest
"$JAVA" -ea -cp "$out:$ANDROID_HOME/platforms/android-36/android.jar" \
    dev.sid.fiveberinspector.OnDeviceAdbCodeBufferOfflineTest
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
exporter=Path('app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbTlsExporter.java').read_text()
assert 'SSLEngines.exportKeyingMaterial(engine,ADB_PAIRING_LABEL,null,64)' in exporter
assert 'Build.VERSION.SDK_INT < 31' in exporter
assert '"TLSv1.3".equals(session.getProtocol())' in exporter
pairingService=Path('app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbPairingService.java').read_text()
assert 'RemoteInput.getResultsFromIntent' in pairingService
assert 'OnDeviceAdbPairingExchange.validCode(code)' in pairingService
assert 'value.toString().toCharArray()' not in pairingService
assert 'setCustomBigContentView(pad)' in pairingService
assert 'keypad.masked()' in pairingService
assert 'PendingIntent.FLAG_IMMUTABLE' in pairingService
assert "Arrays.fill(code," in pairingService
restricted=Path('app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbRestrictedClient.java').read_text()
for required in ('verifiedPairedIdentity()', 'ADB TLS required', 'fixedEntrypoint(', 'A_STLS', 'MAX_PACKETS'):
    assert required in restricted, f'Missing TLS-only restricted session contract: {required}'
for forbidden in ('Runtime.getRuntime().exec', 'ProcessBuilder(', 'adb tcpip', 'setprop '):
    assert forbidden not in restricted, f'Forbidden generic ADB execution: {forbidden}'
print('PASS on-device DNS-SD lifecycle, TLS-only state, and no-adbd-mutation source guards')
PY
