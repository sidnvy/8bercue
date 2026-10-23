#!/usr/bin/env python3
"""Fail closed if experimental on-device ADB falls back to legacy socket bootstrap."""
from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'app' / 'src'
bootstrap = (root / 'release/java/dev/sid/fiveberinspector/LocalAdbBootstrap.java').read_text()
ui = (root / 'main/java/dev/sid/fiveberinspector/MainActivity.java').read_text()
required = ('127.0.0.1', 'PORT = 5555', 'dev.sid.fiveberinspector.Connect',
            'CompanionBinderProvider.beginManagementBootstrap()',
            'CompanionBinderProvider.management()', 'getNoBackupFilesDir')
for item in required:
    assert item in bootstrap, f'Missing required constrained bootstrap: {item}'
for forbidden in ('dev.sid.fiveberinspector.ShellCompanion ', 'ShellCompanionClient.status()',
                  'setprop ', 'adb tcpip', 'kill-server', 'adb_wifi_enabled'):
    assert forbidden not in bootstrap, f'Forbidden unsafe/legacy bootstrap: {forbidden}'
assert bootstrap.index('CompanionBinderProvider.beginManagementBootstrap()') < bootstrap.index('connection.open(')
assert 'if (r.optBoolean("ready")) {' in ui and 'connectDirect();' in ui
print('PASS bounded on-device classic ADB → production Binder bootstrap source checks')
