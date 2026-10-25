#!/usr/bin/env python3
"""Mutation tests of the real release ZIP policy, including optimized Python.

These deliberately altered ZIPs are not signature, Android, or runtime tests.
"""
import argparse
import json
import struct
import subprocess
import sys
import tempfile
import warnings
import zipfile
from pathlib import Path
from hashlib import sha256

from adb_native_policy import LIBRARY, RECEIPT

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('apk', type=Path)
a = p.parse_args()
with zipfile.ZipFile(a.apk) as source:
    contents = {name: source.read(name) for name in source.namelist() if not name.endswith('/')}

def mutate(case):
    files = dict(contents)
    receipt = json.loads(files[RECEIPT])
    if case == 'missing_jni': del files[LIBRARY]
    elif case == 'extra_jni': files['lib/arm64-v8a/libextra.so'] = files[LIBRARY]
    elif case == 'unlisted_native': files['lib/arm64-v8a/unlisted'] = b'unlisted'
    elif case == 'wrong_abi': files[LIBRARY.replace('arm64-v8a', 'x86_64')] = files.pop(LIBRARY)
    elif case == 'missing_receipt': del files[RECEIPT]
    elif case == 'extra_receipt_asset': files['assets/adb-pairing/unlisted.json'] = b'{}'
    elif case == 'extra_lpac': files['assets/fiveber-lpac/unlisted'] = b'unlisted'
    elif case == 'lpac_hash': files['assets/fiveber-lpac/lpac'] += b'changed'
    elif case == 'license': files['assets/legal/third-party-notices.txt'] = b'incomplete'
    elif case.startswith('receipt_'):
        field = case.removeprefix('receipt_')
        receipt[field] = 'unapproved'
    elif case.startswith('elf_'):
        data = bytearray(files[LIBRARY])
        if case == 'elf_machine': struct.pack_into('<H', data, 18, 62)
        elif case == 'elf_type': struct.pack_into('<H', data, 16, 2)
        elif case == 'elf_truncated': data = data[:63]
        elif case == 'elf_dependency': data = data.replace(b'libc.so\0', b'evil.so\0')
        elif case == 'elf_alignment':
            off = struct.unpack_from('<Q', data, 32)[0]
            size, count = struct.unpack_from('<HH', data, 54)
            for i in range(count):
                if struct.unpack_from('<I', data, off + i * size)[0] == 1:
                    struct.pack_into('<Q', data, off + i * size + 48, 4096)
        files[LIBRARY] = data
        # Recompute receipt, so rejection must come from ELF inspection.
        receipt['librarySha256'] = sha256(data).hexdigest()
    if RECEIPT in files: files[RECEIPT] = json.dumps(receipt).encode()
    return files

cases = ['good', 'missing_jni', 'extra_jni', 'unlisted_native', 'wrong_abi',
         'missing_receipt', 'extra_receipt_asset', 'extra_lpac', 'lpac_hash', 'license',
         'duplicate', 'elf_machine', 'elf_type', 'elf_truncated', 'elf_dependency', 'elf_alignment']
cases += ['receipt_' + field for field in json.loads(contents[RECEIPT])]
cases += ['receipt_unreviewed_field']
checks = 0
with tempfile.TemporaryDirectory() as temp:
    fixture = Path(temp) / 'fixture.apk'
    for optimized in [False, True]:
        for case in cases:
            files = mutate(case)
            with zipfile.ZipFile(fixture, 'w') as z:
                for name, data in files.items(): z.writestr(name, data)
                if case == 'duplicate':
                    with warnings.catch_warnings():
                        warnings.simplefilter('ignore', UserWarning)
                        z.writestr(LIBRARY, files[LIBRARY])
            command = [sys.executable, *(['-O'] if optimized else []),
                       str(Path(__file__).with_name('verify_release_surface.py')), str(fixture)]
            result = subprocess.run(command, capture_output=True, text=True)
            if (result.returncode == 0) != (case == 'good'):
                raise SystemExit(f'FAIL release admission {case}, optimized={optimized}: {result.stderr[-500:]}')
            if case != 'good' and '"releaseSurface": "PASS"' in result.stdout:
                raise SystemExit('FAIL rejected payload emitted success')
            checks += 1
print(f'PASS {checks} actual release ZIP mutation checks including python -O; no signature/runtime claim')
