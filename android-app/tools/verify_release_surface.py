#!/usr/bin/env python3
"""Audit actual APK/AAB payloads. This does not certify authorization or runtime gates."""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path

LAB_CLASSES = {
    'PhoneSpeedtestDownload', 'SpeedtestDownloadHalBridge', 'SpeedtestDownloadPolicy',
    'PhoneProfileSwitchSmoke', 'ScopedEnableHalBridge', 'UbigiEmergencyRestore',
    'CardProfileReadOnlyProbe', 'CrossPhoneProfileReadProbe', 'CardBaselineGuard',
    'CompanionReadiness', 'TestAssetGrant', 'TestAssetGrantInspector', 'ReviewedDownloadRecovery',
    'LabShellCompanionClient', 'CompanionBinderStarter', 'CompanionProbeService',
    'KnownCardReadOnlyInventory',
    'CompanionIngressTestInstrumentation', 'CompanionIngressNegativeProbe', 'UiLayoutTestInstrumentation',
}
FORBIDDEN = (b'5D7937BEFD986578', b'0A0E26091CA92454', b'QRF-SPEEDTEST',
             b'8bercue-test-authority', b'rsp.truphone.com', b'com/tananaev/adblib/')

def dex_strings(data):
    if data[:4] != b'dex\n' or len(data) < 112:
        raise ValueError('Invalid DEX')
    count, offset = struct.unpack_from('<II', data, 56)
    if count > 1_000_000 or offset + count * 4 > len(data):
        raise ValueError('Invalid string table')
    result = []
    for i in range(count):
        pos = struct.unpack_from('<I', data, offset + i * 4)[0]
        for _ in range(5):
            if pos >= len(data): raise ValueError('Invalid string offset')
            value = data[pos]; pos += 1
            if not value & 128: break
        else: raise ValueError('Invalid string length')
        end = data.find(b'\0', pos)
        if end < 0: raise ValueError('Unterminated string')
        result.append(data[pos:end])
    return result

def verify(path):
    with zipfile.ZipFile(path) as archive:
        names=archive.namelist()
        if len(names)!=len(set(names)): raise ValueError('Duplicate ZIP entry')
        dex=[n for n in names if re.fullmatch(r'(?:base/dex/)?classes\d*\.dex',n)]
        if not dex: raise ValueError('Missing DEX')
        for name in dex:
            for value in dex_strings(archive.read(name)):
                if any(marker.lower() in value.lower() for marker in FORBIDDEN):
                    raise ValueError('Laboratory constant found in '+name)
                for cls in LAB_CLASSES:
                    prefix=('Ldev/sid/fiveberinspector/'+cls).encode()
                    if value.startswith(prefix) and value[len(prefix):len(prefix)+1] in (b';',b'$'):
                        raise ValueError('Laboratory class found: '+cls)
        prefix='base/' if path.suffix=='.aab' else ''
        manifest=json.loads(archive.read(prefix+'assets/fiveber-lpac/manifest.json'))
        expected={'lpac','libeuicc-driver-loader.so','libeuicc-drivers.so','liblpac-utils.so',
                  'driver/driver_apdu_stdio.so','driver/driver_http_stdio.so'}
        if manifest['abi']!='arm64-v8a' or set(manifest['files'])!=expected:
            raise ValueError('Unexpected native payload set')
        for name, meta in manifest['files'].items():
            data=archive.read(prefix+'assets/fiveber-lpac/'+name)
            if len(data)!=meta['size'] or hashlib.sha256(data).hexdigest()!=meta['sha256']:
                raise ValueError('Native hash mismatch: '+name)
        libraries=[n for n in names if n.startswith(prefix+'lib/') and n.endswith('.so')]
        expected_library=prefix+'lib/arm64-v8a/libeuicc-drivers.so'
        if libraries!=[expected_library] or archive.read(expected_library)!=archive.read(prefix+'assets/fiveber-lpac/libeuicc-drivers.so'):
            raise ValueError('Missing or inconsistent ARM64 package metadata')
        for notice in ['privacy.txt','third-party-notices.txt']:
            if len(archive.read(prefix+'assets/legal/'+notice))<100:
                raise ValueError('Missing bundled disclosure')
        if any(n.lower().endswith(('.keystore','.jks','.properties.pending')) for n in names):
            raise ValueError('Private state/key file included')
    return {'artifact':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
            'releaseSurface':'PASS','dexFiles':len(dex),'nativeFiles':len(expected),
            'productionReady':False}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifacts',type=Path,nargs='+');args=parser.parse_args()
    for artifact in args.artifacts: print(json.dumps(verify(artifact),sort_keys=True))
