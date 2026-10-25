#!/usr/bin/env python3
"""Verify the copyright-permission guard, with public synthetic policy mutations."""
import json
import tempfile
from pathlib import Path
from verify_release_admission import verify

root = Path(__file__).resolve().parents[2]
policy = json.loads((root / 'release/ADB_NATIVE_ADMISSION.json').read_text())
permission = (root / policy['permissionFile']).read_bytes()
notice = (root / 'android-app/app/src/main/assets/legal/third-party-notices.txt').read_bytes()
checks = 0
with tempfile.TemporaryDirectory() as temp:
    target = Path(temp)
    (target / 'release').mkdir()
    (target / 'android-app/app/src/main/assets/legal').mkdir(parents=True)
    for case in ['good', 'pending', 'unauthorized', 'revision', 'source', 'inventory',
                 'path', 'digest', 'proposal', 'not_bundled']:
        candidate = dict(policy)
        text = permission
        disclosure = notice
        if case == 'pending': candidate['status'] = 'PENDING_OWNER_LICENSE_DECISION'
        elif case == 'unauthorized': candidate['publicationAuthorized'] = False
        elif case == 'revision': candidate['boringsslRevision'] = '0' * 40
        elif case == 'source': candidate['sourceArchiveSha256'] = '0' * 64
        elif case == 'inventory': candidate['libraries'] = ['lib/arm64-v8a/libunreviewed.so']
        elif case == 'path': candidate['permissionFile'] = '../unreviewed.txt'
        elif case == 'digest': candidate['permissionSha256'] = '0' * 64
        elif case == 'proposal': text = b'PROPOSAL ONLY\n' + permission
        elif case == 'not_bundled': disclosure = b'missing permission'
        (target / 'release/ADB_NATIVE_ADMISSION.json').write_text(json.dumps(candidate))
        (target / policy['permissionFile']).write_bytes(text)
        (target / 'android-app/app/src/main/assets/legal/third-party-notices.txt').write_bytes(disclosure)
        try:
            verify(target)
            accepted = True
        except ValueError:
            accepted = False
        if accepted != (case == 'good'):
            raise SystemExit('FAIL native permission guard: ' + case)
        checks += 1
print(f'PASS {checks} native copyright-permission guard checks')
