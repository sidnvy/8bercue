#!/usr/bin/env python3
"""Inventory a Lab APK. Never grants production release approval."""
import argparse,hashlib,json,pathlib,subprocess,tempfile,zipfile,re
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('apk',type=pathlib.Path);p.add_argument('--sdk',type=pathlib.Path,required=True)
p.add_argument('--output',type=pathlib.Path);a=p.parse_args()
tools=a.sdk/'build-tools/35.0.0'
subprocess.run([str(tools/'zipalign'),'-c','-P','16','4',str(a.apk)],check=True)
signed=subprocess.check_output([str(tools/'apksigner'),'verify','--verbose','--print-certs',str(a.apk)],text=True)
signer=re.findall(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})',signed)
assert len(signer)==1,'Expected signed Lab APK'
badging=subprocess.check_output([str(tools/'aapt2'),'dump','badging',str(a.apk)],text=True)
assert 'application-debuggable' in badging and "name='dev.sid.fiveberinspector'" in badging,'Expected explicit Lab variant'
readelf=a.sdk/'ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
with zipfile.ZipFile(a.apk) as z,tempfile.TemporaryDirectory() as temp:
    libraries=sorted(n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so'))
    assert libraries==['lib/arm64-v8a/lib8bercue_adb_pairing.so']
    manifest=json.loads(z.read('assets/fiveber-lpac/manifest.json'))
    assert manifest['abi']=='arm64-v8a'
    assert set(manifest['files'])=={'lpac','libeuicc-driver-loader.so','libeuicc-drivers.so','liblpac-utils.so','driver/driver_apdu_stdio.so','driver/driver_http_stdio.so'}
    for name,meta in manifest['files'].items():
        data=z.read('assets/fiveber-lpac/'+name)
        assert len(data)==meta['size'] and hashlib.sha256(data).hexdigest()==meta['sha256']
    libraries+=['assets/fiveber-lpac/'+n for n in manifest['files']]
    assert 'BoringSSL is a fork of OpenSSL' in z.read('assets/legal/third-party-notices.txt').decode()
    inventory=[]
    for name in libraries:
        data=z.read(name);file=pathlib.Path(temp)/pathlib.Path(name).name;file.write_bytes(data)
        header=subprocess.check_output([str(readelf),'-h',str(file)],text=True)
        assert 'AArch64' in header and 'ELF64' in header
        segments=subprocess.check_output([str(readelf),'-lW',str(file)],text=True)
        loads=[line.split() for line in segments.splitlines() if line.strip().startswith('LOAD ')]
        assert loads and all(int(line[-1],16)>=16384 for line in loads)
        dynamic=subprocess.check_output([str(readelf),'-dW',str(file)],text=True)
        needed=[line.split('[')[-1].split(']')[0] for line in dynamic.splitlines() if '(NEEDED)' in line]
        allowed={'libc.so','libm.so','libdl.so','liblog.so'}
        if name.startswith('assets/'):
            allowed|={'libeuicc-drivers.so','libeuicc-driver-loader.so','liblpac-utils.so'}
        assert set(needed)<=allowed
        inventory.append(dict(path=name,sha256=hashlib.sha256(data).hexdigest(),size=len(data),needed=needed))
result=dict(artifact=a.apk.name,sha256=hashlib.sha256(a.apk.read_bytes()).hexdigest(),
    classification='SIGNED_DEBUGGABLE_LAB_ONLY',productionReady=False,signerSha256=signer[0],
    abi='arm64-v8a',elfAndZipAlignment16384='PASS',boringsslNotices='PASS',libraries=inventory)
text=json.dumps(result,indent=2)+'\n'
if a.output:a.output.write_text(text)
print(text,end='')
