# Pixel 10 TLS reconnect acceptance — 2026-10-24

PR #17, issue #18. **Two recovery/read cycles PASS; final source-built candidate acceptance BLOCKED by secure lock screen. Production delivery remains blocked.** This supersedes older provisional status notes; it does not turn their historical NOT TESTED results into passes.

## Scope and safeguards

Only Pixel 10 Pro XL, serial explicitly specified on every ADB command. The app's own UI initiated all management bootstrap and card reads. Workstation ADB was used only for observation, app installation/restart, UI control and a synthetic crypto executable; it did not run Connect or bootstrap management. No new pairing code, app data clearing, key rotation, reboot, adbd/debugging changes, VPN changes, paired-device removal or eUICC mutation was performed. No other phone was operated on. Screens exposed only the app's existing masked card identifiers; identifiers are excluded from this report.

## Hardware acceptance matrix

| Check | Result | Evidence / limit |
| --- | --- | --- |
| Data-preserving signed Lab upgrade | PASS, twice | Installed APK signer matched each candidate before install -r -t; parsed pairing preferences identical before/after each upgrade |
| Previously paired recovery with obsolete DER pin | PASS | Dynamic local NSD endpoint, TLS client identity presented, legacy_der_changed=true, authenticated_management_binder_ready |
| App-only restart with worker/Binder absent | PASS | No management worker before first connect; app UI Connect on this phone launches worker, no workstation bootstrap |
| Shell identity / typed Binder | PASS, two cycles | Process 8bercue-management-v1 UID 2000; provider reports shellUid=true; readiness logged only after accepted typed Binder |
| Removable card | PASS, two cycles | Explicit app selection: physical slot 1, port 0; Card connected · Read verified |
| Profile inventory | PASS, two cycles | povo enabled; SMC disabled; USIMSA disabled; no profile actions |
| Legacy pin migration | PASS | pairedGuid remains present; obsolete serverFingerprint removed only after authenticated Binder |
| Repeat recovery | PARTIAL | Two complete restart/reconnect/read cycles passed; a third full cycle on final source-built candidate is NOT TESTED because device became securely locked |
| Expired card choice and recovery | PASS | Delayed selection rejected with Card choice needs verification; no automatic alternative; fresh explicit selection read same inventory |
| Missing Binder, expired/replayed bootstrap challenge, wrong UID/APK/interface | PASS offline | Actual production provider with controlled Binder/clock; not induced by changing device configuration |
| Changed/disappearing ports, invalid TLS/STLS/CNXN, rejected client identity | PASS offline | Real cert policy tests, restricted-wire mocks and actual NSD callback adapter tests; hardware negatives NOT TESTED |
| Source-built SPAKE2/AES-GCM | PASS on Pixel | Synthetic executable: role agreement, wrong-password rejection, AEAD roundtrip and ciphertext-tamper rejection; no adbd/eUICC access |
| Final source-built Lab APK upgrade | PASS | Matching installed signer, pairing preferences preserved; see exact artifact below |
| Final source-built APK reconnect + eUICC read | NOT TESTED | Secure lock screen interrupted final acceptance; no unlock attempt or substitute control path |
| Reboot / adbd restart / live port change | NOT TESTED intentionally | Would risk control plane or violate safety constraints; offline coverage only |
| Fresh pairing with final source-built JNI | NOT TESTED | Preserve current authorized key and paired devices; no code requested |
| Production-signed native pairing APK | BLOCKED | Native admission/licensing, protected production signer and compatible signed-upgrade plan remain owner decisions |

## Evidence and limitations

The two successful recovery sessions ran the DER-pin fix and client-identity checks with the previously staged pinned-AAR native library. The final Lab candidate additionally contains the source-built native library, late-NSD callback hardening and stricter device CNXN validation. Automated tests/builds pass for those final changes; **do not attribute earlier hardware reads to that exact final APK**.

The repeat-test OCR harness timed out locating Choose card even though transport logs proved Binder readiness. Its completion was not counted as a PASS. The remaining selection/read steps for cycle 2 were observed directly. An expired selection caused by the delay was rejected safely; a newly requested selection then read the expected card and profiles.

During final acceptance the phone showed its secure lock screen and the app process was absent. Retained AndroidRuntime logs included a system-process Lost network stack exception, but its timing/causality was not established. Read-only uptime exceeded 27 hours, ruling out a device reboot during this run. Do not attribute those retained crash entries to the APK. No reboot command was issued. ADB remained online; VPN transport and WireGuard service were present. The exact SH-UNICOM tunnel name was not re-read from private configuration, and end-to-end tunnel reachability was not independently revalidated.

Final observed artifact: SHA-256 `763976193531d692485ac8330730f31322ae8d6c41cc69a428867eca7c6df9e4`, signed debug Lab only, signer SHA-256 `3a1ebed312d2f5ec257cde4a2966e3b8da7533be20b014bd77a48e171f6329ea`. Source-native and actual packaged inventories are generated separately. Production Release stays unsigned engineering-only locally, excludes pairing JNI, and passes the unchanged release surface check with productionReady=false.

## Required owner action

Unlock Pixel 10 normally and leave 8bercue visible to finish final-candidate repeat reconnect and removable-card read. Separately apply the source-native CI workflow patch using an authorized workflow-scoped identity, and decide production crypto admission/licensing and protected signing/migration; uninstalling the Lab to change signer would erase its paired identity and is not an accepted workaround. PR remains Draft until these gates and independent security review are satisfied.
