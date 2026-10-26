#!/usr/bin/env python3
"""Guardrails for a narrowly scoped, non-profile-mutating Telephony refresh.

Real-device validation is tracked separately in docs; this contract test must
run without Android SDK/ADB and must never send a refresh to a phone.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
java = root / 'app/src/main/java/dev/sid/fiveberinspector'
source = (java / 'SystemEsimVisibilityRepair.java').read_text()
server = (java / 'CompanionProfileRpc.java').read_text()
client = (java / 'ShellCompanionClient.java').read_text()
binder = (java / 'ProfileUserService.java').read_text()
ui = (java / 'MainActivity.java').read_text()
aidl = (root / 'app/src/main/aidl/dev/sid/fiveberinspector/IProfileService.aidl').read_text()

assert 'Process.myUid() != Process.SHELL_UID' in source
assert 'card.isEuicc() && !card.isRemovable()' in source
assert 'count != 1' in source and 'card.getCardId() < 0' in source
assert 'getUiccCardsInfo' in source and 'requestEmbeddedSubscriptionInfoListRefresh' in source
assert 'requestRefresh()' in source and 'builtInCardId()' in source
assert 'cardId' not in aidl.split('requestSystemEsimVisibilityRepair()', 1)[-1].split(';', 1)[0]
for method in ('preflightSystemEsimVisibilityRepair', 'requestSystemEsimVisibilityRepair'):
    assert method in server and method in client and method in binder and method in aidl
assert 'ServiceCaller.asShell()' in binder
assert 'this::repairSystemEsimVisibility' in ui
assert 'confirm.button(s(R.string.esim_visibility_request)' in ui
assert 'if(backend!=session)' in ui
assert 'status", "refresh_requested"' in source
# The new implementation cannot edit radio mappings, eUICC contents, or profiles.
for forbidden in ('switchToSubscription(', 'deleteSubscription(', 'downloadSubscription(',
                  'setSimSlotMapping(', 'transmitIcc', 'setEmbedded(', 'reboot('):
    assert forbidden not in source, forbidden
for path in ('values/strings.xml', 'values-b+zh+Hans/strings.xml'):
    tree = ET.parse(root / 'app/src/main/res' / path)
    values = {item.get('name') for item in tree.getroot().findall('string')}
    assert {'esim_visibility_title','esim_visibility_warning','esim_visibility_verify'} <= values
print('System eSIM visibility refresh safety contracts: PASS')
