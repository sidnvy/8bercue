# Android wireless ADB TLS pairing — implementation security checkpoint

Status: **pairing engine NOT wired into the Android app**. Draft PR #17 and issue #18. No production or test release authorization is implied by the results below.

## Source-grounded protocol contract

AOSP's Android 11+ pairing connection exchanges a versioned six-byte big-endian header `version(1) | type(1) | payloadSize(4)` over TLS. Version 1, message types 0 (SPAKE2) and 1 (encrypted PeerInfo), and maximum payload 16,384 bytes. The pairing and authenticated connection ports are independent mDNS services. TLS client identity, TLS exporter output, SPAKE2 and AES-GCM are necessary in addition to discovering ports. The app must never interpret a discovered endpoint as an authorized Shell session.

Source: AOSP `platform/packages/modules/adb` `pairing_connection/pairing_connection.cpp` and `proto/pairing.proto`. Compare against Shizuku's `AdbPairingClient.kt` and `adb_pairing.cpp` (Apache-2.0).

The Java-only frame parser in `OnDeviceAdbPairingFrame` implements only bounded parsing and explicit byte clearing. It is not a cryptographic security boundary and cannot perform pairing without the remaining protocol.

## Isolated dependency investigations (not embedded in this repository)

- `MuntashirAkon/libadb-android` reference at `c849886` is dual GPL-3.0-or-later / Apache-2.0, but its default pairing dependency is `spake2-android` JNI with BouncyCastle; both source/dependency integrity and its broad shell command capability need independent audit.
- `MuntashirAkon/spake2-java` reference at `7615ddd` (LGPL-3.0) was compiled as pure Java in the isolated Workstation lab. A matching Alice/Bob code exchange failed during curve-point validation; source also contains `System.out` diagnostics printing ephemeral private material and intermediate cryptographic state. **Reject this candidate for 8bercue; no code from this reference was integrated.** Do not record ephemeral test secrets in test output or source-control.
- `RikkaApps/Shizuku` reference at `b844bc4` contains the SPAKE2 JNI reference. In an isolated test directory, the C++ JNI code compiled against pinned `io.github.vvb2060.ndk:boringssl:20250114` with Android NDK `27.3.13750724` for arm64-v8a. Dependency AAR digest: `366c9af78cee572ffcfad6e8c17650646b9f79492e8177d35226a2a5569f3fbc`. Build output was an AArch64 ELF shared library, 16 KiB PT_LOAD alignment, with Android system shared-library dependencies only. **This is compilation evidence, NOT TLS/SPAKE2 correctness, device pairing, or binary security approval.** No binary from this experiment belongs in the distributed APK.

## Findings to fix before adapting JNI or delivering an APK

- Pin and verify all native dependency source archives, build tools and output hashes; review Apache/BSD license attribution. Do not rely solely on unpublished prebuilt binaries.
- Avoid variable-length stack allocations indexed by untrusted frame sizes (the reference encrypt/decrypt functions use C++ variable-length stack buffers). Use checked heap allocations with explicit upper bounds and error handling.
- Zero code/password, intermediate session keys, cryptographic state and derived AES keys on all success/error/destroy paths; do not leave plaintext material in Java or JNI logs. Prefer zeroization-aware containers.
- Carefully review handling of JNI arrays and pinning (read-only inputs must not be copied back on release), native pointer validity, one-shot free and exception safety.
- After TLS pairing, establish separate **authenticated ADB STLS** using the same persistent app-owned private key/certificate. Restrict execution to the fixed `Connect` command and its authenticated Binder challenge. No arbitrary shell, port scanning, debug toggle, adbd restart or eUICC writes.
- Android 17 ADB Wi-Fi 2.0 may disable Wi-Fi debugging on networks that the device does not trust; do not treat a vanished discovery service as revoked authorization or silently modify network trust.
- Real-device acceptance requires a clean-install Pixel 10 Pro XL pairing + connection + Binder + read-only EID/Profile verification. No testing or control-plane changes on Pixel 7.

## Release blockers

The existing release verifier currently forbids the legacy `com/tananaev/adblib/` DEX package and extra unreviewed native libraries. Preserve this gate. A reviewed replacement must have its own explicit license, source-inventory, native ABI/16K-page and negative-protocol tests before changing the production release allowlist. Merely renaming code does not satisfy the security review.


## Restricted TLS session components staged (not active in the app)

- `OnDeviceAdbPairingExchange` now models AOSP's two-message SPAKE2 exchange and authenticated PeerInfo exchange over a caller-provided TLS stream. Validates a 6-digit code, a 64-byte same-session exporter, exact 32-byte SPAKE2 messages and 8,208-byte AEAD peer-info frames, and erases temporary buffers. Its `CryptoFactory` is **an intentionally unimplemented real provider**; offline tests use a fake backend that must never be packaged as an ADB authenticator.
- `OnDeviceAdbPairingTransport` restricts the destination to the phone's own DNS-SD pairing endpoint, TLS 1.3, bounded connect/read waits, and a 64-byte exporter supplied by the TLS provider. It must not be activated before the reviewed client identity and key-exporter implementation exists.
- `OnDeviceAdbWirePacket` strictly parses and serializes bounded 24-byte Android ADB wire headers and payloads with checksum/magic enforcement. This is **not** a full transport nor a certificate validator.
- `OnDeviceAdbRestrictedClient` models an STLS-only session (no classic RSA downgrade), requires an explicit verified paired identity from a future TLS upgrader, opens only `CLASSPATH=<installed APK> ... Connect`, validates stream IDs, bounds command output/packet count and never logs command output. Its offline tests inject a fake TLS stream; no actual adbd/TLS authentication is claimed.
- **No new native library or broad ADB library has been embedded in the production APK**, and no actual pairing or authenticated Binder connection has been executed from these new layers.

Known remaining dependencies: app-owned RSA/certificate persistence, Android Conscrypt TLS exporter bound to the same socket, reviewed JNI SPAKE2+HKDF+AES-GCM implementation with proven server interoperability, actual paired-host TLS identity verification, callback-driven UI/notification code input, and clean-install Pixel 10 acceptance. Each must pass real-device and release-surface gates separately.

## 2026-10-24 real cryptography and identity proof (experimental only)

- `android-app/tools/native_adb_pairing/spake2_crypto_contract.cpp` performs a **real** native BoringSSL Alice/Bob SPAKE2 exchange with synthetic pairing digits + synthetic 64-byte TLS-exporter material; derives identical AES-128 keys with AOSP's HKDF label; verifies two-way 8,192-byte PeerInfo AES-GCM round trips, wrong-password rejection and tamper detection. Compiled using pinned BoringSSL (SHA-256 `366c9af78cee572ffcfad6e8c17650646b9f79492e8177d35226a2a5569f3fbc`). The resulting **ARM64 executable ran successfully on Pixel 10 Pro XL** using the preexisting Workstation ADB connection; it did not invoke adbd, change debugging settings or touch eUICC profiles.
- `app/src/release/cpp/on_device_adb_pairing.cpp` and `OnDeviceAdbNativeCrypto.java` provide a JNI wrapper for the already existing strict `Crypto` interface. Native session handles are opaque tokens held in a synchronized handle table, and methods enforce exact message lengths, 8,192-byte payloads and monotonically increasing AEAD nonces. Mutable passwords, derived keys and plaintext buffers are scrubbed on handling paths; double-close and use-after-close are rejected by the Java wrapper. **Pixel 10 tested the JNI bridge** for real SPAKE2 context initialization, invalid-peer rejection and safe disposal using synthetic data only. The `.so` is *not* embedded in the app's released APK yet.
- `OnDeviceAdbIdentity` defines the app's separate, persistent, non-exportable Android Keystore RSA key/certificate, rather than sharing the old classic TCP ADB private key. `OnDeviceAdbPublicKey` encodes a strict 2048-bit key as the AOSP Montgomery public-key struct and NUL-terminated pairing identity. Eight independent encoding assertions passed. **Keystore identity has not yet undergone app-UID on-device acceptance.**
- The build-only `run_adb_crypto_contract.sh` pins the Maven BoringSSL AAR by SHA-256 before extraction, builds both ARM64 artifacts with NDK 27.3 and verifies 16 KiB `PT_LOAD` alignment. GitHub CI compiles these in an isolated source job; the CI job does not run the ARM64 executable or publish the `.so` in the app.
- Public Android `android.net.ssl.SSLEngines.exportKeyingMaterial` (API 31+) is a promising exporter for a platform-TLS `SSLEngine` implementation with no separately bundled Conscrypt native library. However, the TLS engine wrapping, Android Keystore client authentication, certificate trust policy and validated pairing→STLS transition are still unimplemented. Until then the app explicitly remains preview-only. Android 11/API 30 would need a separately reviewed compatible exporter route.

**Do not declare this milestone paired or mergeable.** The real JNI crypto proof authenticates *synthetic peer messages*, not Android Wireless Debugging's actual pairing server. Also preserve the existing release verifier's prohibition against the old `com/tananaev/adblib` and unreviewed native payloads.


## Real Android 17 platform finding, 2026-10-24

A Pixel 10 Pro XL (Android API 37) app-UID TLS 1.3 loopback test failed initially with `AndroidKeyStore` `INCOMPATIBLE_PADDING_MODE`, then `INCOMPATIBLE_DIGEST`. Platform Conscrypt uses private RSA via `Cipher(RSA/ECB/NoPadding)` for certain TLS private-key operations. The dedicated ADB TLS identity has to allow `PURPOSE_DECRYPT`, `ENCRYPTION_PADDING_NONE`, and `DIGEST_NONE`, besides SHA256/384/512 and standard RSA signature paddings. The new versioned v3 identity passed a **real, synthetic loopback Keystore TLS + exporter** check. No existing ADB computer identity or Android paired-device record was removed or rotated.

Real ADB pairing is intentionally gated on a user-visible six-digit system code; native SPAKE2 has only been proven against synthetic local clients, and app Binder with classic ADB has been proven separately. **These separate successes cannot substitute for the combined TLS onboarding test.**

Production `verify_release_surface.py` now returns PASS after removal of the old broad adblib class set; the forbidden markers were left intact. The Release artifact still omits native SPAKE2 by design. Before shipping a real TLS-paired release, audit and pin native library inclusion, pass 16 KiB page tests, verify license notices, and run signed upgrades and a real Pixel10 paired-session test. The current provisional pairing-only trust manager must never be treated as a trusted remote TLS server certificate before SPAKE2 + bound TLS exporter succeed.
