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
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbConnectionPolicy.java \
    tools/OnDeviceAdbConnectionPolicyOfflineTest.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbIdentity.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbNativeCrypto.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbWirePacket.java \
    tools/OnDeviceAdbWirePacketOfflineTest.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbRestrictedClient.java \
    tools/OnDeviceAdbRestrictedClientOfflineTest.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbPublicKey.java \
    tools/OnDeviceAdbPublicKeyOfflineTest.java \
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
# Generate synthetic keys only in an automatically erased temporary directory.
for name in server restarted client; do
    openssl req -x509 -newkey rsa:2048 -nodes -keyout "$out/$name.key" \
        -out "$out/$name.pem" -subj /CN=Adb -days 1 >/dev/null 2>&1
done
openssl req -x509 -key "$out/server.key" -out "$out/renewed.pem" \
    -subj /CN=Adb -set_serial 2 -days 1 >/dev/null 2>&1
"$JAVA" -cp "$out:$ANDROID_HOME/platforms/android-36/android.jar" \
    dev.sid.fiveberinspector.OnDeviceAdbConnectionPolicyOfflineTest \
    "$out/server.pem" "$out/renewed.pem" "$out/restarted.pem" "$out/client.pem"
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
assert 'setCustomBigContentView(pad)' not in pairingService
assert 'new RemoteInput.Builder(CODE_KEY)' in pairingService
assert 'PendingIntent.FLAG_MUTABLE' in pairingService
assert 'PendingIntent.FLAG_IMMUTABLE' in pairingService
# A Settings onPause closes its pairing server. Never launch an Activity from
# this notification; a floating dialog still pauses Settings on real Pixels.
assert 'PendingIntent.getActivity' not in pairingService
assert '.setContentIntent(' not in pairingService
assert 'ACTION_CODE_FROM_ACTIVITY' not in pairingService
manifest=Path('app/src/main/AndroidManifest.xml').read_text()
assert 'OnDeviceAdbPairingCodeActivity' not in manifest
assert not Path('app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbPairingCodeActivity.java').exists()
assert "Arrays.fill(code," in pairingService
restricted=Path('app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbRestrictedClient.java').read_text()
for required in ('clientIdentityPresented()', 'ADB TLS required', 'fixedEntrypoint(', 'A_STLS', 'MAX_PACKETS'):
    assert required in restricted, f'Missing TLS-only restricted session contract: {required}'
for forbidden in ('Runtime.getRuntime().exec', 'ProcessBuilder(', 'adb tcpip', 'setprop '):
    assert forbidden not in restricted, f'Forbidden generic ADB execution: {forbidden}'
wireless=Path('app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbWirelessManager.java').read_text()
assert 'OnDeviceAdbIdentity.getExisting(context)' in wireless
assert 'trustedFingerprint' not in wireless and 'seenFingerprint' not in wireless
assert 'peer certificate changed; pair again' not in wireless
assert wireless.index('Command returned without authenticated management Binder') < wireless.index('pairPrefs.edit().remove("serverFingerprint")')
assert '.putString("serverFingerprint"' not in wireless
identity=Path('app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbIdentity.java').read_text()
assert 'if (!mayCreate) throw new IOException' in identity
print('PASS on-device DNS-SD lifecycle, TLS-only state, and no-adbd-mutation source guards')
PY

# Run the real NSD implementation with controlled asynchronous callbacks.
"$JAVAC" -d "$out/nsd" tools/discovery-stubs/android/content/Context.java \
    tools/discovery-stubs/android/util/Log.java tools/discovery-stubs/android/os/*.java tools/discovery-stubs/android/net/nsd/*.java \
    app/src/release/java/dev/sid/fiveberinspector/OnDeviceAdbDiscovery.java \
    tools/OnDeviceAdbDiscoveryLifecycleTest.java
"$JAVA" -cp "$out/nsd" dev.sid.fiveberinspector.OnDeviceAdbDiscoveryLifecycleTest

python3 tools/test_lab_artifact_gates.py
python3 tools/test_local_adb_binder_source.py

python3 tools/test_wireless_completion.py
