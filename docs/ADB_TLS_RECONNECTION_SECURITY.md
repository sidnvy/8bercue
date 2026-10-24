# Wireless ADB reconnection trust review — 2026-10-24

> Latest code/review status: [2026-10-25 follow-up](PR17_REVIEW_FOLLOWUP_20261025.md). Historical hardware evidence below remains scoped to its tested APK.

PR #17 / issue #18. This review describes the implementation and its limits; it is not independent cryptographic certification or production-release approval.

## Root cause and evidence

`OnDeviceAdbWirelessManager` previously hashed the entire adbd X.509 DER certificate and rejected later sessions whose digest differed, before reading post-TLS CNXN or launching Connect. On Pixel 10, the recovered session logged `tls13_client_identity_presented`, `legacy_der_changed=true`, and subsequently `authenticated_management_binder_ready`. The previous stored pin would have rejected this exact renewed certificate. The existing paired key was accepted without another pairing code.

AOSP `FdConnection::DoTlsHandshake` invokes `GenerateX509Certificate` for every connection. `crypto/x509_generator.cpp` sets notBefore to now and notAfter relative to now, so DER changes while the same RSA key remains valid. `daemon/auth.cpp::adbd_auth_tls_handshake` creates an in-memory server RSA key, not a durable paired server identity. Pinning SPKI would merely postpone breakage until adbd restarts. Pairing exchanges the client public key and a device GUID; it does not establish a durable server-certificate or SPKI pin. The GUID is pairing state, not a cryptographic server proof.

Authoritative sources inspected on 2026-10-24:
- [transport.cpp, DoTlsHandshake](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/transport.cpp)
- [X.509 generator](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/crypto/x509_generator.cpp)
- [adbd client-key authorization and server-key lifecycle](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/auth.cpp)
- [TLS post-handshake rejection check](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/tls/tls_connection.cpp)
- [pairing client](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/adb_wifi.cpp)

## Chosen authority boundary

1. Accept only CONNECTION-purpose mDNS discovery resolving to an address owned by an active interface on this phone. No manual remote destination, port scan, remembered classic 5555 endpoint or debugging reconfiguration. Ignore callbacks from lost, superseded or closed discovery generations.
2. Reconnect loads the existing app-owned Android Keystore RSA identity. It never creates or rotates a missing paired key. Pairing alone may create a key; persisted pairedGuid is required for reconnection.
3. Require STLS version/shape, then TLS 1.3 on the same socket. Management uses a distinct policy from provisional SPAKE2 pairing: require a single self-issued certificate with a valid self-signature, and verify that TLS presented the expected app client public key. Self-signature validates certificate shape/key possession; it does not authenticate an adbd identity. Certificate dates/DER/SPKI are not authorization pins.
4. TLS 1.3 handshake return and local-certificate presentation are not acceptance. Read a valid server device CNXN (version, maximum payload, device banner and framing) on the TLS stream before sending any OPEN. AOSP itself enables a post-handshake read because client-certificate rejection can arrive after client-side handshake completion. Alerts, EOF, timeouts, A_AUTH/classic downgrade and malformed CNXN all fail closed without sending Connect.
5. OPEN permits only the installed APK's fixed production Connect entrypoint. A restricted path grammar prevents shell injection. There is no arbitrary command API. Output/packet budgets remain bounded and output is never logged.
6. Management readiness requires the existing live typed Binder published by kernel-authenticated Shell UID 2000, a fresh single-use challenge, matching protocol/version and APK digest. A TLS stream, CNXN or finished shell command alone cannot report ready. Failed command transport is tolerated only when that independent authenticated Binder is already live.

The ADB protocol authenticates the client to adbd, not a durable server identity to the client. The local-address restriction reduces exposure to network impersonators; forged remote mDNS cannot redirect the socket to another device. A local unprivileged app can bind its own port and simulate TLS/CNXN, but receives only the public client certificate and fixed Connect request; it cannot forge the kernel Shell UID Binder handoff. TLS signatures cannot be replayed onto a different TLS transcript. An attacker already holding Shell/root or controlling the OS falls outside this boundary. This design must not be generalized to arbitrary remote ADB hosts or arbitrary shell execution.

## Migration and regression coverage

An existing pairedGuid and Keystore identity remain untouched. An obsolete serverFingerprint does not authorize or block a session. Its presence triggers only an identifier-free changed/not-changed diagnostic and it is removed after successful Binder authentication. Failed attempts retain it for retry. No raw certificate fingerprint, GUID, challenge, pairing code, private key, EID or ICCID is logged by this path.

Tests use actual generated self-signed certificates (renewal with the same key and a new server key), missing/wrong client identity, invalid signatures/chains and TLS-version rejection. Restricted-client tests cover server rejection/EOF, no OPEN before valid CNXN, invalid STLS/CNXN/device role, classic RSA downgrade, stream IDs and command injection. Actual NSD callbacks are exercised with simulated Android adapters for disappearing endpoints, port changes, outdated resolutions, foreign hosts, invalid ports and close races. The actual production Binder provider's offline tests retain absent/dead service, UID, challenge expiry/replay, APK hash and typed-interface rejection. Mocks do not certify live server-side cryptography; real Pixel results are recorded separately.
