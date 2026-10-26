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
import sys
sys.path.insert(0,"tools")
from verify_release_surface import verify
lab = Path('app/build/outputs/apk/lab/app-lab.apk')
release = Path('app/build/outputs/apk/release/app-release-unsigned.apk')
with ZipFile(lab) as z:
    if 'lib/arm64-v8a/lib8bercue_adb_pairing.so' not in z.namelist():
        raise SystemExit('Lab JNI absent')
if release.exists():
    verify(release)
    print('PASS Lab JNI and existing unsigned Release surface checked; production signature not checked here')
else:
    print('PASS Lab JNI present; NOT CHECKED: unsigned Release APK absent')
PYLAB
