#!/usr/bin/env bash
# Offline source-locked native build. Does not use ADB, cards, or remote services.
set -euo pipefail
LAB="$(cd "$(dirname "$0")/../.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.3.13750724}"
export LPAC_BUILD="${LPAC_BUILD:-$LAB/.native-build/android}"
python3 "$LAB/android-app/tools/prepare_native_sources.py" "$LPAC_BUILD/sources"
LPAC_SOURCE="$LPAC_BUILD/sources/lpac"
CJSON_SOURCE="$LPAC_BUILD/sources/cjson"
cmake -S "$LPAC_SOURCE" -B "$LPAC_BUILD/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=OFF \
  -DCMAKE_EXE_LINKER_FLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384' \
  -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384' \
  -DCMAKE_C_FLAGS="-ffile-prefix-map=$LPAC_BUILD=/8bercue-native" \
  -DFETCHCONTENT_FULLY_DISCONNECTED=ON -DFETCHCONTENT_SOURCE_DIR_CJSON="$CJSON_SOURCE" \
  -DLPAC_DYNAMIC_LIBEUICC=OFF -DSTANDALONE_MODE=ON \
  -DLPAC_WITH_APDU_PCSC=OFF -DLPAC_WITH_HTTP_CURL=OFF \
  -DLPAC_WITH_APDU_AT=OFF -DLPAC_WITH_APDU_GBINDER=OFF \
  -DLPAC_WITH_APDU_QMI=OFF -DLPAC_WITH_APDU_QMI_QRTR=OFF \
  -DLPAC_WITH_APDU_UQMI=OFF -DLPAC_WITH_APDU_MBIM=OFF -DBUILD_TESTING=OFF
cmake --build "$LPAC_BUILD/build" --parallel "${BUILD_JOBS:-4}"
LPAC_ANDROID_BUILD="$LPAC_BUILD/build" python3 "$LAB/android-app/tools/package_native_lpac.py"
python3 "$LAB/android-app/tools/verify_native_release.py" --require-page-size 16384
