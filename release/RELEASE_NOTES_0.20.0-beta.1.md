# 8bercue 0.20.0-beta.1 — candidate

This candidate combines on-device wireless ADB reconnection with simpler
navigation. Settings now has six groups; diagnostics, recovery and card
information are in secondary pages. Wireless pairing retains the Android
six-digit code and remembered app identity. Reconnecting starts only the fixed,
authenticated management companion through TLS 1.3.

VersionCode: **49**. This is a limited direct-distribution Beta; stable/production
qualification remains incomplete. Card operations still require their existing
explicit reviews and confirmations. Unknown outcomes remain blocked for review.

## Candidate acceptance

The merged development source at `3cd8ccf` passed the full main CI, source native
rebuild, Lab/Release builds, lint, actual artifact checks, and Pixel 10 read-only
wireless/navigation acceptance. These hardware results use the stable Lab
signer and are not acceptance of this candidate's production-signed APK.

The release candidate adds the pinned source-built pairing JNI to Release with
an exact library allowlist, source provenance, complete third-party notices,
ARM64 and 16 KiB ELF/ZIP alignment checks. A 16 KiB runtime device was not tested.

**Status at the tagged source snapshot: publication pending.** The owner granted the fixed-revision native linking permission. Protected signing,
the final signed APK's upgrade/onboarding acceptance and the final asset/source
checks must complete before this draft becomes a public prerelease. The workflow
prepares a draft and does not automatically publish it. The final release asset
`FINAL-ACCEPTANCE.json`, when present, records the acceptance of the exact signed
APK and supersedes this source-time pending status. APK and source bytes are not
replaced during finalization.

## Updates and migration

Use only the APK bearing the existing approved public signer fingerprint
`24aa7984b0d0977ff1e87b57beca91b2e36a05c04a5443f1a47aa23d90a6ef1c`.
Updates from a production-signed Beta retain the same application identity and
increase versionCode. A Lab installation uses another signer; do not uninstall
or clear it to force an update. No cross-signer data/Keystore migration is
promised. No eSIM writes are implied by the read-only acceptance above.

Crash reporting remains optional and off by default. Diagnostic sharing keeps
its preview and confirmation. Notices and corresponding pinned native sources
are included in the release source package.

## Cryptographic software acknowledgments

This product includes software developed by the OpenSSL Project for use in the
OpenSSL Toolkit (http://www.openssl.org/).

This product includes cryptographic software written by Eric Young
(eay@cryptsoft.com). Complete upstream terms accompany the APK and source.
