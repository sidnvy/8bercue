#!/usr/bin/env python3
"""Fail closed on an ungranted or mismatched native distribution permission."""
import hashlib
import json
from pathlib import Path
from adb_native_policy import REVISION, SOURCE_SHA256, LIBRARY


def verify(root):
    policy = json.loads((root / 'release/ADB_NATIVE_ADMISSION.json').read_text())
    if policy.get('status') != 'ADMITTED' or policy.get('publicationAuthorized') is not True:
        raise ValueError('Native publication permission is pending')
    if policy.get('boringsslRevision') != REVISION or policy.get('sourceArchiveSha256') != SOURCE_SHA256:
        raise ValueError('Native permission covers a different source revision')
    if set(policy.get('libraries', [])) != {'lib/arm64-v8a/libeuicc-drivers.so', LIBRARY}:
        raise ValueError('Native permission must name the exact approved libraries')
    if policy.get('permissionFile') != 'release/BORINGSSL-LINKING-PERMISSION.txt':
        raise ValueError('Unrecognized permission file')
    permission = (root / policy['permissionFile']).read_bytes()
    if b'PROPOSAL' in permission or len(permission) < 200:
        raise ValueError('Distribution permission has not been granted')
    if hashlib.sha256(permission).hexdigest() != policy.get('permissionSha256'):
        raise ValueError('Native permission digest mismatch')
    notice = (root / 'android-app/app/src/main/assets/legal/third-party-notices.txt').read_bytes()
    if permission not in notice:
        raise ValueError('Distribution permission must be bundled verbatim')
    return {'nativePublicationPermission': 'PASS', 'boringsslRevision': REVISION}


if __name__ == '__main__':
    print(json.dumps(verify(Path(__file__).resolve().parents[2]), sort_keys=True))
