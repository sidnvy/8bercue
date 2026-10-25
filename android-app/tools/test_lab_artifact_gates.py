#!/usr/bin/env python3
"""Exercise production Lab gate logic with synthetic ZIPs/tool outputs, including -O.
This is a negative-policy regression, not an actual APK/signature/ELF acceptance.
"""
import ast, hashlib, json, os, pathlib, re, subprocess, sys, tempfile, zipfile
root=pathlib.Path(__file__).resolve().parents[1]
verifier=root/'tools/verify_lab_artifact.py'
checks=0
DRIVER="""
import os,pathlib,runpy,subprocess,sys
case=os.environ.get('GATE_CASE','good')
def output(args,**kwargs):
    name=pathlib.Path(args[0]).name
    if name=='apksigner':
        if case=='signer':return ''
        cert='Signer #1 certificate SHA-256 digest: '+'a'*64
        return cert+('\\nSigner #2 certificate SHA-256 digest: '+'b'*64 if case=='multiple_signers' else '')
    if name=='aapt2':return "name='dev.sid.fiveberinspector'"+('' if case=='variant' else ' application-debuggable')
    if args[1]=='-h':return 'ELF32 ARM' if case=='machine' else 'ELF64 AArch64'
    if args[1]=='-lW':return 'LOAD 0x1000' if case=='alignment' else 'LOAD 0x4000'
    if args[1]=='-dW':return '(NEEDED) [libevil.so]' if case=='dependency' else '(NEEDED) [libc.so]'
    raise RuntimeError('Unexpected mocked tool call')
subprocess.check_output=output
subprocess.run=lambda *a,**k:None
script=sys.argv[1];sys.argv=[script,*sys.argv[2:]]
runpy.run_path(script,run_name='__main__')
"""
def check(condition,label):
    global checks
    if not condition:raise SystemExit('FAIL '+label)
    checks+=1
def heredoc(name,tag):
    return (root/'tools'/name).read_text().split("<<'"+tag+"'\n",1)[1].split('\n'+tag,1)[0]
with tempfile.TemporaryDirectory() as temp:
    work=pathlib.Path(temp);driver=work/'driver.py';driver.write_text(DRIVER)
    payload=b'synthetic-native-policy-fixture'
    files=['lpac','libeuicc-driver-loader.so','libeuicc-drivers.so','liblpac-utils.so','driver/driver_apdu_stdio.so','driver/driver_http_stdio.so']
    meta=dict(size=len(payload),sha256=hashlib.sha256(payload).hexdigest())
    def apk(path,case):
        manifest=dict(abi='wrong' if case=='abi' else 'arm64-v8a',files={n:dict(meta) for n in files})
        if case=='hash':manifest['files']['lpac']['sha256']='0'*64
        if case=='inventory':del manifest['files']['lpac']
        with zipfile.ZipFile(path,'w') as z:
            z.writestr('lib/arm64-v8a/lib8bercue_adb_pairing.so',payload)
            if case=='extra':z.writestr('lib/arm64-v8a/libextra.so',payload)
            z.writestr('assets/fiveber-lpac/manifest.json',json.dumps(manifest))
            for name in files:z.writestr('assets/fiveber-lpac/'+name,payload)
            if case=='extra_lpac':z.writestr('assets/fiveber-lpac/unlisted.so',payload)
            if case=='duplicate_lpac':
                import warnings
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore',UserWarning)
                    z.writestr('assets/fiveber-lpac/lpac',payload)
            notice=(root/'app/src/lab/assets/legal/third-party-notices.txt').read_bytes()
            z.writestr('assets/legal/third-party-notices.txt',b'BoringSSL is a fork of OpenSSL' if case=='license' else notice)
    for optimized in [False,True]:
        env=dict(os.environ);env.pop('PYTHONOPTIMIZE',None)
        flags=['-O'] if optimized else []
        if optimized:env['PYTHONOPTIMIZE']='2'
        for case in ['good','signer','multiple_signers','variant','abi','hash','inventory','extra','extra_lpac','duplicate_lpac','license','machine','alignment','dependency']:
            path=work/'fixture.apk';apk(path,case);env['GATE_CASE']=case
            result=subprocess.run([sys.executable,*flags,str(driver),str(verifier),str(path),'--sdk',str(work)],env=env,capture_output=True,text=True)
            check((result.returncode==0)==(case=='good'),f'Lab gate {case}, optimized={optimized}')
            if case!='good':check('"productionReady"' not in result.stdout,f'No success inventory for {case}')
        # Execute the actual embedded source ELF gate under the same optimization.
        script=work/'elf_gate.py';script.write_text(heredoc('build_adb_crypto_source.sh','PYELF'))
        for name in ['lib8bercue_adb_pairing.so','adb_pairing_crypto_contract']:(work/name).write_bytes(payload)
        for case in ['good','machine','alignment','dependency']:
            env['GATE_CASE']=case
            result=subprocess.run([sys.executable,*flags,str(driver),str(script),'readelf',str(work)],env=env,capture_output=True,text=True)
            check((result.returncode==0)==(case=='good'),f'Source ELF gate {case}, optimized={optimized}')
            if case!='good':check('PASS' not in result.stdout,'No false ELF PASS')
        # Execute the Lab-presence gate; incomplete synthetic Release ZIPs must fail production admission.
        script=work/'lab_gate.py';script.write_text(heredoc('build_lab_adb_pairing.sh','PYLAB').replace('sys.path.insert(0,"tools")',f'sys.path.insert(0,{str(root / "tools")!r})'))
        lab=work/'app/build/outputs/apk/lab/app-lab.apk';release=work/'app/build/outputs/apk/release/app-release-unsigned.apk'
        lab.parent.mkdir(parents=True,exist_ok=True);release.parent.mkdir(parents=True,exist_ok=True)
        for case in ['absent','isolated','leak','missing_jni']:
            with zipfile.ZipFile(lab,'w') as z:
                if case!='missing_jni':z.writestr('lib/arm64-v8a/lib8bercue_adb_pairing.so',payload)
            release.unlink(missing_ok=True)
            if case!='absent':
                with zipfile.ZipFile(release,'w') as z:
                    if case=='leak':z.writestr('lib/arm64-v8a/lib8bercue_adb_pairing.so',payload)
            result=subprocess.run([sys.executable,*flags,str(script)],cwd=work,env=env,capture_output=True,text=True)
            check((result.returncode==0)==(case=='absent'),f'Variant isolation {case}')
            if case=='absent':check('NOT CHECKED' in result.stdout and 'Release JNI isolated' not in result.stdout,'Absent Release never claimed checked')
            if case in ['isolated','leak','missing_jni']:check('PASS' not in result.stdout,'No false isolation PASS')
for code in [verifier.read_text(),heredoc('build_adb_crypto_source.sh','PYELF'),heredoc('build_lab_adb_pairing.sh','PYLAB')]:
    check(not any(isinstance(n,ast.Assert) for n in ast.walk(ast.parse(code))),'Artifact gate contains no optimizable assert')
print(f'PASS {checks} Lab artifact/embedded gate checks including python -O (synthetic ZIP and tool adapters only)')
