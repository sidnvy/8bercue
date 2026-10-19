#!/usr/bin/env python3
"""Check ALL packaged ELF payloads. Alignment PASS is not 16 KB runtime PASS."""
import argparse,hashlib,json,struct
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--require-page-size',type=int,default=16384);a=p.parse_args()
root=Path(__file__).resolve().parents[1]/'app/src/main/assets/fiveber-lpac'
m=json.loads((root/'manifest.json').read_text());assert m['abi']=='arm64-v8a'
expected={'lpac','libeuicc-driver-loader.so','libeuicc-drivers.so','liblpac-utils.so','driver/driver_apdu_stdio.so','driver/driver_http_stdio.so'}
assert set(m['files'])==expected
for name,meta in sorted(m['files'].items()):
    data=(root/name).read_bytes()
    assert len(data)==meta['size'] and hashlib.sha256(data).hexdigest()==meta['sha256'],name
    assert data[:6]==b'\x7fELF\x02\x01' and struct.unpack_from('<H',data,18)[0]==183,name
    offset=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54);loads=0
    for i in range(count):
        kind,flags,off,vaddr,paddr,filesz,memsz,align=struct.unpack_from('<IIQQQQQQ',data,offset+i*size)
        if kind==1:
            loads+=1
            assert align>=a.require_page_size and align&(align-1)==0,(name,align)
            assert (vaddr-off)%a.require_page_size==0,(name,'offset/vaddr')
    assert loads>0,name
    print('PASS',name,'ELF alignment >=',a.require_page_size)
print('OFFLINE_ONLY: 16KB/4KB Android runtime remains a separate required gate')
