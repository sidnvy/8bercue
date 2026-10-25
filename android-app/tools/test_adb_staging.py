#!/usr/bin/env python3
"""Actual builder must clear old Release inputs even before a toolchain failure."""
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

source = Path(__file__).with_name('build_adb_crypto_source.sh')
checks = 0
with tempfile.TemporaryDirectory() as temp:
    root = Path(temp) / 'android-app'
    (root / 'tools').mkdir(parents=True)
    script = root / 'tools' / source.name
    shutil.copy(source, script)
    for flag in [None, '0', '1']:
        library = root / 'app/build/releaseJniLibs/arm64-v8a/lib8bercue_adb_pairing.so'
        receipt = root / 'app/build/releaseAdbAssets/adb-pairing/provenance.json'
        unrelated = root / 'app/build/labJniLibs/keep-lab-input'
        for path in [library, receipt, unrelated]:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'stale-policy-fixture')
        env = dict(os.environ)
        env.pop('STAGE_ADB_PAIRING_RELEASE', None)
        env['ANDROID_NDK_HOME'] = str(root / 'missing-ndk')
        if flag is not None: env['STAGE_ADB_PAIRING_RELEASE'] = flag
        result = subprocess.run(['bash', str(script)], env=env, capture_output=True, text=True)
        for condition, label in [
            (result.returncode != 0, 'invalid toolchain must fail'),
            (not library.exists(), 'old JNI must be removed'),
            (not receipt.exists(), 'old provenance must be removed'),
            (unrelated.read_bytes() == b'stale-policy-fixture', 'unrelated Lab input retained'),
        ]:
            if not condition: raise SystemExit(f'FAIL stage={flag}: {label}')
            checks += 1
print(f'PASS {checks} actual builder stale-staging/failure checks; no real JNI build or device I/O')
