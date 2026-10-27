# 8bercue 0.20.0-beta.5 — candidate

Operation recovery is now specific to each card. An unconfirmed change on a
previous card no longer prevents managing another card. Returning to the
original card restores its own operation history and any unresolved restriction.
Existing records stay in place; earlier operations are never automatically
retried or reported as successful.

Worker liveness remains global. Reconnecting verifies that owned background
workers have stopped and archives their quarantine evidence separately from
card-operation outcomes. All cards still share the same transaction lock.
Confirmation is offered only when that card's history supports completing it.

VersionCode: **53**. Exact production-signed APK acceptance is pending. Offline
regressions and local builds do not qualify device writes or other phones. The
protected workflow prepares a draft; public promotion follows exact-artifact
acceptance. FINAL-ACCEPTANCE.json records that scope when available.

A same-signer upgrade retains App data and pairing identity. Crash reporting
remains optional and off by default.

## Cryptographic software acknowledgments

This product includes software developed by the OpenSSL Project for use in the
OpenSSL Toolkit (http://www.openssl.org/).

This product includes cryptographic software written by Eric Young
(eay@cryptsoft.com). Complete upstream terms accompany the APK and source.
