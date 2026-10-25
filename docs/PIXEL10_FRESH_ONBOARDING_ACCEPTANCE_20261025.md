# Pixel 10 fresh onboarding and release acceptance — 2026-10-25

**Fresh six-digit pairing and three subsequent app-only reconnect/read cycles PASS on the exact Lab APK below. Production delivery remains incomplete; PR #17 stays Draft.** This report supersedes the earlier fresh-pairing NOT TESTED status for the previous APK, without changing that historical evidence.

## Candidate and change

- Tested code commit: `8d588f4a08ecf72c8cc2f85a5f03cf9599a6a863`.
- Lab APK SHA-256: `07f09a130b468dc64467c135b3d5b8b10d491431ba4651b78964d32956ad9915`.
- Lab signer SHA-256: `3a1ebed312d2f5ec257cde4a2966e3b8da7533be20b014bd77a48e171f6329ea`.
- Packaged pairing JNI SHA-256: `10f9509cfbd28825f5f4ecc5f05a2e91348c7f350987c568c4a8aa4f5e009c1e`.
- Classification: `SIGNED_DEBUGGABLE_LAB_ONLY`, `productionReady=false`.

The only runtime-source difference from the previous accepted Lab is the English/Chinese live-pairing help: Android 12+ replaces Android 11+, matching the platform TLS-exporter API requirement. The discovery-only preview remains Android 11+. Crypto and management code and packaged JNI bytes are unchanged. Later documentation/source-manifest commits do not change the tested APK.

## Fresh-install boundary and owner participation

A matching-signer upgrade to this APK first preserved the old parsed pairing preferences. The owner then explicitly authorized removal of **8bercue Lab's own local data and pairing identity** to test a genuinely fresh start. Only the main-user app instance was uninstalled/reinstalled; pairing preferences were absent before first launch. This is a separate, authorized test from the earlier identity-preserving acceptance.

The owner followed Set up wireless ADB → Start wireless pairing, allowed notifications, opened Android's Pair device with pairing code dialog, entered the code through the app notification, and returned to 8bercue. The assistant did not read, copy, record or send the pairing code. A new paired state, one Shell UID 2000 management worker, authenticated typed Binder and verified card inventory were observed.

The current removable card differs from the card in the earlier post-reboot report. The owner explicitly confirmed this card as the target for read-only acceptance. The baseline is physical slot 1 / port 0, **R&S enabled; Speedtest Travel disabled**. No earlier three-profile baseline is used for this run.

## Hardware results

| Check | Result and boundary |
| --- | --- |
| Fresh install without app pairing state | PASS; absence checked before launch |
| Owner performs notification-based six-digit pairing | PASS; code remains on phone |
| First paired connection and read | PASS; one UID 2000 worker, typed Binder, explicit card selection, Read verified |
| App-only restart, independent wireless reconnect and read | PASS on three further cycles |
| Local endpoint / TLS client identity / authenticated Binder / Shell UID log stages | All four present separately in each reconnect cycle |
| Initial first-pairing protocol-stage logs | NOT RETAINED; later buffer capture lacked them. First-pairing success is supported by the fresh state, owner flow, new paired state and actual Binder/read result, not an invented log trace |
| Pairing preferences after first pairing | Unchanged across all three reconnect cycles |
| Card inventory | R&S enabled; Speedtest Travel disabled, in all four reads |
| Profile writes | Zero; no installation, enable/disable, switch, rename or deletion |
| Wrong-key, adbd restart and debugging-toggle hardware negatives | NOT TESTED in this run; offline coverage is separate |

The computer provided installation, observation and UI control over the existing USB connection. The app initiated its own local management bootstrap; no computer-issued Connect/bootstrap command was used. No debugging/VPN settings, Workstation ADB pairing or other device was changed. The phone was not rebooted during this fresh-onboarding run. The earlier single owner-authorized reboot and expired-choice recovery remain evidence for the earlier exact APK only.

Machine-readable sanitized results: [fresh onboarding hardware matrix](evidence/pixel10-fresh-onboarding-20261025.json). Full identifiers, credentials and pairing material are excluded.

## Build, provenance and artifact checks

- Lab/Release assembly and both Lint tasks PASS; Lint reports zero errors and 67 warnings for each variant. Localization: 2231 assertions across 516 bilingual strings PASS. Gradle unit-test tasks have no test sources; they are not a substantive unit-test result.
- Actual signed Lab artifact verification with optimized Python PASS: exact native/LPAC inventory, signer, hashes, complete BoringSSL notices, AArch64 and 16 KiB ELF/ZIP alignment.
- Normal unsigned Release verification with required reporting PASS. SHA-256: `9ef3fcec16d6939fcf2f9b4f309a2d763df654286af8f39c950793f58942a410`. This Release still excludes pairing JNI.
- Production verification actually rejects both this debug Lab and the unsigned Release. Those expected rejections confirm the guard, not production readiness.
- Pinned upstream BoringSSL source rebuild repeated successfully (39.7 seconds), without staging new JNI into the tested APK. Revision `571a7432a19592c620fa316abde47770dad4f82b`; archive SHA-256 `d44a73d4927b66f0d43b51f171a044a900b91eeb431763083915f33e64713a0e`. ARM64, 16 KiB and system-only dynamic dependencies PASS.
- Existing protocol/callback/provider/UI/recovery/privacy suites passed on the unchanged implementation as recorded in the [previous report](PIXEL10_POST_REBOOT_ACCEPTANCE_20261025.md). They are not claimed as newly repeated hardware negatives here.
- All three Android CI jobs at tested code `8d588f4` PASS: [run 40179480706](https://github.com/sidnvy/8bercue/actions/runs/40179480706). Automated review status also succeeded. That CI still runs the historical pinned-AAR crypto contract, not the proposed all-source crypto step.

## Production delivery audit

The existing production signing mechanism is already provisioned: `beta-release` exposes the four expected signing-input names, and the protected main-only [signer smoke run 39735032895](https://github.com/sidnvy/8bercue/actions/runs/39735032895) succeeded at `91564e75ccd967b2c0dbc1401f0fea7c295fe496`. No secret values were read. The public release certificate was downloaded and its SHA-256 verified as `24aa7984b0d0977ff1e87b57beca91b2e36a05c04a5443f1a47aa23d90a6ef1c`. This proves the baseline mechanism, not signing of the current wireless candidate.

Open gates are independent security review; production native admission and license compatibility review; all-source crypto CI with workflow-authorized credentials; a protected production-signed exact candidate; same-production-signer upgrade/identity and final APK hardware acceptance; and a new version with matching release metadata/source archive. Lab and production signers differ, so a data-preserving cross-signer upgrade is not promised. Current version `0.19.1-beta.3 / 48` remains the already-published version.

The saved CI patch changes only the crypto build step to the pinned upstream source build. GitHub previously rejected it because the current OAuth grant lacks `workflow`; it has not been applied through another credential or control path. Existing production verifiers and release workflows remain unchanged. See the [concrete production admission proposal](PR17_PRODUCTION_ADMISSION_20261025.md) and [native delivery assessment](ADB_NATIVE_DELIVERY.md).

Settings simplification and navigation redesign remain a separate future PR. No tag, public APK upload, merge or release was performed.
