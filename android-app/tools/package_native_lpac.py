#!/usr/bin/env python3
"""Stage prebuilt, NDK-linked Android arm64 lpac in the signed Inspector APK.

Only copies a fixed list of offline-built binaries and emits a SHA-256 manifest.
No ADB calls, SIM access, native execution, or network requests.
"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import shutil

ROOT=Path(__file__).resolve().parents[1]
SOURCE=Path(os.environ["LPAC_ANDROID_BUILD"])
ASSETS=ROOT/"app/src/main/assets/fiveber-lpac"
LOCK_BYTES=(ROOT.parent/"native/source-lock.json").read_bytes()
LOCK=json.loads(LOCK_BYTES)
REVISION=next(item["revision"] for item in LOCK["sources"] if item["component"]=="lpac")
COMPONENTS={
    "lpac":"src/lpac",
    "libeuicc-driver-loader.so":"driver/libeuicc-driver-loader.so",
    "libeuicc-drivers.so":"driver/libeuicc-drivers.so",
    "liblpac-utils.so":"utils/liblpac-utils.so",
    "driver/driver_apdu_stdio.so":"driver/driver_apdu_stdio.so",
    "driver/driver_http_stdio.so":"driver/driver_http_stdio.so",
}

def assert_elf_arm64(binary: bytes, label: str) -> None:
    if len(binary)<64 or binary[:4]!=b"\x7fELF" or binary[4]!=2 or binary[5]!=1:
        raise ValueError(f"{label}: not an ELF64 little-endian binary")
    if int.from_bytes(binary[18:20],"little")!=183:
        raise ValueError(f"{label}: not an ARM64 binary")

def main() -> None:
    ASSETS.mkdir(parents=True,exist_ok=True)
    files={}
    for name, relative in COMPONENTS.items():
        source=SOURCE/relative
        data=source.read_bytes()
        assert_elf_arm64(data,name)
        if not 0 < len(data) < 2_000_000:
            raise ValueError(f"{name}: unexpected size")
        destination=ASSETS/name
        destination.parent.mkdir(parents=True,exist_ok=True)
        destination.write_bytes(data)
        files[name]={"size":len(data),"sha256":hashlib.sha256(data).hexdigest()}
    # Android package ABI metadata must reflect the executable payload in assets.
    # Keep this real driver library in lib/ as well; runtime extraction remains hash-checked.
    abi_dir=ROOT/"app/src/release/jniLibs/arm64-v8a"
    abi_dir.mkdir(parents=True,exist_ok=True)
    (abi_dir/"libeuicc-drivers.so").write_bytes((ASSETS/"libeuicc-drivers.so").read_bytes())
    doc={"format":1,"abi":"arm64-v8a","lpacRevision":REVISION,"sourceLockSha256":hashlib.sha256(LOCK_BYTES).hexdigest(),"androidApi":28,"ndkVersion":"27.3.13750724","pageAlignment":16384,"files":files}
    (ASSETS/"manifest.json").write_text(
        json.dumps(doc,indent=2,sort_keys=True)+"\n",encoding="utf-8")
    print("ANDROID_LPAC_BUNDLE_READY")
    print("abi:",doc["abi"],"upstream:",doc["lpacRevision"],
          "files:",len(files),"totalBytes:",sum(v["size"] for v in files.values()))
    print("digestManifest:",hashlib.sha256((ASSETS/"manifest.json").read_bytes()).hexdigest())

if __name__=="__main__":
    main()
