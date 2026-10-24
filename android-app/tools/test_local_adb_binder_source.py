#!/usr/bin/env python3
"""Fail closed unless in-app ADB recovery is TLS-only and fixed-command."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'app' / 'src'
bootstrap = (root / 'release/java/dev/sid/fiveberinspector/LocalAdbBootstrap.java').read_text()
manager = (root / 'release/java/dev/sid/fiveberinspector/OnDeviceAdbWirelessManager.java').read_text()
client = (root / 'release/java/dev/sid/fiveberinspector/OnDeviceAdbRestrictedClient.java').read_text()
ui = (root / 'main/java/dev/sid/fiveberinspector/MainActivity.java').read_text()
required = ('OnDeviceAdbDiscovery.Purpose.CONNECTION',
            'OnDeviceAdbWirelessManager.hasPaired(context)',
            'OnDeviceAdbWirelessManager.startManagement(context,endpoint.get())',
            'CompanionBinderProvider.management()', 'discovery.close()')
for item in required:
    assert item in bootstrap, f'Missing TLS recovery contract: {item}'
for forbidden in ('127.0.0.1', 'PORT = 5555', 'com.tananaev.adblib',
                  'Runtime.getRuntime().exec', 'setprop ', 'adb tcpip',
                  'kill-server', 'adb_wifi_enabled'):
    assert forbidden not in bootstrap, f'Insecure legacy bootstrap forbidden: {forbidden}'
assert 'CompanionBinderProvider.beginManagementBootstrap()' in manager
assert 'OnDeviceAdbRestrictedClient.run(' in manager
assert 'ADB TLS required' in client
assert 'dev.sid.fiveberinspector.Connect' in client
assert 'if (r.optBoolean("ready")) {' in ui and 'connectDirect();' in ui
assert not list((root / 'release/java/com/tananaev/adblib').glob('*.java'))
print('PASS TLS-only in-app bootstrap, fixed Connect/Binder and no legacy adblib source')
