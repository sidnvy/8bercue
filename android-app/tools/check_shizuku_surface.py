#!/usr/bin/env python3
"""Keep the optional Shizuku integration a fixed Shell bootstrap, not a generic privilege proxy."""
from pathlib import Path
import re
import json
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
runtime=json.loads((root.parent/'release/runtime-dependencies.json').read_text())['components']
sbom=json.loads((root.parent/'release/sbom.cdx.json').read_text())['components']
verification=ET.parse(root/'gradle/verification-metadata.xml').getroot()
ns={'g':'https://schema.gradle.org/dependency-verification'}
for name in ['api','provider','aidl','shared']:
    coordinate='dev.rikka.shizuku:'+name+':13.1.5'
    inputs=[c for c in runtime if c['coordinate']==coordinate]
    records=[c for c in sbom if c.get('group')=='dev.rikka.shizuku' and c['name']==name and c['version']=='13.1.5']
    artifact=name+'-13.1.5.aar'
    pinned=verification.find("g:components/g:component[@group='dev.rikka.shizuku'][@name='"+name+"']/g:artifact[@name='"+artifact+"']/g:sha256",ns)
    if len(inputs)!=1 or len(records)!=1 or pinned is None:
        raise SystemExit('Missing reviewed Shizuku dependency inventory: '+name)
    if inputs[0]['sha256']!=pinned.get('value') or records[0]['hashes']!=[{'alg':'SHA-256','content':inputs[0]['sha256']}] or inputs[0]['license']!='MIT':
        raise SystemExit('Shizuku dependency provenance mismatch: '+name)
print('PASS fixed optional Shizuku API surface, protected provider and no generic command IPC')
