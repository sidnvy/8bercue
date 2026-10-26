# 8bercue 0.20.0-beta.7 — candidate

Enabling or disabling an eSIM can refresh the SIM and invalidate its previous
logical channel. A returned cleanup error now permits a bounded, independent
read of the same card before the old management session is retired. An exact
verified transition remains verified; a missing or changed inventory remains
unknown. The app never retries the state-changing command or rolls it back.

When reconnecting after a lost enable/disable response, the app can confirm the
result automatically only if two stable reads, the complete original card
identity, the precommitted target and full before-inventory fingerprint prove
the exact intended transition. A separate durable receipt preserves the
original unknown record byte for byte. Incomplete history, other unresolved
operations, mismatched state and active workers continue to block changes.
Downloads, rename and delete are not inferred by this recovery path.

Card selection uses short SIM labels rather than exposing the transport's
Secure Element reader name.

Connection setup now presents computer USB ADB and on-device wireless ADB as
equal choices. If a Shizuku service is already running with ADB permissions
(Shell UID 2000, server API 13 or later), Shizuku appears as an optional third
method and requests authorization only after it is selected. Root-started
Shizuku and Sui are not supported by this Shell backend.

After a verified change requires a fresh management session, recovery respects
the selected connection method. Wireless and already-authorized Shizuku can
reconnect without requesting new permission. The computer method directs the
user to run its fixed connection command again. A stopped or revoked service
does not trigger another permission prompt or silently switch to a different
method, and connection recovery never repeats the card change.

VersionCode: **55**. Exact production-signed APK acceptance is pending. The
protected workflow prepares a draft; publication requires testing that artifact.
Detailed device acceptance remains in internal archives. Offline tests and local
builds do not qualify actual operations, mobile data or other phones.

A same-signer upgrade retains app data and pairing identity. Crash reporting
remains optional and off by default.

## Cryptographic software acknowledgments

This product includes software developed by the OpenSSL Project for use in the
OpenSSL Toolkit (http://www.openssl.org/).

This product includes cryptographic software written by Eric Young
(eay@cryptsoft.com). Complete upstream terms accompany the APK and source.
