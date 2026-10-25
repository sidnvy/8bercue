# 8bercue 0.20.0-beta.4 — candidate

When several readable cards are present, the home screen now asks you to choose
a card. It no longer labels that normal selection step as an unavailable card or
sends you to connection diagnostics.

This includes the previous candidate's fix for reading a selected card while an
unconfirmed earlier change belongs to another card. Reading and discovery run on
connection and Refresh. Generic recovery/compatibility test buttons are removed
from Release. Existing operation records and restrictions on changes remain;
earlier changes are never automatically retried.

VersionCode: **52**. The protected workflow prepares a draft. Public promotion
requires acceptance of the exact downloaded production-signed APK. Before this
source snapshot, APK51 passed Moto edge 70 Android 17 dual-card read/Refresh and
saved-identity wireless reconnect. Those checks do not qualify APK52. The two
owner-identified 9eSIM samples returned standard empty profile lists; their
previous contents are unknown. No profile changes or downloads were tested.
The final release asset FINAL-ACCEPTANCE.json records exact-artifact acceptance
when available. A successful read does not establish full card compatibility.

Upgrade with the existing public signer fingerprint
`24aa7984b0d0977ff1e87b57beca91b2e36a05c04a5443f1a47aa23d90a6ef1c`.
A same-signer upgrade retains App data and pairing identity. Crash reporting
remains optional and off by default; manual sharing requires preview confirmation.

## Cryptographic software acknowledgments

This product includes software developed by the OpenSSL Project for use in the
OpenSSL Toolkit (http://www.openssl.org/).

This product includes cryptographic software written by Eric Young
(eay@cryptsoft.com). Complete upstream terms accompany the APK and source.
