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
