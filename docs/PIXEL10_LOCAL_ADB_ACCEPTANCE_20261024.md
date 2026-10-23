# Pixel 10 Pro XL — on-device ADB acceptance (2026-10-24)

**Status: PARTIAL — UI/authorization blocked by secure lock screen. Do not claim self-bootstrap passed.**

## Preconditions
- Model: Pixel 10 Pro XL, Android 17.
- Pre-existing ADB workstation connection online using classic TCP 5555; neither ADB settings nor adbd lifecycle touched.
- Slot 0 reports removable embedded eUICC, with active `povo` profile. No eUICC mutation performed.
- Existing released beta was explicitly authorized for removal; uninstall cleared only that app's local data.

## Executed and evidence
- From feature branch based on latest merged main, `assembleRelease` and 142 existing bootstrap/UI/recovery regressions **PASS**.
- Unsigned APK locally signed using isolated lab debug keystore; APK v3 signature verification **PASS**.
- `adb uninstall dev.sid.fiveberinspector` **PASS** on Pixel 10 only.
- Installed locally signed experiment APK **PASS**. Package is `dev.sid.fiveberinspector`, `0.19.1-beta.3` (same version label as original; not a published release).
- `am start` **PASS**, process observed. UI not accessible while Android reports `deviceLocked=1`; no attempt to bypass secure lock.
- Device's existing TCP ADB remains listening; both Pixel 7 and Pixel 10 still enumerate as `device`.
- Active removable eUICC and `povo` still present after install.
- Android 16 x86_64 emulator did **not** install the ARM64 release APK (`INSTALL_FAILED_NO_MATCHING_ABIS`); emulator not used as a substitute for Pixel 10.

## Not yet accepted
- App-owned ADB RSA authorization prompt and consent.
- End-to-end app-originated connection to its own local `adbd` and actual Shell Companion startup.
- Recovery on already-running companion, denied authorization, reboot, or wireless ADB port changes.
- Android 11+ dynamic-port TLS wireless ADB (not implemented).
- Official release gate: legacy adblib is forbidden by `verify_release_surface.py`; remains intentionally blocked.

## Safety gates
- Preserve both existing Pixel ADB control planes (no reboot, adbd restart, TCP-port change, Wi-Fi debugging toggle, disconnect, or re-pair).
- No eUICC writes; do not use the phone lock screen as a target for bypasses.
- Run actual App UI self-bootstrap only after legitimate screen unlock; if security consent is requested, the device owner must verify it.

## Follow-up after manual unlock
- Android trust service reported `deviceLocked=0`; experimental 8bercue process remained alive and its activity could be launched.
- Android 17 UI hierarchy extraction returned `UI_UNAVAILABLE` (no UI dump acknowledgement). A screenshot of the actual 8bercue home screen confirmed the test build starts, but the local recovery entry is nested in Settings → Advanced recovery.
- Another foreground workflow (`gr.nikolasspyr.integritycheck`, Play Integrity API Checker) took focus immediately after relaunch and remained resumed across three observations. Stop UI testing rather than sending coordinate taps into an unrelated app.
- No application-originated ADB RSA handshake/consent was triggered; no successful Companion startup evidence. This is a foreground-contention blocker, NOT a failure of local ADB transport.
- Both Pixel ADB control-plane connections remained online throughout; no profile management commands were issued.
