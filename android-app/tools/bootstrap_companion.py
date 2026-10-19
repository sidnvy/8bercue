#!/usr/bin/env python3
"""Connect an already authorized phone. Never install, enable TCP ADB, or issue a card operation."""
import argparse,re,subprocess,shlex,time,secrets
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--adb',default='adb');p.add_argument('--allow-existing-wireless',action='store_true');a=p.parse_args()
if not re.fullmatch(r'[A-Za-z0-9._:-]{1,128}',a.serial):p.error('Invalid device serial')
if ':' in a.serial and not a.allow_existing_wireless:p.error('USB is the default; existing authorized wireless transport requires --allow-existing-wireless')
def run(*cmd):return subprocess.check_output([a.adb,'-s',a.serial,*cmd],text=True,timeout=20).strip()
if run('get-state')!='device':raise SystemExit('Device not authorized')
if run('shell','am','get-current-user')!='0':raise SystemExit('Only owner user is supported')
paths=run('shell','pm','path','dev.sid.fiveberinspector').splitlines()
base=[v.removeprefix('package:') for v in paths if v.endswith('/base.apk')]
if len(base)!=1 or not re.fullmatch(r'/data/app/[A-Za-z0-9_~./+=-]+/base\.apk',base[0]):raise SystemExit('Installed app unavailable')
# Handoff is allowed only after the user starts the short-lived session in the app.
challenge=run('shell','content','call','--uri','content://dev.sid.fiveberinspector.companion','--method','getManagementChallenge')
if 'nonce=' not in challenge:raise SystemExit('Choose Connect using computer in 8bercue first')
log_path='/data/local/tmp/8bercue-management-'+secrets.token_hex(12)+'.log'
command='CLASSPATH='+shlex.quote(base[0])+' nohup app_process / --nice-name=8bercue-management-v1 dev.sid.fiveberinspector.CompanionManagementStarter --connect-management > '+shlex.quote(log_path)+' 2>&1 < /dev/null &'
run('shell',command)
for _ in range(10):
 log=run('shell','cat',log_path)
 if 'MANAGEMENT_BINDER_READY' in log:print('PASS management Binder handoff; no card mutation submitted');break
 if 'Exception' in log:raise SystemExit('Handoff failed; inspect local diagnostic and app state')
 time.sleep(0.5)
else:raise SystemExit('Handoff not confirmed; do not repeat a card operation')
