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

The bounded TLS pairing frame parser (`OnDeviceAdbPairingFrame`) and 13 offline framing tests are staged. New `OnDeviceAdbPairingExchange`, `OnDeviceAdbPairingTransport`, `OnDeviceAdbWirePacket`, and `OnDeviceAdbRestrictedClient` add a fail-closed pairing/ADB state-machine **with injected mock cryptography/TLS for offline tests only**; no live TLS pairing has occurred. The source/library/native cryptography review is in `docs/ADB_TLS_PAIRING_SECURITY_REVIEW.md`. These additions do not authenticate TLS or make the pairing flow usable yet.

## Mandatory blockers before Ready for Review / merge

- Implement validated TLS paired ADB connection + native pairing safely, with persistence and user-driven code entry.
- On Android 17 Pixel 10, test a clean-install pairing path and a reboot/port-change path **without mutating its existing control connection**. Only this Pixel 10 is the device under test; do not test or inspect Pixel 7.
- Verify actual Binder handoff and read-only EID/Profile inventory after connection. No profile writes during bootstrap.
- Release verifier **currently FAILS** because it intentionally rejects the old `com/tananaev/adblib/` package. Replace the legacy general client with audited, narrowly scoped protocol code and license attribution. Do not weaken the verifier just to turn CI green.
- Validate key handling, pairing code retention, Android local-network permission requirements, negative/timeout/retry cases, safe signed upgrades, and release surface against the actual APK.

Strict policy: no ADB toggle, adbd restart, port change, phone reboot, re-pair of the workstation, or automatic eSIM write as part of experimental tests. A device that has no wireless debugging available must show a user-controlled settings flow, never silently enable debugging.


## Cryptographic implementation checkpoint

Real BoringSSL native SPAKE2/AES-GCM agreement now passes a bounded synthetic-data execution on Pixel 10. A separate JNI wrapper also passed an on-device lifecycle smoke test. The source-only native bridge is not in the APK release payload. A non-exportable Android Keystore identity and ADB public-key encoder are staged, with Java encoding tests passing. Platform TLS exporter + user-controlled pairing-code input and genuine dynamic-port TLS pairing remain open and must not be claimed complete. See `docs/ADB_TLS_PAIRING_SECURITY_REVIEW.md`.


## Pixel 10 Android 17 lab acceptance — 2026-10-24

- A signed **lab-only** variant packages `lib8bercue_adb_pairing.so` compiled against SHA-256-pinned BoringSSL; normal unsigned **Release** includes no additional JNI payload. Both Gradle variants compile. Test device upgrades only preserve app data/signature; no eUICC changes.
- **App-owned classic ADB → production Binder → physical removable eUICC read-only profile list PASS.** Pixel 10 App started `8bercue-management-v1` as Shell UID2000; the UI reported `Card connected · Read verified` for its removable eUICC on telephony slot 1 port 0; three profiles read (povo enabled, SMC and USIMSA disabled). This demonstrates Binder and card access, **not first-time TLS onboarding**.
- **Actual Pixel Android 17 NSD dynamic-port discovery PASS.** Android `resolveService` returned `._adb-tls-connect._tcp` (unexpected leading dot). Discovery now accepts that exact form; lab UI found the connection port `46737` independently, matching Android Developer options, without scans or settings writes. See test regression.
- **Android Keystore App UID identity PASS.** A dedicated app-private KeyStore RSA key is created/read in the running app. Its TLS 1.3 Conscrypt private-key operations required a KeyStore `PURPOSE_DECRYPT`, `ENCRYPTION_PADDING_NONE`, and `DIGEST_NONE` permission in addition to normal RSA signature authorizations. Identity test alias moved to v3; no existing workstation/ADB keys were rotated.
- **TLS 1.3 exporter integration PASS on synthetic localhost connection on Pixel 10.** Real Android Keystore private key performs TLS handshake and the platform `SSLSockets.exportKeyingMaterial` returned matching 64-byte exporter material on two ends. This is a *local cryptographic interop*, not Android adbd TLS authentication.
- Foreground `connectedDevice` pairing service starts successfully when the user requests it. Its notification permission was explicitly granted and system Developer Options opens. Android-generated **six-digit pairing code has not been entered yet**; no successful `_adb-tls-pairing` / `_adb-tls-connect` real-device STLS/Binder test can be claimed.
- Production Release artifact gate, previously blocked on `com.tananaev.adblib`, now returns **PASS** after deleting the six legacy generic-client source files and replacing local recovery with authenticated TLS-only discovery; the forbidden classes list was **not modified**. The native pairing library is not in the Release APK yet, so this is a verified engineering release *surface*, **not a shippable complete feature**.
- A `lab` build type (debug-signed, source-only native JNI staged in `app/build/labJniLibs`) separates experimental pairing UI from ordinary Release. Production UI remains discover-only until native linking, audited provenance and real user pairing succeed.

### Remaining delivery gate

1. Human on Pixel 10: Android Developer Options → Wireless debugging → **Pair device with pairing code**; enter the six-digit code in the 8bercue foreground notification. Never collect/store code in app logs, ADB logs or GitHub. This action is the only necessary human step; do not programmatically scrape it.
2. Confirm the pairing service mDNS resolves dynamic pairing service, SPAKE2/TLS exporter authentically exchanges GUID, and the Android paired devices list contains 8bercue.
3. Confirm actual TLS-authenticated connection to the separate dynamic CONNECT endpoint, constrained Connect command, Binder handshake, and read-only EID/Profile inventory. Validate timeout and correct user-visible failure messages.
4. Pin and audit native build artifact and notices; include it in a new audited Release artifact contract without weakening the existing mandatory resource inventory, and perform signed-upgrade tests. Android 11/API30 requires additional TLS-exporter support; current verified platform implementation is API31+ only.
5. CI green, review completed, and only then mark Draft PR #17 ready.


## Android 17 notification-code entry UX issue (2026-10-24)

- On the Pixel 10 Pro XL, a foreground 8bercue `RemoteInput` action displayed its inline six-digit reply field and a cursor, but **Android did not present a software keyboard** when the user attempted to edit it. This is an observed UI/IME behavior; the specific Android framework / keyboard fault is unconfirmed.
- The lab-only onboarding notification now includes a **keyboard-free 0–9 keypad**, backspace, and a Pair button shown only when six digits have been entered. It is a decorated expanded Android notification, not a privileged overlay or a separate Settings activity. The user remains able to keep Android's system pairing dialog visible.
- Digit actions use explicit immutable `PendingIntent.getService` targeting the app's **non-exported foreground service**. The code is accumulated only in a private six-character in-memory buffer; the notification displays only masked progress, and submit/cancel/service destruction clears the buffer. It is never logged or persisted.
- `OnDeviceAdbCodeBufferOfflineTest` covers valid length, removal, incomplete-entry rejection, masking, and clearing. Release/Lab compilation and the unmodified Release verifier passed locally.
- **Actual notification keypad press-and-submit on Pixel 10 remains to be verified** after unlocking the phone and reopening the pairing screen; no user secret was read, submitted, or recorded for this change. App update itself completed successfully; a direct external shell attempt to start the non-exported service was correctly denied and was not bypassed.

## Physical pairing acceptance update after user-entered code

On Pixel 10, Android's paired devices UI listed `8bercue@android`; the app still had no persisted `pairedGuid`. Investigation found and corrected an AOSP PeerInfo type mismatch (client public key type `0`, server GUID type `1`). New offline negative tests and Release/Lab compile pass, but this build has not yet been verified on-device. It is not evidence of completed STLS/Binder or clean-install eUICC acceptance. See `docs/ADB_TLS_PAIRING_SECURITY_REVIEW.md`.
