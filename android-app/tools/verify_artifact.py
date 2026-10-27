#!/usr/bin/env python3
"""Validate release APK metadata and signature. Unsigned validation is explicitly separate."""
import argparse, json, re, subprocess
import zipfile
from urllib.parse import urlsplit
from pathlib import Path
from verify_release_surface import verify

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('apk',type=Path);p.add_argument('--build-tools',type=Path,required=True)
p.add_argument('--allow-unsigned',action='store_true')
p.add_argument('--require-reporting',action='store_true',help='Reject a publication APK without a valid HTTPS client DSN')
p.add_argument('--expected-signer-sha256');p.add_argument('--deny-signer-sha256',action='append',default=[])
a=p.parse_args()
for digest in a.deny_signer_sha256+([a.expected_signer_sha256] if a.expected_signer_sha256 else []):
    if not re.fullmatch('[a-fA-F0-9]{64}',digest):p.error('Invalid certificate digest')
if not a.allow_unsigned and not a.expected_signer_sha256:p.error('Production validation requires the owner-approved signer digest')
result=verify(a.apk)
def run(name,*args):
    return subprocess.run([str(a.build_tools/name),*map(str,args)],capture_output=True,text=True,check=True).stdout
badging=run('aapt2','dump','badging',a.apk)
for field in ["name='dev.sid.fiveberinspector'","minSdkVersion:'28'","targetSdkVersion:'36'","native-code: 'arm64-v8a'"]:
    if field not in badging:raise SystemExit('FAIL APK metadata: '+field)
if 'application-debuggable' in badging:raise SystemExit('FAIL debuggable release')
tree=run('aapt2','dump','xmltree',a.apk,'--file','AndroidManifest.xml')
dsn_match=re.search(r'android:name\([^)]*\)="io\.sentry\.dsn"[^\n]*\n\s*A: android:value\([^)]*\)="([^"\n]*)"',tree)
# aapt2 may retain the namespace URI prefix; accept its canonical dump form too.
if not dsn_match:
    dsn_match=re.search(r'android:name\([^)]*\)="io\.sentry\.dsn"[^\n]*\n\s*A: http://schemas.android.com/apk/res/android:value\([^)]*\)="([^"\n]*)"',tree)
dsn=urlsplit(dsn_match.group(1) if dsn_match else '')
configured=dsn.scheme=='https' and bool(dsn.hostname) and bool(dsn.username) and dsn.password is None and bool(re.fullmatch(r'/[0-9]+',dsn.path)) and not dsn.query and not dsn.fragment
if a.require_reporting and not configured:raise SystemExit('FAIL configured HTTPS reporting DSN required')
result['reportingConfiguration']='CONFIGURED' if configured else 'UNAVAILABLE'
# Only the official optional Shizuku Binder handoff is exported. The actual
# one-use launcher is not an Android service/provider and accepts no command input.
providers = re.split(r'(?m)^\s*E: provider(?:\s|$)', tree)[1:]
shizuku = [block.split('E: provider', 1)[0] for block in providers if 'rikka.shizuku.ShizukuProvider' in block]
if len(shizuku) != 1 or 'android.permission.INTERACT_ACROSS_USERS_FULL' not in shizuku[0]:
    raise SystemExit('FAIL protected optional Shizuku handoff required')
if 'ShizukuBootstrapService' in tree:raise SystemExit('FAIL bootstrap must not be an exported Android component')
if not re.search(r'android:allowBackup\([^)]*\)=(?:false|\(type 0x12\)0x0)(?:\s|$)',tree):raise SystemExit('FAIL backup must be disabled')
run('zipalign','-c','-P','16','4',a.apk)
signed=subprocess.run([str(a.build_tools/'apksigner'),'verify','--verbose','--print-certs',str(a.apk)],capture_output=True,text=True)
signers=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})',signed.stdout)
if signed.returncode==0:
    if len(signers)!=1:raise SystemExit('FAIL ambiguous signing identity')
    digest=signers[0].lower()
    if digest in [v.lower() for v in a.deny_signer_sha256]:raise SystemExit('FAIL prohibited test signer')
    if not a.expected_signer_sha256 or digest!=a.expected_signer_sha256.lower():raise SystemExit('FAIL unapproved signing identity')
    result.update(signature='VERIFIED_APPROVED_IDENTITY',signerSha256=digest)
else:
    if not a.allow_unsigned:raise SystemExit('FAIL signed production artifact required')
    with zipfile.ZipFile(a.apk) as archive:
        if any(re.fullmatch(r'META-INF/[^/]+\.(?:RSA|DSA|EC)',name,re.I) for name in archive.namelist()) or b'APK Sig Block 42' in a.apk.read_bytes():
            raise SystemExit('FAIL invalid signature is not an unsigned artifact')
    result['signature']='UNSIGNED_ENGINEERING_ONLY'
result.update(androidMetadata='PASS',zipAlignment16KB='PASS')
print(json.dumps(result,sort_keys=True))
