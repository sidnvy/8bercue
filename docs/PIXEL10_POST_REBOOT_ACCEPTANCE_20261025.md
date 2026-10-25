# Pixel 10 final post-reboot acceptance — 2026-10-25

**The exact final source-built Lab APK below passed all three app-only reconnect/read cycles and a real expired-choice/recovery test. PR #17 remains Draft for independent review and production delivery decisions.** This supersedes prior lock/discovery blockers for earlier APKs, without relabeling historical results.

## Corrections and root causes

The original reconnect defect was the persistent whole-certificate DER pin: AOSP creates a fresh certificate per connection, so certificate renewal was rejected despite the existing authorized client key. Earlier real hardware recorded legacy_der_changed=true and authenticated management without re-pairing.

After the owner-authorized phone reboot, local discovery returned again. The earlier strict CNXN follow-up then exposed a second regression: it required a terminal NUL, while [AOSP send_connect](https://android.googlesource.com/platform/packages/modules/adb/+/1cf2f017d312f73b3dc53bda85ef2610e35a80e9/adb.cpp#327) copies the connection string begin/end without one. Commit fa07d48 accepts that length-delimited banner and the legacy terminal-NUL form, preserving exact device:: role, 4096-byte cap, embedded-NUL rejection, framed STLS/CNXN limits and fixed Connect-only OPEN. New tests fail against the prior implementation. The corrected APK reached authenticated Binder and read verified inventory.

Two non-inline findings in review 5640592931 were also verified and fixed: the homepage now reports an incomplete connection if backend attachment fails after ready; pair() preserves legacy serverFingerprint until authenticated Binder readiness, including explicit re-pairing. Fourteen checks execute the actual extracted completion/pairing methods with controlled I/O, including failed attachment, failed/empty pairing and failed preference commit. A later non-inline finding in review 5641364273 is also fixed: the pairing service rechecks management() on its UI callback before publishing Connected/stopping; a missing Binder reports the existing manual-recovery notice and never auto-retries. The tests exercise the actual launchIfReady method with controlled executor/UI queues, including Binder loss before UI dispatch, success, destroyed-service suppression and one-shot retry exclusion. These are not live new-pairing or Binder race hardware claims.

## Exact artifact and test boundary

- APK SHA-256: `6b78ccfe33246818341f1ef36018c9ba8647458b5dab26cd458066d7e8e5bbf8`.
- Signer SHA-256: `3a1ebed312d2f5ec257cde4a2966e3b8da7533be20b014bd77a48e171f6329ea`; stable internal Lab debug signer, classification SIGNED_DEBUGGABLE_LAB_ONLY, productionReady=false.
- Packaged source-built JNI SHA-256: `10f9509cfbd28825f5f4ecc5f05a2e91348c7f350987c568c4a8aa4f5e009c1e`; unchanged crypto bytes from the previously verified source rebuild.
- Matching-signer install -r -t passed; parsed pairing preferences exactly matched before and after the final upgrade. No clear-data/uninstall or key replacement.
- Three current-APK cycles separately compared all pairing preferences to the post-upgrade baseline and verified no existing management worker after each app-only stop.
- Workstation USB ADB supplied installation, observation and UI clicks. Every management bootstrap was initiated by the app's own Connect on this phone button using local DNS-SD/STLS, not a computer-issued Connect command. No new six-digit code was requested.

## Final hardware matrix

| Gate | Result | Evidence |
| --- | --- | --- |
| App-only restart/reconnect cycles 1, 2 and 3 | PASS for this exact APK | Each PID independently logs local resolved endpoint, TLS client identity presentation, Shell provider calls and authenticated management Binder readiness |
| Shell identity and lifecycle | PASS, all 3 | Exactly one 8bercue-management-v1 process, UID 2000; none before each connection |
| Removable eUICC selection | PASS, all 3 | Explicit physical slot 1, port 0; Card connected · Read verified |
| Profile inventory | PASS, all 3 | povo enabled; SMC disabled; USIMSA disabled |
| Existing pairing state | PASS, all 3 | pairedGuid present, legacy pin absent, preferences unchanged over the cycles; no re-pairing |
| Real expired card choice | PASS | Waited 94 seconds past the actual 90-second TTL without changing system time; selection rejected with Card choice needs verification, no stale verified inventory or automatic alternative |
| Explicit fresh choice after expiry | PASS | Same physical slot/port and profile states read verified; pairing preferences unchanged |
| Owner-authorized reboot/port change | PASS for system recovery | One reboot only, USB and wireless Workstation ADB returned; TLS port changed 46587 to 45777; owner performed first unlock |
| Fresh six-digit pairing using this APK | NOT TESTED | Existing authorized identity deliberately preserved; native crypto contract and pairing method tests are separate evidence |
| Real wrong-key/invalid TLS, forced adbd restart, debugging toggles | NOT TESTED | Negative protocol, certificate and provider coverage is offline; no system reconfiguration used |
| Production-signed native APK upgrade | BLOCKED | Protected production signer, compatible lineage/migration, licensing/native admission and workflow-authorized CI patch remain owner gates |

No Profile was installed, enabled, disabled, switched, renamed or deleted. Only the specified Pixel 10 was operated on. Screenshots expose only the app's masked identifiers; full identifiers and all pairing/authentication material are excluded from reports and logs. The native Workstation stayed in control throughout.

## Local automated checks

- 155 protocol/certificate/discovery assertions; 14 actual completion/pairing method checks; 85 synthetic artifact-policy checks including optimized Python: PASS.
- Existing 39 provider, 74 UI, 332 compatibility, 76 recovery, 44 production recovery-entry, 2231 localization and privacy/identity-composition checks: PASS. Cryptographic/callback/Android mocks are labeled.
- Release/Lab assembly and both Lint tasks: PASS. Gradle unit-test tasks are NO-SOURCE; substantive tests are the offline harnesses.
- Real signed Lab verifier with python -O: PASS, complete single-signer and native inventory, full upstream notice equality, AArch64 and 16 KiB ELF/ZIP alignment. Actual unsigned Release artifact and unchanged release-surface gates: PASS, productionReady=false.
- Source-native JNI inputs and packaged JNI bytes are unchanged from the previous actual source rebuild. Current CI still runs the historical pinned-AAR contract; it is not relabeled as the new all-source BoringSSL workflow. GitHub rejected that workflow patch for missing OAuth workflow scope; no alternate credential path was used.

## WireGuard and desktop observations

Before reboot, the app-UID IPv4 mDNS route used wlan0 and Android discovery showed wlan0, not tun0. The owner-authorized reboot restored local discovery, and successful final cycles ran while VPN transport remained present. These observations do not prove a WireGuard cause or establish an OS root cause for the earlier missing advertisement. No WireGuard/VPN setting was changed.

The Pixel USB had already been connected to m600. The initial default5037-only check missed its transport on another existing local ADB server5039; native USB/process inspection found and verified it. After reboot the existing5037 server acquired the re-enumerated USB device. No ADB server was stopped, restarted or reconfigured. The requested scrcpy window remains open on m600 for the owner.

Production admission is still separate from successful Lab hardware acceptance. See [native signed-delivery assessment](ADB_NATIVE_DELIVERY.md); do not merge or publish this debug Lab as a Release.
