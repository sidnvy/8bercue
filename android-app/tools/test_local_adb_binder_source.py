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

lab=(root/'release/java/dev/sid/fiveberinspector/LabSupport.java').read_text()
assert 'socketEligible(){return false;}' in lab
assert 'wirelessEligible(){return true;}' in lab
for marker in ['private boolean wirelessOnboardingAvailable()', 'private void showWirelessDiscoveryPreview()']:
    section=ui[ui.index(marker):].split('\n  }',1)[0]
    assert 'LabSupport.wirelessEligible()' in section and 'LabSupport.socketEligible()' not in section
service=(root/'release/java/dev/sid/fiveberinspector/OnDeviceAdbPairingService.java').read_text()
handoff=service[service.index('private void launchIfReady()'):service.index('@Override public void onDestroy()')]
assert '|| managementAttempted)return;' in handoff
assert handoff.index('managementAttempted=true;') < handoff.index('io.execute(') < handoff.index('startManagement(')
assert 'managementAttempted=false' not in service
assert 'paired&&!managementAttempted' in service
assert 'Open 8bercue and tap Connect on this phone to retry' in service
assert 'connection_failed class=' in bootstrap and 'failure.getClass().getSimpleName()' in bootstrap
assert 'failure.getMessage()' not in bootstrap
print('PASS legacy socket isolation, one-shot handoff and private failure-category guards')
