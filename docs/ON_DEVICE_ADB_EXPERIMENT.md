# On-device ADB bootstrap — experimental, not releasable

Baseline: `440592c`, branch `feat/on-device-adb-bootstrap`.

## Scope

A deliberately limited compatibility experiment: connect to a **pre-existing** classic ADB TCP listener at `127.0.0.1:5555`, persist an app-owned RSA key in no-backup storage, and launch only the fixed Shell Companion command after user consent. The client checks for an already-running authenticated companion before attempting any ADB connection. It never toggles USB/wireless debugging, restarts adbd, changes ports, or mutates profiles during bootstrap.

This is **not** modern Android TLS pairing (dynamic ports, mDNS, pairing code). It is not P2 boot restoration and does not implement `WRITE_SECURE_SETTINGS`.

## Automated acceptance

- Release Java compile and unsigned APK assembly: PASS.
- Existing bootstrap (39), UI/command (68), recovery (35) regressions: PASS.
- Release surface verifier: **EXPECTED BLOCK**, because it explicitly forbids `com/tananaev/adblib/` in production DEX. Never modify the release verifier merely to silence this error.
- Pixel 7 Pro: only non-mutating existing-transport checks. Do not toggle debugging, restart adbd, change TCP port, reboot, disconnect transport or install this unsigned experiment.

## Remaining work before merge or release

1. Replace the legacy/general adblib implementation with a reviewed, narrowly scoped transport or a vetted library that meets the release threat model, with license attribution and regression tests.
2. Implement Android 11+ TLS paired local ADB discovery and pairing where supported; test error/timeout and network changes without disturbing other sessions.
3. Exercise the actual app-owned key and Shell Companion bootstrap on a spare/noncritical device, including denial, stale socket and service-already-running cases.
4. Re-run publication surface, app security, licensing, privacy and signed-upgrade checks before any release.

Fail closed if the only route requires any ADB configuration mutation on the Pixel 7 Pro.
