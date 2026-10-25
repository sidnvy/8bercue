# 8bercue 0.20.0-beta.3 — candidate

This candidate fixes profile reading when an earlier interrupted change belongs
to another card. Previously, a verified current-card inventory could be discarded
as a card mismatch. Valid legacy records without card binding are also kept as
unconfirmed history while the selected card remains viewable.

Card discovery and reading run on connection and Refresh. Release builds remove
the generic compatibility/recovery test workflow. Connection details are under
Help & feedback; Operation records offer confirmation only for an eligible
previous change after reading its original card. Existing records and restrictions
on changes are retained, and earlier changes are never automatically retried.

VersionCode: **51**. This is a limited Beta candidate. The protected workflow
prepares a draft; publication remains pending acceptance of the exact signed APK.

## Validation scope

The original failure was reproduced on Motorola edge 70, Android 17, with two
owner-identified 9eSIM cards. Native and independent Java reads both returned
empty inventories. The original observation method rejected both cards because
the old operation belonged to another card; the patched method accepted their
verified read-only inventories. The journal/history and quarantine remained
unchanged. This component probe is separate from installed APK acceptance.

At the tagged source snapshot, exact APK51 upgrade, normal UI, dual-card selection
and reconnect acceptance remain pending. Profile changes and downloads have not
been tested on APK51. The final release asset FINAL-ACCEPTANCE.json, when present,
records acceptance of the exact signed artifact without replacing APK/source bytes.

## Updates

Upgrade using the existing public signer fingerprint
`24aa7984b0d0977ff1e87b57beca91b2e36a05c04a5443f1a47aa23d90a6ef1c`.
App data and pairing identity are retained during a same-signer upgrade. No data
clearing or new pairing is required merely to install this update.

Crash reporting remains optional and off by default. Manual diagnostic sharing
retains its preview and confirmation.

## Cryptographic software acknowledgments

This product includes software developed by the OpenSSL Project for use in the
OpenSSL Toolkit (http://www.openssl.org/).

This product includes cryptographic software written by Eric Young
(eay@cryptsoft.com). Complete upstream terms accompany the APK and source.
