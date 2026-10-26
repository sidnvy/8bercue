"""Exact source-built ADB JNI admission. No arbitrary native-library allowance."""
import hashlib
import json
import struct
from pathlib import Path

REVISION = '571a7432a19592c620fa316abde47770dad4f82b'
SOURCE_SHA256 = 'd44a73d4927b66f0d43b51f171a044a900b91eeb431763083915f33e64713a0e'
LIBRARY = 'lib/arm64-v8a/lib8bercue_adb_pairing.so'
RECEIPT = 'assets/adb-pairing/provenance.json'


def check_elf(data):
    if len(data) < 64 or data[:6] != b'\x7fELF\x02\x01':
        raise ValueError('ADB JNI must be little-endian ELF64')
    if struct.unpack_from('<HH', data, 16) != (3, 183):
        raise ValueError('ADB JNI must be an AArch64 shared library')
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    if size != 56 or not 0 < count <= 128 or offset + size * count > len(data):
        raise ValueError('Invalid ADB ELF program headers')
    loads, dynamics = [], []
    for i in range(count):
        kind, flags, off, va, pa, filesz, memsz, align = struct.unpack_from('<IIQQQQQQ', data, offset + i * size)
        if off + filesz > len(data):
            raise ValueError('Truncated ADB ELF segment')
        if kind == 1:
            if align < 16384 or align & (align - 1) or (va - off) % 16384:
                raise ValueError('ADB JNI requires 16 KiB ELF alignment')
            loads.append((off, va, filesz))
        elif kind == 2:
            dynamics.append((off, filesz))
    if not loads or len(dynamics) != 1:
        raise ValueError('Invalid ADB JNI load/dynamic segments')
    off, length = dynamics[0]
    if length % 16:
        raise ValueError('Invalid ADB JNI dynamic table')
    needed, strtab, strsz, terminated = [], None, None, False
    for pos in range(off, off + length, 16):
        tag, value = struct.unpack_from('<qQ', data, pos)
        if tag == 0:
            terminated = True
            break
        if tag in (15, 29):
            raise ValueError('ADB JNI must not specify a library search path')
        if tag == 1:
            needed.append(value)
        elif tag == 5:
            if strtab is not None: raise ValueError('Duplicate ELF string table')
            strtab = value
        elif tag == 10:
            if strsz is not None: raise ValueError('Duplicate ELF string size')
            strsz = value
    if not terminated or strtab is None or strsz is None or not needed:
        raise ValueError('Incomplete ADB JNI dynamic metadata')
    tables = [data[o + strtab - v:o + strtab - v + strsz]
              for o, v, n in loads if v <= strtab and strtab + strsz <= v + n]
    if len(tables) != 1:
        raise ValueError('Invalid ADB JNI string table address')
    table, dependencies = tables[0], []
    for index in needed:
        end = table.find(b'\0', index)
        if index >= len(table) or end < 0:
            raise ValueError('Invalid ADB JNI dependency string')
        name = table[index:end].decode('ascii')
        if name not in {'libc.so', 'libm.so', 'libdl.so', 'liblog.so'}:
            raise ValueError('Unexpected ADB JNI dependency')
        dependencies.append(name)
    return sorted(dependencies)


def verify(archive, prefix=''):
    data = archive.read(prefix + LIBRARY)
    receipt = json.loads(archive.read(prefix + RECEIPT))
    expected = {
        'schema': 1,
        'boringsslRevision': REVISION,
        'sourceArchiveSha256': SOURCE_SHA256,
        'jniSourceSha256': hashlib.sha256((Path(__file__).resolve().parents[1] /
            'app/src/release/cpp/on_device_adb_pairing.cpp').read_bytes()).hexdigest(),
        'librarySha256': hashlib.sha256(data).hexdigest(),
        'abi': 'arm64-v8a',
        'ndk': '27.3.13750724',
    }
    if receipt != expected:
        raise ValueError('ADB JNI source provenance mismatch')
    notice = archive.read(prefix + 'assets/legal/third-party-notices.txt')
    license_bytes = (Path(__file__).resolve().parents[1] / 'third_party/boringssl-LICENSE.txt').read_bytes()
    if license_bytes not in notice:
        raise ValueError('Complete BoringSSL notices required')
    return {'sourceRevision': REVISION, 'sha256': expected['librarySha256'],
            'dynamicDependencies': check_elf(data), 'elfAlignment16KB': 'PASS'}
