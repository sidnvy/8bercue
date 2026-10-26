#!/usr/bin/env python3
"""Keep the optional Shizuku integration a fixed Shell bootstrap, not a generic privilege proxy."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
java = root / 'app/src/main/java/dev/sid/fiveberinspector'
allowed = {
 'MainActivity.java': {'addBinderReceivedListenerSticky', 'addBinderDeadListener',
  'addRequestPermissionResultListener', 'removeBinderReceivedListener',
  'removeBinderDeadListener', 'removeRequestPermissionResultListener', 'requestPermission'},
 'ShizukuConnection.java': {'pingBinder', 'isPreV11', 'getVersion', 'getUid',
  'checkSelfPermission', 'UserServiceArgs', 'bindUserService', 'unbindUserService'},
}
for file in java.glob('*.java'):
    calls = set(re.findall(r'\bShizuku\.(\w+)\s*\(', file.read_text()))
    if calls - allowed.get(file.name, set()):
        raise SystemExit('Unreviewed Shizuku API calls in ' + file.name)
interface = (root / 'app/src/main/aidl/dev/sid/fiveberinspector/IShizukuBootstrap.aidl').read_text()
methods = re.findall(r'^\s*(boolean|void)\s+(\w+)\(([^)]*)\)\s*=\s*(\d+);', interface, re.M)
if methods != [('boolean', 'connect', '', '0'), ('void', 'destroy', '', '16777114')]:
    raise SystemExit('Bootstrap must expose only no-argument connect and reserved destroy')
namespace = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
providers = manifest.findall('application/provider')
protected = [p for p in providers if p.get(namespace + 'name') == 'rikka.shizuku.ShizukuProvider']
if len(protected) != 1 or protected[0].get(namespace + 'permission') != 'android.permission.INTERACT_ACROSS_USERS_FULL':
    raise SystemExit('Shizuku handoff must be protected from ordinary app callers')
for component in manifest.findall('application/*'):
    if 'ShizukuBootstrapService' in str(component.attrib):
        raise SystemExit('Launcher must not be exposed as an Android component')
print('PASS fixed optional Shizuku API surface, protected provider and no generic command IPC')
