#!/usr/bin/env python3
"""Extract hash-locked, complete upstream sources; never edit original checkouts."""
import hashlib,json,sys,tarfile,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
lock_bytes=(ROOT/'native/source-lock.json').read_bytes()
lock=json.loads(lock_bytes)
lock_digest=hashlib.sha256(lock_bytes).hexdigest()
destination=Path(sys.argv[1]).resolve()
# Do not trust a pre-existing build source tree. Reuse only an identical verified tree.
def digest_tree(path):
    h=hashlib.sha256()
    for p in sorted(path.rglob('*')):
        if p.is_file():h.update(str(p.relative_to(path)).encode()+b'\0'+p.read_bytes()+b'\0')
    return h.hexdigest()
for source in lock['sources']:
    archive=ROOT/source['archive']
    assert hashlib.sha256(archive.read_bytes()).hexdigest()==source['sha256'],'source archive digest mismatch'
    target=destination/source['component'];receipt=destination/(source['component']+'.sha256')
    if target.exists():
        if not receipt.is_file() or receipt.read_text().strip()!=lock_digest+':'+digest_tree(target):
            raise SystemExit('BLOCKED: existing native source tree changed; use a fresh build directory')
        continue
    target.mkdir(parents=True)
    with tarfile.open(archive) as bundle:
        # Refuse links and escapes even for hash-pinned archives.
        for item in bundle.getmembers():
            if item.issym() or item.islnk() or item.name.startswith('/') or '..' in Path(item.name).parts:
                raise SystemExit('Unsafe native archive member')
        bundle.extractall(target,filter='data')
    if source['component']=='lpac':
        for patch in lock['patches']:
            path=ROOT/patch['file'];assert hashlib.sha256(path.read_bytes()).hexdigest()==patch['sha256']
            subprocess.run(['git','apply','--check',str(path)],cwd=target,check=True)
            subprocess.run(['git','apply',str(path)],cwd=target,check=True)
    receipt.write_text(lock_digest+':'+digest_tree(target)+'\n')
print('PASS native source closure and patch integrity')
