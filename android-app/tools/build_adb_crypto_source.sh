#!/usr/bin/env bash
# Rebuild every crypto object from pinned upstream source. Lab/contract only.
set -euo pipefail
cd "$(dirname "$0")/.."
revision=571a7432a19592c620fa316abde47770dad4f82b
digest=d44a73d4927b66f0d43b51f171a044a900b91eeb431763083915f33e64713a0e
ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:?}/ndk/27.3.13750724}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl -fsSL --retry 2 --connect-timeout 15 --max-time 150 \
  "https://codeload.github.com/google/boringssl/tar.gz/$revision" -o "$work/source.tar.gz"
python3 - "$work" "$digest" <<'PYVERIFY'
import hashlib,pathlib,sys,tarfile
root=pathlib.Path(sys.argv[1]); archive=root/'source.tar.gz'
if hashlib.sha256(archive.read_bytes()).hexdigest()!=sys.argv[2]:
    raise SystemExit('BoringSSL source digest mismatch')
with tarfile.open(archive) as t:
    for item in t:
        path=pathlib.PurePosixPath(item.name)
        if path.is_absolute() or '..' in path.parts or item.issym() or item.islnk() or not (item.isfile() or item.isdir()):
            raise SystemExit('Unsafe BoringSSL archive member')
    t.extractall(root)
PYVERIFY
source="$work/boringssl-$revision"
cmp "$source/LICENSE" third_party/boringssl-LICENSE.txt
cmake -S tools/native_adb_pairing -B "$work/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DBSSL_SOURCE_ROOT="$source" -DCMAKE_BUILD_TYPE=Release
cmake --build "$work/build" --target 8bercue_adb_pairing adb_pairing_crypto_contract --parallel 4
python3 - "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" "$work/build" <<'PYELF'
import subprocess, sys, pathlib, hashlib, json
root = pathlib.Path(sys.argv[2])
inventory = []
for name in ['lib8bercue_adb_pairing.so', 'adb_pairing_crypto_contract']:
    p = root / name
    header = subprocess.check_output([sys.argv[1], '-h', str(p)], text=True)
    if not ('AArch64' in header and 'ELF64' in header):
        raise SystemExit('Expected ARM64 ELF')
    text = subprocess.check_output([sys.argv[1], '-lW', str(p)], text=True)
    loads = [s.split() for s in text.splitlines() if s.strip().startswith('LOAD ')]
    if not (loads and all((int(s[-1], 16) >= 16384 for s in loads))):
        raise SystemExit('ELF alignment')
    dynamic = subprocess.check_output([sys.argv[1], '-dW', str(p)], text=True)
    needed = [s.split('[')[-1].split(']')[0] for s in dynamic.splitlines() if '(NEEDED)' in s]
    if not set(needed) <= {'libc.so', 'libm.so', 'libdl.so', 'liblog.so'}:
        raise SystemExit('Unexpected dynamic dependency')
    inventory.append(dict(file=name, sha256=hashlib.sha256(p.read_bytes()).hexdigest(), size=p.stat().st_size, abi='arm64-v8a', pageAlignment=16384, needed=needed))
(root / 'inventory.json').write_text(json.dumps(inventory, indent=2) + '\n')
print('PASS source-built crypto ABI, 16 KiB alignment and system-only dynamic dependencies')
PYELF
if [[ "${STAGE_ADB_PAIRING_LAB:-0}" == 1 ]]; then
  mkdir -p app/build/labJniLibs/arm64-v8a
  install -m 0644 "$work/build/lib8bercue_adb_pairing.so" app/build/labJniLibs/arm64-v8a/
  cp "$work/build/inventory.json" app/build/adb-crypto-source-inventory.json
  install -m 0700 "$work/build/adb_pairing_crypto_contract" app/build/adb_pairing_crypto_contract
fi
