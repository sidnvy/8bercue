#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export STAGE_ADB_PAIRING_LAB=1
bash tools/build_adb_crypto_source.sh
./gradlew -p . :app:assembleLab --console=plain
python3 tools/verify_lab_artifact.py app/build/outputs/apk/lab/app-lab.apk --sdk "$ANDROID_HOME" \
  --output app/build/lab-artifact-inventory.json
python3 - <<'PYLAB'
from pathlib import Path
from zipfile import ZipFile
lab=Path('app/build/outputs/apk/lab/app-lab.apk')
release=Path('app/build/outputs/apk/release/app-release-unsigned.apk')
with ZipFile(lab) as z:
    assert 'lib/arm64-v8a/lib8bercue_adb_pairing.so' in z.namelist(), 'Lab JNI absent'
if release.exists():
    with ZipFile(release) as z:
        assert 'lib/arm64-v8a/lib8bercue_adb_pairing.so' not in z.namelist(), 'Experimental JNI leaked into release'
print('PASS Lab JNI present; Release JNI isolated')
PYLAB
