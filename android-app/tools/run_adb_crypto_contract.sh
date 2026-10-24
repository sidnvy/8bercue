#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Isolated source-only *experimental* native build. No APK packages this output.
# Third-party BoringSSL AAR is pinned and checked BEFORE it is extracted.
BSSL_VERSION=20250114
BSSL_SHA256=366c9af78cee572ffcfad6e8c17650646b9f79492e8177d35226a2a5569f3fbc
BSSL_URL="https://repo.maven.apache.org/maven2/io/github/vvb2060/ndk/boringssl/$BSSL_VERSION/boringssl-$BSSL_VERSION.aar"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:-}/ndk/27.3.13750724}}"
if [[ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
  echo 'Android NDK 27.3.13750724 unavailable; set ANDROID_NDK_HOME' >&2
  exit 2
fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl -fsSL --retry 2 --connect-timeout 15 --max-time 150 "$BSSL_URL" -o "$work/boringssl.aar"
python3 - "$work/boringssl.aar" "$work/dependency" "$BSSL_SHA256" <<'PY'
import hashlib, pathlib,sys,zipfile
src,dest,expected=sys.argv[1:];digest=hashlib.sha256(pathlib.Path(src).read_bytes()).hexdigest()
if digest!=expected:raise SystemExit('Pinned BoringSSL digest mismatch: fail closed')
with zipfile.ZipFile(src) as archive:
 for name in archive.namelist():
  path=pathlib.PurePosixPath(name)
  if path.is_absolute() or '..' in path.parts:raise SystemExit('Unsafe dependency archive path')
 archive.extractall(dest)
print('PASS pinned BoringSSL digest and safe package extraction')
PY
cmake -S tools/native_adb_pairing -B "$work/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DBSSL_PREFAB_ROOT="$work/dependency" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$work/build" --parallel 4 >/dev/null
readelf="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
python3 - "$readelf" "$work/build/adb_pairing_crypto_contract" "$work/build/lib8bercue_adb_pairing.so" <<'PY'
import subprocess,sys
for file in sys.argv[2:]:
 p=subprocess.run([sys.argv[1],'-l',file],capture_output=True,text=True,check=True)
 loads=[s.strip() for s in p.stdout.splitlines() if s.strip().startswith('LOAD ')]
 if not loads or any(s.split()[-1]!='0x4000' for s in loads):
  raise SystemExit('16 KiB ELF PT_LOAD alignment failed: '+file)
 print('PASS 16 KiB ELF alignment: '+file.split('/')[-1])
PY
# ART test requires an Android device, intentionally not run from CI.
echo 'PASS ARM64 BoringSSL SPAKE2 test executable and JNI library compile (not bundled in APK)'
