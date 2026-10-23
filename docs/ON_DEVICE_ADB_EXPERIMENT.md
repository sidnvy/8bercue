# On-device ADB — next milestone / Draft PR #17

Baseline: upstream `main` merged at `91564e7`. This document tracks experimental code, **not an approved release**.

## Acceptance layers (do not conflate)

1. **Classic ADB proof:** Pixel 10 Pro XL, Android 17, existing TCP listener on `127.0.0.1:5555`. The app generated an RSA identity, prompted Android's normal debugging authorization, then successfully opened an authenticated ADB session and launched a UID 2000 worker. No adbd configuration changes. The first-generation *legacy socket* IPC was unavailable on this Pixel.
2. **Production Binder transition:** `LocalAdbBootstrap` now launches the official, fixed `Connect` entrypoint, rather than the legacy ShellCompanion. The normal version/nonce/APK-digest/typed-Binder checks still apply. Java compile and existing offline regressions pass; real Binder handoff using this new build has **not** been verified.
3. **Android 11+ Wireless Debugging:** Dynamic pairing and connection ports are advertised independently as `_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp`. The new `OnDeviceAdbDiscovery` uses Android NSD to discover these DNS-SD services, accepting only addresses of the device's own active interfaces. It does not scan ranges, change developer settings, authenticate or pair.
4. **Actual TLS pairing:** **NOT YET IMPLEMENTED.** Requires the Android ADB pairing protocol (TLS exporter + SPAKE2), a persisted app-specific TLS identity and verified mutual-authenticated `STLS` ADB connection. A classic RSA-only connection must never be attempted against a modern TLS port. Do not imply that finding a port means that ADB is authorized.

## Experimental onboarding preview

Settings → **Wireless ADB setup preview** (only the experimental socket-eligible build, Android 11+):

- Explain the one-time Android Developer options → Wireless debugging → Pair device with pairing code step.
- Discover the separate pairing and connection endpoints via `NsdManager` and show only locally resolved ports; no network port scan.
- Optionally open Developer options when the user requests it. The user controls wireless debugging.
- Explicitly label TLS pairing as pending and stop discovery on dismissal.

The final UX should use a foreground pairing notification with `RemoteInput`, analogous to Shizuku, so a user can copy the pairing code from Android Settings without jumping between apps. That behavior remains an acceptance item, not a completed feature.

Reference: RikkaApps/Shizuku, `manager/src/main/java/moe/shizuku/manager/adb/{AdbMdns,AdbPairingService,AdbClient,AdbKey,AdbPairingClient}.kt` (Apache-2.0). Its native SPAKE2 component depends on BoringSSL and should not be pasted into our signed release without separate dependency, license, and security review.

## Completed automated checks

- `android-app/tools/test_wireless_adb_discovery.sh`: JVM service-type and local-address checks; source guards for discovery cleanup and no adbd mutation.
- `android-app/tools/test_local_adb_binder_source.py`: fixed, authenticated Binder entrypoint source checks.
- Existing bootstrap/UI/recovery regressions; release Java build; unsigned Release APK assembly.

The bounded TLS pairing frame parser (`OnDeviceAdbPairingFrame`) and 13 offline framing tests are staged. The source/library/native cryptography review is in `docs/ADB_TLS_PAIRING_SECURITY_REVIEW.md`. These additions do not authenticate TLS or make the pairing flow usable yet.

## Mandatory blockers before Ready for Review / merge

- Implement validated TLS paired ADB connection + native pairing safely, with persistence and user-driven code entry.
- On Android 17 Pixel 10, test a clean-install pairing path and a reboot/port-change path **without mutating its existing control connection**. Only this Pixel 10 is the device under test; do not test or inspect Pixel 7.
- Verify actual Binder handoff and read-only EID/Profile inventory after connection. No profile writes during bootstrap.
- Release verifier **currently FAILS** because it intentionally rejects the old `com/tananaev/adblib/` package. Replace the legacy general client with audited, narrowly scoped protocol code and license attribution. Do not weaken the verifier just to turn CI green.
- Validate key handling, pairing code retention, Android local-network permission requirements, negative/timeout/retry cases, safe signed upgrades, and release surface against the actual APK.

Strict policy: no ADB toggle, adbd restart, port change, phone reboot, re-pair of the workstation, or automatic eSIM write as part of experimental tests. A device that has no wireless debugging available must show a user-controlled settings flow, never silently enable debugging.
