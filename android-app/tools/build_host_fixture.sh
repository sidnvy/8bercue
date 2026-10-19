#!/usr/bin/env bash
# Synthetic offline-test engine. No Android, network or operator traffic.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FIXTURE_BUILD="${FIXTURE_BUILD:-$ROOT/.native-build/host}"
python3 "$ROOT/android-app/tools/prepare_native_sources.py" "$FIXTURE_BUILD/sources"
cmake -S "$FIXTURE_BUILD/sources/lpac" -B "$FIXTURE_BUILD/build" -G Ninja \
 -DCMAKE_BUILD_TYPE=Release -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=OFF \
 -DFETCHCONTENT_FULLY_DISCONNECTED=ON -DFETCHCONTENT_SOURCE_DIR_CJSON="$FIXTURE_BUILD/sources/cjson" \
 -DLPAC_DYNAMIC_LIBEUICC=OFF -DSTANDALONE_MODE=ON \
 -DLPAC_WITH_APDU_PCSC=OFF -DLPAC_WITH_HTTP_CURL=OFF \
 -DLPAC_WITH_APDU_AT=OFF -DLPAC_WITH_APDU_GBINDER=OFF \
 -DLPAC_WITH_APDU_QMI=OFF -DLPAC_WITH_APDU_QMI_QRTR=OFF \
 -DLPAC_WITH_APDU_UQMI=OFF -DLPAC_WITH_APDU_MBIM=OFF -DBUILD_TESTING=OFF
cmake --build "$FIXTURE_BUILD/build" --parallel "${BUILD_JOBS:-4}"
printf 'FIVEBER_LPAC_FIXTURE=%s/build/src/lpac\n' "$FIXTURE_BUILD"
